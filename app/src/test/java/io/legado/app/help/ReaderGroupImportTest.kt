package io.legado.app.help

import android.app.Application
import androidx.room.Room
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.Server
import io.legado.app.model.remote.ReaderServerApi
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ReaderGroupImportTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.injectAsAppCtx()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun remote(id: String = "book", series: String = "系列A", tags: List<String> = listOf("标签B")) = Book(
        bookUrl = "reader-v1://test-scope/$id", name = id, origin = BookType.readerServerLocalTag,
        durChapterTime = 0,
        variable = GSON.toJson(mapOf(
            "readerV1Format" to "epub", "readerV1Series" to series,
            "readerV1Tags" to GSON.toJson(tags)
        ))
    )

    private class Source(var series: String = "系列A", var tags: List<String> = listOf("标签B"),
        var format: String = "epub", var progressFails: Boolean = false) {
        val requests = mutableListOf<String>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url.encodedPath}"
            check(request.method == "GET") { "Classification import must never write to Reader" }
            val body = when (request.url.encodedPath) {
                "/reader/api/v1/books" -> GSON.toJson(mapOf(
                    "items" to listOf(mapOf("id" to "book", "title" to "书名", "format" to format,
                        "series" to series, "tags" to tags)), "total" to 1
                ))
                "/reader/api/v1/sync" -> """{"progress":[]}"""
                else -> error("Unexpected content/manifest request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("test")
                .code(if (request.url.encodedPath.endsWith("/sync") && progressFails) 503 else 200)
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = ReaderServerApi("http://reader.invalid/reader", "owner", "password", { client }, {}).apply {
            setAccessToken("test-token", System.currentTimeMillis() + 3600000)
        }
        val config = Server.ReaderServerConfig("http://reader.invalid/reader", "owner", "password", syncBookProgress = false)
    }

    private fun sync(source: Source) = runBlocking {
        ReaderServerSync.syncServerBookshelf(source.api, source.config, database)
    }

    private fun names() = database.bookGroupDao.all.map { it.groupName }.toSet()

    @Test
    fun shelfImportCreatesDistinctSeriesAndTagGroupsWithoutExtraNetworkCalls() {
        val source = Source(series = "  分类A  ", tags = listOf("分类A", "含,逗号", "分类A", "  ", " 含,逗号 "))
        val first = sync(source)
        assertEquals(1, first.downloaded)
        assertEquals(3, first.groupsCreated)
        assertEquals(1, first.groupedBooks)
        assertTrue(first.failures.isEmpty())
        assertEquals(setOf("Reader · 系列 · 分类A", "Reader · 标签 · 分类A", "Reader · 标签 · 含,逗号"), names())
        val book = database.bookDao.all.single()
        for (group in database.bookGroupDao.all) {
            assertTrue(group.groupId > 0)
            assertEquals(group.groupId, book.group and group.groupId)
            assertFalse(group.enableRefresh)
        }
        assertEquals(listOf("GET /reader/api/v1/books"), source.requests)
        val before = database.bookGroupDao.all
        val second = sync(source)
        assertEquals(0, second.total)
        assertEquals(before, database.bookGroupDao.all)
        assertEquals(book.group, database.bookDao.all.single().group)
    }

    @Test
    fun existingManualGroupsProgressSettingsAndVariablesRemainIntact() {
        val source = Source()
        val remote = runBlocking { source.api.getBookshelf().single() }
        val manual = BookGroup(1, "系列A", cover = "my-cover", order = 7, show = false, bookSort = 3)
        database.bookGroupDao.insert(manual)
        val local = remote.copy(name = "我的书名", group = 1, durChapterIndex = 12, durChapterPos = 345,
            durChapterTime = 777, charset = "UTF-8", customTag = "本地标签",
            variable = GSON.toJson(mapOf("custom" to "keep", "readerV1Format" to "epub")))
        database.bookDao.insert(local)
        val result = sync(source)
        val saved = database.bookDao.getBook(local.bookUrl)!!
        assertEquals(2, result.groupsCreated)
        assertEquals(1, saved.group and 1)
        assertEquals(manual, database.bookGroupDao.getByID(1))
        assertEquals("我的书名", saved.name)
        assertEquals("本地标签", saved.customTag)
        assertEquals("keep", saved.variableMap["custom"])
        assertEquals(12, saved.durChapterIndex)
        assertEquals(345, saved.durChapterPos)
        assertEquals(777L, saved.durChapterTime)
        assertEquals("UTF-8", saved.charset)
    }

    @Test
    fun matchingImportGroupsAreReusedWithoutOverwritingLocalGroupOptions() {
        val book = remote()
        database.bookDao.insert(book)
        val customized = BookGroup(1, "Reader · 系列 · 系列A", "custom-cover", 10, true, false, 2)
        database.bookGroupDao.insert(customized)
        val importer = ReaderGroupImporter(database)
        val first = importer.importBook(book)
        assertEquals(1, first.created)
        assertTrue(first.grouped)
        assertEquals(customized, database.bookGroupDao.getByID(1))
        val other = remote("other")
        database.bookDao.insert(other)
        assertEquals(0, importer.importBook(other).created)
        assertEquals(database.bookDao.getBook(book.bookUrl)!!.group, database.bookDao.getBook(other.bookUrl)!!.group)
    }

    @Test
    fun remoteChangesAddNewMembershipsAndDoNotDeleteExistingLocalClassifications() {
        val source = Source()
        sync(source)
        val before = database.bookDao.all.single().group
        source.series = "新系列"
        source.tags = emptyList()
        val result = sync(source)
        assertEquals(1, result.groupsCreated)
        assertEquals(1, result.groupedBooks)
        assertEquals(before, database.bookDao.all.single().group and before)
        assertEquals(setOf("Reader · 系列 · 系列A", "Reader · 标签 · 标签B", "Reader · 系列 · 新系列"), names())
        source.series = ""
        sync(source)
        assertEquals(3, names().size)
    }

    @Test
    fun missingOrNonReaderBooksDoNotCreateEmptyGroups() {
        val importer = ReaderGroupImporter(database)
        assertEquals(ReaderGroupImporter.Result(), importer.importBook(remote()))
        val local = remote().copy(bookUrl = "https://local.invalid/book")
        database.bookDao.insert(local)
        assertEquals(ReaderGroupImporter.Result(), importer.importBook(local))
        val blank = remote("blank", " ", listOf(" ", ""))
        database.bookDao.insert(blank)
        assertEquals(ReaderGroupImporter.Result(), importer.importBook(blank))
        assertTrue(names().isEmpty())
    }

    @Test
    fun allocationAvoidsSystemIdsAndDanglingBookMemberships() {
        database.bookGroupDao.insert(BookGroup(BookGroup.IdAll, "全部"), BookGroup(1, "手动"))
        val orphan = remote("orphan", "", emptyList()).copy(group = 2)
        val target = remote(tags = emptyList())
        database.bookDao.insert(orphan, target)
        val result = ReaderGroupImporter(database).importBook(target)
        assertEquals(1, result.created)
        val group = database.bookGroupDao.getByName("Reader · 系列 · 系列A")!!
        assertEquals(4L, group.groupId)
        assertEquals(2L, database.bookDao.getBook(orphan.bookUrl)!!.group)
        assertEquals(listOf(target.bookUrl), database.bookDao.getBooksByGroup(4).map { it.bookUrl })
    }

    @Test
    fun lastPositiveGroupBitIsUsableAndOverflowIsReportedWithoutNegativeIds() {
        database.bookGroupDao.insert(*(0..61).map { BookGroup(1L shl it, "本地$it") }.toTypedArray())
        val target = remote(tags = listOf("标签B", "标签C")).copy(group = 1)
        database.bookDao.insert(target)
        val result = ReaderGroupImporter(database).importBook(target)
        assertEquals(1, result.created)
        assertTrue(result.grouped)
        assertEquals(listOf("Reader · 标签 · 标签B", "Reader · 标签 · 标签C"), result.unavailable)
        val last = 1L shl 62
        assertEquals(listOf("Reader · 系列 · 系列A"), database.bookGroupDao.getGroupNames(last))
        assertEquals(last or 1, database.bookDao.getBook(target.bookUrl)!!.group)
        assertNull(database.bookGroupDao.getByID(Long.MIN_VALUE))
        assertEquals(63, database.bookGroupDao.all.size)
    }

    @Test
    fun fullCapacityStillLinksExistingGroupsAndDoesNotReportSuccessForSkippedGroups() {
        database.bookGroupDao.insert(*(0..62).map {
            BookGroup(1L shl it, if (it == 0) "Reader · 标签 · 已有" else "手动$it")
        }.toTypedArray())
        val source = Source(tags = listOf("已有", "放不下"))
        val result = sync(source)
        assertEquals(0, result.groupsCreated)
        assertEquals(1, result.groupedBooks)
        assertEquals(1L, database.bookDao.all.single().group)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().contains("分组容量不足"))
        assertTrue(result.failures.single().contains("2 个"))
        val report = ReaderServerSyncReport(listOf(result.toReportItem("书架 / 分组 / 阅读进度")))
        assertEquals(ReaderServerSyncReport.Outcome.PARTIAL_FAILURE, report.outcome)
        assertTrue(report.summary().contains("失败"))
        assertNull(database.bookGroupDao.getByID(Long.MIN_VALUE))
    }

    @Test
    fun manualGroupAllocationStopsAtTheSameVisibleCapacityAndRecoversAfterDeletion() {
        assertTrue(database.bookGroupDao.canAddGroup)
        assertEquals(1L, database.bookGroupDao.getUnusedId())
        database.bookGroupDao.insert(*(0..61).map { BookGroup(1L shl it, "手动$it") }.toTypedArray())
        assertTrue(database.bookGroupDao.canAddGroup)
        assertEquals(1L shl 62, database.bookGroupDao.getUnusedId())
        val last = BookGroup(1L shl 62, "最后一个")
        database.bookGroupDao.insert(last)
        assertFalse(database.bookGroupDao.canAddGroup)
        val error = assertThrows(IllegalStateException::class.java) { database.bookGroupDao.getUnusedId() }
        assertTrue(error.message!!.contains("上限"))
        database.bookGroupDao.delete(last)
        assertTrue(database.bookGroupDao.canAddGroup)
        assertEquals(last.groupId, database.bookGroupDao.getUnusedId())
    }

    @Test
    fun groupTransactionRollsBackOnMembershipWriteFailureAndReportsPartialFailure() {
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_groups BEFORE UPDATE OF `group` ON books
            BEGIN SELECT RAISE(ABORT, 'test group write failure'); END""")
        val result = sync(Source())
        assertEquals(1, result.downloaded)
        assertEquals(0, result.groupsCreated)
        assertEquals(0, result.groupedBooks)
        assertTrue(names().isEmpty())
        assertEquals(0L, database.bookDao.all.single().group)
        assertTrue(result.failures.single().contains("分组导入失败"))
        assertEquals(ReaderServerSyncReport.Outcome.PARTIAL_FAILURE,
            ReaderServerSyncReport(listOf(result.toReportItem("同步"))).outcome)
    }

    @Test
    fun progressOnlyAndDisabledSyncNeverImportOrAlterGroups() {
        val source = Source()
        val remote = runBlocking { source.api.getBookshelf().single() }
        database.bookGroupDao.insert(BookGroup(1, "手动"))
        database.bookDao.insert(remote.copy(group = 1))
        source.config.syncBookshelf = false
        source.config.syncBookProgress = true
        source.requests.clear()
        val result = sync(source)
        assertTrue(result.failures.isEmpty())
        assertEquals(0, result.groupsCreated)
        assertEquals(0, result.groupedBooks)
        assertEquals(setOf("手动"), names())
        assertEquals(1L, database.bookDao.all.single().group)
        assertEquals(listOf("GET /reader/api/v1/books", "GET /reader/api/v1/sync"), source.requests)
        source.config.syncBookProgress = false
        source.requests.clear()
        assertNotNull(sync(source).skippedReason)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun progressFailureDoesNotBlockShelfAndGroupImport() {
        val source = Source(progressFails = true)
        source.config.syncBookProgress = true
        val result = sync(source)
        assertEquals(1, result.downloaded)
        assertEquals(2, result.groupsCreated)
        assertEquals(1, result.groupedBooks)
        assertTrue(result.failures.single().contains("阅读进度失败"))
        assertEquals(ReaderServerSyncReport.Outcome.PARTIAL_FAILURE,
            ReaderServerSyncReport(listOf(result.toReportItem("同步"))).outcome)
    }

    @Test
    fun unsupportedBooksAreSkippedWithoutCreatingClassificationGroups() {
        val result = sync(Source(format = "unsupported"))
        assertNotNull(result.skippedReason)
        assertEquals(0, result.groupsCreated)
        assertTrue(database.bookDao.all.isEmpty())
        assertTrue(names().isEmpty())
    }
}
