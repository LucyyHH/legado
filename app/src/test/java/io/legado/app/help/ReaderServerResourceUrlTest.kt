package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderServerResourceUrlTest {
    @Test
    fun acceptsOnlyConfiguredOriginAndReverseProxyBasePath() {
        val server = "https://reader.example/reader/"
        assertTrue(ReaderServerResourceUrl.isImageResource(server, "https://reader.example/reader/api/v1/books/abc/cover"))
        assertTrue(ReaderServerResourceUrl.isImageResource(server, "https://reader.example:443/reader/api/v1/books/abc/assets?ref=image.png"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://other.example/reader/api/v1/books/abc/cover"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://reader.example/api/v1/books/abc/cover"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "http://reader.example/reader/api/v1/books/abc/cover"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://reader.example:8443/reader/api/v1/books/abc/cover"))
    }

    @Test
    fun rejectsOtherEndpointsAndMalformedReferences() {
        val server = "https://reader.example"
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://reader.example/api/v1/books/abc/content"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://reader.example/api/v1/books/abc/cover/extra"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "/api/v1/books/abc/cover"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://reader.example/api/v1/books/a%2Fb/cover"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "https://user@reader.example/api/v1/books/abc/cover"))
        assertFalse(ReaderServerResourceUrl.isImageResource(server, "not a URL"))
    }
}
