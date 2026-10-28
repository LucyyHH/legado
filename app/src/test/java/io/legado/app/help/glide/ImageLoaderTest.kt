package io.legado.app.help.glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cachedEpubImagesRemainReadableWhenTurningForwardBackwardAndReopening() {
        val folder = temporaryFolder.newFolder("漫画 书架 #1 + %")
        val pages = (0..7).map { index ->
            File(folder, "page $index.jpg").apply { writeBytes(byteArrayOf(index.toByte())) }
        }
        // ReadManga uses File.toURI() for cached pages and retains one adjacent chapter
        // in each direction. Exercise those same sources as the reading window moves.
        for (current in listOf(3, 4, 5, 4, 3, 3)) {
            for (index in (current - 1)..(current + 1)) {
                val model = ImageLoader.localFile(pages[index].toURI().toString())
                assertTrue("Chapter $index must load from disk at chapter $current: $model", model.isFile)
                assertArrayEquals(byteArrayOf(index.toByte()), model.readBytes())
            }
        }
    }

    @Test
    fun rawFilePathsKeepLiteralSpacesAndPercentEscapes() {
        val image = temporaryFolder.newFile("图片 %20 + #.jpg").apply { writeText("cached") }
        assertEquals(image, ImageLoader.localFile(image.absolutePath))
        assertEquals("cached", ImageLoader.localFile(image.absolutePath).readText())
    }

    @Test
    fun androidFileUrisAlsoResolveToTheCachedFile() {
        val image = temporaryFolder.newFile("0001.jpg").apply { writeText("cached") }
        val androidUri = "file://" + image.absolutePath
        assertEquals(image, ImageLoader.localFile(androidUri))
        assertEquals("cached", ImageLoader.localFile(androidUri).readText())
    }
}
