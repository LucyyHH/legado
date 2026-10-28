package io.legado.app.ui.book.read

internal data class ProgressSyncMenuTargets(
    val readerServer: Boolean,
    val webDav: Boolean,
) {
    val visible: Boolean
        get() = readerServer || webDav
}

internal fun progressSyncMenuTargets(
    inBookshelf: Boolean,
    readerBook: Boolean,
    readerConfigured: Boolean,
    readerProgressEnabled: Boolean,
    webDavReady: Boolean,
): ProgressSyncMenuTargets {
    if (!inBookshelf) return ProgressSyncMenuTargets(readerServer = false, webDav = false)
    return ProgressSyncMenuTargets(
        readerServer = readerBook && readerConfigured && readerProgressEnabled,
        webDav = webDavReady,
    )
}
