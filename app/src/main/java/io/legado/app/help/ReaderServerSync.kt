package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.remote.ReaderServerApi
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
                        syncBookGroup = appCtx.getPrefBoolean(PreferKey.readerServerSyncBookGroup, true),
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
     * 同步书籍分组
     * 双向同步：合并分组列表，服务器优先
     */
    suspend fun syncBookGroups(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                
                currentCoroutineContext().ensureActive()
                
                // 获取服务器分组
                val serverGroups = serverApi.getBookGroups()
                val serverGroupMap = serverGroups.associateBy { it.groupId }
                
                // 获取本地分组（只获取用户自定义分组，groupId > 0）
                val localGroups = appDb.bookGroupDao.all.filter { it.groupId > 0 }
                val localGroupMap = localGroups.associateBy { it.groupId }
                
                var uploaded = 0
                var downloaded = 0
                var updated = 0
                
                // 先处理服务器分组（服务器优先，下载/更新本地）
                for (serverGroup in serverGroups) {
                    if (serverGroup.groupId > 0) {
                        val localGroup = localGroupMap[serverGroup.groupId]
                        if (localGroup == null) {
                            // 本地没有，添加到本地
                            appDb.bookGroupDao.insert(serverGroup)
                            downloaded++
                        } else {
                            // 本地有，用服务器的更新本地
                            if (localGroup.groupName != serverGroup.groupName ||
                                localGroup.order != serverGroup.order) {
                                // 用服务器的 groupName 和 order 更新本地，保留本地独有的字段
                                localGroup.groupName = serverGroup.groupName
                                localGroup.order = serverGroup.order
                                appDb.bookGroupDao.update(localGroup)
                                updated++
                            }
                        }
                    }
                }
                
                // 再处理本地独有的分组（上传到服务器）
                for (localGroup in localGroups) {
                    if (!serverGroupMap.containsKey(localGroup.groupId)) {
                        // 服务器没有，上传
                        if (serverApi.saveBookGroup(localGroup)) {
                            uploaded++
                        }
                    }
                }
                
                // 保存token
                val (token, expireTime) = serverApi.getTokenInfo()
                saveTokenInfo(token, expireTime)
                
                AppLog.put("分组同步完成: 上传 $uploaded, 下载 $downloaded, 更新 $updated")
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
                        // 都有，先检查是否需要修复服务器本地书籍的 origin 和 bookUrl
                        var needUpdate = false
                        var needUpload = false
                        
                        if (serverBook.bookUrl.startsWith("storage/")) {
                            // 服务器本地书籍，确保 origin 和 bookUrl 正确
                            if (localBook.origin != BookType.readerServerLocalTag) {
                                localBook.origin = BookType.readerServerLocalTag
                                needUpdate = true
                            }
                            // 如果 bookUrl 被修改过（不是 storage/ 开头），恢复为服务器的 bookUrl
                            if (!localBook.bookUrl.startsWith("storage/")) {
                                localBook.bookUrl = serverBook.bookUrl
                                needUpdate = true
                            }
                        }
                        
                        // 同步分组（group 字段）
                        if (localBook.group == 0L && serverBook.group != 0L) {
                            // 本地没分组，服务器有分组，使用服务器的
                            localBook.group = serverBook.group
                            needUpdate = true
                        } else if (localBook.group != 0L && serverBook.group == 0L) {
                            // 本地有分组，服务器没分组，上传本地的
                            needUpload = true
                        } else if (localBook.group != serverBook.group && localBook.group != 0L) {
                            // 都有分组但不同，以本地为准上传
                            needUpload = true
                        }
                        
                        // 比较进度
                        if (shouldUpdateProgress(localBook, serverBook)) {
                            // 服务器进度更新，更新本地
                            localBook.durChapterIndex = serverBook.durChapterIndex
                            localBook.durChapterPos = serverBook.durChapterPos
                            localBook.durChapterTitle = serverBook.durChapterTitle
                            localBook.durChapterTime = serverBook.durChapterTime
                            localBook.syncTime = System.currentTimeMillis()
                            appDb.bookDao.update(localBook)
                            updated++
                        } else if (localBook.durChapterTime > serverBook.durChapterTime || needUpload) {
                            // 本地进度更新或需要上传分组，上传
                            if (serverApi.saveBook(localBook)) {
                                uploaded++
                            }
                        } else if (needUpdate) {
                            // origin、bookUrl 或 group 被修复，需要更新数据库
                            localBook.syncTime = System.currentTimeMillis()
                            appDb.bookDao.update(localBook)
                            updated++
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
                        // 标记服务器本地书籍，使用专门的 origin 标记
                        if (serverBook.bookUrl.startsWith("storage/")) {
                            serverBook.origin = BookType.readerServerLocalTag
                        }
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
                
                // 同步分组（在同步书架之前，确保分组信息已就位）
                if (config?.syncBookGroup != false) {
                    syncBookGroups().onSuccess {
                        results["bookGroup"] = it
                    }.onFailure {
                        AppLog.put("同步分组失败: ${it.message}", it)
                    }
                    
                    currentCoroutineContext().ensureActive()
                }
                
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
     * 服务器本地书籍的识别方式：
     * - 优先检查 origin 是否为 BookType.readerServerLocalTag（新方式，不受 bookUrl 修改影响）
     * - 兼容检查 bookUrl 是否以 storage/ 开头（旧方式，兼容已同步但 origin 未更新的书籍）
     * 
     * 所有服务器本地书籍（TXT、EPUB、PDF、CBZ 等）统一通过 API 获取章节
     */
    fun isServerLocalBook(book: Book): Boolean {
        // 优先使用 origin 标记判断（不受 bookUrl 修改影响）
        if (book.origin == BookType.readerServerLocalTag) {
            return true
        }
        // 兼容已同步但 origin 未更新的书籍
        return book.bookUrl.startsWith("storage/")
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
     * 获取服务器本地书籍的图片
     * @param bookUrl 书籍的 bookUrl
     * @param imageSrc 图片源路径
     * @return 图片的字节数据
     */
    suspend fun getBookImage(bookUrl: String, imageSrc: String): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                if (!NetworkUtils.isAvailable()) {
                    throw NoStackTraceException("网络不可用")
                }
                val serverApi = api ?: throw NoStackTraceException("服务器未配置")
                serverApi.getBookImage(bookUrl, imageSrc)
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
