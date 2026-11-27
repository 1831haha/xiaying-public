package com.movieshell.player

import com.movieshell.core.util.SafeFileName
import com.movieshell.player.danmaku.DanmakuSource
import com.movieshell.player.danmaku.DanmakuSource.Anime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.34 B-P1-10：弹幕首结果盲选 + 缓存文件名碰撞。
 * 相似度挑取（纯函数）与缓存 key（完整标题）两处回归。
 */
class DanmakuSearchMatchTest {

    @Test
    fun pickBest_exactTitleWinsOverFirstResult() {
        val animes = listOf(
            Anime(1, "庆余年2"),
            Anime(2, "庆余年"),
        )
        assertEquals(2L, DanmakuSource.pickBestAnime(animes, "庆余年"))
    }

    @Test
    fun pickBest_containsMatchAccepted() {
        // 搜“凡人修仙传”，回“凡人修仙传 第2季”互含即放行。
        val animes = listOf(Anime(7, "凡人修仙传 第2季"))
        assertEquals(7L, DanmakuSource.pickBestAnime(animes, "凡人修仙传"))
    }

    @Test
    fun pickBest_unrelatedFirstResultRejected() {
        // 首结果完全无关时不盲选，宁可无弹幕。
        val animes = listOf(
            Anime(1, "斗破苍穹"),
            Anime(2, "一人之下"),
        )
        assertNull(DanmakuSource.pickBestAnime(animes, "金玉良缘"))
    }

    @Test
    fun pickBest_emptyListReturnsNull() {
        assertNull(DanmakuSource.pickBestAnime(emptyList(), "庆余年"))
        assertNull(DanmakuSource.pickBestAnime(listOf(Anime(1, "庆余年")), ""))
    }

    @Test
    fun titleSimilarity_containsIsHighUnrelatedIsZero() {
        assertEquals(1.0, DanmakuSource.titleSimilarity("凡人修仙传", "凡人修仙传"), 0.0)
        assertEquals(0.9, DanmakuSource.titleSimilarity("凡人修仙传 第2季", "凡人修仙传"), 0.0)
        assertEquals(0.0, DanmakuSource.titleSimilarity("斗破苍穹", "金玉良缘"), 0.0)
        assertTrue(DanmakuSource.titleSimilarity("斗罗大陆", "斗罗大陆2") > 0.5)
    }

    @Test
    fun cacheKey_sameFirstThirtyCharsStillDistinct() {
        // 前 30 字相同的两部长标题：原 take(30) 会撞同一缓存文件。
        val prefix = "这是一个非常非常长的电视剧名字一共三十个字正好到这里四字五字"
        val a = prefix + "第一季"
        val b = prefix + "第二季"
        assertEquals(30, prefix.length)
        assertNotEquals(DanmakuSource.cacheKeyFor(a, 1), DanmakuSource.cacheKeyFor(b, 1))
        // 落到文件名一级也不撞（SafeFileName 摘要按完整 key 算）。
        val fa = SafeFileName.encode(DanmakuSource.cacheKeyFor(a, 1), Regex("[^a-zA-Z0-9_-]"), 60, "_")
        val fb = SafeFileName.encode(DanmakuSource.cacheKeyFor(b, 1), Regex("[^a-zA-Z0-9_-]"), 60, "_")
        assertNotEquals(fa, fb)
    }
}
