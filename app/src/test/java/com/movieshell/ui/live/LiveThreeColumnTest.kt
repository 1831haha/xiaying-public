package com.movieshell.ui.live

import com.movieshell.data.store.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.14 三列：源编号、滚动可见性判断、按台名去重（纯函数）。 */
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
    fun distinctChannelsByName_rememberedSourceWins() {
        val a1 = LiveChannel(id = "k|u1", name = "CCTV1", url = "u1", sourceKey = "k")
        val a2 = LiveChannel(id = "k|u2", name = "CCTV1", url = "u2", sourceKey = "k")
        val b = LiveChannel(id = "k|u3", name = "CCTV2", url = "u3", sourceKey = "k")
        // 没记过：取第一条
        assertEquals(listOf(a1, b), distinctChannelsByName(listOf(a1, a2, b), emptyMap()))
        // 记过源2：取源2
        assertEquals(
            listOf(a2, b),
            distinctChannelsByName(listOf(a1, a2, b), mapOf("CCTV1" to "k|u2")),
        )
        // 记的 id 已失效：回落第一条
        assertEquals(
            listOf(a1, b),
            distinctChannelsByName(listOf(a1, a2, b), mapOf("CCTV1" to "k|gone")),
        )
        // 顺序保持首次出现顺序
        assertEquals(
            listOf(b, a1),
            distinctChannelsByName(listOf(b, a1, a2), emptyMap()),
        )
    }
}
