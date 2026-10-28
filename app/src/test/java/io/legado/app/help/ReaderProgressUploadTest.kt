package io.legado.app.help

import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressUploadTest {

    @Test
    fun `upload uses entry snapshot and success writes only the receipt`() = runBlocking {
        val book = Book(
            bookUrl = "book-url",
            name = "before",
            customIntro = "before intro",
            durChapterIndex = 2,
            durChapterPos = 20,
            durChapterTime = 100,
        )
        val uploadStarted = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        lateinit var uploaded: Book
        val receipts = mutableListOf<Triple<String, Long, Long>>()

        val result = async {
            uploadReaderProgress(
                book = book,
                upload = { snapshot ->
                    uploaded = snapshot
                    uploadStarted.complete(Unit)
                    finishUpload.await()
                    true
                },
                markSynced = { url, progressTime, syncTime ->
                    receipts += Triple(url, progressTime, syncTime)
                },
                now = { 999 },
            )
        }

        uploadStarted.await()
        book.name = "after"
        book.customIntro = "after intro"
        book.durChapterIndex = 5
        book.durChapterPos = 50
        book.durChapterTime = 200
        finishUpload.complete(Unit)

        assertTrue(result.await())
        assertNotSame(book, uploaded)
        assertEquals("before", uploaded.name)
        assertEquals("before intro", uploaded.customIntro)
        assertEquals(2, uploaded.durChapterIndex)
        assertEquals(20, uploaded.durChapterPos)
        assertEquals(100L, uploaded.durChapterTime)
        assertEquals(listOf(Triple("book-url", 100L, 999L)), receipts)
        assertEquals("after", book.name)
        assertEquals("after intro", book.customIntro)
        assertEquals(5, book.durChapterIndex)
        assertEquals(50, book.durChapterPos)
        assertEquals(200L, book.durChapterTime)
    }

    @Test
    fun `false upload throws and does not write a receipt`() {
        val receipts = mutableListOf<Triple<String, Long, Long>>()

        val failure = assertThrows(NoStackTraceException::class.java) {
            runBlocking {
                uploadReaderProgress(
                    book = Book(bookUrl = "book-url", durChapterTime = 100),
                    upload = { false },
                    markSynced = { url, progressTime, syncTime ->
                        receipts += Triple(url, progressTime, syncTime)
                    },
                )
            }
        }

        assertTrue(failure.message.orEmpty().contains("上传阅读进度失败"))
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun `upload exception does not write a receipt`() {
        var marked = false

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                uploadReaderProgress(
                    book = Book(bookUrl = "book-url", durChapterTime = 100),
                    upload = { error("network failed") },
                    markSynced = { _, _, _ -> marked = true },
                )
            }
        }

        assertEquals("network failed", failure.message)
        assertFalse(marked)
    }

    @Test
    fun `upload cancellation does not write a receipt`() {
        var marked = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                uploadReaderProgress(
                    book = Book(bookUrl = "book-url", durChapterTime = 100),
                    upload = { throw CancellationException("cancelled") },
                    markSynced = { _, _, _ -> marked = true },
                )
            }
        }

        assertFalse(marked)
    }
}
