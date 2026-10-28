package io.legado.app.help.book

import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ReaderServerCacheTest {

    @Test
    fun completeCacheReturnsWithoutUsingNetwork() = runBlocking {
        val cache = FakeCache(cached = "cached", complete = true)

        assertEquals("cached", ReaderServerCache.cacheChapter(cache))
        assertEquals(0, cache.fetchCount)
        assertEquals(0, cache.saveImageCount)
        assertEquals(0, cache.completeEvents)
    }

    @Test
    fun blankExistingCacheIsFetchedAgain() = runBlocking {
        val cache = FakeCache(cached = "  ", complete = true, fetched = "fresh text")

        assertEquals("fresh text", ReaderServerCache.cacheChapter(cache))
        assertEquals(1, cache.fetchCount)
        assertEquals(1, cache.saveContentCount)
        assertEquals(1, cache.completeEvents)
    }

    @Test
    fun imageFailureDoesNotCompleteAndRetryReusesSavedText() = runBlocking {
        val cache = FakeCache(cached = "saved text", complete = false)
        cache.imageFailure = IOException("image unavailable")

        try {
            ReaderServerCache.cacheChapter(cache)
            fail("expected image failure")
        } catch (error: IOException) {
            assertEquals("image unavailable", error.message)
        }
        assertEquals(0, cache.fetchCount)
        assertEquals(0, cache.saveContentCount)
        assertEquals(1, cache.saveImageCount)
        assertEquals(0, cache.completeEvents)

        cache.imageFailure = null
        assertEquals("saved text", ReaderServerCache.cacheChapter(cache))
        assertEquals(0, cache.fetchCount)
        assertEquals(0, cache.saveContentCount)
        assertEquals(2, cache.saveImageCount)
        assertEquals(1, cache.completeEvents)
    }

    @Test
    fun blankServerContentNeverPersistsOrCompletes() = runBlocking {
        val cache = FakeCache(cached = null, complete = false, fetched = "  ")

        try {
            ReaderServerCache.cacheChapter(cache)
            fail("expected blank content failure")
        } catch (error: NoStackTraceException) {
            assertEquals("章节内容为空", error.message)
        }
        assertEquals(0, cache.saveContentCount)
        assertEquals(0, cache.saveImageCount)
        assertEquals(0, cache.completeEvents)
    }

    @Test
    fun cancellationPropagatesWithoutCompletionEvent() = runBlocking {
        val cache = FakeCache(cached = null, complete = false, fetched = "server text")
        cache.imageFailure = CancellationException("cancelled")

        try {
            ReaderServerCache.cacheChapter(cache)
            fail("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
        assertEquals(1, cache.saveContentCount)
        assertEquals(1, cache.saveImageCount)
        assertEquals(0, cache.completeEvents)
    }

    @Test
    fun serverFailureNeverPersistsOrCompletes() = runBlocking {
        val cache = FakeCache(cached = null, complete = false)
        cache.fetchFailure = IOException("server unavailable")

        try {
            ReaderServerCache.cacheChapter(cache)
            fail("expected server failure")
        } catch (error: IOException) {
            assertEquals("server unavailable", error.message)
        }
        assertEquals(0, cache.saveContentCount)
        assertEquals(0, cache.saveImageCount)
        assertEquals(0, cache.completeEvents)
    }

    @Test
    fun successfulServerContentSavesEverythingBeforeCompleting() = runBlocking {
        val cache = FakeCache(cached = null, complete = false, fetched = "server text")

        assertEquals("server text", ReaderServerCache.cacheChapter(cache))
        assertEquals(1, cache.fetchCount)
        assertEquals(1, cache.saveContentCount)
        assertEquals(1, cache.saveImageCount)
        assertEquals(1, cache.completeEvents)
    }

    private class FakeCache(
        private var cached: String?,
        private val complete: Boolean,
        private val fetched: String = "unused"
    ) : ReaderServerCache.ChapterCache {
        var fetchCount = 0
        var saveContentCount = 0
        var saveImageCount = 0
        var completeEvents = 0
        var fetchFailure: Throwable? = null
        var imageFailure: Throwable? = null

        override suspend fun cachedContent(): String? = cached
        override fun isComplete(): Boolean = complete
        override suspend fun fetchContent(): String {
            fetchCount++
            fetchFailure?.let { throw it }
            return fetched
        }
        override suspend fun saveContent(content: String) {
            saveContentCount++
            cached = content
        }

        override suspend fun saveImages(content: String) {
            saveImageCount++
            imageFailure?.let { throw it }
        }

        override fun notifyComplete() {
            completeEvents++
        }
    }
}
