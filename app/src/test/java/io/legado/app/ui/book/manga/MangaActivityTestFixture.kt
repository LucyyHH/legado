package io.legado.app.ui.book.manga

import io.legado.app.R
import io.legado.app.databinding.ActivityMangaBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.WebtoonRecyclerView
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import splitties.init.injectAsAppCtx

/** Run the actual menu/paging handlers without starting book downloads or sync. */
internal fun mangaActivityFor(recycler: WebtoonRecyclerView): ReadMangaActivity {
    RuntimeEnvironment.getApplication().injectAsAppCtx()
    val activity = Robolectric.buildActivity(ReadMangaActivity::class.java).get()
    activity.setTheme(R.style.AppTheme_Light)
    val manager = recycler.layoutManager as MangaLayoutManager
    val constructor = ActivityMangaBinding::class.java.declaredConstructors.single().apply {
        isAccessible = true
    }
    // These handlers only need the recycler; unrelated activity views stay uninitialized.
    val arguments = constructor.parameterTypes.map { type ->
        if (type == WebtoonRecyclerView::class.java) recycler else null
    }.toTypedArray()
    val binding = constructor.newInstance(*arguments) as ActivityMangaBinding
    for ((field, value) in listOf("binding\$delegate" to binding, "mLayoutManager\$delegate" to manager)) {
        ReadMangaActivity::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(activity, lazyOf(value))
        }
    }
    ReadMangaActivity::class.java.getDeclaredMethod("setHorizontalScroll", Boolean::class.javaPrimitiveType).apply {
        isAccessible = true
        invoke(activity, AppConfig.enableMangaHorizontalScroll)
    }
    return activity
}
