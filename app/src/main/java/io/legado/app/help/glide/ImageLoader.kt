package io.legado.app.help.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.lifecycle
import java.io.File
import java.net.URI

//https://bumptech.github.io/glide/doc/generatedapi.html
//Instead of GlideApp, use com.bumptech.Glide
@Suppress("unused")
object ImageLoader {

    /** Cached manga pages use File.toURI(), while other callers pass raw paths. */
    internal fun localFile(path: String): File =
        if (path.startsWith("file:")) File(URI(path)) else File(path)

    private fun modelForPath(path: String?, allowDataUrl: Boolean = true): Any? = when {
        path.isNullOrEmpty() || path.isAbsUrl() || (allowDataUrl && path.isDataUrl()) -> path
        path.isContentScheme() -> Uri.parse(path)
        else -> runCatching { localFile(path) }.getOrElse { path }
    }

    /**
     * 自动判断path类型
     */
    fun load(context: Context, path: String?): RequestBuilder<Drawable> {
        return Glide.with(context).load(modelForPath(path))
    }

    fun load(fragment: Fragment, lifecycle: Lifecycle, path: String?): RequestBuilder<Drawable> {
        val requestManager = Glide.with(fragment).lifecycle(lifecycle)
        return requestManager.load(modelForPath(path))
    }

    fun loadBitmap(context: Context, path: String?): RequestBuilder<Bitmap> {
        val requestManager = Glide.with(context).`as`(Bitmap::class.java)
        return requestManager.load(modelForPath(path))
    }

    fun loadFile(context: Context, path: String?): RequestBuilder<File> {
        // File downloads historically treat data URLs as literal paths.
        return Glide.with(context).asFile().load(modelForPath(path, allowDataUrl = false))
    }

    fun load(context: Context, @DrawableRes resId: Int?): RequestBuilder<Drawable> {
        return Glide.with(context).load(resId)
    }

    fun load(context: Context, file: File?): RequestBuilder<Drawable> {
        return Glide.with(context).load(file)
    }

    fun load(context: Context, uri: Uri?): RequestBuilder<Drawable> {
        return Glide.with(context).load(uri)
    }

    fun load(context: Context, drawable: Drawable?): RequestBuilder<Drawable> {
        return Glide.with(context).load(drawable)
    }

    fun load(context: Context, bitmap: Bitmap?): RequestBuilder<Drawable> {
        return Glide.with(context).load(bitmap)
    }

    fun load(context: Context, bytes: ByteArray?): RequestBuilder<Drawable> {
        return Glide.with(context).load(bytes)
    }

}
