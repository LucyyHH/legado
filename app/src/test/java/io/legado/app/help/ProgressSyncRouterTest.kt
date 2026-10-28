package io.legado.app.help

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProgressSyncRouterTest {

    @Test
    fun `enhanced sync still runs Reader when WebDAV is disabled`() = runBlocking {
        val calls = mutableListOf<String>()

        val result = ProgressSyncRouter.run(
            webDavEnabled = false,
            readerServerEnabled = true,
            syncWebDav = { calls += "webdav"; true },
            syncReaderServer = { calls += "reader"; true },
            onFailure = { _, _ -> },
        )

        assertEquals(listOf("reader"), calls)
        assertTrue(result)
    }

    @Test
    fun `disabled Reader is not called`() = runBlocking {
        val calls = mutableListOf<String>()

        val result = ProgressSyncRouter.run(
            webDavEnabled = true,
            readerServerEnabled = false,
            syncWebDav = { calls += "webdav"; true },
            syncReaderServer = { calls += "reader"; true },
            onFailure = { _, _ -> },
        )

        assertEquals(listOf("webdav"), calls)
        assertTrue(result)
    }

    @Test
    fun `Reader failure does not hide WebDAV success or report total success`() = runBlocking {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<ProgressSyncRouter.Backend>()

        val result = ProgressSyncRouter.run(
            webDavEnabled = true,
            readerServerEnabled = true,
            syncWebDav = {
                calls += "webdav"
                true
            },
            syncReaderServer = {
                calls += "reader"
                error("reader failed")
            },
            onFailure = { backend, _ -> failures += backend },
        )

        assertEquals(listOf("webdav", "reader"), calls)
        assertEquals(listOf(ProgressSyncRouter.Backend.ReaderServer), failures)
        assertFalse(result)
    }

    @Test
    fun `WebDAV failure still allows Reader upload`() = runBlocking {
        var readerCalled = false
        val failures = mutableListOf<ProgressSyncRouter.Backend>()

        val result = ProgressSyncRouter.run(
            webDavEnabled = true,
            readerServerEnabled = true,
            syncWebDav = { error("webdav failed") },
            syncReaderServer = { readerCalled = true; true },
            onFailure = { backend, _ -> failures += backend },
        )

        assertTrue(readerCalled)
        assertFalse(result)
        assertEquals(listOf(ProgressSyncRouter.Backend.WebDav), failures)
    }

    @Test
    fun `false action and all disabled do not report success`() = runBlocking {
        val actionFailed = ProgressSyncRouter.run(
            webDavEnabled = true,
            readerServerEnabled = false,
            syncWebDav = { false },
            syncReaderServer = { true },
            onFailure = { _, _ -> },
        )
        val allDisabled = ProgressSyncRouter.run(
            webDavEnabled = false,
            readerServerEnabled = false,
            syncWebDav = { true },
            syncReaderServer = { true },
            onFailure = { _, _ -> },
        )

        assertFalse(actionFailed)
        assertFalse(allDisabled)
    }

    @Test
    fun `cancellation propagates and skips remaining backend`() = runBlocking {
        var readerCalled = false

        try {
            ProgressSyncRouter.run(
                webDavEnabled = true,
                readerServerEnabled = true,
                syncWebDav = { throw CancellationException("cancelled") },
                syncReaderServer = { readerCalled = true; true },
                onFailure = { _, _ -> },
            )
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(readerCalled)
    }
}
