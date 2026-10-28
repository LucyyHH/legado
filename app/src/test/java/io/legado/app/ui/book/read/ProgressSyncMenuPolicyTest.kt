package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSyncMenuPolicyTest {

    @Test
    fun `reader book exposes manual sync without webdav`() {
        val targets = progressSyncMenuTargets(
            inBookshelf = true,
            readerBook = true,
            readerConfigured = true,
            readerProgressEnabled = true,
            webDavReady = false,
        )

        assertTrue(targets.visible)
        assertTrue(targets.readerServer)
        assertFalse(targets.webDav)
    }

    @Test
    fun `disabled reader progress does not expose a reader-only menu`() {
        val targets = progressSyncMenuTargets(
            inBookshelf = true,
            readerBook = true,
            readerConfigured = true,
            readerProgressEnabled = false,
            webDavReady = false,
        )

        assertFalse(targets.visible)
        assertFalse(targets.readerServer)
        assertFalse(targets.webDav)
    }

    @Test
    fun `webdav remains available for a non-reader book`() {
        val targets = progressSyncMenuTargets(
            inBookshelf = true,
            readerBook = false,
            readerConfigured = true,
            readerProgressEnabled = true,
            webDavReady = true,
        )

        assertTrue(targets.visible)
        assertFalse(targets.readerServer)
        assertTrue(targets.webDav)
    }

    @Test
    fun `books outside shelf expose no manual progress targets`() {
        val targets = progressSyncMenuTargets(
            inBookshelf = false,
            readerBook = true,
            readerConfigured = true,
            readerProgressEnabled = true,
            webDavReady = true,
        )

        assertFalse(targets.visible)
        assertFalse(targets.readerServer)
        assertFalse(targets.webDav)
    }
}
