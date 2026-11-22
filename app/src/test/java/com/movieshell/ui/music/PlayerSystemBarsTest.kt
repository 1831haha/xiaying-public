package com.movieshell.ui.music

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSystemBarsTest {

    @Test fun noArtFallsBackToAtmosphereBase() {
        assertEquals(PlayerSystemBars.ATMOSPHERE_BASE, PlayerSystemBars.uniformBarColor(null, false))
        assertEquals(Color(0xFF07070D), PlayerSystemBars.ATMOSPHERE_BASE)
    }

    @Test fun uniformScrimAlphaValues() {
        assertEquals(0.60f, PlayerSystemBars.uniformScrimAlpha(false), 0.001f)
        assertEquals(0.55f, PlayerSystemBars.uniformScrimAlpha(true), 0.001f)
    }

    @Test fun artAverageIsDarkenedByUniformScrim() {
        // v1.4.15：整页一个色——系统栏色 = 平均色盖均匀黑遮罩（普通 0.60，沉浸 0.55），
        // 不再是旧的平均色 × 0.62（顶 38% 黑渐变的算法）。
        // Compose Color 按 8 位通道存储（单步 1/255≈0.0039），允差取 0.005。
        val white = PlayerSystemBars.uniformBarColor(Color(0xFFFFFFFF), immersive = false)
        assertEquals(0.40f, white.red, 0.005f)
        assertEquals(0.40f, white.green, 0.005f)
        assertEquals(0.40f, white.blue, 0.005f)
        val blue = PlayerSystemBars.uniformBarColor(Color(0xFF336699), immersive = false)
        assertEquals(0x33 / 255f * 0.40f, blue.red, 0.005f)
        assertEquals(0x66 / 255f * 0.40f, blue.green, 0.005f)
        assertEquals(0x99 / 255f * 0.40f, blue.blue, 0.005f)
        assertEquals(1f, blue.alpha, 0.005f)
        // 沉浸遮罩 0.55，颜色更亮。
        val whiteImmersive = PlayerSystemBars.uniformBarColor(Color(0xFFFFFFFF), immersive = true)
        assertEquals(0.45f, whiteImmersive.red, 0.005f)
        assertEquals(0.45f, whiteImmersive.green, 0.005f)
        assertEquals(0.45f, whiteImmersive.blue, 0.005f)
    }

    @Test fun averageColorOfPixels() {
        // 红绿蓝白四像素平均：每通道 (255+0+0+255)/4 = 127。
        val avg = PlayerSystemBars.averageColorOf(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()),
        )
        assertEquals(127 / 255f, avg.red, 0.001f)
        assertEquals(127 / 255f, avg.green, 0.001f)
        assertEquals(127 / 255f, avg.blue, 0.001f)
        assertEquals(PlayerSystemBars.ATMOSPHERE_BASE, PlayerSystemBars.averageColorOf(intArrayOf()))
    }

    @Test fun noArtBarColorMatchesAtmosphereTopEdge() {
        // v1.4.15：品牌首色青 0xFF4DD0E1：0.32 铺底 + 均匀黑遮罩（普通 0.60，沉浸 0.55），
        // 两层叠加后普通约 (0.046, 0.112, 0.127)，沉浸约 (0.052, 0.126, 0.143)。
        val normal = PlayerSystemBars.noArtBarColor(Color(0xFF4DD0E1), immersive = false)
        assertEquals(0.046f, normal.red, 0.005f)
        assertEquals(0.112f, normal.green, 0.005f)
        assertEquals(0.127f, normal.blue, 0.005f)
        // 沉浸遮罩 0.55，颜色更亮。
        val immersive = PlayerSystemBars.noArtBarColor(Color(0xFF4DD0E1), immersive = true)
        assertEquals(0.052f, immersive.red, 0.005f)
        assertEquals(0.126f, immersive.green, 0.005f)
        assertEquals(0.143f, immersive.blue, 0.005f)
        assertEquals(PlayerSystemBars.ATMOSPHERE_BASE, PlayerSystemBars.noArtBarColor(null, false))
    }

    @Test fun compositeOverBlendsByAlpha() {
        val blended = PlayerSystemBars.compositeOver(
            Color(red = 1f, green = 0f, blue = 0f, alpha = 0.5f),
            Color(red = 0f, green = 0f, blue = 1f, alpha = 1f),
        )
        assertEquals(0.5f, blended.red, 0.005f)
        assertEquals(0f, blended.green, 0.005f)
        assertEquals(0.5f, blended.blue, 0.005f)
    }

    @Test fun publishAndClearRoundTrip() {
        PlayerSystemBars.publish(Color(0xFF112233))
        assertEquals(Color(0xFF112233), PlayerSystemBars.barColor)
        PlayerSystemBars.clear()
        assertNull(PlayerSystemBars.barColor)
    }
}
