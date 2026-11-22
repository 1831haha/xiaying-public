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
import androidx.compose.runtime.LaunchedEffect
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
 * v1.4.14 三列左列：分组列表（现有 groups 派生不动，我的收藏置顶）+
 * 顶部「更新时间」刷新入口。选中态走主题 token（primaryContainer），
 * 不硬编码参考图浅青。
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
    modifier: Modifier = Modifier,
) {
    val v = MaterialTheme.colorScheme
    // 选中分组变化时左列跟过去（跨台回落改分组时也要跟）。
    LaunchedEffect(selectedGroup) {
        val i = groupNames.indexOf(selectedGroup)
        if (i > 0) listState.scrollToItem(i)
    }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (g == "我的收藏") {
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
            }
        }
    }
}
