package com.movieshell.ui.novel

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.data.novel.BookProbeRecord
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.novel.toSourceHealth
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.SourceHealth

/**
 * v1.4.27 Step 4：书源管理页的行、统计区、测试钮、测试结果弹窗（f5bb9b44 §书源管理页 + 00725506）。
 */

/** 统计区四个数（纯函数，可单测）。 */
data class SourceStats(val total: Int, val enabled: Int, val failed: Int, val unverified: Int) {
    companion object {
        /**
         * 全部 = PY + JSON；启用 = 开关开着的；失效 = JSON 源探测结果为不通/超时；
         * 未验证 = JSON 源还没有探测记录（列表里的“未测”）【口径自定：设计稿只给了四个标签】。
         * PY 源没有被动探测，不计入失效/未验证。
         */
        fun of(
            pyRules: List<PyBookSourceManager.PyBookRule>,
            pyEnabled: Set<String>,
            sources: List<BookSource>,
            probes: Map<String, BookProbeRecord>,
        ): SourceStats {
            var failed = 0
            var unverified = 0
            sources.forEach { s ->
                when (probes[s.key].toSourceHealth()) {
                    SourceHealth.Fail, SourceHealth.Timeout -> failed++
                    SourceHealth.Untested, SourceHealth.Unverified -> unverified++
                    else -> Unit
                }
            }
            return SourceStats(
                total = pyRules.size + sources.size,
                enabled = pyRules.count { it.name in pyEnabled } + sources.count { it.enabled },
                failed = failed,
                unverified = unverified,
            )
        }
    }
}

/** v1.4.29：统计区筛选（纯函数，可单测）。PY 源无探测记录：失效/未验证只看 JSON。 */
enum class StatFilter { ALL, ENABLED, FAILED, UNVERIFIED }

/** 行是否落进当前统计筛选：health 是 JSON 源的探测档位，PY 行传 Untested 即可。 */
fun StatFilter.matchesRow(isPy: Boolean, enabled: Boolean, health: SourceHealth): Boolean = when (this) {
    StatFilter.ALL -> true
    StatFilter.ENABLED -> enabled
    StatFilter.FAILED -> !isPy && (health == SourceHealth.Fail || health == SourceHealth.Timeout)
    StatFilter.UNVERIFIED -> !isPy && (health == SourceHealth.Untested || health == SourceHealth.Unverified)
}

