package com.movieshell.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v1.4.30：整区/行内加载圈（用户点选 E 双环）——外圈淡轨＋亮弧顺时针、
 * 内圈淡轨＋亮弧逆时针，细描边、无中央点。
 * v1.4.32：亮弧改品牌渐变（跟主题走的 LocalBrandGradient，不写死颜色）——
 * 1.4.30 的小说绿 #1A8B5C 与 StatusRing 的“可用”绿撞色，加载中与可用同屏
 * 分不清；渐变只表达“正在加载”，不表达状态。
 *
 * 与 StatusRing 的分工：StatusRing 是状态指示（颜色语义：绿就绪/琥珀加载中/
 * 红失败/灰未选，带中央点），首页源标签 14dp 与服务插件页服务状态仍走它，
 * 颜色语义不动；这里只做“正在加载”的样子，不表达状态。整区加载（换源弹窗、
 * 选源悬浮窗、歌单详情、音乐各页、歌词区）与找书页行内 16dp 细圈走这里。
 */
@Composable
fun LoadingRing(
    diameter: Dp = 40.dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp? = null,
    caption: String? = null,
) {
    val brandColors = LocalBrandGradient.current
    // sweep 渐变要首尾闭合（末尾补回首色），亮弧转过一圈颜色才不断档。
    val ringColors = brandColors + brandColors.first()
    val trackColor = brandColors.first().copy(alpha = 0.15f)
    val transition = rememberInfiniteTransition(label = "loadingRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "loadingRingAngle",
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(diameter)) {
                val w = strokeWidth?.toPx() ?: size.minDimension * 0.07f
                val outerBrush = Brush.sweepGradient(ringColors, center = Offset(size.width / 2f, size.height / 2f))
                // 外圈：淡轨整圆＋亮弧顺时针。
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = w),
                )
                drawArc(
                    brush = outerBrush,
                    startAngle = angle,
                    sweepAngle = 160f,
                    useCenter = false,
                    style = Stroke(width = w, cap = StrokeCap.Round),
                )
                // 内圈：淡轨整圆＋亮弧逆时针，半径缩进 20%。
                val inset = size.minDimension * 0.20f
                val innerSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val innerCenter = Offset(inset + innerSize.width / 2f, inset + innerSize.height / 2f)
                val innerBrush = Brush.sweepGradient(ringColors, center = innerCenter)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = innerSize,
                    style = Stroke(width = w),
                )
                drawArc(
                    brush = innerBrush,
                    startAngle = -angle,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = innerSize,
                    style = Stroke(width = w, cap = StrokeCap.Round),
                )
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = caption,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
