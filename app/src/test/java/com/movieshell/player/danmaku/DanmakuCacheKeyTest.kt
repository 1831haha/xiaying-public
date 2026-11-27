package com.movieshell.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** v1.4.34 D-P2-10：弹幕缓存 key——前 30 字相同的长标题不再撞 key。 */
class DanmakuCacheKeyTest {

    @Test fun sameTitleSameKey() {
        assertEquals(
            DanmakuSource.cacheKeyFor("斗罗大陆", 1),
            DanmakuSource.cacheKeyFor("斗罗大陆", 1),
        )
    }

    @Test fun differentEpisodeDifferentKey() {
        assertNotEquals(
            DanmakuSource.cacheKeyFor("斗罗大陆", 1),
            DanmakuSource.cacheKeyFor("斗罗大陆", 2),
        )
    }

    @Test fun longTitlesSharingFirst30CharsGetDifferentKeys() {
        val prefix = "这是一个非常非常长的剧名用来凑够三十个字的前缀部分再加几个字凑数"
        val a = prefix + "第一季副标题甲"
        val b = prefix + "第二季副标题乙"
        assertEquals(a.take(30), b.take(30))
        assertNotEquals(DanmakuSource.cacheKeyFor(a, 1), DanmakuSource.cacheKeyFor(b, 1))
    }
}
