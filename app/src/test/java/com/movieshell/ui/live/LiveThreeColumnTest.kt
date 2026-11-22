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
        // 顺序保持 key 首次出现顺序
        assertEquals(
            listOf(c5, hd),
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
}
