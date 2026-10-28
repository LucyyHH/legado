package io.legado.app.model.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReaderServerLocatorTest {
    @Test
    fun pdfLocatorUsesOneBasedPageAndAcceptsBrowserDocumentFallback() {
        val value = ReaderServerPdfLocator.format(3, 0.25)
        val parsed = ReaderServerPdfLocator.parse(value)!!
        assertEquals(3, parsed.page)
        assertEquals(0.25, parsed.percent, 0.0)
        assertTrue(ReaderServerPdfLocator.isDocumentLevel("r1:0.0000:pdf"))
        assertTrue(ReaderServerPdfLocator.isDocumentLevel("r1:0.0000:document"))
        assertFalse(ReaderServerPdfLocator.isDocumentLevel("r1:0.0000:chapter:1"))
        assertNull(ReaderServerPdfLocator.parse("{\"chapter\":\"document\",\"block\":0}"))
        assertNull(ReaderServerPdfLocator.parse("{\"chapter\":\"document\",\"block\":2147483648}"))
        assertNull(ReaderServerPdfLocator.parse("{\"chapter\":\"other\",\"block\":3}"))
    }

    @Test
    fun locatorPreservesOpaqueColonContainingSection() {
        val value = ReaderServerLocator.format("chapter:opaque:part%2Fname", 0.42)
        assertEquals("r1:0.4200:chapter:opaque:part%2Fname", value)
        val parsed = ReaderServerLocator.parse(value)!!
        assertEquals("chapter:opaque:part%2Fname", parsed.sectionId)
        assertEquals(0.42, parsed.fraction, 0.00001)
    }

    @Test
    fun locatorUsesDotDecimalAndRejectsUnknownOrMalformedVersions() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("r1:0.5000:xhtml:OEBPS/中文.xhtml", ReaderServerLocator.format("xhtml:OEBPS/中文.xhtml", .5))
        } finally {
            Locale.setDefault(previous)
        }
        assertNull(ReaderServerLocator.parse("r2:0.5:chapter:1"))
        assertNull(ReaderServerLocator.parse("r1:0.5:"))
        assertNull(ReaderServerLocator.parse("r1:NaN:chapter:1"))
        assertNull(ReaderServerLocator.parse("r1:Infinity:chapter:1"))
        assertEquals(1.0, ReaderServerLocator.parse("r1:3.5:chapter:1")!!.fraction, 0.0)
        assertEquals(0.0, ReaderServerLocator.parse("r1:-3.5:chapter:1")!!.fraction, 0.0)
    }

    @Test
    fun rawTextChunkIdsAndParagraphCutsMatchReaderWeb() {
        val first = "甲".repeat(25_000) + "\n\n"
        val second = "乙".repeat(30_000)
        val sections = ReaderServerLocator.splitText(first + second)
        assertEquals(listOf("chunk-0", "chunk-1"), sections.map { it.id })
        assertEquals(first, sections.first().text)
        assertEquals(second, sections.last().text)
        assertEquals(first + second, sections.joinToString("") { it.text })
    }

    @Test
    fun rawTextWithWebOnlyHeadingsKeepsPrefaceAndPartIds() {
        // Bracketed headings are detected by Web but not by Reader's TXT manifest.
        val text = "前言正文\r\n【第一章】开始\r\n" + "甲".repeat(61_000) + "\r\n卷二\r\n结束"
        val sections = ReaderServerLocator.splitText(text)
        assertEquals(listOf("preface", "ch0-0", "ch0-1", "ch1-0"), sections.map { it.id })
        assertEquals("前言正文", sections.first().text)
        assertTrue(sections[1].title.endsWith("(1/2)"))
        assertTrue(sections[2].title.endsWith("(2/2)"))
        assertFalse(sections.any { it.text.contains('\r') })
        assertTrue(sections.last().text.endsWith("结束"))
    }
}
