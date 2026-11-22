package com.movieshell.ui.music

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * v1.3.2：播放页系统栏跟随整体色——与 1.3.1 阅读页同款做法（单一事实来源）：
 * MusicPlayerScreen 发布当前页的整体色，MainActivity 在 music_player 路由上
 * 用它做状态栏/导航栏底色，离页清掉；图标深浅仍走 MainActivity 现成的
 * luminance 判断。
 *
 * v1.4.15：整页一个色（C 改良版）——模糊封面铺满 + 整页均匀黑遮罩，
 * 不再是顶 38% 底 88% 的黑渐变。整体色取法：有封面时封面平均色盖
 * 同一层均匀遮罩，无封面时品牌渐变首色同样叠加。旧的 atmosphereBarColor
 * （平均色 × 0.62）随黑渐变一起废止。
 */
object PlayerSystemBars {
    /** 当前播放页整体色；null = 不在播放页内。 */
    var barColor by mutableStateOf<Color?>(null)
        private set

    fun publish(color: Color) {
        barColor = color
    }

    fun clear() {
        barColor = null
    }

    /** 播放页氛围底色（PlayerAtmosphere 的底色，无封面时用它）。 */
    val ATMOSPHERE_BASE = Color(0xFF07070D)

    /** v1.4.15：均匀遮罩透明度——普通 0.60，沉浸 0.55（与 PlayerAtmosphere 同值）。 */
    fun uniformScrimAlpha(immersive: Boolean): Float = if (immersive) 0.55f else 0.60f

    /** 封面平均色 → 系统栏色：盖一层与页面相同的均匀黑遮罩，与页面顶边同色。 */
    fun uniformBarColor(artAverage: Color?, immersive: Boolean): Color {
        val base = artAverage ?: return ATMOSPHERE_BASE
        return compositeOver(Color.Black.copy(alpha = uniformScrimAlpha(immersive)), base)
    }

    /**
     * v1.4.15：无封面时系统栏色——页面顶边是品牌渐变首色（0.32 透明度）
     * 铺在氛围底色上，再压一层与页面相同的均匀黑遮罩。直接回到底色会
     * 比页面顶边黑一截，看着就是“没跟上”。
     */
    fun noArtBarColor(brandTop: Color?, immersive: Boolean): Color {
        val top = brandTop ?: return ATMOSPHERE_BASE
        val overBase = compositeOver(top.copy(alpha = 0.32f), ATMOSPHERE_BASE)
        return compositeOver(Color.Black.copy(alpha = uniformScrimAlpha(immersive)), overBase)
    }

    /** src（带透明度）盖在不透明的 dst 上。 */
    fun compositeOver(src: Color, dst: Color): Color {
        val a = src.alpha
        return Color(
            red = (src.red * a + dst.red * (1f - a)).coerceIn(0f, 1f),
            green = (src.green * a + dst.green * (1f - a)).coerceIn(0f, 1f),
            blue = (src.blue * a + dst.blue * (1f - a)).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    /**
     * 取 bitmap 的平均色：缩到 1×1（双线性采样即全图平均），不引 Palette。
     * v1.3.4：Coil 默认解出来的是 HARDWARE 位图，getPixel 直接读会抛
     * IllegalStateException（1.3.3 真机一进播放页就闪退的根因）。硬件
     * 位图先 copy 成 ARGB_8888 再读；任何异常都回到底色，取色绝不能
     * 让 App 闪退。
     */
    fun averageColor(bitmap: Bitmap): Color {
        return try {
            if (bitmap.width <= 0 || bitmap.height <= 0) return ATMOSPHERE_BASE
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return ATMOSPHERE_BASE
            } else {
                bitmap
            }
            val px = Bitmap.createScaledBitmap(readable, 1, 1, true).getPixel(0, 0)
            Color(px)
        } catch (_: Exception) {
            ATMOSPHERE_BASE
        }
    }

    /** 像素数组的平均色（可单测的纯函数；bitmap 版只负责取像素）。 */
    fun averageColorOf(pixels: IntArray): Color {
        if (pixels.isEmpty()) return ATMOSPHERE_BASE
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size.toLong()
        return Color(
            red = (r / n).toInt() / 255f,
            green = (g / n).toInt() / 255f,
            blue = (b / n).toInt() / 255f,
            alpha = 1f,
        )
    }
}
