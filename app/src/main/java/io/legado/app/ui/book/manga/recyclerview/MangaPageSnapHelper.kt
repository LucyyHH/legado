package io.legado.app.ui.book.manga.recyclerview

import android.view.View
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

internal class MangaPageSnapHelper : PagerSnapHelper() {
    private var reader: WebtoonRecyclerView? = null

    fun configure(
        recyclerView: WebtoonRecyclerView,
        enabled: Boolean = true,
        animationsEnabled: Boolean = true
    ) {
        reader = recyclerView
        recyclerView.pageAnimationsEnabled = animationsEnabled
        attachToRecyclerView(if (enabled) recyclerView else null)
    }

    override fun createScroller(layoutManager: RecyclerView.LayoutManager): RecyclerView.SmoothScroller? {
        val view = reader ?: return null
        if (view.pageAnimationsEnabled) return super.createScroller(layoutManager)
        return object : LinearSmoothScroller(view.context) {
            override fun onSeekTargetStep(dx: Int, dy: Int, state: RecyclerView.State, action: Action) {
                action.jumpTo(targetPosition)
            }

            override fun onTargetFound(targetView: View, state: RecyclerView.State, action: Action) {
                action.jumpTo(targetPosition)
            }
        }
    }
}
