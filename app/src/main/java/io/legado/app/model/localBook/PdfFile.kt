package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.ReaderServerPdf
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SystemUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.ceil


class PdfFile(var book: Book) {
    companion object : BaseLocalBookParse {
        private var pFile: PdfFile? = null

        private data class SourceVersion(val path: String, val length: Long, val modifiedAt: Long)

        private fun sourceVersion(book: Book): SourceVersion? {
            if (!ReaderServerPdf.isPdf(book)) return null
            val file = ReaderServerPdf.cachedFile(book)
            return SourceVersion(file.absolutePath, file.length(), file.lastModified())
        }

        /**
         * pdf分页尺寸
         */
        const val PAGE_SIZE = 10

        @Synchronized
        private fun getPFile(book: Book): PdfFile {
            if (pFile == null || pFile?.book?.bookUrl != book.bookUrl ||
                pFile?.sourceVersion != sourceVersion(book)
            ) {
                pFile?.closePdf()
                pFile = PdfFile(book)
                return pFile!!
            }
            pFile?.book = book
            return pFile!!
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getPFile(book).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getPFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getPFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getPFile(book).getImage(href)
        }

        @Synchronized
        fun release(bookUrl: String? = null) {
            val current = pFile ?: return
            if (bookUrl == null || current.book.bookUrl == bookUrl) {
                current.closePdf()
                pFile = null
            }
        }

    }

    /**
     *持有引用，避免被回收
     */
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var released = false
    private val sourceVersion = sourceVersion(book)
    private val pdfRenderer: PdfRenderer?
        get() = if (released) null else renderer ?: readPdf()

    init {
        upBookCover(true)
    }

    /**
     * 读取PDF文件
     *
     * @return
     */
    private fun readPdf(): PdfRenderer? {
        val descriptor = if (ReaderServerPdf.isPdf(book)) {
            val file = ReaderServerPdf.cachedFile(book)
            if (!file.isFile) return null
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            val uri = book.getLocalUri()
            if (uri.isContentScheme()) {
                appCtx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            } else {
                ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        return try {
            PdfRenderer(descriptor).also {
                fileDescriptor = descriptor
                renderer = it
            }
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    /** Closing must never evaluate the lazy opener or reopen an invalid document. */
    private fun closePdf() {
        if (released) return
        released = true
        val currentRenderer = renderer
        val descriptor = fileDescriptor
        renderer = null
        fileDescriptor = null
        runCatching { currentRenderer?.close() }
        runCatching { descriptor?.close() }
    }


    /**
     * 渲染PDF页面
     * 根据index打开pdf页面,并渲染到Bitmap
     *
     * @param renderer
     * @param index
     * @return
     */
    private fun openPdfPage(renderer: PdfRenderer, index: Int): Bitmap? {
        if (index < 0 || index >= renderer.pageCount) {
            return null
        }
        return renderer.openPage(index).use { page ->
            createBitmap(
                SystemUtils.screenWidthPx,
                (SystemUtils.screenWidthPx.toDouble() * page.height / page.width).toInt()
            ).apply {
                this.eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }

    }

    private fun getContent(chapter: BookChapter): String? {
        val currentRenderer = pdfRenderer ?: return null
        val pageSize = if (ReaderServerPdf.isPdf(book)) 1 else PAGE_SIZE
        val start = chapter.index.toLong() * pageSize
        if (start < 0 || start >= currentRenderer.pageCount) return null
        val end = (start + pageSize).coerceAtMost(currentRenderer.pageCount.toLong()).toInt()
        return buildString {
            (start.toInt() until end).forEach { page ->
                append("<img src=\"").append(page).append("\" >\n")
            }
        }
    }

    private fun getImage(href: String): InputStream? {
        val index = href.toIntOrNull() ?: return null
        val currentRenderer = pdfRenderer ?: return null
        return try {
            openPdfPage(currentRenderer, index)?.let(BitmapUtils::toInputStream)
        } catch (_: Exception) {
            null
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()

        pdfRenderer?.let { renderer ->
            if (renderer.pageCount > 0) {
                val readerPdf = ReaderServerPdf.isPdf(book)
                val chapterCount = if (readerPdf) {
                    renderer.pageCount
                } else {
                    ceil((renderer.pageCount.toDouble() / PAGE_SIZE)).toInt()
                }
                (0 until chapterCount).forEach {
                    val chapter = BookChapter()
                    chapter.index = it
                    chapter.bookUrl = book.bookUrl
                    chapter.title = if (readerPdf) "第${it + 1}页" else "分段_${it}"
                    chapter.url = if (readerPdf) "reader-pdf:$it" else "pdf_$it"
                    chapterList.add(chapter)
                }
            }
        }
        return chapterList
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            pdfRenderer?.let { renderer ->
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!)).use { out ->
                    openPdfPage(renderer, 0)?.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (pdfRenderer == null) {
            pFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".pdf", "")
            }
        }

    }

    protected fun finalize() {
        closePdf()
    }
}
