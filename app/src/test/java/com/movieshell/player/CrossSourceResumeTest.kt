package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.4.33：CrossSourceResume 单测——集数后缀剥离（有序候选表）、
 * 同剧同集跳满 3 次即停、播稳 20 秒清会话、TTL 过期重开、
 * 跨站片名不一致仍续链、换源位置只消费一次。
 */
class CrossSourceResumeTest {

    @Before
    fun setUp() {
        CrossSourceResume.clear()
    }

    @Test
    fun `strip episode suffix with space`() {
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 第5集"))
    }

    @Test
    fun `strip episode suffix without space`() {
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹第5集"))
    }

    @Test
    fun `strip episode suffix with spaces around number`() {
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 第 5 集"))
    }

    @Test
    fun `strip ep suffix`() {
        assertEquals("剧名", CrossSourceResume.stripEpisodeSuffix("剧名 EP12"))
        assertEquals("剧名", CrossSourceResume.stripEpisodeSuffix("剧名 E3"))
    }

    @Test
    fun `show name with trailing digit is untouched`() {
        assertEquals("庆余年2", CrossSourceResume.stripEpisodeSuffix("庆余年2"))
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹"))
    }

    @Test
    fun `session excludes current keys and caps at 3 hops`() {
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        assertEquals(setOf("sub:a"), CrossSourceResume.excludedKeys())
        CrossSourceResume.recordHop("sub:b")
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:b")))
        assertEquals(setOf("sub:a", "sub:b"), CrossSourceResume.excludedKeys())
        CrossSourceResume.recordHop("sub:c")
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:c")))
        CrossSourceResume.recordHop("sub:d")
        assertFalse(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:d")))
    }

    @Test
    fun `new episode mid-chain does not reset hops`() {
        // v1.4.33 M2：同一轮链内不看片名/集数——跨站后集数分组不同会被钳到别的
        // 下标，换集不再另开会话；新会话只从 TTL 过期或 clear() 开始。
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b")
        CrossSourceResume.recordHop("sub:c")
        CrossSourceResume.recordHop("sub:d")
        assertFalse(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:d")))
        assertFalse(CrossSourceResume.begin("斗破苍穹", 5, setOf("sub:a")))
        CrossSourceResume.clear()
        assertTrue(CrossSourceResume.begin("斗破苍穹", 5, setOf("sub:a")))
        assertEquals(setOf("sub:a"), CrossSourceResume.excludedKeys())
    }

    @Test
    fun `session restarts after TTL expires`() {
        // S11：跳满后同剧同集不再续链；TTL 过期后重开，已试集合清空。
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b")
        CrossSourceResume.recordHop("sub:c")
        CrossSourceResume.recordHop("sub:d")
        assertFalse(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:d")))
        CrossSourceResume.backdateStartedAtForTest(CrossSourceResume.SESSION_TTL_MS + 1000)
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        assertEquals(setOf("sub:a"), CrossSourceResume.excludedKeys())
    }

    @Test
    fun `strip chinese numerals fullwidth digits and ranges`() {
        // S11/S1：中文数字、全角数字、区间、带尾巴、更新至。
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 第十二集"))
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 第５集"))
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 第1-2集"))
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 第5集 高清"))
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 更新至12集"))
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 更新至第12集"))
    }

    @Test
    fun `strip bare trailing number but not season or year`() {
        // S11/S1：无“第”的纯数字剥；第N季是季不是集，不剥；年份后缀不剥。
        assertEquals("斗破苍穹", CrossSourceResume.stripEpisodeSuffix("斗破苍穹 05"))
        assertEquals("庆余年 第2季", CrossSourceResume.stripEpisodeSuffix("庆余年 第2季"))
        assertEquals("剧名 2024", CrossSourceResume.stripEpisodeSuffix("剧名 2024"))
    }

    @Test
    fun `strip ep suffix needs separator for single E`() {
        // S11/S2：单个 E 须有前置分隔符，“...SE2”这类片名不被误剥；
        // 大写 EP 不带分隔符也可剥。
        assertEquals("某某SE2", CrossSourceResume.stripEpisodeSuffix("某某SE2"))
        assertEquals("剧名", CrossSourceResume.stripEpisodeSuffix("剧名EP12"))
        assertEquals("剧名", CrossSourceResume.stripEpisodeSuffix("剧名 E12"))
    }

    @Test
    fun `chain continues when site renames the show`() {
        // S11/M2：跨站后片名逐字不同（带年份/副标题）仍续链，已试源不丢。
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b")
        assertTrue(CrossSourceResume.begin("斗破苍穹 2024", 4, setOf("sub:b")))
        assertEquals(setOf("sub:a", "sub:b"), CrossSourceResume.excludedKeys())
        CrossSourceResume.recordHop("sub:c")
        assertTrue(CrossSourceResume.begin("斗破苍穹·年番", 5, setOf("sub:c")))
        assertEquals(setOf("sub:a", "sub:b", "sub:c"), CrossSourceResume.excludedKeys())
    }

    @Test
    fun `consumePendingPos only once`() {
        // S11/M3：换源带的位置只消费一次；对不上剧集不消费也不返回。
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b", 30000L)
        assertEquals(0L, CrossSourceResume.consumePendingPos("斗破苍穹", 5))
        assertEquals(0L, CrossSourceResume.consumePendingPos("庆余年", 4))
        assertEquals(30000L, CrossSourceResume.consumePendingPos("斗破苍穹", 4))
        assertEquals(0L, CrossSourceResume.consumePendingPos("斗破苍穹", 4))
        // posMs<=0 不覆盖已记的位置。
        CrossSourceResume.recordHop("sub:c", 45000L)
        CrossSourceResume.recordHop("sub:d", 0L)
        assertEquals(45000L, CrossSourceResume.consumePendingPos("斗破苍穹", 4))
    }

    @Test
    fun `clearIfActiveFor only clears its own show and episode`() {
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b")
        CrossSourceResume.clearIfActiveFor("斗破苍穹", 5)
        assertEquals(setOf("sub:a", "sub:b"), CrossSourceResume.excludedKeys())
        CrossSourceResume.clearIfActiveFor("斗破苍穹", 4)
        assertTrue(CrossSourceResume.excludedKeys().isEmpty())
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
    }

    @Test
    fun `clearIfChainActive only clears a chain in progress`() {
        // TA 复核 1a：刚开的新会话（hops==0）不清；跳过一次后才清。
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.clearIfChainActive()
        assertEquals(setOf("sub:a"), CrossSourceResume.excludedKeys())
        CrossSourceResume.recordHop("sub:b")
        CrossSourceResume.clearIfChainActive()
        assertTrue(CrossSourceResume.excludedKeys().isEmpty())
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
    }

    @Test
    fun `clearIfChainActive clears renamed show and clamped episode`() {
        // TA 复核 1a：跨站改名/集数被限定后，逐字比对的 clearIfActiveFor
        // 清不掉，clearIfChainActive 只认 hops>0，能清掉。
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b")
        assertTrue(CrossSourceResume.begin("斗破苍穹 2024", 5, setOf("sub:b")))
        CrossSourceResume.clearIfActiveFor("斗破苍穹 2024", 5)
        assertEquals(setOf("sub:a", "sub:b"), CrossSourceResume.excludedKeys())
        CrossSourceResume.clearIfChainActive()
        assertTrue(CrossSourceResume.excludedKeys().isEmpty())
    }
}
