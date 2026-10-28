package io.legado.app.help

import io.legado.app.data.entities.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderServerConfigTest {
    @Test
    fun existingSettingsKeepCredentialsAndSupportedSyncOptions() {
        val server = Server(
            type = Server.TYPE.READER_SERVER,
            config = """{
                "url":"https://reader.example/reader",
                "username":"reader-user",
                "password":"fixture-password",
                "token":"fixture-token",
                "tokenExpireTime":12345,
                "syncBookSource":false,
                "syncBookGroup":true,
                "syncRssSource":true,
                "syncBookshelf":false,
                "syncBookProgress":true
            }"""
        )

        val restored = server.getReaderServerConfig()
        assertNotNull(restored)
        assertEquals("https://reader.example/reader", restored!!.url)
        assertEquals("reader-user", restored.username)
        assertEquals("fixture-password", restored.password)
        assertEquals("fixture-token", restored.token)
        assertEquals(12345L, restored.tokenExpireTime)
        assertFalse(restored.syncBookshelf)
        assertTrue(restored.syncBookProgress)
    }
}
