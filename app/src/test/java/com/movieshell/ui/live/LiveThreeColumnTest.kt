package com.movieshell.ui.live

import com.movieshell.data.store.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.14 三列：源编号、滚动可见性判断、按台名去重（纯函数）。
 * v1.4.16：去重改全局归一化（normalizeChannelKey），另补对照表与短名显示。
 */
class LiveThreeColumnTest {

    @Test
    fun sourceLabel_numberingStartsAtOne() {
        assertEquals("源1", sourceLabel(0))
        assertEquals("源2", sourceLabel(1))
        assertEquals("源9", sourceLabel(8))
    }

    @Test
    fun shouldScrollToIndex_visibleStaysPut() {
        // 在可见范围内：不跳
        assertFalse(shouldScrollToIndex(0, 6, 3))
        assertFalse(shouldScrollToIndex(0, 6, 0))
        assertFalse(shouldScrollToIndex(0, 6, 6))
        // 在可见范围外：跳
        assertTrue(shouldScrollToIndex(0, 6, 7))
        assertTrue(shouldScrollToIndex(3, 9, 2))
        // 尚未布局：跳
        assertTrue(shouldScrollToIndex(-1, -1, 0))
        // 无目标：不跳
        assertFalse(shouldScrollToIndex(0, 6, -1))
    }

    @Test
    fun normalizeChannelKey_table() {
        // 同一台的不同写法合到一个 key
        assertEquals("CCTV1", normalizeChannelKey("CCTV-1HD"))
        assertEquals("CCTV1", normalizeChannelKey("CCTV1"))
        assertEquals("CCTV1", normalizeChannelKey("CCTV-1 高清"))
        assertEquals("CCTV1", normalizeChannelKey("CCTV1超高清"))
        assertEquals("CCTV1", normalizeChannelKey("CCTV01"))
        assertEquals("CCTV1", normalizeChannelKey("ＣＣＴＶ－１ＨＤ"))
        assertEquals("CCTV2", normalizeChannelKey("CCTV-2HD"))
        // 真不同的台不合：地域后缀、+、4K 一律保留
        assertEquals("CCTV4", normalizeChannelKey("CCTV4"))
        assertEquals("CCTV4欧洲", normalizeChannelKey("CCTV4欧洲"))
        assertEquals("CCTV4美洲", normalizeChannelKey("CCTV4美洲"))
        assertEquals("CCTV5", normalizeChannelKey("CCTV5"))
        assertEquals("CCTV5+", normalizeChannelKey("CCTV5+"))
        assertEquals("CCTV4K", normalizeChannelKey("CCTV-4K"))
        assertEquals("CCTV10", normalizeChannelKey("CCTV10"))
    }

    @Test
    fun distinctChannelsGlobally_mergesSpellingVariants() {
        val hd = LiveChannel(id = "a|u1", name = "CCTV-1HD", url = "u1", sourceKey = "a")
        val plain = LiveChannel(id = "b|u2", name = "CCTV1", url = "u2", sourceKey = "b")
        val eu = LiveChannel(id = "a|u3", name = "CCTV4欧洲", url = "u3", sourceKey = "a")
        val c4 = LiveChannel(id = "b|u4", name = "CCTV4", url = "u4", sourceKey = "b")
        val plus = LiveChannel(id = "a|u5", name = "CCTV5+", url = "u5", sourceKey = "a")
        val c5 = LiveChannel(id = "b|u6", name = "CCTV5", url = "u6", sourceKey = "b")
        val rows = listOf(hd, plain, eu, c4, plus, c5)
        // CCTV-1HD 与 CCTV1 合成一行取第一条；欧洲／CCTV4 各留一行；5+ 与 5 两行
        assertEquals(listOf(hd, eu, c4, plus, c5), distinctChannelsGlobally(rows, emptyMap()))
        // 记过 CCTV1 的源2：代表行换成源2
        assertEquals(
            listOf(plain, eu, c4, plus, c5),
            distinctChannelsGlobally(rows, mapOf("CCTV1" to "b|u2")),
        )
        // 记的 id 已失效：回落第一条
        assertEquals(
            listOf(hd, eu, c4, plus, c5),
            distinctChannelsGlobally(rows, mapOf("CCTV1" to "b|gone")),
        )
        // 顺序保持 key 首次出现顺序；每组代表行取组内第一条
        assertEquals(
            listOf(c5, plain),
            distinctChannelsGlobally(listOf(c5, plain, hd), emptyMap()),
        )
    }

