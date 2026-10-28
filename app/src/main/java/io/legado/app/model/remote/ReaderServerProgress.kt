package io.legado.app.model.remote

/** Raw Reader progress, kept separate from shelf metadata until conflict resolution chooses it. */
data class ReaderServerProgress(
    val bookUrl: String,
    val locator: String,
    val updatedAt: Long,
    val chapterTitle: String? = null,
    val chapterIndex: Int? = null,
    val chapterPosition: Int = 0
)
