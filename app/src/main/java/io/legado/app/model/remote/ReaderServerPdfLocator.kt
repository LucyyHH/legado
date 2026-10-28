package io.legado.app.model.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Reader Web PDF locator. `block` is the one-based PDF page number. */
internal object ReaderServerPdfLocator {
    data class Position(val page: Int, val percent: Double)

    fun parse(value: String): Position? {
        val root = runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull() ?: return null
        if (root.string("chapter") != "document") return null
        val block = root.number("block") ?: return null
        if (!block.isFinite() || block < 1 || block > Int.MAX_VALUE) return null
        val page = block.toInt()
        if (page.toDouble() != block) return null
        val percent = root.number("percent")?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        return Position(page, percent)
    }

    /** Current Reader Web writes only a document-level r1 locator for its Blob viewer. */
    fun isDocumentLevel(value: String): Boolean {
        val position = ReaderServerLocator.parse(value) ?: return false
        return position.sectionId == "pdf" || position.sectionId == "document"
    }

    fun format(page: Int, percent: Double): String {
        require(page >= 1)
        val safePercent = percent.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        return JsonObject().apply {
            addProperty("chapter", "document")
            addProperty("spine", 0)
            addProperty("block", page)
            addProperty("ratio", 0)
            addProperty("percent", safePercent)
        }.toString()
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }?.asString

    private fun JsonObject.number(key: String): Double? =
        get(key)?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull()
        }
}
