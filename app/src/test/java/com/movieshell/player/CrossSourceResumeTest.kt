package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.4.33：CrossSourceResume 单测——集数后缀剥离只认两种写法、
 * 同剧同集跳满 3 次即停、播起来清会话。
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
    fun `new episode starts a fresh session`() {
        assertTrue(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:a")))
        CrossSourceResume.recordHop("sub:b")
        CrossSourceResume.recordHop("sub:c")
        CrossSourceResume.recordHop("sub:d")
        assertFalse(CrossSourceResume.begin("斗破苍穹", 4, setOf("sub:d")))
        assertTrue(CrossSourceResume.begin("斗破苍穹", 5, setOf("sub:a")))
        assertEquals(setOf("sub:a"), CrossSourceResume.excludedKeys())
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
}
