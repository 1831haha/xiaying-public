package com.movieshell.data.novel

import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** v1.4.27 Step4 审稿小修：A1 双启、A2 Partial、A3 失效。 */
class Step4FixTest {

    @Before fun setUp() { NovelTocService.clearAll(); NovelExploreLoader.clearAll() }
    @After fun tearDown() { NovelTocService.clearAll(); NovelExploreLoader.clearAll() }

    // ───── A1 ─────

    @Test fun a1_concurrentStartLaunchesOnlyOnce() {
        val id = "a1-" + System.nanoTime()
        val runs = AtomicInteger()
        val started = AtomicInteger()
        val release = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        val done = CountDownLatch(32)
        repeat(32) {
            pool.execute {
                gate.await()
                if (NovelBookCache.launchOnce(id) { runs.incrementAndGet(); release.await(5, TimeUnit.SECONDS) }) started.incrementAndGet()
                done.countDown()
            }
        }
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("32 次几乎同时的 start 只应真正启动 1 次", 1, started.get())
        assertTrue(NovelBookCache.isRunning(id))
        release.countDown()
        // 任务结束后自行移出 jobs，下一次 start 能再次启动
        val deadline = System.currentTimeMillis() + 5_000
        while (NovelBookCache.isRunning(id) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, runs.get())
        val again = CountDownLatch(1)
        assertTrue(NovelBookCache.launchOnce(id) { again.countDown() })
        assertTrue(again.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test fun a1_finishedOldJobDoesNotRemoveNewOne() {
        val id = "a1b-" + System.nanoTime()
        val first = CountDownLatch(1)
        assertTrue(NovelBookCache.launchOnce(id) { first.countDown() })
        assertTrue(first.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (NovelBookCache.isRunning(id) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val hold = CountDownLatch(1)
        assertTrue(NovelBookCache.launchOnce(id) { hold.await(5, TimeUnit.SECONDS) })
        assertTrue("新任务登记后应可查到在跑", NovelBookCache.isRunning(id))
        NovelBookCache.cancel(id)
        hold.countDown()
    }

    // ───── A2 ─────

    private fun ch(i: Int, no: Int) = Chapter("b", i, "第${no}章 标题", "", "u$i")

    @Test fun a2_readyWithGapIsPartial() {
        val chapters = (1..20).map { ch(it - 1, it) } + (100..110).mapIndexed { j, no -> ch(20 + j, no) }
        val st = NovelTocService.shelfStateOf("READY", chapters)
        assertTrue("有缺段的终态目录应为 Partial，实际 $st", st is NovelTocService.TocState.Partial)
        val gap = (st as NovelTocService.TocState.Partial).gap
        assertEquals(20, gap.fromNo); assertEquals(100, gap.toNo)
        assertEquals(chapters.size, st.chapters.size)
    }

    @Test fun a2_otherStates() {
        val full = (1..30).map { ch(it - 1, it) }
        assertTrue(NovelTocService.shelfStateOf("READY", full) is NovelTocService.TocState.Ready)
        assertTrue(NovelTocService.shelfStateOf("READY", emptyList()) is NovelTocService.TocState.Failed)
        assertTrue(NovelTocService.shelfStateOf("LOADING", full) is NovelTocService.TocState.Loading)
        assertTrue(NovelTocService.shelfStateOf("FAILED", full) is NovelTocService.TocState.Failed)
        assertTrue(NovelTocService.shelfStateOf(null, full) is NovelTocService.TocState.Failed)
    }

    // ───── A3 ─────

    @Test fun a3_invalidateCachesDropsTocAndExploreOfThatSourceOnly() = runBlocking<Unit> {
        val toc = listOf(ChapterMeta(0, "第一章", "u1"))
        NovelTocService.put("s.com", "https://s.com/b/1", toc)
        NovelTocService.put("t.com", "https://t.com/b/1", toc)
        val books = listOf(NovelSearchResult(remoteId = "1", title = "甲"))
        var calls = 0
        suspend fun load(src: String) = NovelExploreLoader.loadWith(NovelExploreLoader.keyOf(src, "/c", 1), false) { calls++; books }
        load("s.com"); load("t.com")
        assertEquals(2, calls)

        BookSourceManager.invalidateCaches("s.com")

        assertNull(NovelTocService.cachedChapters("s.com", "https://s.com/b/1"))
        assertNotNull(NovelTocService.cachedChapters("t.com", "https://t.com/b/1"))
        load("s.com"); load("t.com")
        assertEquals("s.com 发现缓存应失效重抓、t.com 仍命中", 3, calls)
    }

    @Test fun a3_tocInvalidateSourceIsPrefixExact() {
        val toc = listOf(ChapterMeta(0, "第一章", "u1"))
        NovelTocService.put("s.com", "r1", toc)
        NovelTocService.put("s.com.cn", "r1", toc)
        NovelTocService.invalidateSource("s.com")
        assertNull(NovelTocService.cachedChapters("s.com", "r1"))
        assertNotNull("前缀带分隔符，s.com 不能误清 s.com.cn", NovelTocService.cachedChapters("s.com.cn", "r1"))
    }
}
