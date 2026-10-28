package io.legado.app.help.book

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ReaderServerSync
import io.legado.app.utils.postEvent

/**
 * Caches Reader Server chapters as one atomic completion step.
 *
 * Text is deliberately persisted before images. If an image download fails, the next attempt
 * reuses that text and retries only the missing images. A SAVE_CONTENT event is emitted only
 * once both text and all images have been written.
 */
object ReaderServerCache {

    suspend fun cacheChapter(
        book: Book,
        chapter: BookChapter,
        concurrency: Int = 1
    ): String {
        if (ReaderServerPdf.isPdf(book)) {
            ReaderServerPdf.ensureDownloaded(book)
            val content = io.legado.app.model.localBook.PdfFile.getContent(book, chapter)
            if (content.isNullOrBlank()) throw NoStackTraceException("PDF 页面内容为空")
            postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
            return content
        }
        return cacheChapter(object : ChapterCache {
            override suspend fun cachedContent(): String? = BookHelp.getContent(book, chapter)

            override fun isComplete(): Boolean = BookHelp.hasImageContent(book, chapter)

            override suspend fun fetchContent(): String {
                ReaderServerSync.initConfig()
                return ReaderServerSync.getBookContent(book.bookUrl, chapter.index, chapter.url)
                    .getOrThrow()
            }

            override suspend fun saveContent(content: String) {
                BookHelp.saveText(book, chapter, content)
            }

            override suspend fun saveImages(content: String) {
                BookHelp.saveImages(null, book, chapter, content, concurrency, throwOnError = true)
            }

            override fun notifyComplete() {
                postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
            }
        })
    }

    /** Narrow seam for unit tests; production code should use [cacheChapter]. */
    internal interface ChapterCache {
        suspend fun cachedContent(): String?
        fun isComplete(): Boolean
        suspend fun fetchContent(): String
        suspend fun saveContent(content: String)
        suspend fun saveImages(content: String)
        fun notifyComplete()
    }

    internal suspend fun cacheChapter(cache: ChapterCache): String {
        val cached = cache.cachedContent()?.takeIf { it.isNotBlank() }
        if (cached != null) {
            if (cache.isComplete()) return cached
            cache.saveImages(cached)
            cache.notifyComplete()
            return cached
        }

        val content = cache.fetchContent()
        if (content.isBlank()) throw NoStackTraceException("章节内容为空")
        cache.saveContent(content)
        cache.saveImages(content)
        cache.notifyComplete()
        return content
    }
}
