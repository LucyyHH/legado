package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReaderServerPdfTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pdfDetectionUsesScopedUrlAndFormatMetadata() {
        assertTrue(
            ReaderServerPdf.isPdf(
                Book(
                    bookUrl = "reader-v1://account/book-id",
                    originName = "title-without-extension",
                    variable = "{\"readerV1Format\":\"PDF\"}"
                )
            )
        )
        assertFalse(
            ReaderServerPdf.isPdf(
                Book(bookUrl = "https://example.test/book.pdf", variable = "{\"readerV1Format\":\"pdf\"}")
            )
        )
    }

    @Test
    fun existingCacheIsValidatedOnceAndReturnedWithoutNetwork() = runBlocking {
        val target = temporaryFolder.newFile("reader.pdf").apply { writeText("%PDF-cached") }
        val cache = FakeDocumentCache(target)

        assertSame(target, ReaderServerPdf.ensureDownloaded("book-hit", cache))
        assertSame(target, ReaderServerPdf.ensureDownloaded("book-hit", cache))
        assertEquals(0, cache.downloadCount)
        assertEquals(1, cache.validationCount)
    }

    @Test
    fun concurrentRequestsShareOneDownload() = runBlocking {
        val target = File(temporaryFolder.root, "concurrent/reader.pdf")
        val downloadStarted = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        val cache = FakeDocumentCache(target) { partial ->
            downloadStarted.complete(Unit)
            finishDownload.await()
            partial.writeText("%PDF-valid")
        }

        val first = async { ReaderServerPdf.ensureDownloaded("book-concurrent", cache) }
        downloadStarted.await()
        val second = async { ReaderServerPdf.ensureDownloaded("book-concurrent", cache) }
        finishDownload.complete(Unit)

        assertSame(target, first.await())
        assertSame(target, second.await())
        assertEquals(1, cache.downloadCount)
        assertEquals(1, cache.validationCount)
        assertTrue(target.isFile)
    }

    @Test
    fun failedDownloadNeverPromotesPartialFile() = runBlocking {
        val target = File(temporaryFolder.root, "failure/reader.pdf")
        val cache = FakeDocumentCache(target) { partial ->
            partial.writeText("half")
            throw IOException("connection lost")
        }

        try {
            ReaderServerPdf.ensureDownloaded("book-failure", cache)
            fail("expected download failure")
        } catch (error: IOException) {
            assertEquals("connection lost", error.message)
        }
        assertFalse(target.exists())
        assertNoPartialFiles(requireNotNull(target.parentFile))
    }

    @Test
    fun cancellationDeletesPartialAndPropagates() = runBlocking {
        val target = File(temporaryFolder.root, "cancel/reader.pdf")
        val cache = FakeDocumentCache(target) { partial ->
            partial.writeText("half")
            throw CancellationException("cancelled")
        }

        try {
            ReaderServerPdf.ensureDownloaded("book-cancel", cache)
            fail("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
        assertFalse(target.exists())
        assertNoPartialFiles(requireNotNull(target.parentFile))
    }

    @Test
    fun invalidPdfIsDeletedInsteadOfPromoted() = runBlocking {
        val target = File(temporaryFolder.root, "invalid/reader.pdf")
        val cache = FakeDocumentCache(target, valid = false)

        try {
            ReaderServerPdf.ensureDownloaded("book-invalid", cache)
            fail("expected invalid PDF failure")
        } catch (error: IOException) {
            assertEquals("服务器返回的 PDF 文件无效", error.message)
        }
        assertEquals(1, cache.validationCount)
        assertFalse(target.exists())
        assertNoPartialFiles(requireNotNull(target.parentFile))
    }

    @Test
    fun corruptExistingCacheIsReplaced() = runBlocking {
        val target = File(temporaryFolder.root, "corrupt/reader.pdf")
        requireNotNull(target.parentFile).mkdirs()
        target.writeText("not a pdf")
        val cache = FakeDocumentCache(target)

        ReaderServerPdf.ensureDownloaded("book-corrupt", cache)

        assertEquals(1, cache.downloadCount)
        assertEquals(1, cache.validationCount)
        assertTrue(target.readText().startsWith("%PDF-"))
    }

    @Test
    fun truncatedPdfWithValidHeaderIsValidatedThenReplaced() = runBlocking {
        val target = File(temporaryFolder.root, "truncated/reader.pdf")
        requireNotNull(target.parentFile).mkdirs()
        target.writeText("%PDF-truncated")
        val cache = FakeDocumentCache(
            targetFile = target,
            validator = { it.readText() == "%PDF-fresh" },
            downloader = { it.writeText("%PDF-fresh") }
        )

        ReaderServerPdf.ensureDownloaded("book-truncated", cache)

        assertEquals(1, cache.downloadCount)
        assertEquals(2, cache.validationCount)
        assertEquals("%PDF-fresh", target.readText())
    }

    @Test
    fun failedReplacementKeepsOriginalTruncatedFile() = runBlocking {
        val target = File(temporaryFolder.root, "preserve/reader.pdf")
        requireNotNull(target.parentFile).mkdirs()
        target.writeText("%PDF-truncated")
        val cache = FakeDocumentCache(
            targetFile = target,
            validator = { false },
            downloader = { throw IOException("offline") }
        )

        try {
            ReaderServerPdf.ensureDownloaded("book-preserve", cache)
            fail("expected replacement failure")
        } catch (error: IOException) {
            assertEquals("offline", error.message)
        }
        assertEquals("%PDF-truncated", target.readText())
        assertNoPartialFiles(requireNotNull(target.parentFile))
    }

    @Test
    fun cancellationAfterUncooperativeDownloadStillPreventsPromotion() = runBlocking {
        val target = File(temporaryFolder.root, "late-cancel/reader.pdf")
        val downloadStarted = CompletableDeferred<Unit>()
        var attempt = 0
        val cache = FakeDocumentCache(target) { partial ->
            attempt++
            if (attempt == 1) {
                downloadStarted.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    // Simulate a downloader which swallows cancellation and returns successfully.
                    partial.writeText("%PDF-late")
                }
            } else {
                partial.writeText("%PDF-retry")
            }
        }
        val job = launch {
            ReaderServerPdf.ensureDownloaded("book-late-cancel", cache)
        }
        downloadStarted.await()
        job.cancelAndJoin()

        assertFalse(target.exists())
        assertNoPartialFiles(requireNotNull(target.parentFile))

        ReaderServerPdf.ensureDownloaded("book-late-cancel", cache)

        assertTrue(target.isFile)
        assertEquals(2, cache.partialFiles.size)
        assertFalse(cache.partialFiles[0].absolutePath == cache.partialFiles[1].absolutePath)
        assertNoPartialFiles(requireNotNull(target.parentFile))
    }

    @Test
    fun forgettingRebuiltFileForcesValidationEvenWhenItsStampMatches() = runBlocking {
        val target = temporaryFolder.newFile("rebuild.pdf").apply { writeText("%PDF-aaaa") }
        val originalModified = target.lastModified()
        val cache = FakeDocumentCache(target)

        ReaderServerPdf.ensureDownloaded("book-rebuild", cache)
        target.writeText("%PDF-bbbb")
        assertEquals("%PDF-aaaa".length.toLong(), target.length())
        assertTrue(target.setLastModified(originalModified))
        ReaderServerPdf.forget(target)

        ReaderServerPdf.ensureDownloaded("book-rebuild", cache)

        assertEquals(2, cache.validationCount)
    }

    @Test
    fun fileChangedDuringValidationIsNotAcceptedAsCached() = runBlocking {
        val target = temporaryFolder.newFile("changing.pdf").apply { writeText("%PDF-original") }
        var firstValidation = true
        val cache = FakeDocumentCache(
            targetFile = target,
            validator = { file ->
                if (file == target && firstValidation) {
                    firstValidation = false
                    file.writeText("%PDF-changed-during-validation")
                    true
                } else {
                    file.readText() == "%PDF-fresh"
                }
            },
            downloader = { it.writeText("%PDF-fresh") }
        )

        ReaderServerPdf.ensureDownloaded("book-changing", cache)

        assertEquals(1, cache.downloadCount)
        assertEquals("%PDF-fresh", target.readText())
        assertNoPartialFiles(requireNotNull(target.parentFile))
    }

    @Test
    fun validationCacheEvictsLeastRecentlyUsedEntry() = runBlocking {
        val caches = (0..32).map { index ->
            FakeDocumentCache(
                temporaryFolder.newFile("lru-$index.pdf").apply { writeText("%PDF-$index") }
            )
        }

        caches.forEachIndexed { index, cache ->
            ReaderServerPdf.ensureDownloaded("book-lru-$index", cache)
        }
        ReaderServerPdf.ensureDownloaded("book-lru-0", caches.first())

        assertEquals(2, caches.first().validationCount)
    }

    @Test
    fun validatingDifferentDocumentsDoesNotHoldGlobalCacheLock() = runBlocking<Unit> {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = FakeDocumentCache(
            temporaryFolder.newFile("parallel-a.pdf").apply { writeText("%PDF-a") },
            validator = {
                firstStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                true
            }
        )
        val second = FakeDocumentCache(
            temporaryFolder.newFile("parallel-b.pdf").apply { writeText("%PDF-b") },
            validator = {
                secondStarted.countDown()
                true
            }
        )

        val firstJob = async(Dispatchers.Default) { ReaderServerPdf.ensureDownloaded("book-a", first) }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val secondJob = async(Dispatchers.Default) { ReaderServerPdf.ensureDownloaded("book-b", second) }
        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
        }
        firstJob.await()
        secondJob.await()
    }

    private fun assertNoPartialFiles(directory: File) {
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".pdf.part") })
    }

    private class FakeDocumentCache(
        override val targetFile: File,
        private val valid: Boolean = true,
        private val validator: ((File) -> Boolean)? = null,
        private val downloader: suspend (File) -> Unit = { it.writeText("%PDF-fake") }
    ) : ReaderServerPdf.DocumentCache {
        var downloadCount = 0
        var validationCount = 0
        val partialFiles = mutableListOf<File>()

        override suspend fun download(partialFile: File) {
            downloadCount++
            partialFiles.add(partialFile)
            downloader(partialFile)
        }

        override fun validate(file: File): Boolean {
            validationCount++
            return validator?.invoke(file) ?: valid
        }
    }
}
