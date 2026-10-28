package io.legado.app.help.book

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.legado.app.data.entities.Book
import io.legado.app.help.ReaderServerSync
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap

/** Downloads Reader v1 PDF documents into the normal per-book cache directory. */
object ReaderServerPdf {
    private const val cachedFileName = "reader.pdf"
    private const val partialFileSuffix = ".pdf.part"
    private val pdfHeader = byteArrayOf(
        '%'.code.toByte(),
        'P'.code.toByte(),
        'D'.code.toByte(),
        'F'.code.toByte(),
        '-'.code.toByte()
    )
    private val downloadLocks = Array(16) { Mutex() }
    private const val validationCacheSize = 32
    private val validationLock = Any()
    private val validationCache = object : LinkedHashMap<String, ValidationEntry>(
        validationCacheSize,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ValidationEntry>): Boolean =
            size > validationCacheSize
    }

    fun isPdf(book: Book): Boolean =
        book.bookUrl.startsWith("reader-v1://") &&
            book.variableMap["readerV1Format"].equals("pdf", ignoreCase = true)

    fun cachedFile(book: Book): File =
        File(File(BookHelp.cachePath, book.getFolderName()), cachedFileName)

    fun isCached(book: Book): Boolean = isValidated(documentCache(book))

    /** Drops validation for a document whose on-disk cache was removed or replaced. */
    fun forget(book: Book) = forget(cachedFile(book))

    /** Drops all remembered validation results after bulk cache maintenance. */
    fun forgetAll() {
        synchronized(validationLock) {
            validationCache.clear()
        }
    }

    suspend fun ensureDownloaded(book: Book): File =
        ensureDownloaded(book.bookUrl, documentCache(book))

    private fun documentCache(book: Book): DocumentCache =
        object : DocumentCache {
            override val targetFile: File = cachedFile(book)

            override suspend fun download(partialFile: File) {
                ReaderServerSync.downloadBookDocument(book.bookUrl, partialFile).getOrThrow()
            }

            override fun validate(file: File): Boolean = validatePdf(file)
        }

    private fun validatePdf(file: File): Boolean = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.pageCount > 0 }
        }
    }.getOrDefault(false)

    private fun hasPdfHeader(file: File): Boolean {
        if (!file.isFile || file.length() <= 5L) return false
        return runCatching {
            file.inputStream().buffered().use { input ->
                pdfHeader.indices.all { input.read() == pdfHeader[it].toInt() }
            }
        }.getOrDefault(false)
    }

    /** Narrow file seam used to verify download promotion without Android PDF APIs. */
    internal interface DocumentCache {
        val targetFile: File
        suspend fun download(partialFile: File)
        fun validate(file: File): Boolean
    }

    private data class FileStamp(val length: Long, val lastModified: Long)
    private data class ValidationEntry(val stamp: FileStamp, val valid: Boolean)

    private fun fileStamp(file: File): FileStamp? =
        file.takeIf { it.isFile }?.let { FileStamp(it.length(), it.lastModified()) }

    internal fun forget(file: File) {
        forget(file.absolutePath)
    }

    private fun forget(path: String) {
        synchronized(validationLock) {
            validationCache.remove(path)
        }
    }

    private fun cachedValidation(path: String, stamp: FileStamp): Boolean? =
        synchronized(validationLock) {
            validationCache[path]?.takeIf { it.stamp == stamp }?.valid
        }

    private fun rememberValidation(path: String, stamp: FileStamp, valid: Boolean) {
        synchronized(validationLock) {
            validationCache[path] = ValidationEntry(stamp, valid)
        }
    }

    private fun isValidated(cache: DocumentCache): Boolean {
        val file = cache.targetFile
        val path = file.absolutePath
        if (!hasPdfHeader(file)) {
            forget(path)
            return false
        }
        val beforeValidation = fileStamp(file) ?: return false
        cachedValidation(path, beforeValidation)?.let { return it }

        // PdfRenderer performs disk I/O. Keep it outside the cache lock so unrelated
        // documents can validate in parallel. Do not cache a result for a changing file.
        val valid = cache.validate(file)
        val afterValidation = fileStamp(file) ?: return false
        if (afterValidation != beforeValidation) return false
        rememberValidation(path, afterValidation, valid)
        return valid
    }

    private fun recordValidated(file: File) {
        fileStamp(file)?.let { rememberValidation(file.absolutePath, it, true) }
    }

    internal suspend fun ensureDownloaded(identity: String, cache: DocumentCache): File {
        val target = cache.targetFile
        if (isValidated(cache)) return target

        val lock = downloadLocks[(identity.hashCode() and Int.MAX_VALUE) % downloadLocks.size]
        return lock.withLock {
            if (isValidated(cache)) return@withLock target

            val parent = target.parentFile
                ?: throw IOException("PDF 缓存目录无效")
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("无法创建 PDF 缓存目录")
            }
            // A cancelled HTTP callback may finish after this coroutine releases its mutex.
            // Give every attempt its own path so that a late callback cannot alter a retry.
            val partial = File.createTempFile("reader-", partialFileSuffix, parent)

            try {
                cache.download(partial)
                currentCoroutineContext().ensureActive()
                if (!hasPdfHeader(partial) || !cache.validate(partial)) {
                    throw IOException("服务器返回的 PDF 文件无效")
                }
                currentCoroutineContext().ensureActive()
                if (isValidated(cache)) {
                    partial.delete()
                    return@withLock target
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("无法替换损坏的 PDF 离线缓存")
                }
                if (!partial.renameTo(target)) {
                    throw IOException("无法保存 PDF 离线缓存")
                }
                forget(partial)
                recordValidated(target)
                target
            } catch (error: Throwable) {
                partial.delete()
                throw error
            }
        }
    }
}
