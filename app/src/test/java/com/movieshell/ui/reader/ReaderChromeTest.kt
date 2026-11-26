package com.movieshell.ui.reader

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.domain.model.ContentFail
import com.movieshell.domain.model.ContentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 Step 4c：阅读器顶/底栏数值对设计稿、段评角标、淡入淡出时长、查看原文状态。 */
class ReaderChromeTest {

    @Test
    fun tokensMatchDesign() {
        assertEquals(48.dp, ReaderChromeTokens.BarHeight)
        assertEquals(34.dp, ReaderChromeTokens.BackButton)
        assertEquals(18.dp, ReaderChromeTokens.Icon)
        assertEquals(14.sp, ReaderChromeTokens.TitleSize)
        assertEquals(10.sp, ReaderChromeTokens.InfoSize)
        assertEquals(14.dp, ReaderChromeTokens.BarCorner)
        assertTrue(ReaderChromeTokens.BG_ALPHA in 0.55f..0.65f)
        assertEquals(ReaderChromeTokens.BG_ALPHA, ReaderChromeTokens.barAlpha(blurAvailable = true))
        assertEquals(0.92f, ReaderChromeTokens.barAlpha())
    }

    @Test
    fun badgeText() {
        assertNull(readerBadgeText(0))
        assertNull(readerBadgeText(-3))
        assertEquals("7", readerBadgeText(7))
        assertEquals("99", readerBadgeText(99))
        assertEquals("99+", readerBadgeText(100))
    }

    @Test
    fun fadeRespectsReduceMotion() {
        assertEquals(150, readerChromeFadeMs(false))
        assertEquals(0, readerChromeFadeMs(true))
    }

    @Test
    fun rawStateOk() {
        val s = rawStateOf(3, "第四章", ContentResult.Ok("原文正文\n广告行")) { "x" }
        assertEquals(ReaderRawState(3, "第四章", "原文正文\n广告行", null), s)
    }

    @Test
    fun rawStateBlankFailedAndNull() {
        val blank = rawStateOf(1, "t", ContentResult.Ok("   ")) { "x" }
        assertNull(blank.raw)
        assertTrue(blank.error!!.startsWith("原文为空"))
        val failed = rawStateOf(1, "t", ContentResult.Failed(ContentFail.TIMEOUT)) { "超时:$it" }
        assertEquals("超时:TIMEOUT", failed.error)
        val none = rawStateOf(1, "t", null) { "x" }
        assertEquals("拉取原文失败，请稍后重试。", none.error)
    }
}
