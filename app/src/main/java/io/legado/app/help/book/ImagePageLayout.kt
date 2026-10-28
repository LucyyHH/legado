package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.jsoup.Jsoup

/** Layout decisions shared by the text and manga readers for document pages. */
internal object ImagePageLayout {
    fun isPageBased(book: Book?, chapter: BookChapter, content: String): Boolean {
        if (book == null) return false
        if (chapter.isVolume) return false
        if (book.isPdf) return true
        if (!book.bookUrl.startsWith("reader-v1://")) return false
        return when (book.variableMap["readerV1Format"]?.lowercase()) {
            "cbz", "comic-dir", "image" -> true
            "epub" -> {
                val document = Jsoup.parseBodyFragment(content)
                val text = document.text().trim()
                document.select("img").isNotEmpty() &&
                    (text.isBlank() || text == chapter.title &&
                        chapter.title.matches(Regex("(?i).+\\.(?:jpe?g|png|gif|webp|avif|bmp)")))
            }
            else -> false
        }
    }

    fun showChapterTitle(pageBased: Boolean, normallyVisible: Boolean): Boolean =
        !pageBased && normallyVisible
}
