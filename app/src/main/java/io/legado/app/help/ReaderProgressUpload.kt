package io.legado.app.help

import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException

internal suspend fun uploadReaderProgress(
    book: Book,
    upload: suspend (Book) -> Boolean,
    markSynced: (String, Long, Long) -> Unit,
    now: () -> Long = System::currentTimeMillis,
): Boolean {
    val snapshot = book.copy()
    val bookUrl = snapshot.bookUrl
    val progressTime = snapshot.durChapterTime
    if (!upload(snapshot)) {
        throw NoStackTraceException("上传阅读进度失败")
    }
    markSynced(bookUrl, progressTime, now())
    return true
}
