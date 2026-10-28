package io.legado.app.ui.book.manga.entities

import io.legado.app.data.entities.BookChapter

data class MangaChapter(
    val chapter: BookChapter,
    val pages: List<BaseMangaPage>,
    val imageCount: Int
) {
    fun imagePosition(index: Int): Int =
        pages.indexOfFirst { it is MangaPage && it.index == index }.coerceAtLeast(0)

    companion object {
        fun create(
            chapter: BookChapter,
            images: List<MangaPage>,
            hideTitle: Boolean,
            pageBased: Boolean
        ): MangaChapter {
            val count = images.size
            val imagePages = images.map { it.copy(imageCount = count, pageBased = pageBased) }
            if ((hideTitle || pageBased) && count > 0) return MangaChapter(chapter, imagePages, count)
            val pages = if (count == 0 && chapter.isVolume) {
                listOf(ReaderLoading(chapter.index, -1, chapter.title, true))
            } else {
                listOf(ReaderLoading(chapter.index, -1, "阅读 ${chapter.title}")) + imagePages +
                    ReaderLoading(chapter.index, count, "已读完 ${chapter.title}")
            }
            return MangaChapter(chapter, pages, count)
        }
    }
}
