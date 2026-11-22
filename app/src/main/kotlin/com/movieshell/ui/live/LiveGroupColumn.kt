package com.movieshell.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v1.4.14 三列左列：顶部「更新时间」刷新入口＋分组列表。
 * 选中态走主题 token（primaryContainer），不硬编码参考图浅青。
 * v1.4.16：只剩“全部频道／我的收藏”两项，各带去重后台数；自动滚动整段
 * 删掉——用户自己滑，不需要点击跟随变动。
 * v1.4.17：恢复分组——左列重回 全部频道／我的收藏／真实分组（按 M3U
 * group-title 首次出现顺序）／未分组，各带去重后台数；自动滚动继续没有。
 */
@Composable
internal fun LiveGroupColumn(
    groupNames: List<String>,
    selectedGroup: String?,
    onSelectGroup: (String) -> Unit,
    lastRefreshText: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    listState: LazyListState,
    counts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val v = MaterialTheme.colorScheme
    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        // 更新时间：点一下执行现有频道刷新；刷新中转圈，第二行小字是上次刷新时间。
        item(key = "refresh") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "更新时间",
                        fontSize = 12.sp,
                        color = v.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = v.primary,
                        )
                    } else {
                        Text(
                            text = lastRefreshText,
                            fontSize = 9.sp,
                            color = v.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        items(groupNames, key = { "g:$it" }) { g ->
            val selected = g == selectedGroup
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) v.primaryContainer else Color.Transparent)
                    .clickable { onSelectGroup(g) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (g == LIVE_TAB_FAVORITES) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = if (selected) v.onPrimaryContainer else v.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            text = g,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) v.onPrimaryContainer else v.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        text = "${counts[g] ?: 0} 个频道",
                        fontSize = 9.sp,
                        color = (if (selected) v.onPrimaryContainer else v.onSurfaceVariant).copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
