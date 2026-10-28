package io.legado.app.model.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** Opt-in integration test. Point only at an isolated fixture server with the test account. */
class ReaderServerLiveContractTest {
    @Test
    fun readsFixtureTextEpubComicAndPdfAndRestoresProgress() {
        val base = System.getenv("READER_COMPAT_TEST_URL")
        assumeTrue("isolated Reader fixture not configured", !base.isNullOrBlank())
        val http = OkHttpClient()
        try {
            runBlocking {
                val api = ReaderServerApi(base!!, "legadocompat", "test-password-only", { http }, {})
                api.login()
                val books = api.getBookshelf()
                assertEquals(4, books.size)
                var imageCount = 0
                for (book in books) {
                    if (book.variableMap["readerV1Format"] == "pdf") {
                        // The browser E2E pauses after a real page turn. Verify the
                        // exact locator it wrote before sending Legado's next page.
                        System.getenv("READER_COMPAT_PDF_INITIAL_PAGE")?.toIntOrNull()?.let { page ->
                            val fromWeb = api.getBookProgress(book.copy(durChapterTime = 0))
                            assertNotNull(fromWeb)
                            assertEquals(page - 1, fromWeb!!.durChapterIndex)
                            assertEquals(0, fromWeb.durChapterPos)
                        }
                        val directory = Files.createTempDirectory("reader-pdf-live").toFile()
                        try {
                            val document = java.io.File(directory, "document.pdf")
                            api.downloadBookDocument(book.bookUrl, document)
                            assertTrue(document.length() > 100)
                            assertEquals("%PDF-", document.inputStream().use { String(it.readNBytes(5)) })
                            book.totalChapterNum = 3
                            book.durChapterIndex = 2
                            book.durChapterTime = System.currentTimeMillis()
                            assertTrue(api.saveBookProgress(book))
                            val other = ReaderServerApi(base, "legadocompat", "test-password-only", { http }, {})
                            val restored = other.getBookProgress(book.copy(durChapterIndex = 0, durChapterTime = 0))
                            assertNotNull(restored)
                            assertEquals(2, restored!!.durChapterIndex)
                            assertEquals(0, restored.durChapterPos)
                            assertEquals(book.durChapterTime, restored.durChapterTime)
                        } finally {
                            directory.deleteRecursively()
                        }
                        continue
                    }
                    val chapters = api.getChapterList(book.bookUrl)
                    assertTrue("${book.name} has a directory", chapters.isNotEmpty())
                    val last = chapters.last()
                    val content = api.getBookContent(book.bookUrl, last.index, last.url)
                    assertTrue("${book.name} has content", content.isNotBlank())
                    for (image in Jsoup.parse(content).select("img[src]")) {
                        val url = image.attr("src")
                        val bytes = api.getBookImage(book.bookUrl, url)
                        assertTrue("${book.name} image bytes", bytes.size > 8)
                        assertEquals(0x89, bytes[0].toInt() and 0xff)
                        assertEquals('P'.code, bytes[1].toInt())
                        assertFalse(url.contains("access_token"))
                        imageCount++
                    }
                    book.durChapterIndex = last.index
                    book.durChapterPos = content.length / 3
                    book.durChapterTime = System.currentTimeMillis()
                    assertTrue(api.saveBookProgress(book))
                    val other = ReaderServerApi(base, "legadocompat", "test-password-only", { http }, {})
                    val restored = other.getBookProgress(book.copy(
                        durChapterIndex = 0, durChapterPos = 0, durChapterTime = 0
                    ))
                    assertNotNull(restored)
                    assertEquals(last.index, restored!!.durChapterIndex)
                    assertEquals(book.durChapterTime, restored.durChapterTime)
                    assertEquals(book.durChapterPos.toDouble(), restored.durChapterPos.toDouble(), 1.0)
                }
                assertTrue("EPUB and comic pictures", imageCount >= 2)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
