package io.legado.app.model

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookSettingsSnapshotTest {

    @Test
    fun `snapshot freezes target book and mutable reading settings before delayed save`() {
        val first = Book(name = "first", author = "author", bookUrl = "reader-v1://first").apply {
            charset = "GBK"
            setPageAnim(2)
            setUseReplaceRule(true)
        }

        val snapshot = snapshotBookSettings(first)
        first.bookUrl = "reader-v1://second"
        first.charset = "UTF-8"
        first.setPageAnim(4)
        first.setUseReplaceRule(false)

        assertEquals("reader-v1://first", snapshot.bookUrl)
        assertEquals("GBK", snapshot.charset)
        val saved = Book.Converters().stringToReadConfig(snapshot.readConfigJson)
        assertEquals(2, saved?.pageAnim)
        assertEquals(true, saved?.useReplaceRule)
        assertFalse(snapshot.readConfigJson.orEmpty().contains("reader-v1://second"))
    }
}
