package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import java.math.BigDecimal

/** Reader metadata affects the display label, never the book/cache identity. */
internal object ReaderBookDisplay {
    fun name(book: Book): String {
        if (!book.bookUrl.startsWith("reader-v1://") ||
            !book.variableMap["readerV1Format"].equals("pdf", true) ||
            book.name != book.variableMap["readerV1Title"]
        ) return book.name
        val series = book.variableMap["readerV1Series"]?.trim().orEmpty()
        if (series.isBlank() || book.name.contains(series, true)) return book.name
        val index = book.variableMap["readerV1SeriesIndex"]?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }
        return buildString {
            append(series)
            if (index != null) append(' ').append(index)
            append(" · ").append(book.name)
        }
    }

    private fun metadata(book: Book) = book.variableMap.filterKeys { it.startsWith("readerV1") }

    fun sameMetadata(local: Book, remote: Book): Boolean = metadata(local) == metadata(remote)

    fun mergeVariables(local: Book, remote: Book): String =
        GSON.toJson(local.variableMap.filterKeys { !it.startsWith("readerV1") } + metadata(remote))
}
