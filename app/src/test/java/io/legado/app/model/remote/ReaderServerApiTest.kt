package io.legado.app.model.remote

import com.google.gson.JsonParser
import io.legado.app.model.remote.ReaderTestHttpExchange as HttpExchange
import io.legado.app.model.remote.ReaderTestHttpServer as HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** HTTP contract tests exercise the production client without a real account or Android device. */
class ReaderServerApiTest {
    private data class Reply(val status: Int = 200, val body: String, val type: String = "application/json; charset=utf-8")

    private fun withServer(handler: (HttpExchange) -> Reply, test: (ReaderServerApi) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val reply = handler(exchange)
                val bytes = reply.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", reply.type)
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val http = OkHttpClient()
        try {
            // A reverse proxy subpath must survive every endpoint and resource request.
            test(ReaderServerApi("http://127.0.0.1:${server.address.port}/reader", "tester", "password123", { http }, {}))
        } finally {
            server.stop(0)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun session(token: String = "token-one") = """{"user":{"id":"user-1","username":"tester","displayName":"Test"},"accessToken":"$token","accessTokenExpiresAt":${System.currentTimeMillis() + 3600000},"refreshToken":"refresh-$token","refreshTokenExpiresAt":${System.currentTimeMillis() + 7200000}}"""

    @Test
    fun oldServerIsRejectedWithoutTryingReader3() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestURI.toString()
            when (exchange.requestURI.path) {
                "/reader/reader3/login" -> Reply(body = """{"isSuccess":true,"data":{"username":"tester","accessToken":"tester:legacy"}}""")
                else -> Reply(404, "{}")
            }
        }) { api ->
            val failure = assertThrows(Exception::class.java) { runBlocking { api.login() } }
            assertTrue(failure.message.orEmpty().contains("仅支持新版 Reader（/api/v1）"))
            assertEquals(listOf("/reader/api/v1/auth/login"), requests)
            assertFalse(requests.any { it.contains("reader3") })
        }
    }

    @Test
    fun nonV1LoginResponsesAreRejectedClearly() {
        val bodies = listOf(
            "<!doctype html><html><body>Reader</body></html>",
            "{}",
            """{"isSuccess":false,"errorMsg":"not found"}"""
        )
        for (body in bodies) {
            val requests = CopyOnWriteArrayList<String>()
            withServer({ exchange ->
                requests += exchange.requestURI.path
                Reply(body = body)
            }) { api ->
                val failure = assertThrows(Exception::class.java) { runBlocking { api.login() } }
                assertTrue(failure.message.orEmpty().contains("仅支持新版 Reader（/api/v1）"))
                assertEquals(listOf("/reader/api/v1/auth/login"), requests)
            }
        }
    }

    @Test
    fun modernLoginFailureNeverFallsBackToLegacy() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/reader/api/v1/instance" -> Reply(body = """{"name":"reader","apiVersion":1}""")
                "/reader/api/v1/auth/login" -> Reply(401, """{"error":{"code":"BAD_CREDENTIALS","message":"bad credentials"}}""")
                else -> Reply(404, "{}")
            }
        }) { api ->
            val failure = assertThrows(Exception::class.java) { runBlocking { api.login() } }
            assertTrue(failure.message.orEmpty().contains("bad credentials"))
            assertFalse(requests.any { it.contains("reader3") })
        }
    }

    @Test
    fun modernSessionRefreshesOnUnauthorizedAndUsesBearer() {
        var refreshes = 0
        var reads = 0
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/instance" -> Reply(body = """{"name":"reader","apiVersion":1}""")
                "/reader/api/v1/auth/login" -> Reply(body = session())
                "/reader/api/v1/auth/refresh" -> { refreshes++; Reply(body = session("token-two")) }
                "/reader/api/v1/auth/me" -> {
                    reads++
                    if (exchange.requestHeaders.getFirst("Authorization") == "Bearer token-one") Reply(401, """{"error":{"code":"TOKEN_EXPIRED","message":"expired"}}""")
                    else Reply(body = """{"user":{"username":"tester","displayName":"Test"}}""")
                }
                else -> Reply(404, "{}")
            }
        }) { api -> runBlocking {
            api.login()
            assertEquals("tester", api.getUserInfo().username)
            assertEquals(1, refreshes)
            assertEquals(2, reads)
            assertEquals("token-two", api.getTokenInfo().first)
        } }
    }

    @Test
    fun modernShelfOpaqueResourcesAndTimestampedProgressWorkTogether() {
        val requests = CopyOnWriteArrayList<String>()
        val progressBodies = CopyOnWriteArrayList<String>()
        val manifest = """{"kind":"reflowable","total":2,"groups":[{"id":"g","seq":0,"title":"全书","count":2,"offset":0}],"items":[{"id":"first","seq":0,"title":"第一章","kind":"chapter","mediaType":"application/xhtml+xml","href":"xhtml:OEBPS/Text/first.xhtml"},{"id":"second","seq":1,"title":"第二章","kind":"chapter","mediaType":"application/xhtml+xml","href":"xhtml:OEBPS/Text/second.xhtml"}]}"""
        withServer({ exchange ->
            val path = exchange.requestURI.path
            requests += exchange.requestURI.toString()
            if (path.startsWith("/reader/api/v1/") && path != "/reader/api/v1/instance" && !path.contains("/auth/login")) {
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer token-one") return@withServer Reply(401, """{"error":{"code":"NO_TOKEN"}}""")
            }
            when {
                path == "/reader/api/v1/instance" -> Reply(body = """{"name":"reader","apiVersion":1}""")
                path == "/reader/api/v1/auth/login" -> Reply(body = session())
                path == "/reader/api/v1/books" -> {
                    val second = exchange.requestURI.query.orEmpty().split('&').contains("page=2")
                    val id = if (second) "book-b" else "book-a"
                    Reply(body = """{"items":[{"id":"$id","title":"$id","author":"作者","format":"epub","coverUrl":"/api/v1/books/$id/cover","description":"测试","tags":[]}],"total":2,"page":${if(second) 2 else 1},"pageSize":1}""")
                }
                path == "/reader/api/v1/sync" -> Reply(body = """{"progress":[],"notes":[],"serverTime":123}""")
                path.endsWith("/manifest") -> Reply(body = """{"book":{"format":"epub"},"content":$manifest}""")
                path.endsWith("/items") -> Reply(body = manifest)
                path.endsWith("/toc") -> Reply(body = """{"toc":[{"href":"xhtml:OEBPS/Text/first.xhtml","title":"第一章","level":0,"spine":0},{"href":"xhtml:OEBPS/Text/second.xhtml","title":"第二章","level":0,"spine":1}]}""")
                path.endsWith("/assets") -> Reply(body = "<html><body><p>正文测试</p><img src=\"/api/v1/books/book-a/assets?ref=asset%3AOEBPS%2FImages%2Fa%20b.png\"/></body></html>", type = "application/xhtml+xml; charset=utf-8")
                path.startsWith("/reader/api/v1/sync/progress/") -> {
                    progressBodies += exchange.requestBody.bufferedReader().readText()
                    val saved = JsonParser.parseString(progressBodies.last()).asJsonObject
                    saved.addProperty("bookId", "book-a")
                    Reply(body = """{"progress":$saved}""")
                }
                else -> Reply(404, "{}")
            }
        }) { api -> runBlocking {
            val books = api.getBookshelf()
            assertEquals(2, books.size)
            assertNotEquals(books[0].bookUrl, books[1].bookUrl)
            assertTrue(books.all { it.bookUrl.startsWith("reader-v1://") && it.durChapterTime == 0L })
            assertFalse(requests.any { it.contains("/sync") || it.contains("/manifest") || it.contains("/assets") })
            val book = books.first()
            val beforeForeignRequest = requests.size
            try {
                api.getResourceImage(book.coverUrl!!.replace("127.0.0.1", "localhost"))
                fail("foreign origin must be rejected before sending credentials")
            } catch (_: io.legado.app.exception.NoStackTraceException) {
                assertEquals(beforeForeignRequest, requests.size)
            }
            val chapters = api.getChapterList(book.bookUrl)
            assertEquals(2, chapters.size)
            val content = api.getBookContent(book.bookUrl, 1, chapters[1].url)
            assertTrue(content.contains("正文测试"))
            assertTrue(content.contains("/assets?"))
            assertFalse(content.contains("token-one"))
            book.durChapterIndex = 1
            book.durChapterPos = 1
            book.durChapterTime = 50000
            assertTrue(api.saveBookProgress(book))
            val payload = JsonParser.parseString(progressBodies.single()).asJsonObject
            assertEquals(50000, payload["updatedAt"].asLong)
            assertTrue(payload["locator"].asString.startsWith("r1:"))
            assertTrue(requests.any { it.contains("assets?ref=xhtml") && it.contains("second.xhtml") })
            assertFalse(requests.any { it.contains("accessToken=") || it.contains("access_token=") })
        } }
    }
}