    @Test
    fun displayNameForKey_shortNameWins() {
        val hd = LiveChannel(id = "a|u1", name = "CCTV-1HD", url = "u1", sourceKey = "a")
        val plain = LiveChannel(id = "b|u2", name = "CCTV1", url = "u2", sourceKey = "b")
        // 短名优先，与出现顺序无关
        assertEquals("CCTV1", displayNameForKey("CCTV1", listOf(hd, plain)))
        assertEquals("CCTV1", displayNameForKey("CCTV1", listOf(plain, hd)))
        // 只有长名变体时显示它本身
        assertEquals("CCTV-1HD", displayNameForKey("CCTV1", listOf(hd)))
        // 没候选时回 key 本身
        assertEquals("CCTV9", displayNameForKey("CCTV9", emptyList()))
    }

    @Test
    fun distinctChannelsGlobally_rememberedSourceWins() {
        val a1 = LiveChannel(id = "k|u1", name = "CCTV1", url = "u1", sourceKey = "k")
        val a2 = LiveChannel(id = "k|u2", name = "CCTV1", url = "u2", sourceKey = "k")
        val b = LiveChannel(id = "k|u3", name = "CCTV2", url = "u3", sourceKey = "k")
        // 没记过：取第一条
        assertEquals(listOf(a1, b), distinctChannelsGlobally(listOf(a1, a2, b), emptyMap()))
        // 记过源2：取源2
        assertEquals(
            listOf(a2, b),
            distinctChannelsGlobally(listOf(a1, a2, b), mapOf("CCTV1" to "k|u2")),
        )
        // 记的 id 已失效：回落第一条
        assertEquals(
            listOf(a1, b),
            distinctChannelsGlobally(listOf(a1, a2, b), mapOf("CCTV1" to "k|gone")),
        )
        // 顺序保持首次出现顺序
        assertEquals(
            listOf(b, a1),
            distinctChannelsGlobally(listOf(b, a1, a2), emptyMap()),
        )
    }

    @Test
    fun liveGroupNames_order() {
        val cctv1 = LiveChannel(id = "a|u1", name = "CCTV1", url = "u1", sourceKey = "a", group = "央视频道", favorite = true)
        val cctv2 = LiveChannel(id = "a|u2", name = "CCTV2", url = "u2", sourceKey = "a", group = "央视频道")
        val hunan = LiveChannel(id = "a|u3", name = "湖南卫视", url = "u3", sourceKey = "a", group = "卫视频道")
        val movieA = LiveChannel(id = "a|u4", name = "电影A", url = "u4", sourceKey = "a", group = "电影")
        val movieB = LiveChannel(id = "a|u5", name = "电影B", url = "u5", sourceKey = "a", group = "电影")
        val plain = LiveChannel(id = "a|u6", name = "无分组台", url = "u6", sourceKey = "a", group = "")
        // 全部频道 → 我的收藏（有收藏）→ 真实分组按首次出现顺序 → 未分组最后
        assertEquals(
            listOf("全部频道", "我的收藏", "央视频道", "卫视频道", "电影", "未分组"),
            liveGroupNames(listOf(cctv1, cctv2, hunan, movieA, movieB, plain)),
        )
    }

    @Test
    fun liveGroupNames_noFavoritesNoUngrouped() {
        val movieA = LiveChannel(id = "a|u1", name = "电影A", url = "u1", sourceKey = "a", group = "电影")
        val cctv1 = LiveChannel(id = "a|u2", name = "CCTV1", url = "u2", sourceKey = "a", group = "央视频道")
        // 无收藏 → 无我的收藏项；无无分组频道 → 无未分组项；顺序按首次出现（电影在前），不排序
        assertEquals(
            listOf("全部频道", "电影", "央视频道"),
            liveGroupNames(listOf(movieA, cctv1)),
        )
        assertEquals(listOf("全部频道"), liveGroupNames(emptyList()))
    }

    @Test
    fun liveScopeChannels_groupRange() {
        val cctv1 = LiveChannel(id = "a|u1", name = "CCTV1", url = "u1", sourceKey = "a", group = "央视频道")
        val cctv2 = LiveChannel(id = "a|u2", name = "CCTV2", url = "u2", sourceKey = "a", group = "央视频道")
        val hunan = LiveChannel(id = "a|u3", name = "湖南卫视", url = "u3", sourceKey = "a", group = "卫视频道")
        val movieA1 = LiveChannel(id = "a|u4", name = "电影A", url = "u4", sourceKey = "a", group = "电影")
        val movieA2 = LiveChannel(id = "b|u5", name = "电影A", url = "u5", sourceKey = "b", group = "电影")
        val movieB = LiveChannel(id = "a|u6", name = "电影B", url = "u6", sourceKey = "a", group = "电影")
        val plain = LiveChannel(id = "a|u7", name = "无分组台", url = "u7", sourceKey = "a", group = "")
        val rows = listOf(cctv1, cctv2, hunan, movieA1, movieA2, movieB, plain)
        // 电影分组只含该组三条（含同名两条变体），去重后剩两行
        assertEquals(listOf(movieA1, movieA2, movieB), liveScopeChannels(rows, "电影"))
        assertEquals(
            listOf(movieA1, movieB),
            distinctChannelsGlobally(liveScopeChannels(rows, "电影"), emptyMap()),
        )
        // 全部频道／null 取全量；未分组只取无分组频道；不存在的分组取空
        assertEquals(rows, liveScopeChannels(rows, "全部频道"))
        assertEquals(rows, liveScopeChannels(rows, null))
        assertEquals(listOf(plain), liveScopeChannels(rows, "未分组"))
        assertEquals(listOf(cctv1, cctv2), liveScopeChannels(rows, "央视频道"))
        assertEquals(emptyList<LiveChannel>(), liveScopeChannels(rows, "不存在的分组"))
    }

