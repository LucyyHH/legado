package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage
import org.junit.Assert.*
import org.junit.Test

class ImagePageLayoutTest {
    private fun readerBook(format: String) = Book(
        bookUrl = "reader-v1://scope/book", name = "完整书名",
        variable = "{\"readerV1Format\":\"$format\"}"
    )

    @Test
    fun pdfPageContainsNoSyntheticChapterTitleBeforeItsImage() {
        val chapter = BookChapter(index = 0, title = "第1页")
        val pageBased = ImagePageLayout.isPageBased(readerBook("pdf"), chapter, "<img src=\"0\">")
        assertTrue(pageBased)
        assertFalse("第1页 must not become a separate text page",
            ImagePageLayout.showChapterTitle(pageBased, true))
        assertFalse(ImagePageLayout.showChapterTitle(pageBased, false))
    }

    @Test
    fun imageOnlyEpubChapterProducesJustTheImageEvenWhenTitlesAreEnabled() {
        val chapter = BookChapter(index = 0, title = "0001.jpg")
        val pageBased = ImagePageLayout.isPageBased(readerBook("epub"), chapter, "<img src=\"0001.jpg\">")
        val assembled = MangaChapter.create(
            chapter, listOf(MangaPage(chapterIndex = 0, chapterSize = 8)),
            hideTitle = false, pageBased = pageBased
        )
        assertTrue(pageBased)
        assertEquals("No 阅读/已读完 items between document pages", 1, assembled.pages.size)
        assertTrue(assembled.pages.single() is MangaPage)
        assertEquals(0, assembled.imagePosition(0))
    }

    @Test
    fun textEpubAndWebMangaKeepTheirExistingChapterPresentation() {
        val chapter = BookChapter(index = 0, title = "第一章")
        assertFalse(ImagePageLayout.isPageBased(readerBook("epub"), chapter, "正文<img src=\"a.jpg\">"))
        assertFalse(ImagePageLayout.isPageBased(Book(bookUrl = "https://example.test/book"), chapter, "<img src=\"a.jpg\">"))
        assertTrue(ImagePageLayout.showChapterTitle(false, true))
        val assembled = MangaChapter.create(
            chapter, listOf(MangaPage(chapterIndex = 0, chapterSize = 8)), false, false
        )
        assertEquals(3, assembled.pages.size)
        assertEquals(1, assembled.imageCount)
        assertEquals(1, assembled.imagePosition(0))
    }

    @Test
    fun documentPageIndicesDoNotIncludeHiddenChapterBoundaries() {
        val chapter = BookChapter(index = 3, title = "0004.jpg")
        val images = listOf(
            MangaPage(chapterIndex = 3, chapterSize = 8, index = 0),
            MangaPage(chapterIndex = 3, chapterSize = 8, index = 1)
        )
        val continuous = MangaChapter.create(chapter, images, false, true)
        val titled = MangaChapter.create(chapter, images, false, false)
        val hidden = MangaChapter.create(chapter, images, true, false)
        assertEquals(1, continuous.imagePosition(1))
        assertEquals(2, titled.imagePosition(1))
        assertEquals(1, hidden.imagePosition(1))
        assertEquals(listOf(2, 2), continuous.pages.map { (it as MangaPage).imageCount })
    }

    @Test
    fun explicitVolumeHeadingsAndRealEpubTextRemainVisible() {
        val volume = BookChapter(index = 0, title = "第一卷", isVolume = true)
        assertFalse(ImagePageLayout.isPageBased(readerBook("epub"), volume, ""))
        val chapter = BookChapter(index = 0, title = "0001.jpg")
        assertFalse(ImagePageLayout.isPageBased(readerBook("epub"), chapter, "真正正文<img src=\"0001.jpg\">"))
        assertTrue(ImagePageLayout.isPageBased(readerBook("epub"), chapter, "0001.jpg\n<img src=\"0001.jpg\">"))
    }
}
