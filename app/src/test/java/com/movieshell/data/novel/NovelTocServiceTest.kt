package com.movieshell.data.novel

import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.model.ParagraphComment
import com.movieshell.domain.source.NovelEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** v1.4.27 解缠⑤：目录单服务的抓取+缓存（NovelStore/Context 路径走真机回归）。 */
class NovelTocServiceTest {

    private class FakeEngine(var result: () -> List<ChapterMeta>) : NovelEngine {
        var calls = 0
        override suspend fun search(keyword: String, allowGate: Boolean, page: Int) = emptyList<NovelSearchResult>()
        override suspend fun chapters(book: NovelSearchResult): List<ChapterMeta> { calls++; return result() }
        override suspend fun content(chapter: ChapterMeta, allowGate: Boolean): ContentResult = throw UnsupportedOperationException()
        override suspend fun explore(categoryUrl: String, page: Int, allowGate: Boolean) = emptyList<NovelSearchResult>()
        override suspend fun bookDetail(book: NovelSearchResult): BookDetailInfo = throw UnsupportedOperationException()
        override suspend fun detailCover(book: NovelSearchResult): String? = null
        override suspend fun paragraphComments(novelId: String, chapter: ChapterMeta, allowGate: Boolean) = emptyList<ParagraphComment>()
    }

    private var now = 1_000_000L
    private val book = NovelSearchResult(sourceKey = "s1", remoteId = "https://a/b/1", title = "书")
    private val toc = listOf(ChapterMeta(0, "第一章", "u1"), ChapterMeta(1, "第二章", "u2"))

    @Before fun setUp() { NovelTocService.clearAll(); NovelTocService.clock = { now } }
    @After fun tearDown() { NovelTocService.clearAll(); NovelTocService.clock = { System.currentTimeMillis() } }

    @Test fun fetchStoresNonEmptyInCache() = runBlocking {
        val e = FakeEngine { toc }
        assertEquals(toc, NovelTocService.fetchChapters(e, "s1", book))
        assertSame(toc, NovelTocService.cachedChapters("s1", book.remoteId))
        assertEquals(1, e.calls)
    }

    @Test fun emptyTocIsNotCached() = runBlocking {
        NovelTocService.fetchChapters(FakeEngine { emptyList() }, "s1", book)
        assertNull(NovelTocService.cachedChapters("s1", book.remoteId))
    }

    @Test fun exceptionPropagatesAndNothingCached() = runBlocking {
        try {
            NovelTocService.fetchChapters(FakeEngine { throw IllegalStateException("boom") }, "s1", book)
            fail("should throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertNull(NovelTocService.cachedChapters("s1", book.remoteId))
    }

    @Test fun cacheExpiresAfterTtl() = runBlocking {
        NovelTocService.fetchChapters(FakeEngine { toc }, "s1", book)
        now += NovelTocService.CACHE_TTL_MS + 1
        assertNull(NovelTocService.cachedChapters("s1", book.remoteId))
    }

    @Test fun cacheKeyedBySourceAndRemoteId() = runBlocking {
        NovelTocService.fetchChapters(FakeEngine { toc }, "s1", book)
        assertNull(NovelTocService.cachedChapters("s2", book.remoteId))
        assertNull(NovelTocService.cachedChapters("s1", "https://a/b/2"))
        NovelTocService.invalidate("s1", book.remoteId)
        assertNull(NovelTocService.cachedChapters("s1", book.remoteId))
    }

    @Test fun blankRemoteIdNeverCached() = runBlocking {
        NovelTocService.fetchChapters(FakeEngine { toc }, "s1", book.copy(remoteId = ""))
        assertNull(NovelTocService.cachedChapters("s1", ""))
    }

    @Test fun capacityEvictsOldest() = runBlocking {
        val e = FakeEngine { toc }
        repeat(NovelTocService.MAX_ENTRIES + 1) { i ->
            now += 1
            NovelTocService.fetchChapters(e, "s1", book.copy(remoteId = "r$i"))
        }
        assertNull(NovelTocService.cachedChapters("s1", "r0"))
        assertEquals(toc, NovelTocService.cachedChapters("s1", "r${NovelTocService.MAX_ENTRIES}"))
        assertEquals(toc, NovelTocService.cachedChapters("s1", "r1"))
    }
}