    @Test
    fun shouldFallbackToAll_rules() {
        val inMovie = LiveChannel(id = "a|u1", name = "电影A", url = "u1", sourceKey = "a", group = "电影")
        val inDrama = LiveChannel(id = "a|u2", name = "剧A", url = "u2", sourceKey = "a", group = "电视剧")
        val noGroup = LiveChannel(id = "a|u3", name = "无分组台", url = "u3", sourceKey = "a", group = "")
        val fav = LiveChannel(id = "a|u4", name = "CCTV1", url = "u4", sourceKey = "a", group = "央视频道", favorite = true)
        val notFav = LiveChannel(id = "a|u5", name = "CCTV2", url = "u5", sourceKey = "a", group = "央视频道", favorite = false)
        // 在电影分组：组内不动，组外／无分组切到全部频道
        assertFalse(shouldFallbackToAll("电影", inMovie))
        assertTrue(shouldFallbackToAll("电影", inDrama))
        assertTrue(shouldFallbackToAll("电影", noGroup))
        // 在未分组：无分组不动，分组切到全部频道
        assertFalse(shouldFallbackToAll("未分组", noGroup))
        assertTrue(shouldFallbackToAll("未分组", inMovie))
        // 收藏 tab 旧行为不变：回落到已收藏不动，回落到未收藏切到全部频道
        assertFalse(shouldFallbackToAll("我的收藏", fav))
        assertTrue(shouldFallbackToAll("我的收藏", notFav))
        // 全部频道／首帧 null：永不切
        assertFalse(shouldFallbackToAll("全部频道", inDrama))
        assertFalse(shouldFallbackToAll(null, inDrama))
    }

    @Test
    fun liveScopeChannels_literalUngroupedGroup() {
        val blank = LiveChannel(id = "a|u1", name = "无分组台", url = "u1", sourceKey = "a", group = "")
        val literal = LiveChannel(id = "a|u2", name = "字面未分组台", url = "u2", sourceKey = "a", group = "未分组")
        val movie = LiveChannel(id = "a|u3", name = "电影A", url = "u3", sourceKey = "a", group = "电影")
        val rows = listOf(blank, literal, movie)
        // 审稿 M1：字面「未分组」与空串分组是同一范围，都进未分组
        assertEquals(listOf(blank, literal), liveScopeChannels(rows, "未分组"))
        assertEquals(listOf(movie), liveScopeChannels(rows, "电影"))
        // 左列只出现一次未分组项，未分组去重后两台
        assertEquals(listOf("全部频道", "电影", "未分组"), liveGroupNames(rows))
        assertEquals(2, distinctChannelsGlobally(liveScopeChannels(rows, "未分组"), emptyMap()).size)
        // 回落到字面未分组台时不切回全部频道
        assertFalse(shouldFallbackToAll("未分组", literal))
        assertFalse(shouldFallbackToAll("未分组", blank))
        assertTrue(shouldFallbackToAll("未分组", movie))
        // 在电影分组回落到字面未分组台：切到全部频道
        assertTrue(shouldFallbackToAll("电影", literal))
    }

    @Test
    fun liveGroupNames_skipsLiteralUpdateTimeGroup() {
        val stamp = LiveChannel(id = "a|u1", name = "20261122 07:59", url = "u1", sourceKey = "a", group = "更新时间")
        val cctv1 = LiveChannel(id = "a|u2", name = "CCTV1", url = "u2", sourceKey = "a", group = "央视频道")
        val rows = listOf(stamp, cctv1)
        // 审稿 S1：字面「更新时间」分组不出现在左列（标签已被刷新入口占用）
        assertEquals(listOf("全部频道", "央视频道"), liveGroupNames(rows))
        // 该频道仍在全部频道里，不丢
        assertEquals(rows, liveScopeChannels(rows, "全部频道"))
        assertEquals(2, distinctChannelsGlobally(liveScopeChannels(rows, "全部频道"), emptyMap()).size)
    }
}
