package io.legado.app.model.remote

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.secureOkHttpClient
import okhttp3.OkHttpClient
import java.io.File

/** Client for the current Reader `/api/v1` protocol. */
class ReaderServerApi(
    serverUrl: String,
    username: String,
    password: String,
    private val clientProvider: (() -> OkHttpClient)? = null,
    private val log: (String) -> Unit = { AppLog.put(it) }
) {
    private val httpClient get() = clientProvider?.invoke() ?: if (AppConfig.readerServerStrictSsl) {
        secureOkHttpClient
    } else {
        okHttpClient
    }

    private val modern = ReaderServerV1(serverUrl, username, password) { httpClient }

    constructor(config: Server.ReaderServerConfig) : this(
        config.url,
        config.username,
        config.password
    )

    fun setAccessToken(token: String?, expireTime: Long) = modern.setAccessToken(token, expireTime)

    fun getTokenInfo(): Pair<String?, Long> = modern.getTokenInfo()

    suspend fun login(): LoginResponse = modern.login()

    suspend fun getUserInfo(): UserInfo = modern.getUserInfo()

    suspend fun getBookshelf(): List<Book> = modern.getBookshelf()

    suspend fun getProgressRecords(
        bookUrls: List<String> = emptyList()
    ): Map<String, ReaderServerProgress> = modern.getProgressRecords(bookUrls)

    suspend fun resolveProgress(book: Book, progress: ReaderServerProgress): Book =
        modern.resolveProgress(book, progress)

    suspend fun getBookProgress(book: Book): Book? = modern.getBookProgress(book)

    suspend fun saveBookProgress(book: Book): Boolean = modern.saveBookProgress(book)

    suspend fun getChapterList(bookUrl: String): List<BookChapter> = modern.getChapterList(bookUrl)

    suspend fun getBookContent(
        bookUrl: String,
        chapterIndex: Int,
        chapterUrl: String? = null
    ): String = modern.getBookContent(bookUrl, chapterIndex, chapterUrl)

    suspend fun getBookImage(bookUrl: String, imageSrc: String): ByteArray =
        modern.getBookImage(bookUrl, imageSrc)

    suspend fun getResourceImage(url: String): ByteArray = modern.getResourceImage(url)

    suspend fun downloadBookDocument(bookUrl: String, target: File) =
        modern.downloadBookDocument(bookUrl, target)

    suspend fun deleteBook(book: Book): Boolean = modern.deleteBook(book)

    suspend fun searchBook(key: String): List<Book> = modern.searchBook(key)

    suspend fun testConnection(): Boolean {
        return try {
            setAccessToken(null, 0L)
            login()
            true
        } catch (e: Exception) {
            log("Reader Server 连接测试失败: ${e.message}")
            throw e
        }
    }

    @Keep
    data class LoginResponse(
        val token: String? = null,
        val expireTime: Long? = null,
        val username: String? = null
    )

    @Keep
    data class UserInfo(
        val username: String? = null,
        val nickname: String? = null,
        val enableLocalStore: Boolean = false
    )
}
