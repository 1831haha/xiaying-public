package com.movieshell.ui.novel

import com.movieshell.data.novel.BookProbeRecord
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.SourceHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4.27 Step 4a：设计稿数值与纯逻辑（动效分档、减动效、统计区口径、五档名）。 */
class NovelUiTest {

    @Test
    fun design_numbers_match_f5bb9b44() {
        assertEquals(48f, NovelTokens.BarHeight.value)
        assertEquals(16f, NovelTokens.BarMarginH.value)
        assertEquals(14f, NovelTokens.BarCorner.value)
        assertEquals(34f, NovelTokens.BarButton.value)
        assertEquals(18f, NovelTokens.BarIcon.value)
        assertEquals(15f, NovelTokens.BarTitleSize.value)
        assertEquals(10f, NovelTokens.BarTitleGap.value)
        assertEquals(6f, NovelTokens.BarActionGap.value)
        assertEquals(48f, NovelTokens.StatsHeight.value)
        assertEquals(38f, NovelTokens.SearchHeight.value)
        assertEquals(11f, NovelTokens.SearchCorner.value)
        assertEquals(0.55f, NovelTokens.SearchBgAlpha)
        assertEquals(44f, NovelTokens.RowHeight.value)
        assertEquals(3f, NovelTokens.RowGap.value)
        assertEquals(7f, NovelTokens.RowDot.value)
        assertEquals(30f, NovelTokens.SwitchW.value)
        assertEquals(18f, NovelTokens.SwitchH.value)
        assertEquals(36f, NovelTokens.RowHit.value)
        assertEquals(44f, NovelTokens.MenuItem.value)
        assertEquals(0xFF0C1424.toInt(), NovelTokens.PageBg.toArgbCompat())
        assertEquals(0xFF1A8B5C.toInt(), NovelTokens.Accent.toArgbCompat())
    }

    private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int =
        ((alpha * 255 + 0.5f).toInt() shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or
            ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

    @Test
    fun motion_tiers_and_reduce_motion() {
        assertEquals(1f to 1f, NovelMotion.target(false, NovelMotion.Press.SMALL, false))
        assertEquals(0.94f to 0.88f, NovelMotion.target(true, NovelMotion.Press.SMALL, false))
        assertEquals(0.98f to 0.88f, NovelMotion.target(true, NovelMotion.Press.CARD, false))
        // 减动效：取消缩放，保留透明度反馈
        assertEquals(1f to 0.88f, NovelMotion.target(true, NovelMotion.Press.SMALL, true))
        assertEquals(100, NovelMotion.Press.SMALL.pressMs)
        assertEquals(140, NovelMotion.Press.SMALL.releaseMs)
        assertEquals(110, NovelMotion.Press.CARD.pressMs)
        assertEquals(150, NovelMotion.pageFadeMs(false))
        assertEquals(0, NovelMotion.pageFadeMs(true))
        assertEquals(180, NovelMotion.MENU_MS)
    }

    @Test
    fun stats_counts() {
        val py = listOf(PyBookSourceManager.PyBookRule("a.py", "a", 1), PyBookSourceManager.PyBookRule("b.py", "b", 1))
        val src = listOf(
            BookSource(name = "1", url = "https://s1"),
            BookSource(name = "2", url = "https://s2", enabled = false),
            BookSource(name = "3", url = "https://s3"),
            BookSource(name = "4", url = "https://s4"),
        )
        val probes = mapOf("s1" to BookProbeRecord("OK", 10), "s2" to BookProbeRecord("FAIL"), "s3" to BookProbeRecord("SLOW", 5000))
        assertEquals(SourceStats(total = 6, enabled = 4, failed = 1, unverified = 1), SourceStats.of(py, setOf("a.py"), src, probes))
    }

    @Test
    fun five_tier_labels() {
        assertEquals("通过", testTierLabel(SourceHealth.Ok(1)))
        assertEquals("通过", testTierLabel(SourceHealth.Slow(5000)))
        assertEquals("未验证", testTierLabel(SourceHealth.Unverified))
        assertEquals("失败", testTierLabel(SourceHealth.Fail))
        assertEquals("超时", testTierLabel(SourceHealth.Timeout))
        assertEquals("未配置搜索", testTierLabel(SourceHealth.Unconfigured))
    }
}