/** 统计信息区：48dp；数字 15sp、标签 10sp、重点数字（启用）绿色；点一格按该档筛列表，再点回全部。 */
@Composable
fun SourceStatsStrip(
    stats: SourceStats,
    modifier: Modifier = Modifier,
    selected: StatFilter = StatFilter.ALL,
    onSelect: (StatFilter) -> Unit = {},
) {
    val p = novelPalette()
    GlassSurface(
        modifier.fillMaxWidth().height(NovelTokens.StatsHeight),
        shape = RoundedCornerShape(NovelTokens.RowCorner),
        shadow = false,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val cells = listOf(
                Triple("全部", stats.total, p.title) to StatFilter.ALL,
                Triple("启用", stats.enabled, p.success) to StatFilter.ENABLED,
                Triple("失效", stats.failed, if (stats.failed > 0) p.error else p.title) to StatFilter.FAILED,
                Triple("未验证", stats.unverified, p.title) to StatFilter.UNVERIFIED,
            )
            cells.forEachIndexed { i, (cell, filter) ->
                val (label, n, color) = cell
                if (i > 0) {
                    // 分隔竖线：描边色、高 18dp【自定】
                    Box(Modifier.width(1.dp).height(18.dp).background(p.glassStroke))
                }
                val isSel = filter == selected
                Column(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .novelClickable(kind = NovelMotion.Press.SMALL, onClick = {
                            onSelect(if (isSel) StatFilter.ALL else filter)
                        })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("$n", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isSel) p.accent else color, maxLines = 1)
                    Text(label, fontSize = 10.sp, color = if (isSel) p.accent else p.hint, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 单条书源（SourceCompactRow）：44dp、圆角 10dp；状态圆点 7dp；源名 13sp/Medium（源名优先收缩）；
 * 状态文案 10sp 单行省略（最宽【自定 64dp】）；开关 30×18dp；编辑/删除图标 16dp（热区 36×36dp）；行内间距 8dp。
 * 相邻的 36dp 热区之间不再加 8dp——热区内已各留 10dp 视觉空白，视觉间距 ≥ 8dp【自定，保证 360dp 宽不挤破】。
 * 批量模式：左侧换勾选框、右侧只留开关（测试/编辑/删除隐藏，删除走底部批量栏）【自定】。
 */
@Composable
fun SourceCompactRow(
    name: String,
    statusText: String,
    statusColor: Color,
    dotColor: Color,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    testPhase: SourceTestRunner.Phase?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    batchMode: Boolean = false,
    selected: Boolean = false,
    onSelect: (Boolean) -> Unit = {},
    onExplore: (() -> Unit)? = null,
) {
    val p = novelPalette()
    val shape = RoundedCornerShape(NovelTokens.RowCorner)
    Row(
        modifier
            .fillMaxWidth()
            .height(NovelTokens.RowHeight)
            .clip(shape)
            // 行底：玻璃填充减半透明【自定 45%】——设计稿明确“不要明显圆角卡片底色”
            .background(p.glass.copy(alpha = p.glass.alpha * 0.45f))
            .border(1.dp, p.glassStroke.copy(alpha = p.glassStroke.alpha * 0.6f), shape)
            .novelClickable(kind = NovelMotion.Press.CARD, onLongClick = onLongClick, onClick = { if (batchMode) onSelect(!selected) else onClick() })
            .padding(start = NovelTokens.RowPadH, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v1.4.29：批量模式也留状态圆点——原来圆点位被勾选框顶掉，折叠组里更看不见通断。
        Box(Modifier.size(NovelTokens.RowDot).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(NovelTokens.RowInnerGap))
        if (batchMode) {
            MiniCheck(selected) { onSelect(it) }
            Spacer(Modifier.width(NovelTokens.RowInnerGap))
        }
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = p.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(NovelTokens.RowInnerGap))
        Text(
            statusText,
            fontSize = 10.sp,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 64.dp),
        )
        if (onExplore != null && !batchMode) {
            // 「发现」文字链：11sp 主强调绿、热区高 36dp【自定】
            Box(
                Modifier.height(NovelTokens.RowHit).clip(RoundedCornerShape(8.dp)).novelClickable(onClick = onExplore).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text("发现", fontSize = 11.sp, color = p.accent, maxLines = 1) }
        } else {
            Spacer(Modifier.width(4.dp))
        }
        if (!batchMode) TestButton(testPhase, onTest)
        NovelMiniSwitch(enabled, onEnabledChange)
        if (!batchMode) {
            RowIconButton(Icons.Outlined.Edit, "编辑", p.body, onEdit)
            RowIconButton(Icons.Outlined.Delete, "删除", p.error, onDelete)
        }
    }
}

/** 批量勾选：18dp 圆角方框、热区 36dp【自定】。 */
@Composable
private fun MiniCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = novelPalette()
    Box(
        Modifier.size(NovelTokens.RowHit).novelClickable(role = Role.Checkbox) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .then(if (checked) Modifier.background(p.accent) else Modifier.border(1.5.dp, p.hint, RoundedCornerShape(5.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Outlined.Check, "已选", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * 搜索测试行内钮（00725506 §4）：空闲播放图标（16dp，主强调绿）；排队中「等待」10sp；测试中 20dp 转圈（沿用 PY）。
 * 热区 36×36dp；状态切换淡入淡出（140ms，减动效即时）。
 */
@Composable
fun TestButton(phase: SourceTestRunner.Phase?, onClick: () -> Unit) {
    val p = novelPalette()
    val reduce = rememberReduceMotion()
    Box(
        Modifier
            .size(NovelTokens.RowHit)
            .clip(CircleShape)
            .novelClickable(enabled = phase == null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(phase, animationSpec = tween(if (reduce) 0 else NovelMotion.SMALL_RELEASE_MS), label = "test-btn") { ph ->
            Box(Modifier.size(NovelTokens.RowHit), contentAlignment = Alignment.Center) {
                when (ph) {
                    SourceTestRunner.Phase.RUNNING ->
                        CircularProgressIndicator(Modifier.size(NovelTokens.Spinner), color = p.accent, strokeWidth = 2.dp)
                    SourceTestRunner.Phase.QUEUED ->
                        Text("等待", fontSize = 10.sp, color = p.hint, maxLines = 1)
                    null -> Icon(Icons.Outlined.PlayArrow, "搜索测试", tint = p.accent, modifier = Modifier.size(NovelTokens.RowIcon))
                }
            }
        }
    }
}

/** 分组头：高 32dp【自定】、11sp 辅助色、折叠箭头 14dp 旋转（140ms）。 */
@Composable
fun SourceGroupHeader(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val p = novelPalette()
    val reduce = rememberReduceMotion()
    val rot by animateFloatAsState(
        if (collapsed) 0f else 90f,
        tween(if (reduce) 0 else NovelMotion.SMALL_RELEASE_MS, easing = NovelMotion.Easing),
        label = "grp",
    )
    Row(
        modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp)).novelClickable(kind = NovelMotion.Press.CARD, onClick = onToggle).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, if (collapsed) "展开" else "收起", tint = p.hint, modifier = Modifier.size(14.dp).rotate(rot))
        Spacer(Modifier.width(4.dp))
        Text("$title（$count）", fontSize = 11.sp, color = p.hint, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 区段标题（本地 PY / JSON）：12sp SemiBold 正文色、上 8 下 4dp【自定】。 */
@Composable
fun SourceSectionTitle(text: String, modifier: Modifier = Modifier) {
    val p = novelPalette()
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.body, modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** 五档结果名（00725506 §背景：通过 / 未验证 / 失败 / 超时 / 未配置搜索）。 */
fun testTierLabel(h: SourceHealth): String = when (h) {
    is SourceHealth.Ok, is SourceHealth.Slow -> "通过"
    SourceHealth.Unverified -> "未验证"
    SourceHealth.Fail -> "失败"
    SourceHealth.Timeout -> "超时"
    SourceHealth.Unconfigured -> "未配置搜索"
    SourceHealth.Untested -> "未测"
}

/**
 * 搜索测试结果弹窗：与搜索验证弹窗同款 88%×60%、16dp 圆角（总定稿“弹窗统一”）；
 * 标题行「搜索测试」+ 源名 + 关闭图标；中间档位徽标（按档位着色）+「关键词：…」+ 结果行；底部「知道了」。
 */
@Composable
fun SourceTestResultDialog(r: TestResultDisplay, onDismiss: () -> Unit) {
    val p = novelPalette()
    val c = p.healthColor(r.health)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.60f),
            shape = RoundedCornerShape(16.dp),
            color = if (p.dark) Color(0xFF132036) else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, p.glassStroke),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("搜索测试", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.title)
                        Spacer(Modifier.height(2.dp))
                        Text(r.sourceTitle, fontSize = 11.sp, color = p.hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    GlassIconButton(Icons.Outlined.Close, "关闭", onDismiss, tint = p.body)
                }
                HorizontalDivider(color = p.glassStroke)
                Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 档位徽标：12sp、档位色 15% 底【自定】
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(testTierLabel(r.health), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c) }
                    Text("关键词：${r.keyword}", fontSize = 13.sp, color = p.body)
                    Text(r.line, fontSize = 14.sp, color = c)
                }
                HorizontalDivider(color = p.glassStroke)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) { NovelPillButton("知道了", primary = true, onClick = onDismiss) }
            }
        }
    }
}
