package io.legado.app.ui.book.manga

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.viewbinding.ViewBinding
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.MangaPageSnapHelper
import io.legado.app.ui.book.manga.recyclerview.MangaVH
import io.legado.app.ui.book.manga.recyclerview.WebtoonFrame
import io.legado.app.ui.book.manga.recyclerview.WebtoonRecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    qualifiers = "w540dp-h1200dp-xhdpi")
class MangaViewportScrollTest {
    private class Binding(private val view: View) : ViewBinding {
        override fun getRoot() = view
    }

    private class PageHolder(val frame: FrameLayout) : MangaVH<Binding>(Binding(frame), frame.context) {
        val image = AppCompatImageView(frame.context).apply {
            adjustViewBounds = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        init {
            frame.addView(image)
            val progress = FrameLayout(frame.context).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            frame.addView(progress)
            initComponent(ProgressBar(frame.context), image, TextView(frame.context), flProgress = progress)
        }
        fun bind(pageBased: Boolean, horizontal: Boolean = false, lastImage: Boolean = true) {
            // The listener applies layout before Glide assigns the loaded drawable.
            applyImageLayout(horizontal, isLastImage = lastImage, pageBased = pageBased)
            image.setImageDrawable(object : ColorDrawable(Color.GRAY) {
                override fun getIntrinsicWidth() = 1200
                override fun getIntrinsicHeight() = 675
            })
        }
    }

