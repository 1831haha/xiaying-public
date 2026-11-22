package com.movieshell.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v1.4.14 三列左列：顶部「更新时间」刷新入口＋分组列表。
 * v1.4.17：栏目分类按钮按 DS 原样——未选中白底（主题 surface）＋细边
 * 描边，选中深绿实心（主题 primary）＋白字；单行名，不带台数、不带星标；
 * 行间留 6dp 间隔。颜色一律走主题 token，不硬编码。
 * 自动滚动继续没有——用户自己滑，不需要点击跟随变动。
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
    val shape = RoundedCornerShape(8.dp)
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        // 更新时间：点一下执行现有频道刷新；刷新中转圈，第二行小字是上次刷新时间。
        // 容器与分类按钮同款（白底＋描边），只是多一行小字。
        item(key = "refresh") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(shape)
                    .background(v.surface)
                    .border(1.dp, v.outlineVariant, shape)
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "更新时间",
                        fontSize = 12.sp,
                        color = v.onSurface,
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
            val borderModifier =
                if (selected) Modifier else Modifier.border(1.dp, v.outlineVariant, shape)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(shape)
                    .background(if (selected) v.primary else v.surface)
                    .then(borderModifier)
                    .clickable { onSelectGroup(g) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = g,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) v.onPrimary else v.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
