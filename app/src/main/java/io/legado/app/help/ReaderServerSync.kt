package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ReaderServerPdf
import io.legado.app.help.book.ReaderBookDisplay
import io.legado.app.model.localBook.PdfFile
import io.legado.app.help.config.AppConfig
import io.legado.app.model.remote.ReaderServerApi
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.SecurePreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File

/**
 * Reader Server 同步管理器
 * 同步新版 Reader 的书架和阅读进度
 */
object ReaderServerSync {
    
    private var api: ReaderServerApi? = null
    private var config: Server.ReaderServerConfig? = null
    private val configMutex = Mutex()
    
    val isConfigured: Boolean
        get() = config != null && config?.url?.isNotBlank() == true
    
    val isOk: Boolean
        get() = api != null && isConfigured
    
    /**
     * 初始化同步配置
     */
    suspend fun initConfig() {
        withContext(Dispatchers.IO) {
            configMutex.withLock {
                syncCatching {
                    val serverUrl = appCtx.getPrefString(PreferKey.readerServerUrl)?.trim()?.trimEnd('/')
                    val username = appCtx.getPrefString(PreferKey.readerServerUsername)
                    val password = SecurePreferences.getString(PreferKey.readerServerPassword)
                    if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
                        api = null
                        config = null
                        return@syncCatching
                    }
                    val savedToken = SecurePreferences.getString(PreferKey.readerServerToken)
                    val tokenExpireTime = SecurePreferences.getLong(PreferKey.readerServerTokenExpire, 0L)
                    val savedTokenOwner = SecurePreferences.getString("${PreferKey.readerServerToken}.owner")
                    val tokenOwner = "$serverUrl\n$username"
                    val serverConfig = Server.ReaderServerConfig(
                        url = serverUrl,
                        username = username,
                        password = password,
                        token = savedToken,
                        tokenExpireTime = tokenExpireTime,
                        syncBookshelf = appCtx.getPrefBoolean(PreferKey.readerServerSyncBookshelf, true),
                        syncBookProgress = appCtx.getPrefBoolean(PreferKey.readerServerSyncProgress, true)
                    )
                    val previous = config
                    val sameCredentials = previous != null && previous.url == serverUrl && previous.username == username &&
                            previous.password == password
                    if (api == null || !sameCredentials) {
                        // Credentials identify the API instance, including its refreshed session.
                        // Never restore another server/account's token after a configuration change.
                        api = ReaderServerApi(serverConfig).also { serverApi ->
                            if (previous == null && savedTokenOwner == tokenOwner && !savedToken.isNullOrBlank() && tokenExpireTime > System.currentTimeMillis()) {
                                serverApi.setAccessToken(savedToken, tokenExpireTime)
                            }
                        }
                    }
                    config = serverConfig
                }.onFailure {
                    api = null
                    config = null
                    AppLog.put("初始化 Reader Server 配置失败: ${it.message}", it)
                }
            }
        }
    }

    /**
     * 保存token信息（到加密存储）
     */
    private fun saveTokenInfo(token: String?, expireTime: Long, owner: ReaderServerApi) {
        if (api !== owner) return
        SecurePreferences.putString(PreferKey.readerServerToken, token ?: "")
        SecurePreferences.putLong(PreferKey.readerServerTokenExpire, expireTime)
        config?.let { SecurePreferences.putString("${PreferKey.readerServerToken}.owner", "${it.url}\n${it.username}") }
    }
    
    /**
     * 测试连接
     */
    suspend fun testConnection(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            syncCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                
                // testConnection 会抛出详细异常
                serverApi.testConnection()
                
                // 登录成功后保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime, serverApi)
                
                true
            }
        }
    }
    
    /** Import the Reader shelf and reconcile reading progress by its last-read timestamp. */
    suspend fun syncBookshelf(): Result<SyncResult> = withContext(Dispatchers.IO) {
        syncCatching {
            if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
            val serverApi = api ?: throw NoStackTraceException("服务器未配置")
            val syncConfig = config ?: throw NoStackTraceException("服务器未配置")
            syncServerBookshelf(serverApi, syncConfig, appDb)
        }
    }

    /** V1 identities are scoped to the server and account; never merge by title/author. */
    internal suspend fun syncServerBookshelf(
        serverApi: ReaderServerApi,
        syncConfig: Server.ReaderServerConfig,
        database: AppDatabase
    ): SyncResult {
        if (!syncConfig.syncBookshelf && !syncConfig.syncBookProgress) {
            return SyncResult(skippedReason = "已关闭书架和阅读进度同步")
        }
        val serverBooks = serverApi.getBookshelf()
        val failures = mutableListOf<String>()
        val skippedBooks = mutableListOf<String>()
        val supported = serverBooks.filter { book ->
            val reason = book.variableMap["readerV1UnsupportedReason"]
            if (!reason.isNullOrBlank()) skippedBooks += "《${book.name}》：$reason"
            reason.isNullOrBlank()
        }
        val progressBooks = if (syncConfig.syncBookshelf) supported else {
            supported.filter { database.bookDao.has(it.bookUrl) }
        }
        // Shelf metadata never downloads manifests or chapter bodies. A failed progress
        // request still allows metadata to be refreshed and is reported as a failure.
        val progress = if (syncConfig.syncBookProgress && progressBooks.isNotEmpty()) {
            try {
                serverApi.getProgressRecords(progressBooks.map { it.bookUrl })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += "读取 Reader 阅读进度失败：${error.localizedMessage}"
                null
            }
        } else null
        var uploaded = 0
        var downloaded = 0
        var updated = 0
        for (serverBook in supported) {
            currentCoroutineContext().ensureActive()
            var changed = false
            try {
                val localBook = database.bookDao.getBook(serverBook.bookUrl)
                val rawProgress = progress?.get(serverBook.bookUrl)
                if (localBook == null) {
                    if (!syncConfig.syncBookshelf) continue
                    requireNoTitleConflict(serverBook, database)
                    val imported = if (rawProgress != null && rawProgress.updatedAt > 0) {
                        try {
                            serverApi.resolveProgress(serverBook, rawProgress)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += "《${serverBook.name}》进度未同步：${error.localizedMessage}"
                            serverBook
                        }
                    } else serverBook
                    imported.syncTime = System.currentTimeMillis()
                    // REPLACE could otherwise delete a book imported or renamed while the
                    // remote progress was being resolved. Recheck both keys atomically.
                    val inserted = database.runInTransaction(java.util.concurrent.Callable {
                        if (database.bookDao.has(imported.bookUrl)) false else {
                            requireNoTitleConflict(imported, database)
                            database.bookDao.insert(imported)
                            true
                        }
                    })
                    if (inserted) downloaded++
                    continue
                }
                if (syncConfig.syncBookshelf && readerMetadataChanged(localBook, serverBook)) {
                    changed = database.runInTransaction(java.util.concurrent.Callable {
                        // Custom variables may have changed since the loop read localBook.
                        // Re-read and merge under the same write transaction.
                        val current = database.bookDao.getBook(serverBook.bookUrl) ?: return@Callable 0
                        database.bookDao.updateReaderMetadata(
                            serverBook.bookUrl, serverBook.intro, serverBook.coverUrl,
                            serverBook.kind, serverBook.originName, serverBook.type,
                            serverBook.totalChapterNum, ReaderBookDisplay.mergeVariables(current, serverBook),
                            System.currentTimeMillis()
                        )
                    }) > 0
                }
                if (progress != null) {
                    if (rawProgress != null && rawProgress.updatedAt > localBook.durChapterTime) {
                        val resolved = serverApi.resolveProgress(localBook.copy(variable = serverBook.variable), rawProgress)
                        // Resolving may involve slow network IO. The SQL guard compares the
                        // current row again so reading on this device always wins if newer.
                        changed = database.bookDao.applyReaderProgress(
                            resolved.bookUrl, resolved.durChapterIndex, resolved.durChapterPos,
                            resolved.durChapterTitle, resolved.durChapterTime, resolved.totalChapterNum, System.currentTimeMillis()
                        ) > 0 || changed
                    } else if (localBook.durChapterTime > (rawProgress?.updatedAt ?: 0L)) {
                        uploadProgress(serverApi, localBook, database)
                        uploaded++
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("同步 Reader 书籍失败: ${error.message}", error)
                failures += "《${serverBook.name}》同步失败：${error.localizedMessage}"
            } finally {
                if (changed) updated++
            }
        }
        var groupsCreated = 0
        var groupedBooks = 0
        if (syncConfig.syncBookshelf) {
            val importer = ReaderGroupImporter(database)
            val unavailable = linkedSetOf<String>()
            for (serverBook in supported) {
                currentCoroutineContext().ensureActive()
                try {
                    val result = importer.importBook(serverBook)
                    groupsCreated += result.created
                    if (result.grouped) groupedBooks++
                    unavailable += result.unavailable
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += "《${serverBook.name}》分组导入失败：${error.localizedMessage}"
                }
            }
            if (unavailable.isNotEmpty()) {
                failures += "分组容量不足，${unavailable.size} 个系列或标签未导入：" +
                    unavailable.take(5).joinToString("、") + if (unavailable.size > 5) "等" else ""
            }
        }
        val (token, expireTime) = serverApi.getTokenInfo()
        saveTokenInfo(token, expireTime, serverApi)
        return SyncResult(
            uploaded, downloaded, updated, failures = failures, skippedBooks = skippedBooks,
            groupsCreated = groupsCreated, groupedBooks = groupedBooks,
            skippedReason = if (supported.isEmpty() && skippedBooks.isNotEmpty()) "书架仅含当前客户端暂不支持的格式" else null
        )
    }

    private fun requireNoTitleConflict(book: Book, database: AppDatabase) {
        val conflict = database.bookDao.getBook(book.name, book.author)
        if (conflict != null && conflict.bookUrl != book.bookUrl) {
            throw NoStackTraceException("《${book.name}》与本地同名书冲突，已保留本地书；请先处理同名书再同步")
        }
    }

    private fun readerMetadataChanged(local: Book, server: Book): Boolean =
        local.intro != server.intro || local.coverUrl != server.coverUrl ||
            local.kind != server.kind || local.originName != server.originName ||
            local.type != server.type || local.origin != BookType.readerServerLocalTag ||
            (server.totalChapterNum > 0 && local.totalChapterNum != server.totalChapterNum) ||
            !ReaderBookDisplay.sameMetadata(local, server)

    private fun requireSaved(saved: Boolean, message: String) {
        if (!saved) throw NoStackTraceException(message)
    }

    private suspend fun <T> syncCatching(action: suspend () -> T): Result<T> {
        return try {
            Result.success(action())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Upload a stable snapshot; acknowledge only its timestamp in the current database row. */
    suspend fun uploadBookProgress(book: Book): Result<Boolean> = withContext(Dispatchers.IO) {
        syncCatching {
            if (!NetworkUtils.isAvailable() || config?.syncBookProgress != true) return@syncCatching false
            val serverApi = api ?: return@syncCatching false
            if (!book.bookUrl.startsWith("reader-v1://")) return@syncCatching false
            uploadProgress(serverApi, book)
            val (token, expireTime) = serverApi.getTokenInfo()
            saveTokenInfo(token, expireTime, serverApi)
            true
        }
    }

    private suspend fun uploadProgress(
        serverApi: ReaderServerApi, book: Book, database: AppDatabase = appDb
    ): Boolean =
        uploadReaderProgress(
            book,
            upload = { serverApi.saveBookProgress(it) },
            markSynced = { url, readTime, syncedAt ->
                database.bookDao.markReaderProgressSynced(url, readTime, syncedAt)
            }
        )

    /** Return a newer remote position for the reader's existing confirmation flow. */
    suspend fun syncBookProgress(book: Book): Result<Book?> = withContext(Dispatchers.IO) {
        syncCatching {
            if (!NetworkUtils.isAvailable() || config?.syncBookProgress != true) return@syncCatching null
            val serverApi = api ?: return@syncCatching null
            val snapshot = book.copy()
            if (!snapshot.bookUrl.startsWith("reader-v1://")) return@syncCatching null
            val remote = serverApi.getBookProgress(snapshot)
            val newer = remote?.takeIf { it.durChapterTime > snapshot.durChapterTime }
            if (newer == null && snapshot.durChapterTime > (remote?.durChapterTime ?: 0L)) {
                uploadProgress(serverApi, snapshot)
            }
            val (token, expireTime) = serverApi.getTokenInfo()
            saveTokenInfo(token, expireTime, serverApi)
            // A newer document-level timestamp carries no new position to confirm.
            newer?.takeUnless {
                it.durChapterIndex == snapshot.durChapterIndex && it.durChapterPos == snapshot.durChapterPos
            }
        }
    }

    /** Only supported server work is included, so local-only features never emit skip notices. */
    suspend fun syncAll(): Result<ReaderServerSyncReport> = withContext(Dispatchers.IO) {
        syncCatching {
            val name = "书架 / 分组 / 阅读进度"
            val items = mutableListOf<ReaderServerSyncItem>()
            syncBookshelf().fold(onSuccess = { result ->
                items += result.toReportItem(name)
                if (result.skippedBooks.isNotEmpty()) {
                    items += ReaderServerSyncItem.skipped("未支持的书籍", result.skippedBooks.joinToString("；"))
                }
            }, onFailure = { error ->
                if (error is CancellationException) throw error
                AppLog.put("同步$name 失败: ${error.message}", error)
                items += ReaderServerSyncItem.failed(name, error.localizedMessage ?: "未知错误")
            })
            ReaderServerSyncReport(items)
        }
    }

    /** Keep previously imported remote entries distinct from files on this device. */
    fun isServerLocalBook(book: Book): Boolean {
        if (book.origin == BookType.readerServerLocalTag) {
            return true
        }
        // Recognizing old stored identities does not enable the removed reader3 transport.
        return book.bookUrl.startsWith("storage/") || book.bookUrl.startsWith("reader-v1://")
    }
    
    /**
     * 删除服务器上的书籍
     */
    suspend fun deleteBook(book: Book): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            syncCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.deleteBook(book).also { requireSaved(it, "删除服务器书籍失败") }
            }
        }
    }
    
    /** Download only to a temporary file; the PDF cache validates and promotes it. */
    suspend fun downloadBookDocument(bookUrl: String, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        syncCatching {
            initConfig()
            if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用，PDF 尚未离线缓存")
            val serverApi = api ?: throw NoStackTraceException("服务器未配置")
            serverApi.downloadBookDocument(bookUrl, target)
            val (token, expireTime) = serverApi.getTokenInfo()
            saveTokenInfo(token, expireTime, serverApi)
        }
    }

    /** Cached PDFs can generate their directory without a network or configured server. */
    suspend fun getChapterList(bookUrl: String): Result<List<io.legado.app.data.entities.BookChapter>> =
        withContext(Dispatchers.IO) {
            syncCatching {
                val pdf = appDb.bookDao.getBook(bookUrl)?.takeIf(ReaderServerPdf::isPdf)
                if (pdf != null) {
                    ReaderServerPdf.ensureDownloaded(pdf)
                    return@syncCatching PdfFile.getChapterList(pdf)
                }
                if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getChapterList(bookUrl)
            }
        }

    suspend fun getBookContent(bookUrl: String, chapterIndex: Int, chapterUrl: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            syncCatching {
                val pdf = appDb.bookDao.getBook(bookUrl)?.takeIf(ReaderServerPdf::isPdf)
                if (pdf != null) {
                    if (chapterIndex < 0) throw NoStackTraceException("PDF 页码超出范围")
                    ReaderServerPdf.ensureDownloaded(pdf)
                    val chapter = io.legado.app.data.entities.BookChapter(
                        bookUrl = bookUrl, index = chapterIndex, url = "reader-pdf:$chapterIndex"
                    )
                    return@syncCatching PdfFile.getContent(pdf, chapter)?.takeIf { it.isNotBlank() }
                        ?: throw NoStackTraceException("PDF 页码超出范围")
                }
                if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getBookContent(bookUrl, chapterIndex, chapterUrl)
            }
        }

    /**
     * 获取服务器本地书籍的图片
     * @param bookUrl 书籍的 bookUrl
     * @param imageSrc 图片源路径
     * @return 图片的字节数据
     */
    suspend fun getBookImage(bookUrl: String, imageSrc: String): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            syncCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getBookImage(bookUrl, imageSrc)
            }
        }
    }
    
    /** Match only this configured server's protected book images, without adding tokens to URLs. */
    fun isServerResourceUrl(url: String): Boolean {
        val serverUrl = AppConfig.readerServerUrl ?: config?.url ?: return false
        return ReaderServerResourceUrl.isImageResource(serverUrl, url)
    }

    suspend fun getResourceImage(url: String): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            syncCatching {
                initConfig()
                if (!isServerResourceUrl(url)) throw NoStackTraceException("图片地址不属于当前 Reader Server")
                if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getResourceImage(url)
            }
        }
    }

    /**
     * 同步结果
     */
    data class SyncResult(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val updated: Int = 0,
        val skippedReason: String? = null,
        val failures: List<String> = emptyList(),
        val skippedBooks: List<String> = emptyList(),
        val groupsCreated: Int = 0,
        val groupedBooks: Int = 0
    ) {
        val total: Int get() = uploaded + downloaded + updated + groupsCreated + groupedBooks

        internal fun toReportItem(name: String): ReaderServerSyncItem = when {
            skippedReason != null -> ReaderServerSyncItem.skipped(name, skippedReason)
            failures.isNotEmpty() -> ReaderServerSyncItem.failed(
                name, "$this；${failures.joinToString("；")}", total > 0
            )
            else -> ReaderServerSyncItem.completed(name, toString())
        }
        
        override fun toString(): String {
            return "上传: $uploaded, 下载: $downloaded, 更新: $updated" +
                if (groupsCreated > 0 || groupedBooks > 0) "；新增分组: $groupsCreated，归类书籍: $groupedBooks" else ""
        }
    }
}
