package com.movieshell.data.novel

import com.movieshell.domain.model.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v1.4.27 P0.1：整本缓存主循环。 */
class NovelBookCacheTest {

    private fun chapters(n: Int, cached: Set<Int> = emptySet(), noUrl: Set<Int> = emptySet()) =
        (0 until n).map { i ->
            Chapter("b", i, "第${i}章", if (i in cached) "已有" else "", if (i in noUrl) "" else "u$i")
        }

    @Test fun downloadsOnlyPendingAndFlushesInBatches() = runBlocking {
        val flushed = mutableListOf<Map<Int, Pair<String, String>>>()
        val fetched = mutableListOf<Int>()
        val p = NovelBookCache.download(
            chapters(45, cached = setOf(0, 1), noUrl = setOf(2)),
            onProgress = {},
            fetch = { c -> fetched += c.index; "正文${c.index}" },
            flush = { flushed += it; it.size },
        )
        assertEquals((3 until 45).toList(), fetched)
        assertEquals(listOf(20, 20, 2), flushed.map { it.size })
        assertEquals("u3" to "正文3", flushed[0][3])
        assertEquals(45, p.done); assertEquals(45, p.total); assertFalse(p.running); assertNull(p.stopReason)
    }

    @Test fun stopsAfterConsecutiveFailuresAndKeepsBatch() = runBlocking {
        val flushed = mutableListOf<Int>()
        val p = NovelBookCache.download(
            chapters(30),
            onProgress = {},
            fetch = { c -> if (c.index < 3) "ok" else null },
            flush = { flushed += it.keys; it.size },
        )
        assertEquals(listOf(0, 1, 2), flushed)
        assertEquals(3, p.done)
        assertEquals(NovelBookCache.MAX_CONSECUTIVE_FAIL, p.failed)
        assertFalse(p.running)
        assertTrue(p.stopReason!!.contains("已暂停"))
    }

    @Test fun scatteredFailuresDoNotStop() = runBlocking {
        val p = NovelBookCache.download(
            chapters(10), onProgress = {},
            fetch = { c -> if (c.index % 2 == 0) null else "ok" },
            flush = { it.size },
        )
        assertEquals(5, p.failed); assertEquals(5, p.done)
        assertTrue(p.stopReason!!.contains("5 章失败"))
    }

    @Test fun cancellationFlushesWhatWasFetched() = runBlocking {
        val flushed = mutableListOf<Int>()
        try {
            NovelBookCache.download(
                chapters(10), onProgress = {},
                fetch = { c -> if (c.index == 4) throw CancellationException("stop") else "ok" },
                flush = { flushed += it.keys; it.size },
            )
            fail("should throw")
        } catch (e: CancellationException) {
            assertEquals(listOf(0, 1, 2, 3), flushed)
        }
    }

    @Test fun progressReported() = runBlocking {
        val seen = mutableListOf<Int>()
        NovelBookCache.download(chapters(3), onProgress = { seen += it.done }, fetch = { "x" }, flush = { it.size })
        assertEquals(listOf(0, 1, 2, 3), seen.distinct())
    }
}
