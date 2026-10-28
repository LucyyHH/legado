package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class ReaderBookDisplayTest {
    private fun pdf() = Book(
        bookUrl = "reader-v1://scope/pdf", name = "第一个实验对象",
        variable = """{"readerV1Format":"pdf","readerV1Title":"第一个实验对象","readerV1Series":"小裤裤笔记","readerV1SeriesIndex":"2.0"}"""
    )

    @Test
    fun fullDisplayNamePreservesBookAndOfflineCacheIdentity() {
        val book = pdf()
        val folder = book.getFolderName()
        assertEquals("小裤裤笔记 2 · 第一个实验对象", book.getDisplayName())
        assertEquals("第一个实验对象", book.name)
        assertEquals(folder, book.getFolderName())
    }

    @Test
    fun customNamesAndTitlesAlreadyContainingSeriesAreNotPrefixed() {
        assertEquals("我的自定义书名", pdf().copy(name = "我的自定义书名").getDisplayName())
        val original = pdf()
        val title = "小裤裤笔记 第一个实验对象"
        val book = original.copy(name = title,
            variable = GSON.toJson(original.variableMap + ("readerV1Title" to title)))
        assertEquals(book.name, book.getDisplayName())
        assertEquals("第一个实验对象", pdf().copy(bookUrl = "https://example.test/pdf").getDisplayName())
    }

    @Test
    fun mergingReaderMetadataPreservesCustomVariablesAndDoesNotRenameExistingBook() {
        val old = Book(bookUrl = "reader-v1://scope/pdf", name = "第一个实验对象",
            variable = """{"readerV1Format":"pdf","custom":"keep"}""")
        val remote = pdf()
        assertFalse(ReaderBookDisplay.sameMetadata(old, remote))
        val merged = old.copy(variable = ReaderBookDisplay.mergeVariables(old, remote))
        assertTrue(ReaderBookDisplay.sameMetadata(merged, remote))
        assertEquals("keep", merged.variableMap["custom"])
        assertEquals(old.name, merged.name)
        assertEquals("小裤裤笔记 2 · 第一个实验对象", merged.getDisplayName())
    }

    @Test
    fun missingOrInvalidVolumeNeverAddsNullOrNanToTheName() {
        val original = pdf()
        val book = original.copy(variable = GSON.toJson(original.variableMap + ("readerV1SeriesIndex" to "NaN")))
        assertEquals("小裤裤笔记 · 第一个实验对象", book.getDisplayName())
        val noSeries = book.copy(variable = GSON.toJson(book.variableMap + ("readerV1Series" to "")))
        assertEquals(noSeries.name, noSeries.getDisplayName())
    }
}
