package io.legado.app.help.glide

import android.app.Application
import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.RequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ImageLoaderModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    private fun withLoaders(test: (List<Pair<String, (String?) -> RequestBuilder<*>>>) -> Unit) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val fragment = Fragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "image-loader").commitNow()
        try {
            test(listOf(
                "drawable" to { path -> ImageLoader.load(activity, path) },
                "fragment" to { path -> ImageLoader.load(fragment, fragment.lifecycle, path) },
                "bitmap" to { path -> ImageLoader.loadBitmap(activity, path) },
                "file" to { path -> ImageLoader.loadFile(activity, path) }
            ))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun RequestBuilder<*>.model(): Any? =
        RequestBuilder::class.java.getDeclaredField("model").apply { isAccessible = true }.get(this)

    @Test
    fun allLoadersPreserveNetworkContentAndEmptyModels() = withLoaders { loaders ->
        val content = "content://test.images/page%201.jpg"
        for ((path, expected) in listOf(
            null to null,
            "" to "",
            "https://example.invalid/page%201.jpg" to "https://example.invalid/page%201.jpg",
            "HTTP://example.invalid/page.jpg" to "HTTP://example.invalid/page.jpg",
            content to Uri.parse(content)
        )) {
            for ((name, load) in loaders) assertEquals("$name: $path", expected, load(path).model())
        }
    }

    @Test
    fun allLoadersDecodeCachedFileUrisAndPreserveLiteralPaths() = withLoaders { loaders ->
        val folder = temporaryFolder.newFolder("图片 + % #")
        val file = File(folder, "page %20 一.jpg").apply { writeText("cached page") }
        for (path in listOf(file.toURI().toString(), file.absolutePath, Uri.fromFile(file).toString())) {
            for ((name, load) in loaders) {
                val model = load(path).model()
                assertEquals("$name: $path", file, model)
                assertEquals("cached page", (model as File).readText())
            }
        }
    }

    @Test
    fun malformedFileUrisRetainTheOriginalGlideFallback() = withLoaders { loaders ->
        val invalid = "file:///invalid path.jpg"
        for ((name, load) in loaders) assertEquals(name, invalid, load(invalid).model())
    }

    @Test
    fun dataUrlsKeepTheirExistingFileDownloadBehavior() = withLoaders { loaders ->
        val data = "data:image/png;base64,aGVsbG8="
        for ((name, load) in loaders) {
            assertEquals(name, if (name == "file") File(data) else data, load(data).model())
        }
    }
}
