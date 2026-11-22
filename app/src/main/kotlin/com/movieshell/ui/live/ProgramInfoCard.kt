package com.movieshell.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.source.EpgFetcher
import com.movieshell.data.store.LiveChannel
import com.movieshell.ui.theme.V5
import java.util.Date

/**
 * v0.80.0：节目信息卡（对标 DsPlayer 播放器下方那张卡）。
 * v0.292.0 V5：「正在播」卡——surfaceContainer 24dp 圆角，16dp 内边距：
 * 头部「正在播 · 开始–结束」（labelLarge primary）+ 节目名（titleMedium）；
 * 节目进度（4dp）；底部下一档。无 EPG 时显示台名 + 提示。
 * v1.4.14 三列：卡上不再放搜索/收藏/设置图标（移到中列当前台条）与
 * 同名源分段按钮（移到右列）；右上角只留展开/收起箭头，默认展开。
 */
@Composable
internal fun ProgramInfoCard(
    channel: LiveChannel?,
    epg: Pair<EpgFetcher.Program?, EpgFetcher.Program?>?,
    // v1.4.11 第 1 步：节目单来自全局兜底时打“全局”小标（仅兜底命中时）。
    epgFromGlobal: Boolean = false,
    hasEpgSource: Boolean = true,
) {
    val (now, next) = epg ?: (null to null)
    val v = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(V5.CornerCard))
            .background(v.surfaceContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (now != null) {
                    Text(
                        text = "正在播" + (if (epgFromGlobal) " · 全局" else "") +
                            " · ${timeFmt.format(Date(now.startMs))}–${timeFmt.format(Date(now.stopMs))}",
                        style = MaterialTheme.typography.labelLarge,
                        color = v.primary,
                        maxLines = 1,
                    )
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = v.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = listOfNotNull(
                            channel?.name ?: "暂无频道",
                            channel?.group?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = v.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (!hasEpgSource) "暂无节目单，可在直播设置 EPG 里填全局节目单地址" else "节目单加载中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = v.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起节目信息" else "展开节目信息",
                    tint = v.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            if (now != null && now.stopMs > now.startMs) {
                val p = ((System.currentTimeMillis() - now.startMs).toFloat() / (now.stopMs - now.startMs)).coerceIn(0f, 1f)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(v.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(v.primary),
                    )
                }
            }
            // v1.4.11 审稿 S1：有正在播、无下一档时也要显示“暂无下一档信息”，
            // 不只多源时才显示（单源无下一档是常见情况）。
            if (next != null || now != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        text = if (next != null) {
                            "下一档" + (if (epgFromGlobal && now == null) " · 全局" else "") +
                                " ${timeFmt.format(Date(next.startMs))} ${next.title}"
                        } else if (now != null) {
                            "暂无下一档信息"
                        } else {
                            (channel?.name ?: "")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = v.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