    private class PageAdapter(private val pageBased: Boolean, private val horizontal: Boolean = false) : ListAdapter<Int, PageHolder>(
        AsyncDifferConfig.Builder(object : DiffUtil.ItemCallback<Int>() {
            override fun areItemsTheSame(old: Int, new: Int) = old == new
            override fun areContentsTheSame(old: Int, new: Int) = old == new
        }).setBackgroundThreadExecutor { it.run() }.setMainThreadExecutor { it.run() }.build()
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -1)
            })
        override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(pageBased, horizontal)
    }

    private fun withReader(pageBased: Boolean, horizontal: Boolean = false,
        test: (WebtoonFrame, WebtoonRecyclerView, MangaLayoutManager) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().visible().get()
        try {
            val frame = WebtoonFrame(activity)
            val recycler = WebtoonRecyclerView(activity)
            recycler.lockPageSwipeDirection = pageBased
            frame.addView(recycler, FrameLayout.LayoutParams(-1, -1))
            val manager = MangaLayoutManager(activity)
            if (horizontal) manager.orientation = RecyclerView.HORIZONTAL
            recycler.layoutManager = manager
            recycler.itemAnimator = null
            recycler.adapter = PageAdapter(pageBased, horizontal).apply { submitList(listOf(0, 1, 2)) }
            activity.setContentView(frame)
            settle()
            test(frame, recycler, manager)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    @Test
    fun shortImageChaptersMustNotLeaveTheReaderWithoutAScrollRange() = withReader(true) { _, recycler, manager ->
        assertTrue("Adjacent EPUB chapters must stay outside the current screen", recycler.canScrollVertically(1))
        assertEquals(recycler.height, recycler.getChildAt(0).height)
        assertEquals(0, manager.findFirstVisibleItemPosition())
        assertEquals(0, manager.findLastVisibleItemPosition())
        val holder = recycler.findViewHolderForAdapterPosition(0) as PageHolder
        assertEquals(ImageView.ScaleType.FIT_CENTER, holder.image.scaleType)
    }

    @Test
    fun verticalSwipesTurnToNextAndPreviousImageWithoutChapterButtons() = withReader(true) { frame, recycler, manager ->
        val seen = arrayListOf<Int>()
        recycler.setPreScrollListener { _, _, _, position -> seen += position }
        MangaPageSnapHelper().configure(recycler)
        manager.scrollToPositionWithOffset(1, 0)
        settle()
        swipe(frame, up = true)
        settle()
        assertEquals(2, manager.findFirstCompletelyVisibleItemPosition())
        assertEquals(2, seen.last())
        swipe(frame, up = false)
        settle()
        assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
        assertEquals(1, seen.last())
    }

    @Test
    fun normalWebMangaKeepsItsContinuousImageLayout() = withReader(false) { _, recycler, _ ->
        val holder = recycler.findViewHolderForAdapterPosition(0) as PageHolder
        assertEquals(ImageView.ScaleType.FIT_XY, holder.image.scaleType)
        assertTrue("The final continuous image retains its minimum height", holder.itemView.minimumHeight > 0)
        holder.bind(pageBased = false, lastImage = false)
        settle()
        assertEquals("Earlier images must not reserve chapter-end space", 0, holder.itemView.minimumHeight)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, holder.itemView.layoutParams.height)
        holder.bind(pageBased = false, lastImage = true)
        settle()
        assertEquals(recycler.resources.displayMetrics.heightPixels * 2 / 3, holder.itemView.minimumHeight)
    }

    @Test
    fun clickVolumeAndAutomaticPagingShareTheAnimationPreference() {
        for (horizontal in listOf(false, true)) {
            for (animated in listOf(false, true)) {
                withReader(true, horizontal) { frame, recycler, manager ->
                    recycler.context.applicationContext.injectAsAppCtx()
                    AppConfig.enableMangaHorizontalScroll = horizontal
                    AppConfig.disableMangaPageAnim = !animated
                    AppConfig.disableHorizontalPageSnap = false
                    val previousChapter = ReadManga.curMangaChapter
                    ReadManga.curMangaChapter = MangaChapter.create(
                        BookChapter(), listOf(MangaPage(0, 3)), false, true
                    )
                    try {
                        val activity = mangaActivityFor(recycler)
                        val expectedState = if (animated) RecyclerView.SCROLL_STATE_SETTLING else RecyclerView.SCROLL_STATE_IDLE
                        val clickNext = ReadMangaActivity::class.java.getDeclaredMethod("scrollToNext").apply {
                            isAccessible = true
                        }
                        var stateAfterClick = -1
                        frame.onNextPage {
                            clickNext.invoke(activity)
                            stateAfterClick = recycler.scrollState
                        }
                        for (entry in listOf("click", "volume", "automatic")) {
                            manager.scrollToPositionWithOffset(1, 0)
                            settle()
                            when (entry) {
                                "click" -> {
                                    val downTime = SystemClock.uptimeMillis() + 1000
                                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                                        MotionEvent.obtain(downTime, downTime + if (action == MotionEvent.ACTION_UP) 60 else 0,
                                            action, frame.width * .85f, frame.height * .85f, 0).let {
                                            frame.dispatchTouchEvent(it)
                                            it.recycle()
                                        }
                                    }
                                }
                                "volume" -> {
                                    activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null)
                                    assertEquals(expectedState, recycler.scrollState)
                                }
                                "automatic" -> {
                                    activity.scrollPage()
                                    assertEquals(expectedState, recycler.scrollState)
                                }
                            }
                            settle()
                            if (entry == "click") assertEquals(expectedState, stateAfterClick)
                            assertEquals("$entry paging: horizontal=$horizontal, animated=$animated",
                                2, manager.findFirstCompletelyVisibleItemPosition())
                            activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null)
                            assertEquals(expectedState, recycler.scrollState)
                            settle()
                            assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
                        }
                    } finally {
                        ReadManga.curMangaChapter = previousChapter
                    }
                }
            }
        }
    }

    @Test
    fun continuousAutoScrollStaysSmoothWhenPageAnimationIsDisabled() = withReader(true) { _, recycler, manager ->
        recycler.context.applicationContext.injectAsAppCtx()
        AppConfig.enableMangaHorizontalScroll = false
        AppConfig.disableMangaPageAnim = true
        val activity = mangaActivityFor(recycler)
        ReadMangaActivity::class.java.getDeclaredField("enableAutoScroll").apply {
            isAccessible = true
            setBoolean(activity, true)
        }
        ReadMangaActivity::class.java.getDeclaredMethod("updatePageSnap").apply {
            isAccessible = true
            invoke(activity)
        }
        assertNull(recycler.onFlingListener)
        activity.scrollBy(100)
        assertEquals("Continuous auto-scroll must retain the duration-based smooth call",
            RecyclerView.SCROLL_STATE_SETTLING, recycler.scrollState)
        assertEquals(0, manager.findFirstVisibleItemPosition())
        settle()
        assertTrue(manager.findFirstVisibleItemPosition() > 0)
    }

    @Test
    fun repeatedSwipesKeepWorkingAsTheThreeChapterWindowMoves() {
        verifyMovingWindow(animationsEnabled = true)
        verifyMovingWindow(animationsEnabled = false)
    }

    private fun verifyMovingWindow(animationsEnabled: Boolean) = withReader(true) { frame, recycler, manager ->
        val adapter = recycler.adapter as PageAdapter
        var current = 30
        adapter.submitList(listOf(29, 30, 31))
        MangaPageSnapHelper().configure(recycler, animationsEnabled = animationsEnabled)
        manager.scrollToPositionWithOffset(1, 0)
        settle()
        recycler.setPreScrollListener { _, _, _, position ->
            val chapter = adapter.currentList[position]
            if (chapter != current) {
                current = chapter
                recycler.post { adapter.submitList(listOf(chapter - 1, chapter, chapter + 1)) }
            }
        }
        for (expected in 31..35) {
            swipe(frame, up = true)
            settle()
            assertEquals("Forward swipe (animations=$animationsEnabled) must reach chapter $expected", expected, current)
            assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
        }
        for (expected in 34 downTo 30) {
            swipe(frame, up = false)
            settle()
            assertEquals("Backward swipe (animations=$animationsEnabled) must reach chapter $expected", expected, current)
            assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
        }
    }

    @Test
    fun recycledContinuousHolderBecomesAFullImagePage() = withReader(false) { _, recycler, _ ->
        val holder = recycler.findViewHolderForAdapterPosition(0) as PageHolder
        holder.bind(pageBased = true)
        settle()
        assertEquals(recycler.height, holder.itemView.height)
        assertEquals(0, holder.itemView.minimumHeight)
        assertEquals(ImageView.ScaleType.FIT_CENTER, holder.image.scaleType)
    }

    @Test
    fun horizontalGesturesWithVerticalDriftDoNotTurnVerticalPages() = withReader(true) { frame, recycler, manager ->
        MangaPageSnapHelper().configure(recycler)
        for (dx in listOf(-.7f, .7f)) {
            for (dy in listOf(-.04f, 0f, .04f)) {
                manager.scrollToPositionWithOffset(1, 0)
                settle()
                drag(frame, frame.width * dx, frame.height * dy)
                settle()
                assertEquals("Horizontal drag ($dx, $dy) must not turn a vertical page",
                    1, manager.findFirstCompletelyVisibleItemPosition())
            }
        }
    }

    @Test
    fun verticalGesturesWithHorizontalDriftDoNotTurnHorizontalPages() = withReader(true, horizontal = true) { frame, recycler, manager ->
        MangaPageSnapHelper().configure(recycler)
        for (dy in listOf(-.7f, .7f)) {
            for (dx in listOf(-.08f, 0f, .08f)) {
                manager.scrollToPositionWithOffset(1, 0)
                settle()
                drag(frame, frame.width * dx, frame.height * dy)
                settle()
                assertEquals("Vertical drag ($dx, $dy) must not turn a horizontal page",
                    1, manager.findFirstCompletelyVisibleItemPosition())
            }
        }
    }

    @Test
    fun horizontalSwipesStillTurnHorizontalPages() = withReader(true, horizontal = true) { frame, recycler, manager ->
        MangaPageSnapHelper().configure(recycler)
        manager.scrollToPositionWithOffset(1, 0)
        settle()
        drag(frame, -frame.width * .7f, 0f)
        settle()
        assertEquals(2, manager.findFirstCompletelyVisibleItemPosition())
        drag(frame, frame.width * .7f, 0f)
        settle()
        assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
    }

    @Test
    fun rejectedDragIsNotATapAndTheFollowingVerticalSwipeWorks() = withReader(true) { frame, recycler, manager ->
        var taps = 0
        frame.onNextPage { taps++ }
        frame.onPrevPage { taps++ }
        MangaPageSnapHelper().configure(recycler)
        manager.scrollToPositionWithOffset(1, 0)
        settle()
        drag(frame, -frame.width * .7f, -frame.height * .04f)
        settle()
        assertEquals(0, taps)
        assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
        swipe(frame, up = true)
        settle()
        assertEquals(2, manager.findFirstCompletelyVisibleItemPosition())
    }

    @Test
    fun zoomedImagesStillAllowHorizontalPanningInVerticalMode() = withReader(true) { frame, recycler, _ ->
        recycler.onScale(2f)
        settle()
        drag(frame, -frame.width * .5f, 0f)
        settle()
        assertEquals(2f, recycler.scaleX)
        assertTrue("Zoomed images must still pan sideways", recycler.x < 0f)
    }

    @Test
    fun diagonalSwipesAlongTheConfiguredDirectionStillTurnPages() {
        for (horizontal in listOf(false, true)) {
            withReader(true, horizontal) { frame, recycler, manager ->
                MangaPageSnapHelper().configure(recycler)
                manager.scrollToPositionWithOffset(1, 0)
                settle()
                drag(frame, -frame.width * if (horizontal) .7f else .04f,
                    -frame.height * if (horizontal) .04f else .7f)
                settle()
                assertEquals(2, manager.findFirstCompletelyVisibleItemPosition())
            }
        }
    }

    @Test
    fun pageTapZonesStillWorkWithDirectionLock() = withReader(true) { frame, _, _ ->
        var next = 0
        frame.onNextPage { next++ }
        val downTime = SystemClock.uptimeMillis() + 1000
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(downTime, downTime + if (action == MotionEvent.ACTION_UP) 60 else 0,
                action, frame.width * .85f, frame.height * .85f, 0).let {
                frame.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        settle()
        assertEquals(1, next)
    }

    @Test
    fun twoFingerPinchStillZoomsTheImage() = withReader(true) { frame, recycler, _ ->
        pinchAndPan(frame, recycler, rejectFirst = false, checkPan = false)
    }

    @Test
    fun disabledAnimationStillAlignsThePageAfterSlowDragging() {
        for (horizontal in listOf(false, true)) {
            withReader(true, horizontal) { frame, recycler, manager ->
                MangaPageSnapHelper().configure(recycler, animationsEnabled = false)
                manager.scrollToPositionWithOffset(1, 0)
                settle()
                val downTime = SystemClock.uptimeMillis() + 1000
                for (step in 0..12) {
                    val fraction = step / 12f
                    val action = if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
                    MotionEvent.obtain(downTime, downTime + step * 32L, action,
                        frame.width * if (horizontal) .8f - .3f * fraction else .5f,
                        frame.height * if (horizontal) .5f else .8f - .3f * fraction, 0).let {
                        frame.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                MotionEvent.obtain(downTime, downTime + 1500, MotionEvent.ACTION_UP,
                    frame.width * .5f, frame.height * .5f, 0).let {
                    frame.dispatchTouchEvent(it)
                    it.recycle()
                }
                settle()
                assertEquals("Disabling animation must retain page alignment",
                    manager.findFirstVisibleItemPosition(), manager.findLastVisibleItemPosition())
                assertNotEquals(RecyclerView.NO_POSITION, manager.findFirstCompletelyVisibleItemPosition())
            }
        }
    }

    @Test
    fun rejectedDragCanHandOverToPinchAndPanning() = withReader(true) { frame, recycler, _ ->
        pinchAndPan(frame, recycler, rejectFirst = true, checkPan = true)
    }

    @Test
    fun disabledAnimationJumpsToTheNextPageWhenFlinging() {
        for (horizontal in listOf(false, true)) {
            withReader(true, horizontal) { frame, recycler, manager ->
                MangaPageSnapHelper().configure(recycler, animationsEnabled = false)
                manager.scrollToPositionWithOffset(1, 0)
                settle()
                drag(frame, -frame.width * if (horizontal) .7f else 0f,
                    -frame.height * if (horizontal) 0f else .7f)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                assertEquals("Disabled animation should jump instead of animating between pages",
                    2, manager.findFirstCompletelyVisibleItemPosition())
                settle()
                drag(frame, frame.width * if (horizontal) .7f else 0f,
                    frame.height * if (horizontal) 0f else .7f)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                assertEquals(1, manager.findFirstCompletelyVisibleItemPosition())
                settle()
            }
        }
    }

    @Test
    fun pinchPanningWithoutPriorRejectionStillWorks() = withReader(true) { frame, recycler, _ ->
        pinchAndPan(frame, recycler, rejectFirst = false, checkPan = true)
    }

    private fun pinchAndPan(frame: WebtoonFrame, recycler: WebtoonRecyclerView, rejectFirst: Boolean, checkPan: Boolean) {
        val downTime = SystemClock.uptimeMillis() + 1000
        fun dispatch(action: Int, time: Long, left: Float, right: Float? = null) {
            val xs = listOfNotNull(left, right)
            val properties = Array(xs.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(xs.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = xs[index]
                    y = frame.height * .5f
                    pressure = 1f
                    size = 1f
                }
            }
            MotionEvent.obtain(downTime, downTime + time, action, xs.size, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).let {
                frame.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0, frame.width * if (rejectFirst) .3f else .35f)
        if (rejectFirst) dispatch(MotionEvent.ACTION_MOVE, 16, frame.width * .35f)
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            32, frame.width * .35f, frame.width * .65f)
        for (step in 1..6) {
            dispatch(MotionEvent.ACTION_MOVE, 32L + step * 32,
                frame.width * (.35f - step * .025f), frame.width * (.65f + step * .025f))
        }
        val before = recycler.x
        if (checkPan) {
            for (step in 1..3) {
                dispatch(MotionEvent.ACTION_MOVE, 256L + step * 32,
                    frame.width * (.2f + step * .03f), frame.width * (.8f + step * .03f))
            }
        }
        val after = recycler.x
        dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            400, frame.width * if (checkPan) .29f else .2f,
            frame.width * if (checkPan) .89f else .8f)
        dispatch(MotionEvent.ACTION_UP, 432, frame.width * if (checkPan) .29f else .2f)
        settle()
        assertTrue("Direction lock must allow two-finger zoom", recycler.scaleX > 1f)
        if (checkPan) {
            assertEquals("Gesture takeover must not add an unintended zoom", 1.5f, recycler.scaleX, .05f)
            assertTrue("The zoomed image must pan in the same gesture", kotlin.math.abs(after - before) > 10f)
        }
    }

    private fun swipe(frame: View, up: Boolean) {
        drag(frame, 0f, frame.height * if (up) -.7f else .7f)
    }

    private fun drag(frame: View, dx: Float, dy: Float) {
        val downTime = SystemClock.uptimeMillis() + 1000
        val startX = frame.width * when {
            dx > 0 -> .15f
            dx < 0 -> .85f
            else -> .5f
        }
        val startY = frame.height * when {
            dy > 0 -> .15f
            dy < 0 -> .85f
            else -> .5f
        }
        for (step in 0..12) {
            val fraction = step / 12f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            MotionEvent.obtain(downTime, downTime + step * 32L, action,
                startX + dx * fraction, startY + dy * fraction, 0).let {
                frame.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }
}
