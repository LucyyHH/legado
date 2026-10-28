package io.legado.app.model.remote

import java.util.Locale

/** Reader Web's r1 locator: split only the first two colons; refs are opaque. */
internal object ReaderServerLocator {
    data class Position(val sectionId: String, val fraction: Double)
    data class TextSection(val id: String, val title: String, val text: String)

    fun parse(value: String): Position? {
        if (!value.startsWith("r1:")) return null
        val separator = value.indexOf(':', 3)
        if (separator < 0 || separator == value.lastIndex) return null
        val fraction = value.substring(3, separator).toDoubleOrNull() ?: return null
        if (!fraction.isFinite()) return null
        return Position(value.substring(separator + 1), fraction.coerceIn(0.0, 1.0))
    }

    fun format(sectionId: String, fraction: Double): String {
        require(sectionId.isNotEmpty())
        val safe = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        return "r1:${String.format(Locale.ROOT, "%.4f", safe)}:$sectionId"
    }

    // Match web/src/formats/txt.ts for TXT whose server manifest has no chapters.
    private val headings = listOf(
        Regex("^\\s*第\\s*[0-9０-９零一二三四五六七八九十百千万两]+\\s*[章回节卷部篇集]\\s*[:：.、\\-—]?\\s*(.{0,60})$"),
        Regex("^\\s*chapter\\s+([0-9]+|[ivxlcdm]+)\\b\\s*[:：.、\\-—]?\\s*(.{0,60})$", RegexOption.IGNORE_CASE),
        Regex("^\\s*[【（(\\[]\\s*第\\s*[0-9０-９零一二三四五六七八九十百千万两]+\\s*[章回节卷部篇集]\\s*[】）)\\]]\\s*(.{0,60})$"),
        Regex("^\\s*[卷部]\\s*[0-9０-９零一二三四五六七八九十百千万两]+\\s*(.{0,60})$")
    )

    fun splitText(value: String): List<TextSection> {
        val text = value.replace("\r\n", "\n").replace('\r', '\n')
        val chapters = mutableListOf<Pair<String, Int>>()
        var offset = 0
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.length in 1..80 && headings.any { it.matches(trimmed) }) {
                chapters.add(trimmed to offset)
            }
            offset += line.length + 1
        }
        if (chapters.isEmpty()) return chunks(text, 40_000).mapIndexed { index, body ->
            TextSection("chunk-$index", if (text.length <= 40_000) "正文" else "第 ${index + 1} 段", body)
        }
        val sections = mutableListOf<TextSection>()
        val preface = text.substring(0, chapters.first().second).trim()
        if (preface.isNotEmpty()) sections.add(TextSection("preface", "前言", preface))
        chapters.forEachIndexed { index, chapter ->
            val end = chapters.getOrNull(index + 1)?.second ?: text.length
            val parts = chunks(text.substring(chapter.second, end), 60_000)
            parts.forEachIndexed { part, body ->
                sections.add(TextSection("ch$index-$part", if (parts.size > 1) "${chapter.first} (${part + 1}/${parts.size})" else chapter.first, body))
            }
        }
        return sections
    }

    private fun chunks(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxLength).coerceAtMost(text.length)
            if (end < text.length) {
                val windowStart = start + (maxLength * 0.6).toInt()
                val window = text.substring(windowStart, end)
                val blank = window.lastIndexOf("\n\n")
                val newline = window.lastIndexOf('\n')
                if (blank >= 0) end = windowStart + blank + 2
                else if (newline >= 0) end = windowStart + newline + 1
            }
            val body = text.substring(start, end)
            if (body.isNotBlank()) result.add(body)
            start = end
        }
        return result
    }
}
