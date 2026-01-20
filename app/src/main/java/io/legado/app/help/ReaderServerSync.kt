package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.ReaderServerApi
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * Reader Server 同步管理器
 * 负责与 reader3 服务器进行数据同步
 */
object ReaderServerSync {
    private const val TAG = "ReaderServerSync"
    
    private var api: ReaderServerApi? = null
    private var config: Server.ReaderServerConfig? = null
    
    val isConfigured: Boolean
        get() = config != null && config?.url?.isNotBlank() == true
    
    val isOk: Boolean
        get() = api != null && isConfigured
    
    /**
     * 初始化同步配置
     */
    suspend fun initConfig() {
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                api = null
                config = null
                
                val serverUrl = appCtx.getPrefString(PreferKey.readerServerUrl)
                val username = appCtx.getPrefString(PreferKey.readerServerUsername)
                val password = appCtx.getPrefString(PreferKey.readerServerPassword)
                
                if (!serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
                    val savedToken = appCtx.getPrefString(PreferKey.readerServerToken)
                    val tokenExpireTime = appCtx.getPrefString(PreferKey.readerServerTokenExpire)?.toLongOrNull() ?: 0L
                    
                    val serverConfig = Server.ReaderServerConfig(
                        url = serverUrl,
                        username = username,
                        password = password,
                        token = savedToken,
                        tokenExpireTime = tokenExpireTime,
                        syncBookSource = appCtx.getPrefBoolean(PreferKey.readerServerSyncBookSource, true),
                        syncBookshelf = appCtx.getPrefBoolean(PreferKey.readerServerSyncBookshelf, true),
                        syncBookProgress = appCtx.getPrefBoolean(PreferKey.readerServerSyncProgress, true),
                        syncRssSource = appCtx.getPrefBoolean(PreferKey.readerServerSyncRssSource, true)
                    )
                    
                    config = serverConfig
                    val serverApi = ReaderServerApi(serverConfig)
                    // 如果有保存的 token，恢复到 API 实例
                    if (!savedToken.isNullOrBlank() && tokenExpireTime > System.currentTimeMillis()) {
                        serverApi.setAccessToken(savedToken, tokenExpireTime)
                    }
                    api = serverApi
                }
            }.onFailure {
                AppLog.put("初始化 Reader Server 配置失败: ${it.message}", it)
            }
        }
    }
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(
        serverUrl: String,
        username: String,
        password: String,
        syncBookSource: Boolean = true,
        syncBookshelf: Boolean = true,
        syncBookProgress: Boolean = true,
        syncRssSource: Boolean = true
    ) {
        withContext(Dispatchers.IO) {
            appCtx.putPrefString(PreferKey.readerServerUrl, serverUrl)
            appCtx.putPrefString(PreferKey.readerServerUsername, username)
            appCtx.putPrefString(PreferKey.readerServerPassword, password)
            
            // 清除旧token
            appCtx.putPrefString(PreferKey.readerServerToken, "")
            appCtx.putPrefString(PreferKey.readerServerTokenExpire, "0")
            
            initConfig()
        }
    }
    
    /**
     * 保存token信息
     */
    private fun saveTokenInfo(token: String?, expireTime: Long) {
        appCtx.putPrefString(PreferKey.readerServerToken, token ?: "")
        appCtx.putPrefString(PreferKey.readerServerTokenExpire, expireTime.toString())
    }
    
    /**
     * 测试连接
     */
    suspend fun testConnection(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                
                // testConnection 会抛出详细异常
                serverApi.testConnection()
                
                // 登录成功后保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                true
            }
        }
    }
    
    /**
     * 同步书源
     * 双向同步：本地有而服务器没有的上传，服务器有而本地没有的下载
     * 都有的比较更新时间，保留较新的
     */
    suspend fun syncBookSources(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                if (config?.syncBookSource != true) {
                    return@runCatching SyncResult(0, 0, 0)
                }
                
                currentCoroutineContext().ensureActive()
                
                // 获取服务器书源
                val serverSources = serverApi.getBookSources()
                val serverSourceMap = serverSources.associateBy { it.bookSourceUrl }
                
                // 获取本地书源
                val localSources = appDb.bookSourceDao.all
                val localSourceMap = localSources.associateBy { it.bookSourceUrl }
                
                var uploaded = 0
                var downloaded = 0
                var updated = 0
                
                val toUpload = mutableListOf<BookSource>()
                val toDownload = mutableListOf<BookSource>()
                
                // 检查本地书源
                for (localSource in localSources) {
                    val serverSource = serverSourceMap[localSource.bookSourceUrl]
                    if (serverSource == null) {
                        // 服务器没有，需要上传
                        toUpload.add(localSource)
                    } else {
                        // 都有，比较更新时间
                        if (localSource.lastUpdateTime > serverSource.lastUpdateTime) {
                            // 本地较新，上传
                            toUpload.add(localSource)
                        } else if (serverSource.lastUpdateTime > localSource.lastUpdateTime) {
                            // 服务器较新，下载
                            toDownload.add(serverSource)
                        }
                    }
                }
                
                // 检查服务器书源（找出本地没有的）
                for (serverSource in serverSources) {
                    if (!localSourceMap.containsKey(serverSource.bookSourceUrl)) {
                        toDownload.add(serverSource)
                    }
                }
                
                currentCoroutineContext().ensureActive()
                
                // 上传书源
                if (toUpload.isNotEmpty()) {
                    if (serverApi.saveBookSources(toUpload)) {
                        uploaded = toUpload.size
                    }
                }
                
                // 下载书源
                if (toDownload.isNotEmpty()) {
                    for (source in toDownload) {
                        currentCoroutineContext().ensureActive()
                        val existingSource = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                        if (existingSource != null) {
                            appDb.bookSourceDao.update(source)
                            updated++
                        } else {
                            appDb.bookSourceDao.insert(source)
                            downloaded++
                        }
                    }
                }
                
                // 保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                AppLog.put("书源同步完成: 上传 $uploaded, 下载 $downloaded, 更新 $updated")
                SyncResult(uploaded, downloaded, updated)
            }
        }
    }
    
    /**
     * 同步书架
     * 双向同步：合并书架，阅读进度取较新的
     */
    suspend fun syncBookshelf(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                if (config?.syncBookshelf != true) {
                    return@runCatching SyncResult(0, 0, 0)
                }
                
                currentCoroutineContext().ensureActive()
                
                // 获取服务器书架
                val serverBooks = serverApi.getBookshelf()
                val serverBookMap = serverBooks.associateBy { "${it.name}_${it.author}" }
                
                // 获取本地书架
                val localBooks = appDb.bookDao.all
                val localBookMap = localBooks.associateBy { "${it.name}_${it.author}" }
                
                var uploaded = 0
                var downloaded = 0
                var updated = 0
                
                // 检查本地书籍
                for (localBook in localBooks) {
                    currentCoroutineContext().ensureActive()
                    val key = "${localBook.name}_${localBook.author}"
                    val serverBook = serverBookMap[key]
                    
                    if (serverBook == null) {
                        // 服务器没有，上传
                        if (serverApi.saveBook(localBook)) {
                            uploaded++
                        }
                    } else {
                        // 都有，比较进度
                        if (shouldUpdateProgress(localBook, serverBook)) {
                            // 服务器进度更新，更新本地
                            localBook.durChapterIndex = serverBook.durChapterIndex
                            localBook.durChapterPos = serverBook.durChapterPos
                            localBook.durChapterTitle = serverBook.durChapterTitle
                            localBook.durChapterTime = serverBook.durChapterTime
                            localBook.syncTime = System.currentTimeMillis()
                            appDb.bookDao.update(localBook)
                            updated++
                        } else if (localBook.durChapterTime > serverBook.durChapterTime) {
                            // 本地进度更新，上传
                            if (serverApi.saveBook(localBook)) {
                                uploaded++
                            }
                        }
                    }
                }
                
                // 检查服务器书籍（找出本地没有的）
                for (serverBook in serverBooks) {
                    currentCoroutineContext().ensureActive()
                    val key = "${serverBook.name}_${serverBook.author}"
                    if (!localBookMap.containsKey(key)) {
                        // 本地没有，添加到本地
                        serverBook.syncTime = System.currentTimeMillis()
                        appDb.bookDao.insert(serverBook)
                        downloaded++
                    }
                }
                
                // 保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                AppLog.put("书架同步完成: 上传 $uploaded, 下载 $downloaded, 更新 $updated")
                SyncResult(uploaded, downloaded, updated)
            }
        }
    }
    
    /**
     * 判断是否应该用服务器进度更新本地
     */
    private fun shouldUpdateProgress(local: Book, server: Book): Boolean {
        // 如果服务器的章节索引更大，或者索引相同但位置更大，则更新
        if (server.durChapterIndex > local.durChapterIndex) {
            return true
        }
        if (server.durChapterIndex == local.durChapterIndex && server.durChapterPos > local.durChapterPos) {
            return true
        }
        // 如果服务器的阅读时间更新，也考虑更新
        if (server.durChapterTime > local.durChapterTime) {
            return true
        }
        return false
    }
    
    /**
     * 上传阅读进度
     */
    suspend fun uploadBookProgress(book: Book): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    return@runCatching false
                }
                val serverApi = api ?: return@runCatching false
                if (config?.syncBookProgress != true) {
                    return@runCatching false
                }
                
                val progress = BookProgress(book)
                val result = serverApi.saveBookProgress(progress)
                
                if (result) {
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
                
                // 保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                result
            }
        }
    }
    
    /**
     * 上传阅读进度（使用BookProgress对象）
     */
    suspend fun uploadBookProgress(progress: BookProgress): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    return@runCatching false
                }
                val serverApi = api ?: return@runCatching false
                if (config?.syncBookProgress != true) {
                    return@runCatching false
                }
                
                val result = serverApi.saveBookProgress(progress)
                
                // 保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                result
            }
        }
    }
    
    /**
     * 同步订阅源
     */
    suspend fun syncRssSources(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                if (config?.syncRssSource != true) {
                    return@runCatching SyncResult(0, 0, 0)
                }
                
                currentCoroutineContext().ensureActive()
                
                // 获取服务器订阅源
                val serverSources = serverApi.getRssSources()
                val serverSourceMap = serverSources.associateBy { it.sourceUrl }
                
                // 获取本地订阅源
                val localSources = appDb.rssSourceDao.all
                val localSourceMap = localSources.associateBy { it.sourceUrl }
                
                var uploaded = 0
                var downloaded = 0
                var updated = 0
                
                val toUpload = mutableListOf<RssSource>()
                val toDownload = mutableListOf<RssSource>()
                
                // 检查本地订阅源
                for (localSource in localSources) {
                    val serverSource = serverSourceMap[localSource.sourceUrl]
                    if (serverSource == null) {
                        toUpload.add(localSource)
                    }
                }
                
                // 检查服务器订阅源
                for (serverSource in serverSources) {
                    if (!localSourceMap.containsKey(serverSource.sourceUrl)) {
                        toDownload.add(serverSource)
                    }
                }
                
                currentCoroutineContext().ensureActive()
                
                // 上传订阅源
                if (toUpload.isNotEmpty()) {
                    if (serverApi.saveRssSources(toUpload)) {
                        uploaded = toUpload.size
                    }
                }
                
                // 下载订阅源
                for (source in toDownload) {
                    currentCoroutineContext().ensureActive()
                    appDb.rssSourceDao.insert(source)
                    downloaded++
                }
                
                // 保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                AppLog.put("订阅源同步完成: 上传 $uploaded, 下载 $downloaded, 更新 $updated")
                SyncResult(uploaded, downloaded, updated)
            }
        }
    }
    
    /**
     * 执行全部同步
     */
    suspend fun syncAll(): Result<Map<String, SyncResult>> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                if (!isOk) {
                    throw NoStackTraceException("服务器未配置")
                }
                
                val results = mutableMapOf<String, SyncResult>()
                
                // 同步书源
                syncBookSources().onSuccess {
                    results["bookSource"] = it
                }.onFailure {
                    AppLog.put("同步书源失败: ${it.message}", it)
                }
                
                currentCoroutineContext().ensureActive()
                
                // 同步书架
                syncBookshelf().onSuccess {
                    results["bookshelf"] = it
                }.onFailure {
                    AppLog.put("同步书架失败: ${it.message}", it)
                }
                
                currentCoroutineContext().ensureActive()
                
                // 同步订阅源
                syncRssSources().onSuccess {
                    results["rssSource"] = it
                }.onFailure {
                    AppLog.put("同步订阅源失败: ${it.message}", it)
                }
                
                results
            }
        }
    }
    
    /**
     * 判断书籍是否为服务器上的本地存储书籍
     */
    fun isServerLocalBook(book: Book): Boolean {
        return book.bookUrl.startsWith("storage/localStore/")
    }
    
    /**
     * 从服务器下载本地存储的书籍文件
     * @param book 书籍对象，bookUrl 应该是 "storage/localStore/xxx.epub" 格式
     * @return 下载成功后的本地 Uri
     */
    suspend fun downloadBookFile(book: Book): Result<Uri> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                
                if (!isServerLocalBook(book)) {
                    throw NoStackTraceException("书籍不是服务器本地存储的书籍")
                }
                
                // 从 bookUrl 中提取文件名
                val fileName = book.bookUrl.substringAfterLast("/")
                if (fileName.isBlank()) {
                    throw NoStackTraceException("无法获取书籍文件名")
                }
                
                AppLog.put("开始从服务器下载书籍文件: ${book.name} ($fileName)")
                
                // 从 bookUrl 中去除 "storage/localStore" 前缀，获取相对路径
                // 服务器端会自动拼接 storage/localStore 目录
                val relativePath = "/" + book.bookUrl.removePrefix("storage/localStore/")
                
                // 从服务器下载文件
                val inputStream = serverApi.downloadLocalStoreFile(relativePath)
                
                // 保存到本地
                val localUri = LocalBook.saveBookFile(inputStream, fileName)
                
                // 更新书籍的 bookUrl 为本地路径
                val newBookUrl = FileDoc.fromUri(localUri, false).toString()
                book.bookUrl = newBookUrl
                book.save()
                
                AppLog.put("书籍文件下载完成: ${book.name}, 保存到: $newBookUrl")
                
                // 保存 token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                localUri
            }
        }
    }
    
    /**
     * 获取章节列表
     */
    suspend fun getChapterList(bookUrl: String): Result<List<io.legado.app.data.entities.BookChapter>> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getChapterList(bookUrl)
            }
        }
    }
    
    /**
     * 获取章节内容
     */
    suspend fun getBookContent(bookUrl: String, chapterIndex: Int): Result<String> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getBookContent(bookUrl, chapterIndex)
            }
        }
    }
    
    /**
     * 同步结果
     */
    data class SyncResult(
        val uploaded: Int,
        val downloaded: Int,
        val updated: Int
    ) {
        val total: Int get() = uploaded + downloaded + updated
        
        override fun toString(): String {
            return "上传: $uploaded, 下载: $downloaded, 更新: $updated"
        }
    }
}
