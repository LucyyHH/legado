package io.legado.app.model.remote

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Node Reader's /api/v1 protocol. All persisted URLs are free of credentials. */
internal class ReaderServerV1(
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val httpClient: () -> OkHttpClient
) {
    private val base = (serverUrl.trimEnd('/') + "/").toHttpUrl().also {
        if (it.username.isNotEmpty() || it.password.isNotEmpty() || it.query != null || it.fragment != null) {
            fail("Reader 服务器地址不能包含账号、查询参数或片段")
        }
    }
    private val apiPath = base.encodedPath.trimEnd('/') + "/api/v1"
    // SQLite NOCASE only folds ASCII. Do not collapse distinct Unicode accounts.
    private val accountKey = username.trim().map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    private val scope = MessageDigest.getInstance("SHA-256")
        .digest("${base.toString().trimEnd('/')}\n$accountKey".toByteArray())
        .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val loginMutex = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var expiresAt = 0L
    private var refreshToken: String? = null
    private val publications = ConcurrentHashMap<String, Publication>()
    private val bookFormats = ConcurrentHashMap<String, String>()
    private val chapterLengths = ConcurrentHashMap<String, Int>()

    private data class Payload(val status: Int, val contentType: String, val bytes: ByteArray) {
        fun text() = bytes.toString(Charsets.UTF_8)
    }
    private data class DocumentResponse(val status: Int, val location: String?)
    private data class Section(
        val href: String, val ref: String, val title: String, val mediaType: String,
        val kind: String, val text: String? = null
    )
    private data class Publication(val format: String, val sections: List<Section>)

    companion object {
        fun isBookUrl(url: String): Boolean = url.startsWith("reader-v1://")
        private const val PAGE_SIZE = 200
        private const val RAW_CHUNK = 256 * 1024
        private const val MAX_TEXT_CHARS = 32 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private fun fail(message: String): Nothing = throw NoStackTraceException(message)
    }

    fun setAccessToken(token: String?, expireTime: Long) {
        this.token = token
        expiresAt = expireTime
        refreshToken = null
    }

    fun getTokenInfo(): Pair<String?, Long> = token to expiresAt

    suspend fun login(): ReaderServerApi.LoginResponse = loginMutex.withLock {
        acceptSession(loginJson(loginRequest()))
        loginResponse()
    }

    private fun loginResponse() = ReaderServerApi.LoginResponse(token, expiresAt, username)

    private fun acceptSession(session: JsonObject) {
        val access = session.string("accessToken")
        val expiry = session.long("accessTokenExpiresAt")
        if (access.isBlank() || expiry <= System.currentTimeMillis()) fail("Reader 登录响应缺少有效令牌或过期时间")
        token = access
        expiresAt = expiry
        refreshToken = session.string("refreshToken").takeIf { it.isNotBlank() }
    }

    private suspend fun renewLocked() {
        val refresh = refreshToken
        if (refresh != null) {
            val response = raw(api("auth", "refresh"), "POST", mapOf("refreshToken" to refresh))
            if (response.status in 200..299) {
                acceptSession(json(response))
                return
            }
            if (response.status != 401 && response.status != 403) check(response)
        }
        acceptSession(loginJson(loginRequest()))
    }

    private suspend fun loginRequest(): Payload = raw(
        api("auth", "login"),
        "POST",
        mapOf("username" to username, "password" to password)
    )

    private suspend fun validToken(): String = loginMutex.withLock {
        if (token == null || System.currentTimeMillis() >= expiresAt - 15_000) renewLocked()
        token ?: fail("Reader 未登录")
    }

    private suspend fun request(url: HttpUrl, method: String = "GET", body: Any? = null): Payload {
        requireSameOrigin(url)
        val used = validToken()
        var response = raw(url, method, body, used)
        if (response.status == 401) {
            val replacement = loginMutex.withLock {
                // A parallel request may already have refreshed this rejected token.
                if (token == used) renewLocked()
                token ?: fail("Reader 未登录")
            }
            response = raw(url, method, body, replacement)
        }
        return check(response)
    }

    private suspend fun raw(url: HttpUrl, method: String, body: Any?, bearer: String? = null): Payload {
        requireSameOrigin(url)
        // Redirects are handled explicitly, so credentials never reach another origin.
        val client = httpClient().newBuilder().followRedirects(false).followSslRedirects(false).build()
        var target = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder().url(target)
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            if (method == "GET") builder.get()
            else builder.method(method, GSON.toJson(body ?: emptyMap<String, Any>()).toRequestBody(JSON))
            val result = execute(client.newCall(builder.build()))
            if (result.first.status !in 300..399) return result.first
            if (method != "GET") fail("Reader API 不接受登录或写入请求重定向，请填写最终服务器地址")
            val location = result.second ?: fail("Reader 资源重定向缺少地址")
            target = target.resolve(location) ?: fail("Reader 资源重定向地址无效")
            requireSameOrigin(target)
            if ((url.encodedPath.endsWith("/assets") || url.encodedPath.endsWith("/cover")) && target.encodedPath != url.encodedPath) {
                fail("Reader 图片或章节重定向到了其他资源，已拒绝发送登录凭据")
            }
        }
        fail("Reader 资源重定向次数过多")
    }

    private suspend fun execute(call: Call): Pair<Payload, String?> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        Payload(it.code, it.header("Content-Type").orEmpty(), it.body.bytes()) to it.header("Location")
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun executeDocument(call: Call, target: File): DocumentResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
                target.delete()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    target.delete()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val metadata = DocumentResponse(it.code, it.header("Location"))
                            // The cache needs the complete original document. A 206 body may
                            // start with %PDF- yet still contain only the first range.
                            if (it.code != 200) return@use metadata
                            if (!it.header("Content-Range").isNullOrBlank()) {
                                fail("Reader 返回了部分 PDF，无法建立完整离线缓存")
                            }
                            val contentType = it.header("Content-Type").orEmpty().substringBefore(';').trim()
                            if (!contentType.equals("application/pdf", true)) {
                                fail("Reader 返回的文档不是 PDF")
                            }
                            val body = it.body
                            val expected = it.header("Content-Length")?.toLongOrNull()
                                ?: body.contentLength()
                            var copied = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(target, false).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var prefixSize = 0
                                    while (prefixSize < 5) {
                                        if (!continuation.isActive) throw IOException("PDF 下载已取消")
                                        val count = input.read(buffer, prefixSize, 5 - prefixSize)
                                        if (count < 0) break
                                        prefixSize += count
                                    }
                                    if (prefixSize != 5 || !buffer.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                                        fail("Reader 返回的文档缺少有效 PDF 文件头")
                                    }
                                    output.write(buffer, 0, prefixSize)
                                    copied += prefixSize
                                    while (true) {
                                        if (!continuation.isActive) throw IOException("PDF 下载已取消")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        copied += count
                                    }
                                }
                            }
                            if (expected >= 0 && copied != expected) {
                                fail("Reader PDF 下载不完整：应为 $expected 字节，实际为 $copied 字节")
                            }
                            metadata
                        }
                        if (continuation.isActive) continuation.resume(result) else target.delete()
                    } catch (e: Exception) {
                        target.delete()
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private fun check(response: Payload): Payload {
        if (response.status !in 200..299) {
            val message = runCatching { JsonParser.parseString(response.text()).asJsonObject.getAsJsonObject("error")?.string("message") }.getOrNull()
            fail("Reader 请求失败 HTTP ${response.status}${message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        return response
    }

    private fun json(response: Payload): JsonObject {
        check(response)
        return runCatching { JsonParser.parseString(response.text()).asJsonObject }.getOrElse {
            fail("Reader 返回了无效 JSON，请检查服务器地址和反向代理")
        }
    }

    private fun loginJson(response: Payload): JsonObject {
        if (response.status == 404 || response.status == 405) {
            fail("仅支持新版 Reader（/api/v1），请升级服务器或检查地址")
        }
        check(response)
        val session = runCatching {
            JsonParser.parseString(response.text()).asJsonObject.takeIf {
                it.string("accessToken").isNotBlank() && it.long("accessTokenExpiresAt") > 0
            }
        }.getOrNull()
        return session ?: fail("仅支持新版 Reader（/api/v1），该地址未返回新版登录信息，请检查服务器版本或地址")
    }

    private fun api(vararg segments: String): HttpUrl = base.newBuilder()
        .addPathSegments("api/v1").apply { segments.forEach { addPathSegment(it) } }.build()

    private fun asset(id: String, ref: String): HttpUrl = api("books", id, "assets").newBuilder()
        .addQueryParameter("ref", ref).build()

    private fun requireSameOrigin(url: HttpUrl) {
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || !url.encodedPath.startsWith(base.encodedPath)) {
            fail("Reader 资源不属于当前服务器，已拒绝发送登录凭据")
        }
    }

    private fun synthetic(id: String): String {
        val encoded = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        return "reader-v1://$scope/$encoded"
    }

    private fun id(bookUrl: String): String {
        val parsed = runCatching { URI(bookUrl) }.getOrNull() ?: fail("Reader 书籍地址无效")
        if (parsed.scheme != "reader-v1" || parsed.host != scope || parsed.rawQuery != null || parsed.rawFragment != null ||
            parsed.rawUserInfo != null || parsed.port != -1 || parsed.rawPath.isNullOrEmpty() || parsed.rawPath.substring(1).contains('/')) {
            fail("此书籍属于其他 Reader 服务器或账号，请先切换到对应配置")
        }
        return URLDecoder.decode(parsed.rawPath.substring(1), "UTF-8").takeIf { it.isNotBlank() }
            ?: fail("Reader 书籍 ID 为空")
    }

    suspend fun getUserInfo(): ReaderServerApi.UserInfo {
        val user = json(request(api("auth", "me"))).getAsJsonObject("user") ?: fail("Reader 用户信息为空")
        return ReaderServerApi.UserInfo(user.string("username"), user.string("displayName"), true)
    }

    private suspend fun books(search: String? = null): List<Book> {
        val result = linkedMapOf<String, Book>()
        var page = 1
        do {
            val url = api("books").newBuilder().addQueryParameter("page", page.toString())
                .addQueryParameter("pageSize", PAGE_SIZE.toString()).apply {
                    if (search != null) addQueryParameter("search", search)
                }.build()
            val response = json(request(url))
            val items = response.array("items")
            for (value in items) {
                val dto = value.asJsonObject
                val bookId = dto.string("id").takeIf { it.isNotBlank() } ?: fail("Reader 书籍缺少 ID")
                val format = dto.string("format")
                bookFormats[bookId] = format
                result[bookId] = Book(
                    bookUrl = synthetic(bookId), origin = BookType.readerServerLocalTag,
                    originName = "Reader · $format", name = dto.string("title"),
                    author = dto.string("author"), intro = dto.string("description"),
                    kind = dto.arrayOrEmpty("tags").joinToString(", ") { it.asString },
                    coverUrl = dto.string("coverUrl").takeIf { it.isNotBlank() }?.let {
                        val resolved = resourceUrl(it)
                        validateImageResource(resolved)
                        resolved.newBuilder().removeAllQueryParameters("access_token").removeAllQueryParameters("accessToken").build().toString()
                    },
                    type = if (format in setOf("cbz", "comic-dir", "image")) BookType.image else BookType.text,
                    totalChapterNum = 0, durChapterTime = 0, latestChapterTime = dto.long("updatedAt"),
                    variable = GSON.toJson(buildMap<String, String> {
                        put("readerV1Format", format)
                        put("readerV1Title", dto.string("title"))
                        put("readerV1Series", dto.string("series"))
                        put("readerV1SeriesIndex", dto.string("seriesIndex"))
                        put("readerV1Tags", dto.arrayOrEmpty("tags").toString())
                        if (!supportedFormat(format)) put("readerV1UnsupportedReason", "当前客户端不支持 $format，请在 Reader 网页阅读")
                    }),
                    canUpdate = false
                )
            }
            val total = response.long("total")
            if (items.size() == 0 || result.size >= total) break
            if (++page > 100_000) fail("Reader 书架分页异常")
        } while (true)
        return result.values.toList()
    }

    suspend fun getBookshelf(): List<Book> = books()

    suspend fun getProgressRecords(bookUrls: List<String> = emptyList()): Map<String, ReaderServerProgress> {
        val requestedIds = bookUrls.associateWith { id(it) }
        // The sync endpoint caps each response at 5000. Pull until no new timestamp.
        val progress = linkedMapOf<String, JsonObject>()
        var since = 0L
        var hitLimit = false
        while (true) {
            val response = json(request(api("sync").newBuilder().addQueryParameter("since", since.toString()).build()))
            val batch = response.array("progress")
            batch.forEach { value -> value.asJsonObject.let { progress[it.string("bookId")] = it } }
            if (batch.size() < 5000) break
            hitLimit = true
            val next = batch.maxOf { it.asJsonObject.long("updatedAt") }
            if (next <= since) break
            since = next
        }
        if (hitLimit) {
            requestedIds.values.filterNot(progress::containsKey).forEach { bookId ->
                val value = json(request(api("sync", "progress", bookId))).get("progress")
                value?.takeUnless { it.isJsonNull }?.asJsonObject?.let { progress[bookId] = it }
            }
        }
        val allowed = requestedIds.values.toSet().takeIf { bookUrls.isNotEmpty() }
        return progress.mapNotNull { (bookId, saved) ->
            if (allowed != null && bookId !in allowed) return@mapNotNull null
            val bookUrl = synthetic(bookId)
            bookUrl to progressRecord(bookUrl, saved)
        }.toMap(linkedMapOf())
    }

    suspend fun resolveProgress(book: Book, progress: ReaderServerProgress): Book {
        if (progress.bookUrl != book.bookUrl) fail("Reader 阅读进度与书籍不匹配")
        val format = JsonParser.parseString(book.variable).asJsonObject.string("readerV1Format")
        if (format == "pdf") {
            val position = ReaderServerPdfLocator.parse(progress.locator)
            if (position != null) {
                if (book.totalChapterNum > 0 && position.page > book.totalChapterNum) {
                    fail("服务器 PDF 页码超出当前文档页数")
                }
                return book.copy(
                    durChapterIndex = position.page - 1,
                    durChapterTitle = "第${position.page}页",
                    durChapterTime = progress.updatedAt,
                    durChapterPos = 0
                )
            }
            if (ReaderServerPdfLocator.isDocumentLevel(progress.locator)) {
                // The browser's Blob/PDF viewer cannot expose its current page. Keep any
                // page already known by Legado while still accepting the newer timestamp.
                return book.copy(durChapterTime = progress.updatedAt)
            }
            fail("服务器 PDF 阅读位置不是可识别的页码定位符")
        }
        if (!supportedFormat(format)) fail("阅读客户端暂不支持 Reader 的 $format 格式进度")
        val position = ReaderServerLocator.parse(progress.locator)
            ?: fail("服务器阅读位置不是可识别的 r1 定位符")
        val publication = publication(book.bookUrl)
        val index = publication.sections.indexOfFirst { matchesSection(it.href, position.sectionId) }
        if (index < 0) fail("服务器阅读位置已不在当前目录中")
        val resolved = book.copy(
            totalChapterNum = publication.sections.size,
            durChapterIndex = index,
            durChapterTitle = publication.sections[index].title,
            durChapterTime = progress.updatedAt,
            durChapterPos = 0
        )
        if (position.fraction > 0) {
            val content = sectionContent(id(book.bookUrl), publication.sections[index])
            resolved.durChapterPos = (content.length * position.fraction).roundToInt().coerceIn(0, content.length)
        }
        return resolved
    }

    suspend fun getBookProgress(book: Book): Book? {
        val bookId = id(book.bookUrl)
        val value = json(request(api("sync", "progress", bookId))).get("progress")
        val saved = value?.takeUnless { it.isJsonNull }?.asJsonObject ?: return null
        val progress = progressRecord(book.bookUrl, saved)
        return if (progress.updatedAt > book.durChapterTime) resolveProgress(book, progress)
        else book.copy(durChapterTime = progress.updatedAt)
    }

    suspend fun searchBook(key: String): List<Book> = books(key)

    private suspend fun publication(bookUrl: String, reload: Boolean = false): Publication {
        val bookId = id(bookUrl)
        if (!reload) publications[bookId]?.let { return it }
        val root = json(request(api("books", bookId, "manifest").newBuilder().addQueryParameter("group", "all").build()))
        val format = root.getAsJsonObject("book")?.string("format") ?: fail("Reader 书籍信息为空")
        if (!supportedFormat(format)) {
            fail("阅读客户端暂不支持 Reader 的 $format 格式，请在 Reader 网页中阅读")
        }
        val content = root.get("content")?.takeUnless { it.isJsonNull }?.asJsonObject
            ?: fail("Reader 未提供该书的可阅读资源")
        var sections = content.array("items").map { value ->
            val item = value.asJsonObject
            val href = item.string("href").takeIf { it.isNotBlank() } ?: fail("Reader 章节资源地址为空")
            val ref = item.string("resourceRef").takeIf { it.isNotBlank() }
                ?: if (format == "txt" && item.string("format") == "html" && href.startsWith("chapter:")) {
                    "chapter-full:" + href.removePrefix("chapter:")
                } else href
            Section(href, ref, item.string("title"), item.string("mediaType"), item.string("kind"))
        }
        if (format == "txt" && sections.isEmpty()) {
            val text = StringBuilder()
            while (true) {
                val chunk = request(asset(bookId, "chunk:${text.length}")).text()
                if (text.length + chunk.length > MAX_TEXT_CHARS) fail("无目录 TXT 超过 32 Mi 字符，请在服务器分章后阅读")
                text.append(chunk)
                if (chunk.length < RAW_CHUNK) break
            }
            sections = ReaderServerLocator.splitText(text.toString()).map {
                Section(it.id, it.id, it.title, "text/plain", "chapter", it.text)
            }
        }
        if (sections.isEmpty()) fail("Reader 书籍没有可阅读章节")
        if (content.long("total") > sections.size && format != "txt") fail("Reader 目录不完整，请更新服务器后重试")
        return Publication(format, sections).also { publications[bookId] = it }
    }

    suspend fun getChapterList(bookUrl: String): List<BookChapter> {
        val bookId = id(bookUrl)
        if (bookFormats[bookId] == "pdf") fail("PDF 需要下载原文档并使用本地 PDF 阅读器打开")
        val publication = publication(bookUrl, true)
        if (publication.format == "pdf") fail("PDF 需要下载原文档并使用本地 PDF 阅读器打开")
        return publication.sections.mapIndexed { index, section ->
            BookChapter(
                url = asset(bookId, section.ref).toString(), title = section.title,
                baseUrl = base.toString(), bookUrl = bookUrl, index = index,
                variable = GSON.toJson(mapOf("readerSectionId" to section.href))
            )
        }
    }

    suspend fun getBookContent(bookUrl: String, chapterIndex: Int, chapterUrl: String? = null): String {
        val bookId = id(bookUrl)
        if (bookFormats[bookId] == "pdf") fail("PDF 是二进制文档，不能作为章节正文读取")
        val publication = publication(bookUrl)
        if (publication.format == "pdf") fail("PDF 是二进制文档，不能作为章节正文读取")
        val section = publication.sections.getOrNull(chapterIndex) ?: fail("Reader 章节索引超出目录")
        if (chapterUrl != null) {
            val expected = asset(bookId, section.ref)
            val actual = chapterUrl.toHttpUrlOrNull() ?: fail("Reader 章节地址无效")
            requireSameOrigin(actual)
            if (actual != expected) fail("Reader 章节已更新，请刷新目录后重试")
        }
        return sectionContent(bookId, section)
    }

    private suspend fun sectionContent(bookId: String, section: Section): String {
        val content = when {
            section.kind == "page" -> "<img src=\"${asset(bookId, section.ref)}\">"
            section.text != null -> section.text
            else -> {
                val response = request(asset(bookId, section.ref))
                val body = response.text()
                if (section.mediaType.contains("html") || section.mediaType.contains("xml")) {
                    htmlContent(bookId, section.ref, body)
                } else if (section.mediaType.startsWith("text/plain") || response.contentType.startsWith("text/plain")) body
                else fail("Reader 章节格式不受支持：${section.mediaType}")
            }
        }
        chapterLengths["$bookId\n${section.href}"] = content.length
        return content
    }

    private fun htmlContent(bookId: String, chapterRef: String, html: String): String {
        val document = Jsoup.parse(html, base.toString())
        document.select("script, style, title, link, iframe, object").remove()
        document.select("image").forEach { image ->
            image.tagName("img")
            if (image.hasAttr("xlink:href")) image.attr("src", image.attr("xlink:href"))
            else if (image.hasAttr("href")) image.attr("src", image.attr("href"))
        }
        document.select("img").forEach { image ->
            val src = image.attr("src")
            if (src.startsWith("data:image/")) return@forEach
            // Some EPUBs escape spaces in archive paths. Reader leaves those links
            // relative when its archive lookup cannot match the escaped spelling.
            val resolved = if (chapterRef.startsWith("xhtml:") &&
                !src.startsWith("/api/v1/") && !src.startsWith(apiPath + "/") &&
                !Regex("^(?:[a-z][a-z0-9+.-]*:|//)", RegexOption.IGNORE_CASE).containsMatchIn(src)) {
                val path = src.substringBefore('#').substringBefore('?')
                val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
                val chapter = chapterRef.removePrefix("xhtml:")
                val target = URI(null, null, "/$chapter", null)
                    .resolve(URI(null, null, decoded, null)).normalize().path.removePrefix("/")
                asset(bookId, target)
            } else resourceUrl(src)
            validateImageResource(resolved, bookId)
            image.attr("src", resolved.newBuilder().removeAllQueryParameters("access_token").removeAllQueryParameters("accessToken").build().toString())
            image.removeAttr("srcset")
        }
        // Keep text and images without depending on Android's HTML/span machinery.
        // TextNode.text also decodes entities, while TXT bytes never enter a parser.
        val result = StringBuilder()
        fun append(node: Node) {
            when (node) {
                is TextNode -> result.append(node.text())
                is Element -> {
                    if (node.tagName() == "img") {
                        result.append("\n<img src=\"").append(node.attr("src").replace("\"", "%22")).append("\">\n")
                    } else {
                        if (node.isBlock || node.tagName() == "br") result.append('\n')
                        node.childNodes().forEach { append(it) }
                        if (node.isBlock) result.append('\n')
                    }
                }
            }
        }
        append(document.body())
        return result.toString().replace(Regex("\\n[ \\t]*\\n+"), "\n").trim()
    }

    private fun validateImageResource(url: HttpUrl, bookId: String? = null) {
        requireSameOrigin(url)
        val prefix = apiPath + "/books/"
        if (!url.encodedPath.startsWith(prefix)) fail("Reader 图片不是书籍资源")
        val rest = url.encodedPath.removePrefix(prefix).split('/')
        if (rest.size != 2 || rest[0].isBlank() || rest[1] !in setOf("cover", "assets")) fail("Reader 图片资源路径无效")
        if (bookId != null && URLDecoder.decode(rest[0].replace("+", "%2B"), "UTF-8") != bookId) fail("Reader 图片属于其他书籍")
        if (rest[1] == "assets" && url.queryParameter("ref").isNullOrBlank()) fail("Reader 图片缺少资源引用")
    }

    suspend fun getBookImage(bookUrl: String, imageSrc: String): ByteArray {
        val bookId = id(bookUrl)
        if (imageSrc.startsWith("data:image/")) {
            val comma = imageSrc.indexOf(',')
            if (comma < 0) fail("Reader 内嵌图片地址无效")
            val header = imageSrc.substring(0, comma)
            val data = imageSrc.substring(comma + 1)
            return if (header.endsWith(";base64", true)) {
                Base64.decode(data, Base64.DEFAULT)
            } else URLDecoder.decode(data.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
        }
        val url = resourceUrl(imageSrc)
        validateImageResource(url, bookId)
        return imageBytes(request(url))
    }

    suspend fun getResourceImage(url: String): ByteArray {
        val parsed = url.toHttpUrlOrNull() ?: fail("Reader 图片地址无效")
        validateImageResource(parsed)
        return imageBytes(request(parsed))
    }

    suspend fun downloadBookDocument(bookUrl: String, target: File) {
        if (target.exists() && !target.delete()) fail("无法清理旧的 PDF 临时文件")
        if (target.absoluteFile.parentFile?.isDirectory != true) fail("PDF 临时文件目录不存在")
        try {
            val bookId = id(bookUrl)
            val format = bookFormats[bookId] ?: run {
                val book = json(request(api("books", bookId))).getAsJsonObject("book")
                    ?: fail("Reader 书籍信息为空")
                book.string("format").also { bookFormats[bookId] = it }
            }
            if (format != "pdf") fail("Reader 的 $format 格式不是可下载的 PDF 文档")

            val original = asset(bookId, "document")
            val used = validToken()
            var result = downloadDocument(original, target, used, bookId)
            if (result.status == 401) {
                val replacement = loginMutex.withLock {
                    if (token == used) renewLocked()
                    token ?: fail("Reader 未登录")
                }
                result = downloadDocument(original, target, replacement, bookId)
            }
            if (result.status != 200) fail("Reader PDF 下载失败 HTTP ${result.status}")
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private suspend fun downloadDocument(
        original: HttpUrl,
        target: File,
        bearer: String,
        bookId: String
    ): DocumentResponse {
        val client = httpClient().newBuilder().followRedirects(false).followSslRedirects(false).build()
        var url = original
        repeat(6) {
            currentCoroutineContext().ensureActive()
            validateDocumentResource(url, bookId)
            val call = client.newCall(Request.Builder().url(url)
                .header("Authorization", "Bearer $bearer").get().build())
            val response = executeDocument(call, target)
            if (response.status !in 300..399) return response
            val location = response.location ?: fail("Reader PDF 重定向缺少地址")
            url = url.resolve(location) ?: fail("Reader PDF 重定向地址无效")
        }
        fail("Reader PDF 重定向次数过多")
    }

    private fun validateDocumentResource(url: HttpUrl, bookId: String) {
        requireSameOrigin(url)
        val expected = asset(bookId, "document")
        if (url.encodedPath != expected.encodedPath || url.queryParameterValues("ref") != listOf("document") ||
            url.queryParameterNames.any { it != "ref" }) {
            fail("Reader PDF 重定向到了其他资源，已拒绝发送登录凭据")
        }
    }

    private fun imageBytes(response: Payload): ByteArray {
        if (!response.contentType.startsWith("image/", true) || response.bytes.isEmpty()) fail("Reader 返回的资源不是有效图片")
        return response.bytes
    }

    suspend fun saveBookProgress(book: Book): Boolean {
        val format = runCatching { JsonParser.parseString(book.variable).asJsonObject.string("readerV1Format") }
            .getOrDefault("")
        return if (format == "pdf") savePdfProgress(book) else saveProgress(
            book.bookUrl, book.durChapterIndex, book.durChapterPos,
            book.durChapterTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    private suspend fun savePdfProgress(book: Book): Boolean {
        val bookId = id(book.bookUrl)
        val index = book.durChapterIndex
        if (index < 0 || index == Int.MAX_VALUE) fail("PDF 阅读页码无效")
        val page = index + 1
        if (book.totalChapterNum > 0 && page > book.totalChapterNum) {
            fail("PDF 阅读页码超出当前文档页数")
        }
        val percentage = if (book.totalChapterNum > 0) {
            (page.toDouble() / book.totalChapterNum).coerceIn(0.0, 1.0)
        } else 0.0
        val locator = ReaderServerPdfLocator.format(page, percentage)
        val time = book.durChapterTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val response = json(request(api("sync", "progress", bookId), "PUT", mapOf(
            "locator" to locator, "percentage" to percentage,
            "chapterTitle" to "第${page}页", "device" to "Legado Android", "updatedAt" to time
        )))
        verifySavedProgress(response, locator, time)
        return true
    }

    private suspend fun saveProgress(bookUrl: String, chapterIndex: Int, chapterPosition: Int, time: Long): Boolean {
        val bookId = id(bookUrl)
        val sections = publication(bookUrl).sections
        val section = sections.getOrNull(chapterIndex) ?: fail("Reader 阅读进度超出章节目录")
        val length = chapterLengths["$bookId\n${section.href}"]
            ?: if (chapterPosition > 0) sectionContent(bookId, section).length else 0
        val fraction = if (length > 0) (chapterPosition.toDouble() / length).coerceIn(0.0, 1.0) else 0.0
        val locator = ReaderServerLocator.format(section.href, fraction)
        val response = json(request(api("sync", "progress", bookId), "PUT", mapOf(
            "locator" to locator,
            "percentage" to (chapterIndex + fraction) / sections.size,
            "chapterTitle" to section.title, "device" to "Legado Android", "updatedAt" to time
        )))
        verifySavedProgress(response, locator, time)
        return true
    }

    private fun verifySavedProgress(response: JsonObject, locator: String, time: Long) {
        val saved = response.get("progress")
        if (saved == null || saved.isJsonNull) fail("Reader 未保存阅读进度")
        val merged = saved.asJsonObject
        if (merged.long("updatedAt") != time || merged.string("locator") != locator) {
            fail("Reader 服务器有更新进度，请重新同步")
        }
    }

    suspend fun deleteBook(book: Book): Boolean {
        val result = json(request(api("library", "browse", "shelf"), "POST",
            mapOf("bookIds" to listOf(id(book.bookUrl)), "action" to "remove")))
        if (result.arrayOrEmpty("failed").size() > 0 || result.long("applied") != 1L) fail("Reader 未能将书籍移出书架")
        publications.remove(id(book.bookUrl))
        return true
    }

    private fun matchesSection(href: String, locator: String): Boolean = href == locator ||
        (href.startsWith("xhtml:") && href.removePrefix("xhtml:") == locator)

    private fun supportedFormat(format: String): Boolean = format in setOf("txt", "epub", "chapters", "cbz", "comic-dir", "image", "pdf")

    private fun resourceUrl(value: String): HttpUrl {
        // Reader emits root-relative API paths even behind a path-prefix proxy.
        // Resolve those against the configured API root, not the HTTP origin.
        val path = if (value.startsWith("/api/v1/")) base.encodedPath.trimEnd('/') + value else value
        return (base.resolve(path) ?: fail("Reader 资源地址无效")).also { requireSameOrigin(it) }
    }

    private fun progressRecord(bookUrl: String, saved: JsonObject) = ReaderServerProgress(
        bookUrl = bookUrl,
        locator = saved.string("locator"),
        updatedAt = saved.long("updatedAt"),
        chapterTitle = saved.string("chapterTitle").takeIf { it.isNotBlank() }
    )

    private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.long(key: String): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?: fail("Reader 响应缺少 $key 列表")
    private fun JsonObject.arrayOrEmpty(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
}
