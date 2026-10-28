package io.legado.app.help

/** Results include unsupported/disabled work so an empty sync never looks successful. */
data class ReaderServerSyncReport(val items: List<ReaderServerSyncItem>) {
    enum class Outcome { SUCCESS, PARTIAL_FAILURE, FAILURE, SKIPPED }

    val outcome: Outcome
        get() {
            val failed = items.any { it.state == ReaderServerSyncItem.State.FAILED }
            val completed = items.any {
                it.state == ReaderServerSyncItem.State.COMPLETED || it.hasSuccessfulChanges
            }
            return when {
                failed && completed -> Outcome.PARTIAL_FAILURE
                failed -> Outcome.FAILURE
                completed -> Outcome.SUCCESS
                else -> Outcome.SKIPPED
            }
        }

    fun summary(): String = items.joinToString("\n") { item ->
        val status = when (item.state) {
            ReaderServerSyncItem.State.COMPLETED -> "完成"
            ReaderServerSyncItem.State.FAILED -> "失败"
            ReaderServerSyncItem.State.SKIPPED -> "跳过"
        }
        "${item.name}（$status）：${item.detail}"
    }
}

data class ReaderServerSyncItem(
    val name: String,
    val state: State,
    val detail: String,
    val hasSuccessfulChanges: Boolean = false
) {
    enum class State { COMPLETED, FAILED, SKIPPED }

    companion object {
        fun completed(name: String, detail: String) = ReaderServerSyncItem(name, State.COMPLETED, detail)
        fun failed(name: String, detail: String, hasSuccessfulChanges: Boolean = false) =
            ReaderServerSyncItem(name, State.FAILED, detail, hasSuccessfulChanges)
        fun skipped(name: String, reason: String) = ReaderServerSyncItem(name, State.SKIPPED, reason)
    }
}
