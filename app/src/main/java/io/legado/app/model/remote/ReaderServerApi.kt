package io.legado.app.model.remote

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reader Server API 客户端
 * 封装与 reader3 服务器的所有 API 通信
 */
class ReaderServerApi(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private var token: String? = null
    private var tokenExpireTime: Long = 0

    companion object {
        private const val TAG = "ReaderServerApi"
        private const val API_PREFIX = "/reader3"
        
        // API 端点
        private const val API_LOGIN = "$API_PREFIX/login"
        private const val API_GET_USER_INFO = "$API_PREFIX/getUserInfo"
        private const val API_GET_BOOKSHELF = "$API_PREFIX/getBookshelf"
        private const val API_SAVE_BOOK = "$API_PREFIX/saveBook"
        private const val API_DELETE_BOOK = "$API_PREFIX/deleteBook"
        private const val API_GET_BOOK_SOURCES = "$API_PREFIX/getBookSources"
        private const val API_SAVE_BOOK_SOURCE = "$API_PREFIX/saveBookSource"
        private const val API_SAVE_BOOK_SOURCES = "$API_PREFIX/saveBookSources"
        private const val API_DELETE_BOOK_SOURCES = "$API_PREFIX/deleteBookSources"
        private const val API_GET_CHAPTER_LIST = "$API_PREFIX/getChapterList"
        private const val API_GET_BOOK_CONTENT = "$API_PREFIX/getBookContent"
        private const val API_SAVE_BOOK_PROGRESS = "$API_PREFIX/saveBookProgress"
        private const val API_GET_RSS_SOURCES = "$API_PREFIX/getRssSources"
        private const val API_SAVE_RSS_SOURCES = "$API_PREFIX/saveRssSources"
        private const val API_DELETE_RSS_SOURCES = "$API_PREFIX/deleteRssSources"
        private const val API_SEARCH_BOOK = "$API_PREFIX/searchBook"
    }

    /**
     * 从配置创建API实例
     */
    constructor(config: Server.ReaderServerConfig) : this(
        config.url,
        config.username,
        config.password
    ) {
        this.token = config.token
        this.tokenExpireTime = config.tokenExpireTime
    }

    private val baseUrl: String
        get() = serverUrl.trimEnd('/')

    /**
     * 获取当前token，如果过期则自动刷新
     */
    private suspend fun getValidToken(): String {
        if (token != null && tokenExpireTime > System.currentTimeMillis()) {
            return token!!
        }
        login()
        return token ?: throw NoStackTraceException("登录失败，无法获取token")
    }

    /**
     * 登录获取token
     */
    suspend fun login(): LoginResponse {
        val url = "$baseUrl$API_LOGIN"
        val body = GSON.toJson(mapOf(
            "username" to username,
            "password" to password
        ))
        
        val response = okHttpClient.newCallStrResponse {
            url(url)
            postJson(body)
        }
        
        if (!response.isSuccessful()) {
            throw NoStackTraceException("登录请求失败: ${response.code()}")
        }
        
        val result = GSON.fromJsonObject<ApiResponse<LoginResponse>>(response.body)
            .getOrNull() ?: throw NoStackTraceException("解析登录响应失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "登录失败")
        }
        
        result.data?.let {
            this.token = it.token
            // 默认token有效期24小时
            this.tokenExpireTime = System.currentTimeMillis() + (it.expireTime ?: 24 * 60 * 60 * 1000L)
            return it
        }
        
        throw NoStackTraceException("登录响应数据为空")
    }

    /**
     * 获取用户信息
     */
    suspend fun getUserInfo(): UserInfo {
        val url = "$baseUrl$API_GET_USER_INFO"
        val response = requestGet(url)
        
        val result = GSON.fromJsonObject<ApiResponse<UserInfo>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析用户信息失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "获取用户信息失败")
        }
        
        return result.data ?: throw NoStackTraceException("用户信息为空")
    }

    /**
     * 获取书架
     */
    suspend fun getBookshelf(): List<Book> {
        val url = "$baseUrl$API_GET_BOOKSHELF"
        val response = requestGet(url)
        
        val result = GSON.fromJsonObject<ApiResponse<List<Book>>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析书架数据失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "获取书架失败")
        }
        
        return result.data ?: emptyList()
    }

    /**
     * 保存书籍到书架
     */
    suspend fun saveBook(book: Book): Boolean {
        val url = "$baseUrl$API_SAVE_BOOK"
        val body = GSON.toJson(book)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析保存书籍响应失败")
        
        return result.isSuccess
    }

    /**
     * 删除书籍
     */
    suspend fun deleteBook(book: Book): Boolean {
        val url = "$baseUrl$API_DELETE_BOOK"
        val body = GSON.toJson(book)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析删除书籍响应失败")
        
        return result.isSuccess
    }

    /**
     * 获取所有书源
     */
    suspend fun getBookSources(): List<BookSource> {
        val url = "$baseUrl$API_GET_BOOK_SOURCES"
        val response = requestGet(url)
        
        val result = GSON.fromJsonObject<ApiResponse<List<BookSource>>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析书源数据失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "获取书源失败")
        }
        
        return result.data ?: emptyList()
    }

    /**
     * 保存单个书源
     */
    suspend fun saveBookSource(source: BookSource): Boolean {
        val url = "$baseUrl$API_SAVE_BOOK_SOURCE"
        val body = GSON.toJson(source)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析保存书源响应失败")
        
        return result.isSuccess
    }

    /**
     * 批量保存书源
     */
    suspend fun saveBookSources(sources: List<BookSource>): Boolean {
        val url = "$baseUrl$API_SAVE_BOOK_SOURCES"
        val body = GSON.toJson(sources)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析批量保存书源响应失败")
        
        return result.isSuccess
    }

    /**
     * 删除书源
     */
    suspend fun deleteBookSources(sources: List<BookSource>): Boolean {
        val url = "$baseUrl$API_DELETE_BOOK_SOURCES"
        val body = GSON.toJson(sources)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析删除书源响应失败")
        
        return result.isSuccess
    }

    /**
     * 获取章节列表
     */
    suspend fun getChapterList(bookUrl: String): List<BookChapter> {
        val url = "$baseUrl$API_GET_CHAPTER_LIST?url=${java.net.URLEncoder.encode(bookUrl, "UTF-8")}"
        val response = requestGet(url)
        
        val result = GSON.fromJsonObject<ApiResponse<List<BookChapter>>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析章节列表失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "获取章节列表失败")
        }
        
        return result.data ?: emptyList()
    }

    /**
     * 获取章节内容
     */
    suspend fun getBookContent(bookUrl: String, chapterIndex: Int): String {
        val url = "$baseUrl$API_GET_BOOK_CONTENT?url=${java.net.URLEncoder.encode(bookUrl, "UTF-8")}&index=$chapterIndex"
        val response = requestGet(url)
        
        val result = GSON.fromJsonObject<ApiResponse<String>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析章节内容失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "获取章节内容失败")
        }
        
        return result.data ?: ""
    }

    /**
     * 保存阅读进度
     */
    suspend fun saveBookProgress(progress: BookProgress): Boolean {
        val url = "$baseUrl$API_SAVE_BOOK_PROGRESS"
        val body = GSON.toJson(progress)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析保存进度响应失败")
        
        return result.isSuccess
    }

    /**
     * 获取所有订阅源
     */
    suspend fun getRssSources(): List<RssSource> {
        val url = "$baseUrl$API_GET_RSS_SOURCES"
        val response = requestGet(url)
        
        val result = GSON.fromJsonObject<ApiResponse<List<RssSource>>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析订阅源数据失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "获取订阅源失败")
        }
        
        return result.data ?: emptyList()
    }

    /**
     * 批量保存订阅源
     */
    suspend fun saveRssSources(sources: List<RssSource>): Boolean {
        val url = "$baseUrl$API_SAVE_RSS_SOURCES"
        val body = GSON.toJson(sources)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析批量保存订阅源响应失败")
        
        return result.isSuccess
    }

    /**
     * 删除订阅源
     */
    suspend fun deleteRssSources(sources: List<RssSource>): Boolean {
        val url = "$baseUrl$API_DELETE_RSS_SOURCES"
        val body = GSON.toJson(sources)
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<Any>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析删除订阅源响应失败")
        
        return result.isSuccess
    }

    /**
     * 搜索书籍
     */
    suspend fun searchBook(key: String): List<Book> {
        val url = "$baseUrl$API_SEARCH_BOOK"
        val body = GSON.toJson(mapOf("key" to key))
        val response = requestPost(url, body)
        
        val result = GSON.fromJsonObject<ApiResponse<List<Book>>>(response)
            .getOrNull() ?: throw NoStackTraceException("解析搜索结果失败")
        
        if (!result.isSuccess) {
            throw NoStackTraceException(result.errorMsg ?: "搜索失败")
        }
        
        return result.data ?: emptyList()
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean {
        return try {
            login()
            true
        } catch (e: Exception) {
            AppLog.put("Reader Server 连接测试失败: ${e.message}", e)
            false
        }
    }

    /**
     * 获取当前token信息用于保存
     */
    fun getTokenInfo(): Pair<String?, Long> {
        return Pair(token, tokenExpireTime)
    }

    /**
     * GET 请求
     */
    private suspend fun requestGet(url: String): String {
        val token = getValidToken()
        val response = okHttpClient.newCallStrResponse {
            url(url)
            addHeader("Authorization", "Bearer $token")
            addHeader("accessToken", token)
        }
        
        if (!response.isSuccessful()) {
            if (response.code() == 401) {
                // Token 过期，清除并重试
                this.token = null
                this.tokenExpireTime = 0
                val newToken = getValidToken()
                val retryResponse = okHttpClient.newCallStrResponse {
                    url(url)
                    addHeader("Authorization", "Bearer $newToken")
                    addHeader("accessToken", newToken)
                }
                if (!retryResponse.isSuccessful()) {
                    throw NoStackTraceException("请求失败: ${retryResponse.code()}")
                }
                return retryResponse.body ?: ""
            }
            throw NoStackTraceException("请求失败: ${response.code()}")
        }
        
        return response.body ?: ""
    }

    /**
     * POST 请求
     */
    private suspend fun requestPost(url: String, body: String): String {
        val token = getValidToken()
        val response = okHttpClient.newCallStrResponse {
            url(url)
            addHeader("Authorization", "Bearer $token")
            addHeader("accessToken", token)
            postJson(body)
        }
        
        if (!response.isSuccessful()) {
            if (response.code() == 401) {
                // Token 过期，清除并重试
                this.token = null
                this.tokenExpireTime = 0
                val newToken = getValidToken()
                val retryResponse = okHttpClient.newCallStrResponse {
                    url(url)
                    addHeader("Authorization", "Bearer $newToken")
                    addHeader("accessToken", newToken)
                    postJson(body)
                }
                if (!retryResponse.isSuccessful()) {
                    throw NoStackTraceException("请求失败: ${retryResponse.code()}")
                }
                return retryResponse.body ?: ""
            }
            throw NoStackTraceException("请求失败: ${response.code()}")
        }
        
        return response.body ?: ""
    }

    /**
     * API 响应数据结构
     */
    @Keep
    data class ApiResponse<T>(
        val isSuccess: Boolean = false,
        val errorMsg: String? = null,
        val data: T? = null
    )

    /**
     * 登录响应
     */
    @Keep
    data class LoginResponse(
        val token: String? = null,
        val expireTime: Long? = null,
        val username: String? = null
    )

    /**
     * 用户信息
     */
    @Keep
    data class UserInfo(
        val username: String? = null,
        val nickname: String? = null,
        val enableLocalStore: Boolean = false
    )
}
