package com.movieshell.data.novel

import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** v1.4.27 解缠⑥：发现加载器缓存口径。 */
class NovelExploreLoaderTest {

    private var now = 5_000_000L
    private var calls = 0
    private val books = listOf(NovelSearchResult(remoteId = "1", title = "甲"))
    private val k = NovelExploreLoader.keyOf("s.com", "/cat/1", 1)

    @Before fun setUp() { NovelExploreLoader.clearAll(); NovelExploreLoader.clock = { now } }
    @After fun tearDown() { NovelExploreLoader.clearAll(); NovelExploreLoader.clock = { System.currentTimeMillis() } }

    private suspend fun load(key: String = k, force: Boolean = false, r: () -> List<NovelSearchResult> = { books }) =
        NovelExploreLoader.loadWith(key, force) { calls++; r() }

    @Test fun secondLoadHitsCache() = runBlocking {
        assertEquals(books, load()); assertEquals(books, load())
        assertEquals(1, calls)
    }

    @Test fun forceBypassesCache() = runBlocking {
        load(); load(force = true)
        assertEquals(2, calls)
    }

    @Test fun emptyResultNotCached() = runBlocking {
        assertTrue(load { emptyList() }.isEmpty())
        assertEquals(books, load())
        assertEquals(2, calls)
    }

    @Test fun ttlExpires() = runBlocking {
        load(); now += NovelExploreLoader.CACHE_TTL_MS + 1; load()
        assertEquals(2, calls)
    }

    @Test fun pageAndCategoryAreSeparateKeys() = runBlocking {
        load(); load(NovelExploreLoader.keyOf("s.com", "/cat/1", 2)); load(NovelExploreLoader.keyOf("s.com", "/cat/2", 1))
        assertEquals(3, calls)
    }

    @Test fun exceptionBecomesEmptyAndKeepsNothing() = runBlocking {
        load()
        assertTrue(load(force = true) { throw RuntimeException("x") }.isEmpty())
        // 失败的强刷把旧缓存清掉（下一次重新抓），与“空结果不缓存”同口径
        load(); assertEquals(3, calls)
    }

    @Test fun cancellationRethrown() = runBlocking {
        try {
            load { throw CancellationException("gone") }
            fail("should rethrow")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test fun invalidateSourceDropsOnlyThatSource() = runBlocking {
        load(); load(NovelExploreLoader.keyOf("t.com", "/cat/1", 1))
        NovelExploreLoader.invalidateSource("s.com")
        load(); load(NovelExploreLoader.keyOf("t.com", "/cat/1", 1))
        assertEquals(3, calls)
    }

    @Test fun capacityCapped() = runBlocking {
        repeat(NovelExploreLoader.MAX_ENTRIES + 5) { i -> now += 1; load(NovelExploreLoader.keyOf("s.com", "/c", i)) }
        val before = calls
        load(NovelExploreLoader.keyOf("s.com", "/c", 0))
        assertEquals(before + 1, calls) // 最旧的被挤掉
    }
}
