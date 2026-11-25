package com.movieshell.ui.music

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
 * v1.4.19：全屏一个色——系统栏与页面共用同一个纯色底，不再取封面平均色。
 * 1.4.15 的均匀黑遮罩只压掉六成，封面自己上下明暗/色相差得太多，剩下的
 * 四成照样看得出上下不同色；栏色取全图平均色也对不上页面顶边那块更亮的
 * 模糊封面。取色整条链（uniformBarColor / noArtBarColor /
 * uniformScrimAlpha / averageColor / averageColorOf / compositeOver）
 * 随模糊铺底一起删掉。
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

    /** 播放页底色——页面与系统栏共用的唯一纯色。 */
    val ATMOSPHERE_BASE = Color(0xFF07070D)
}
