package com.movieshell.ui.music

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSystemBarsTest {

    @Test fun atmosphereBaseIsTheSingleFullscreenColor() {
        // v1.4.19：全屏一个色——页面与系统栏共用同一个纯色底 0xFF07070D，
        // 不再取封面平均色、不再盖均匀黑遮罩。
        assertEquals(Color(0xFF07070D), PlayerSystemBars.ATMOSPHERE_BASE)
    }

    @Test fun publishAndClearRoundTrip() {
        PlayerSystemBars.publish(Color(0xFF112233))
        assertEquals(Color(0xFF112233), PlayerSystemBars.barColor)
        PlayerSystemBars.clear()
        assertNull(PlayerSystemBars.barColor)
    }
}
