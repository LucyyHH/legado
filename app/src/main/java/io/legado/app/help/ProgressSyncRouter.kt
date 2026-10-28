package io.legado.app.help

import kotlinx.coroutines.CancellationException

/**
 * Runs the configured progress backends independently so one backend cannot prevent the other.
 */
internal object ProgressSyncRouter {

    enum class Backend {
        WebDav,
        ReaderServer,
    }

    suspend fun run(
        webDavEnabled: Boolean,
        readerServerEnabled: Boolean,
        syncWebDav: suspend () -> Boolean,
        syncReaderServer: suspend () -> Boolean,
        onFailure: (Backend, Throwable) -> Unit,
    ): Boolean {
        var enabled = false
        var succeeded = true
        if (webDavEnabled) {
            enabled = true
            val backendSucceeded = runBackend(Backend.WebDav, syncWebDav, onFailure)
            succeeded = succeeded && backendSucceeded
        }
        if (readerServerEnabled) {
            enabled = true
            val backendSucceeded = runBackend(Backend.ReaderServer, syncReaderServer, onFailure)
            succeeded = succeeded && backendSucceeded
        }
        return enabled && succeeded
    }

    private suspend fun runBackend(
        backend: Backend,
        action: suspend () -> Boolean,
        onFailure: (Backend, Throwable) -> Unit,
    ): Boolean {
        return try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(backend, error)
            false
        }
    }
}
