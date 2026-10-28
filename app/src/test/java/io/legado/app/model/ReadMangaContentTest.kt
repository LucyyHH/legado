package io.legado.app.model

import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReadMangaContentTest {
    @After
    fun clearReadingWindow() {
        ReadManga.clearMangaChapter()
        ReadManga.durChapterIndex = 0
        ReadManga.durChapterPos = 0
    }

    private fun chapter(index: Int, pageBased: Boolean, count: Int = 1) = MangaChapter.create(
        BookChapter(index = index, title = "000$index.jpg"),
        (0 until count).map { page ->
            MangaPage(chapterIndex = index, chapterSize = 8, index = page, mImageUrl = "$index/$page")
        },
        hideTitle = false, pageBased = pageBased
    )

    @Test
    fun continuousEpubWindowKeepsPreviousAndNextImagesAroundTheSavedPage() {
        val chapters = (0..7).map { chapter(it, pageBased = true) }
        for (current in listOf(3, 4, 5, 6, 5, 4, 3, 2, 1, 0, 7, 4)) {
            ReadManga.durChapterIndex = current
            ReadManga.durChapterPos = 0
            ReadManga.prevMangaChapter = chapters.getOrNull(current - 1)
            ReadManga.curMangaChapter = chapters[current]
            ReadManga.nextMangaChapter = chapters.getOrNull(current + 1)

            val content = ReadManga.mangaContents
            val pages = content.items.map { it as MangaPage }
            val expected = ((current - 1).coerceAtLeast(0)..(current + 1).coerceAtMost(7)).toList()
            assertEquals(expected, pages.map { it.chapterIndex })
            assertEquals(current, pages[content.pos].chapterIndex)
            assertTrue(content.curFinish)
            assertEquals(current < 7, content.nextFinish)
            if (current > 0) assertEquals(current - 1, pages[content.pos - 1].chapterIndex)
            if (current < 7) assertEquals(current + 1, pages[content.pos + 1].chapterIndex)
        }
    }

    @Test
    fun mixedChapterBoundariesDoNotChangeTheSavedImagePosition() {
        ReadManga.prevMangaChapter = chapter(2, pageBased = false, count = 2)
        ReadManga.curMangaChapter = chapter(3, pageBased = true, count = 3)
        ReadManga.nextMangaChapter = chapter(4, pageBased = false, count = 2)
        ReadManga.durChapterIndex = 3
        ReadManga.durChapterPos = 2
        val content = ReadManga.mangaContents
        val page = content.items[content.pos] as MangaPage
        assertEquals(3, page.chapterIndex)
        assertEquals(2, page.index)
        assertEquals(6, content.pos)
        assertEquals(2, ReadManga.durChapterPos)
    }
}
