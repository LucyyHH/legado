package io.legado.app.help

import com.google.gson.JsonParser
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import java.util.concurrent.Callable

/** Import Reader classifications additively; existing groups and memberships are never removed. */
internal class ReaderGroupImporter(private val database: AppDatabase) {
    data class Result(val created: Int = 0, val grouped: Boolean = false, val unavailable: List<String> = emptyList())

    fun importBook(remote: Book): Result {
        if (!remote.bookUrl.startsWith("reader-v1://")) return Result()
        val names = groupNames(remote)
        if (names.isEmpty()) return Result()
        return database.runInTransaction(Callable {
            if (!database.bookDao.has(remote.bookUrl)) return@Callable Result()
            val groups = database.bookGroupDao.all.toMutableList()
            // A dangling membership must not be reassigned to a different classification.
            var used = groups.filter { it.groupId > 0 }.fold(0L) { mask, group -> mask or group.groupId }
            if (used != Long.MAX_VALUE && names.any { name ->
                    groups.none { it.groupName == name && validId(it.groupId) }
                }) {
                database.bookDao.getGroupMasks().forEach { used = used or it }
            }
            var created = 0
            var mask = 0L
            val unavailable = mutableListOf<String>()
            for (name in names) {
                val existing = groups.firstOrNull { it.groupName == name && validId(it.groupId) }
                val id = if (existing != null) existing.groupId else {
                    // The sign bit is not supported by Legado's positive-ID group queries.
                    val free = (0..62).firstOrNull { used and (1L shl it) == 0L }?.let { 1L shl it }
                    if (free == null) {
                        unavailable += name
                        continue
                    }
                    val group = BookGroup(
                        groupId = free, groupName = name,
                        order = (groups.maxOfOrNull { it.order } ?: 0) + 1,
                        enableRefresh = false
                    )
                    database.bookGroupDao.insert(group)
                    groups += group
                    used = used or free
                    created++
                    free
                }
                mask = mask or id
            }
            Result(created, mask != 0L && database.bookDao.addReaderGroups(remote.bookUrl, mask) > 0, unavailable)
        })
    }

    private fun validId(id: Long): Boolean = id > 0 && id and (id - 1) == 0L

    private fun groupNames(book: Book): Set<String> = buildSet {
        book.variableMap["readerV1Series"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add("Reader · 系列 · $it")
        }
        book.variableMap["readerV1Tags"]?.let { raw ->
            for (tag in JsonParser.parseString(raw).asJsonArray) {
                tag.asString.trim().takeIf { it.isNotEmpty() }?.let { add("Reader · 标签 · $it") }
            }
        }
    }
}
