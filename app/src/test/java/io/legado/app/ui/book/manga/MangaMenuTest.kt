package io.legado.app.ui.book.manga

import android.app.Application
import android.view.Menu
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.WebtoonRecyclerView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class MangaMenuTest {
    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        AppConfig.enableMangaHorizontalScroll = false
        AppConfig.disableMangaPageAnim = false
        AppConfig.disableHorizontalPageSnap = false
    }

    private fun menuFor(activity: ReadMangaActivity): Menu = MenuBuilder(activity).also { menu ->
        SupportMenuInflater(activity).inflate(R.menu.book_manga, menu)
        ReadMangaActivity::class.java.getDeclaredMethod("upMenu", Menu::class.java).apply {
            isAccessible = true
            invoke(activity, menu)
        }
    }

    private fun withMenu(test: (ReadMangaActivity, Menu, WebtoonRecyclerView) -> Unit) {
        val recycler = WebtoonRecyclerView(RuntimeEnvironment.getApplication()).apply {
            layoutManager = MangaLayoutManager(context)
        }
        val activity = mangaActivityFor(recycler)
        test(activity, menuFor(activity), recycler)
    }

    @Test
    fun reenablingAnimationKeepsTheSnapCheckboxAndFirstClickConsistent() {
        AppConfig.enableMangaHorizontalScroll = true
        AppConfig.disableMangaPageAnim = true
        withMenu { activity, menu, recycler ->
            val animation = menu.findItem(R.id.menu_disable_manga_page_anim)
            val snap = menu.findItem(R.id.menu_disable_horizontal_page_snap)
            assertTrue(animation.isChecked)
            assertFalse(snap.isVisible)
            activity.onCompatOptionsItemSelected(animation)
            assertTrue(snap.isVisible)
            assertFalse("Animation must not masquerade as the snap preference", snap.isChecked)
            assertNotNull(recycler.onFlingListener)
            activity.onCompatOptionsItemSelected(snap)
            assertTrue("The first click must disable snapping", AppConfig.disableHorizontalPageSnap)
            assertTrue(snap.isChecked)
            assertNull(recycler.onFlingListener)
            activity.onCompatOptionsItemSelected(snap)
            assertFalse(AppConfig.disableHorizontalPageSnap)
            assertFalse(snap.isChecked)
            assertNotNull(recycler.onFlingListener)
        }
    }

    @Test
    fun animationTogglesAndMenuRecreationPreserveTheExplicitSnapPreference() {
        AppConfig.enableMangaHorizontalScroll = true
        AppConfig.disableHorizontalPageSnap = true
        withMenu { activity, menu, recycler ->
            val animation = menu.findItem(R.id.menu_disable_manga_page_anim)
            repeat(2) {
                activity.onCompatOptionsItemSelected(animation)
                assertTrue(AppConfig.disableHorizontalPageSnap)
                assertTrue(menu.findItem(R.id.menu_disable_horizontal_page_snap).isChecked)
                assertNull(recycler.onFlingListener)
            }
            val recreated = menuFor(activity)
            val snap = recreated.findItem(R.id.menu_disable_horizontal_page_snap)
            assertTrue(snap.isVisible)
            assertTrue(snap.isChecked)
            activity.onCompatOptionsItemSelected(snap)
            assertFalse(AppConfig.disableHorizontalPageSnap)
            assertNotNull(recycler.onFlingListener)
        }
    }

    @Test
    fun togglingAnimationInVerticalModeNeverShowsHorizontalSnapSettings() {
        AppConfig.disableMangaPageAnim = true
        withMenu { activity, menu, _ ->
            val animation = menu.findItem(R.id.menu_disable_manga_page_anim)
            repeat(2) {
                activity.onCompatOptionsItemSelected(animation)
                assertFalse(menu.findItem(R.id.menu_disable_horizontal_page_snap).isVisible)
            }
        }
    }

    @Test
    fun changingDirectionKeepsSnapVisibilityConsistentWithAnimationSettings() {
        withMenu { activity, menu, _ ->
            val direction = menu.findItem(R.id.menu_enable_horizontal_scroll)
            val animation = menu.findItem(R.id.menu_disable_manga_page_anim)
            val snap = menu.findItem(R.id.menu_disable_horizontal_page_snap)
            activity.onCompatOptionsItemSelected(direction)
            assertTrue(direction.isChecked)
            assertTrue(snap.isVisible)
            activity.onCompatOptionsItemSelected(animation)
            assertFalse(snap.isVisible)
            activity.onCompatOptionsItemSelected(direction)
            assertFalse(direction.isChecked)
            activity.onCompatOptionsItemSelected(direction)
            assertTrue(direction.isChecked)
            assertFalse("Changing direction must not reveal a hidden snap setting", snap.isVisible)
            activity.onCompatOptionsItemSelected(animation)
            assertTrue(snap.isVisible)
            assertFalse(snap.isChecked)
        }
    }
}
