package io.legado.app.model.remote

import io.legado.app.model.remote.ReaderTestHttpExchange as HttpExchange
import io.legado.app.model.remote.ReaderTestHttpServer as HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ReaderServerV1Test {
    private data class Reply(
        val body: String,
        val type: String = "application/json",
        val status: Int = 200,
        val location: String? = null,
        val declaredLength: Long? = null,
        val contentRange: String? = null
    )

    private fun withServer(handler: (HttpExchange) -> Reply, test: (String, OkHttpClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val http = OkHttpClient()
        server.createContext("/") { exchange ->
            try {
                val reply = handler(exchange)
                exchange.responseHeaders.add("Content-Type", reply.type)
                reply.location?.let { exchange.responseHeaders.add("Location", it) }
                reply.contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
                val bytes = reply.body.toByteArray()
                exchange.sendResponseHeaders(reply.status, reply.declaredLength ?: bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}/reader", http)
        } finally {
            server.stop(0)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun session() = Reply("""{"accessToken":"valid-token","accessTokenExpiresAt":${System.currentTimeMillis() + 3600000},"refreshToken":"refresh-token"}""")

    @Test
    fun imageOnlyEpubChaptersResolveImagesInBothDirections() {
        // Structure of the reported EPUB: a cover plus 110 one-image XHTML pages,
        // with image names containing spaces and no directory prefix.
        fun chapterName(index: Int) = if (index == 0) "titlepage.xhtml" else "page_$index.xhtml"
        fun imageName(index: Int) = if (index == 0) "cover.jpeg"
            else "${index - 1} - ${index.toString().padStart(5, '0')}.jpg"
        val sections = (0..110).joinToString(",") { index ->
            """{"href":"xhtml:${chapterName(index)}","title":"${imageName(index)}","kind":"chapter","mediaType":"application/xhtml+xml"}"""
        }
        val imageRequests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"image-epub","title":"图片书","format":"epub"}],"total":1}""")
                "/reader/api/v1/books/image-epub/manifest" -> {
                    assertEquals("group=all", exchange.requestURI.rawQuery)
                    Reply("""{"book":{"format":"epub"},"content":{"total":111,"items":[$sections]}}""")
                }
                "/reader/api/v1/books/image-epub/assets" -> {
                    assertEquals("Bearer valid-token", exchange.requestHeaders.getFirst("Authorization"))
                    val ref = URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("ref="), "UTF-8")
                    if (ref.startsWith("xhtml:")) {
                        val name = ref.removePrefix("xhtml:")
                        val index = if (name == "titlepage.xhtml") 0 else name.removePrefix("page_").substringBefore('.').toInt()
                        val image = if (index % 2 == 0) {
                            """<svg><image xlink:href="${imageName(index).replace(" ", "%20")}"/></svg>"""
                        } else {
                            """<img src="${imageName(index)}"/>"""
                        }
                        Reply("<html><body>$image</body></html>", "application/xhtml+xml")
                    } else {
                        imageRequests += ref
                        Reply(ref, "image/jpeg")
                    }
                }
                else -> Reply("unexpected", status = 404)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val chapters = api.getChapterList(book.bookUrl)
            assertEquals(111, chapters.size)
            val order = listOf(0, 1, 2, 3, 39, 40, 41, 79, 80, 81, 109, 110, 109, 80, 40, 3, 2, 1, 0, 40)
            for (index in order) {
                val chapter = chapters[index]
                val content = api.getBookContent(book.bookUrl, index, chapter.url)
                assertTrue(io.legado.app.help.book.ImagePageLayout.isPageBased(book, chapter, content))
                val src = org.jsoup.Jsoup.parse(content).selectFirst("img")!!.attr("src")
                assertFalse(src.contains("valid-token"))
                val expectedRef = imageName(index)
                assertArrayEquals(expectedRef.toByteArray(), api.getBookImage(book.bookUrl, src))
                assertArrayEquals(expectedRef.toByteArray(), api.getResourceImage(src))
            }
            assertEquals(order.flatMap { listOf(imageName(it), imageName(it)) }, imageRequests)
        } }
    }

    @Test
    fun pdfShelfDisplaysSeriesAndVolumeWithoutLoadingManifests() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-series","title":"第一个实验对象","series":"小裤裤笔记","seriesIndex":2,"format":"pdf"}],"total":1}""")
                else -> Reply("{}", status = 404)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            assertEquals("小裤裤笔记 2 · 第一个实验对象", book.getDisplayName())
            assertEquals("第一个实验对象", book.name)
            assertEquals(listOf("/reader/api/v1/auth/login", "/reader/api/v1/books"), requests.toList())
        } }
    }

    @Test
    fun imageRefsKeepEveryCharacterAndForeignScopeAndEndpointsAreRejected() {
        val requests = CopyOnWriteArrayList<String>()
        val ref = "page:opaque:part /+?&中文"
        withServer({ exchange ->
            requests.add(exchange.requestURI.toString())
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"comic-a","title":"漫画","format":"cbz"}],"total":1}""")
                "/reader/api/v1/books/comic-a/manifest" -> Reply("""{"book":{"format":"cbz"},"content":{"total":1,"items":[{"href":"$ref","title":"第一张","kind":"page","mediaType":"image/png"}]}}""")
                "/reader/api/v1/books/comic-a/assets" -> {
                    assertEquals("Bearer valid-token", exchange.requestHeaders.getFirst("Authorization"))
                    assertEquals(ref, URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("ref="), "UTF-8"))
                    Reply("image bytes", "image/png")
                }
                else -> Reply("{}", status = 404)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val chapter = api.getChapterList(book.bookUrl).single()
            val html = api.getBookContent(book.bookUrl, 0, chapter.url)
            assertFalse(html.contains("valid-token"))
            val src = org.jsoup.Jsoup.parse(html).selectFirst("img")!!.attr("src")
            assertArrayEquals("image bytes".toByteArray(), api.getBookImage(book.bookUrl, src))
            val requestCount = requests.size
            val other = ReaderServerV1(base, "other-account", "password") { http }
            assertThrows(Exception::class.java) { runBlocking { other.getChapterList(book.bookUrl) } }
            assertThrows(Exception::class.java) { runBlocking { api.getResourceImage("$base/api/v1/auth/me") } }
            assertThrows(Exception::class.java) { runBlocking { api.getBookImage(book.bookUrl, "https://example.invalid/api/v1/books/comic-a/assets?ref=x") } }
            assertEquals(requestCount, requests.size)
        } }
    }

    @Test
    fun shelfAndRawProgressDoNotLoadContentAndResolutionIsPerBook() {
        val body = "第一章\n正文 <literal> 不是 HTML\n" + "甲".repeat(100)
        val refs = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"txt-a","title":"小说","format":"txt"},{"id":"pdf-a","title":"扫描书","format":"pdf"}],"total":2}""")
                "/reader/api/v1/sync" -> Reply("""{"progress":[{"bookId":"txt-a","locator":"r1:0.2500:chapter:0","updatedAt":12345},{"bookId":"pdf-a","locator":"{\"chapter\":\"document\",\"spine\":0,\"block\":9,\"ratio\":0,\"percent\":0}","updatedAt":54321}]}""")
                "/reader/api/v1/books/txt-a/manifest" -> Reply("""{"book":{"format":"txt"},"content":{"total":1,"items":[{"href":"chapter:0","format":"html","title":"第一章","kind":"chapter","mediaType":"text/plain"}]}}""")
                "/reader/api/v1/books/pdf-a/manifest" -> Reply("""{"book":{"format":"pdf"},"content":{"total":1,"items":[]}}""")
                "/reader/api/v1/books/txt-a/assets" -> {
                    refs.add(URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("ref="), "UTF-8"))
                    Reply(body, "text/plain; charset=utf-8")
                }
                else -> Reply("{}", status = 404)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val books = api.getBookshelf()
            assertEquals(2, books.size)
            assertTrue(refs.isEmpty())
            assertTrue(books.all { it.durChapterTime == 0L })
            val progress = api.getProgressRecords(books.map { it.bookUrl })
            assertEquals(2, progress.size)
            assertTrue(refs.isEmpty())
            val txt = api.resolveProgress(books.first(), progress.getValue(books.first().bookUrl))
            assertEquals(12345L, txt.durChapterTime)
            assertEquals(Math.round(body.length * .25).toInt(), txt.durChapterPos)
            assertEquals(body, api.getBookContent(txt.bookUrl, 0))
            assertEquals(listOf("chapter-full:0", "chapter-full:0"), refs.toList())
            val pdf = books.last()
            assertTrue(pdf.variableMap["readerV1UnsupportedReason"].isNullOrEmpty())
            val resolvedPdf = api.resolveProgress(pdf, progress.getValue(pdf.bookUrl))
            assertEquals(8, resolvedPdf.durChapterIndex)
            assertEquals("第9页", resolvedPdf.durChapterTitle)
            assertEquals(54321L, resolvedPdf.durChapterTime)
            val failure = assertThrows(Exception::class.java) { runBlocking { api.getChapterList(pdf.bookUrl) } }
            assertTrue(failure.message.orEmpty().contains("PDF"))
        } }
    }

    @Test
    fun pdfProgressUsesWebJsonPagesAndNeverLoadsManifest() {
        val requests = CopyOnWriteArrayList<String>()
        var upload: String? = null
        withServer({ exchange ->
            requests += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                "/reader/api/v1/sync" -> Reply("""{"progress":[{"bookId":"pdf-a","locator":"{\"chapter\":\"document\",\"spine\":0,\"block\":3,\"ratio\":0,\"percent\":0}","updatedAt":12345}]}""")
                "/reader/api/v1/sync/progress/pdf-a" -> {
                    upload = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    Reply("""{"progress":$upload}""")
                }
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            assertEquals("pdf", book.variableMap["readerV1Format"])
            assertTrue(book.variableMap["readerV1UnsupportedReason"].isNullOrEmpty())
            val raw = api.getProgressRecords(listOf(book.bookUrl)).getValue(book.bookUrl)
            val restored = api.resolveProgress(book, raw)
            assertEquals(2, restored.durChapterIndex)
            assertEquals("第3页", restored.durChapterTitle)
            assertEquals(12345L, restored.durChapterTime)

            restored.durChapterIndex = 9
            restored.totalChapterNum = 10
            restored.durChapterTime = 77777
            assertTrue(api.saveBookProgress(restored))
            val sent = JsonParser.parseString(upload).asJsonObject
            val locator = JsonParser.parseString(sent.get("locator").asString).asJsonObject
            assertEquals("document", locator.get("chapter").asString)
            assertEquals(10, locator.get("block").asInt)
            assertEquals(0, locator.get("spine").asInt)
            assertEquals(1.0, locator.get("percent").asDouble, 0.0)
            assertEquals(1.0, sent.get("percentage").asDouble, 0.0)
            assertEquals("第10页", sent.get("chapterTitle").asString)
            assertFalse(requests.any { it.contains("manifest") || it.contains("assets") })
        } }
    }

    @Test
    fun browserDocumentLevelPdfProgressKeepsKnownLocalPage() {
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                "/reader/api/v1/sync" -> Reply("""{"progress":[{"bookId":"pdf-a","locator":"r1:0.0000:pdf","updatedAt":54321}]}""")
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single().apply {
                durChapterIndex = 11
                durChapterTitle = "第12页"
            }
            val raw = api.getProgressRecords(listOf(book.bookUrl)).getValue(book.bookUrl)
            val restored = api.resolveProgress(book, raw)
            assertEquals(11, restored.durChapterIndex)
            assertEquals("第12页", restored.durChapterTitle)
            assertEquals(54321L, restored.durChapterTime)
        } }
    }

    @Test
    fun pdfDownloadStreamsValidatedDocumentAndDeletesInvalidResponses() {
        var mode = "pdf"
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                "/reader/api/v1/books/pdf-a/assets" -> {
                    assertEquals("ref=document", exchange.requestURI.rawQuery)
                    assertEquals("Bearer valid-token", exchange.requestHeaders.getFirst("Authorization"))
                    when (mode) {
                        "pdf" -> Reply("%PDF-1.7\nfixture", "application/pdf")
                        "html" -> Reply("<html>login</html>", "text/html")
                        "short" -> Reply("%PDF-short", "application/pdf", declaredLength = 100)
                        "partial" -> Reply("%PDF-part", "application/pdf", status = 206)
                        else -> Reply("%PDF-range", "application/pdf", contentRange = "bytes 0-9/100")
                    }
                }
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val target = File.createTempFile("reader-pdf-", ".part")
            try {
                api.downloadBookDocument(book.bookUrl, target)
                assertEquals("%PDF-1.7\nfixture", target.readText())
                mode = "html"
                assertThrows(Exception::class.java) { runBlocking { api.downloadBookDocument(book.bookUrl, target) } }
                assertFalse(target.exists())
                mode = "short"
                assertThrows(Exception::class.java) { runBlocking { api.downloadBookDocument(book.bookUrl, target) } }
                assertFalse(target.exists())
                mode = "partial"
                assertThrows(Exception::class.java) { runBlocking { api.downloadBookDocument(book.bookUrl, target) } }
                assertFalse(target.exists())
                mode = "range"
                assertThrows(Exception::class.java) { runBlocking { api.downloadBookDocument(book.bookUrl, target) } }
                assertFalse(target.exists())
            } finally {
                target.delete()
            }
        } }
    }

    @Test
    fun pdfDownloadRefreshesRejectedBearerOnce() {
        val bearers = CopyOnWriteArrayList<String>()
        var downloads = 0
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/auth/refresh" -> Reply(
                    """{"accessToken":"token-two","accessTokenExpiresAt":${System.currentTimeMillis() + 3600000},"refreshToken":"refresh-token-two"}"""
                )
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                "/reader/api/v1/books/pdf-a/assets" -> {
                    bearers += exchange.requestHeaders.getFirst("Authorization").orEmpty()
                    downloads++
                    if (downloads == 1) Reply("{}", status = 401)
                    else Reply("%PDF-1.7\nrefreshed", "application/pdf")
                }
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val target = File.createTempFile("reader-pdf-refresh-", ".part")
            try {
                api.downloadBookDocument(book.bookUrl, target)
                assertEquals(listOf("Bearer valid-token", "Bearer token-two"), bearers.toList())
                assertTrue(target.readText().startsWith("%PDF-"))
            } finally {
                target.delete()
            }
        } }
    }

    @Test
    fun pdfDownloadRejectsCrossOriginRedirectWithoutLeakingBearer() {
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                "/reader/api/v1/books/pdf-a/assets" -> Reply("redirect", "text/plain", 302, "https://example.invalid/manual.pdf")
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val target = File.createTempFile("reader-pdf-", ".part")
            val failure = assertThrows(Exception::class.java) {
                runBlocking { api.downloadBookDocument(book.bookUrl, target) }
            }
            assertTrue(failure.message.orEmpty().contains("当前服务器"))
            assertFalse(target.exists())
        } }
    }

    @Test
    fun pdfDownloadRejectsRedirectWithAmbiguousDocumentRef() {
        var assetRequests = 0
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                "/reader/api/v1/books/pdf-a/assets" -> {
                    assetRequests++
                    Reply("redirect", "text/plain", 302,
                        "/reader/api/v1/books/pdf-a/assets?ref=document&ref=other")
                }
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val target = File.createTempFile("reader-pdf-ref-", ".part")
            val failure = assertThrows(Exception::class.java) {
                runBlocking { api.downloadBookDocument(book.bookUrl, target) }
            }
            assertTrue(failure.message.orEmpty().contains("其他资源"))
            assertEquals(1, assetRequests)
            assertFalse(target.exists())
        } }
    }

    @Test
    fun pdfProgressRejectsOverflowAndKnownOutOfBoundsPage() {
        val valid = ReaderServerPdfLocator.format(4, 1.0)
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""")
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single().apply { totalChapterNum = 3 }
            val remote = ReaderServerProgress(book.bookUrl, valid, 123)
            assertThrows(Exception::class.java) { runBlocking { api.resolveProgress(book, remote) } }
            book.durChapterIndex = Int.MAX_VALUE
            assertThrows(Exception::class.java) { runBlocking { api.saveBookProgress(book) } }
        } }
    }

    @Test
    fun cancellingPdfDownloadCancelsCallAndDeletesPartialFile() = runBlocking {
        val jsonType = "application/json".toMediaType()
        val pdfType = "application/pdf".toMediaType()
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body: ResponseBody
            val type: String
            when (path) {
                "/reader/api/v1/auth/login" -> {
                    body = session().body.toResponseBody(jsonType)
                    type = "application/json"
                }
                "/reader/api/v1/books" -> {
                    body = """{"items":[{"id":"pdf-a","title":"手册","format":"pdf"}],"total":1}""".toResponseBody(jsonType)
                    type = "application/json"
                }
                "/reader/api/v1/books/pdf-a/assets" -> {
                    val source = object : Source {
                        private var headerSent = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (!headerSent) {
                                headerSent = true
                                sink.writeUtf8("%PDF-")
                                return 5
                            }
                            while (!chain.call().isCanceled()) Thread.sleep(5)
                            throw java.io.IOException("cancelled")
                        }
                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                    body = object : ResponseBody() {
                        override fun contentType() = pdfType
                        override fun contentLength() = 100L
                        override fun source() = source
                    }
                    type = "application/pdf"
                }
                else -> {
                    body = "{}".toResponseBody(jsonType)
                    type = "application/json"
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", type).body(body).build()
        }.build()
        try {
            val api = ReaderServerV1("http://127.0.0.1/reader", "owner", "password") { client }
            val book = api.getBookshelf().single()
            val target = File.createTempFile("reader-pdf-cancel-", ".part")
            val job = async { api.downloadBookDocument(book.bookUrl, target) }
            delay(100)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(target.exists())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun resourceRedirectCannotMoveBearerToAnotherOrigin() {
        val seen = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            seen.add(exchange.requestURI.path)
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books/book-a/cover" -> Reply("redirect", "text/plain", 302, "https://example.invalid/cover")
                else -> Reply("{}", status = 404)
            }
        }) { base, http ->
            val api = ReaderServerV1(base, "owner", "password") { http }
            val failure = assertThrows(Exception::class.java) { runBlocking { api.getResourceImage("$base/api/v1/books/book-a/cover") } }
            assertTrue(failure.message.orEmpty().contains("当前服务器"))
            assertEquals(listOf("/reader/api/v1/auth/login", "/reader/api/v1/books/book-a/cover"), seen.toList())
        }
    }

    @Test
    fun lastWriterWinsRejectionIsNotReportedAsSuccessfulUpload() {
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"txt-a","title":"小说","format":"txt"}],"total":1}""")
                "/reader/api/v1/books/txt-a/manifest" -> Reply("""{"book":{"format":"txt"},"content":{"total":1,"items":[{"href":"chapter:0","format":"html","title":"第一章","kind":"chapter","mediaType":"text/plain"}]}}""")
                "/reader/api/v1/sync/progress/txt-a" -> Reply("""{"progress":{"locator":"r1:0.5000:chapter:0","updatedAt":60000}}""")
                else -> Reply("{}", status = 404)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single().apply { durChapterTime = 50000 }
            val failure = assertThrows(Exception::class.java) { runBlocking { api.saveBookProgress(book) } }
            assertTrue(failure.message.orEmpty().contains("服务器有更新进度"))
        } }
    }
    @Test
    fun brokenPublicationDoesNotBlockMetadataOrOtherProgressRecords() {
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"broken","title":"损坏书","format":"epub"},{"id":"healthy","title":"健康书","format":"txt"}],"total":2}""")
                "/reader/api/v1/sync" -> Reply("""{"progress":[{"bookId":"broken","locator":"r1:0.5000:xhtml:missing","updatedAt":12345}]}""")
                else -> Reply("""{"error":{"message":"book file missing"}}""", status = 404)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val books = api.getBookshelf()
            assertEquals(2, books.size)
            val records = api.getProgressRecords(books.map { it.bookUrl })
            assertEquals(1, records.size)
            val failure = assertThrows(Exception::class.java) {
                runBlocking { api.resolveProgress(books.first(), records.getValue(books.first().bookUrl)) }
            }
            assertTrue(failure.message.orEmpty().contains("404"))
            assertEquals(0L, books.last().durChapterTime)
        } }
    }

    @Test
    fun shelfMetadataHasNoProgressManifestOrAssetRequestBudget() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"txt-a","title":"小说","format":"txt"}],"total":1}""")
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val book = ReaderServerV1(base, "owner", "password") { http }.getBookshelf().single()
            assertEquals(0L, book.durChapterTime)
            assertEquals(listOf("/reader/api/v1/auth/login", "/reader/api/v1/books"), requests.toList())
        } }
    }

    @Test
    fun olderSingleProgressDoesNotResolveManifestOrContent() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"txt-a","title":"小说","format":"txt"}],"total":1}""")
                "/reader/api/v1/sync/progress/txt-a" -> Reply("""{"progress":{"bookId":"txt-a","locator":"r1:0.5000:chapter:0","updatedAt":100}}""")
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single().apply { durChapterTime = 200 }
            requests.clear()
            val server = api.getBookProgress(book)
            assertNotNull(server)
            assertEquals(100L, server!!.durChapterTime)
            assertEquals(listOf("/reader/api/v1/sync/progress/txt-a"), requests.toList())
        } }
    }

    @Test
    fun syncLimitFallsBackToRequestedSingleBookProgress() {
        val requests = CopyOnWriteArrayList<String>()
        val capped = (0 until 5000).joinToString(",") {
            """{"bookId":"other-$it","locator":"r1:0.0000:chapter:0","updatedAt":1}"""
        }
        withServer({ exchange ->
            requests += exchange.requestURI.toString()
            when (exchange.requestURI.path) {
                "/reader/api/v1/auth/login" -> session()
                "/reader/api/v1/books" -> Reply("""{"items":[{"id":"wanted","title":"小说","format":"txt"}],"total":1}""")
                "/reader/api/v1/sync" -> if (exchange.requestURI.query.orEmpty().contains("since=0")) {
                    Reply("""{"progress":[$capped]}""")
                } else Reply("""{"progress":[]}""")
                "/reader/api/v1/sync/progress/wanted" -> Reply("""{"progress":{"bookId":"wanted","locator":"r1:0.5000:chapter:0","updatedAt":2}}""")
                else -> Reply("unexpected", status = 500)
            }
        }) { base, http -> runBlocking {
            val api = ReaderServerV1(base, "owner", "password") { http }
            val book = api.getBookshelf().single()
            val records = api.getProgressRecords(listOf(book.bookUrl))
            assertEquals(2L, records.getValue(book.bookUrl).updatedAt)
            assertTrue(requests.any { it.endsWith("/sync/progress/wanted") })
            assertFalse(requests.any { it.contains("/manifest") || it.contains("/assets") })
        } }
    }

}
