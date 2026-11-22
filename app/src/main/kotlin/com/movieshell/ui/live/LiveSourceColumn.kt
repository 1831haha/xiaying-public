package com.movieshell.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import com.movieshell.data.store.LiveChannel

/** 源编号：源1/源2…（下标从 0 起，显示从 1 起）。纯函数，可单测。 */
internal fun sourceLabel(index: Int): String = "源${index + 1}"

/**
 * 目标行不在可见范围时才跳（可见时不打断用户正在看的位置）。
 * firstVisible/lastVisible 为 -1 表示尚未布局，此时要跳。纯函数，可单测。
 */
internal fun shouldScrollToIndex(firstVisible: Int, lastVisible: Int, target: Int): Boolean {
    if (target < 0) return false
    if (lastVisible < firstVisible) return true
    return target < firstVisible || target > lastVisible
}

/**
 * v1.4.14 三列右列：只列当前台的同名源（源1/源2…），不是全部订阅源。
 * 点源直接切；失败源打红点；长按弹源详情；隐藏流不显示在右列。
 * 自动切源时高亮跟随实际播放源（读 selectedId 即可）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveSourceColumn(
    sources: List<LiveChannel>,
    selectedId: String?,
    failedIds: Set<String>,
    onSelectSource: (LiveChannel) -> Unit,
    onLongPressSource: (LiveChannel) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val v = MaterialTheme.colorScheme
    val selectedIndex = sources.indexOfFirst { it.id == selectedId }
    // 选中源变化时只在不可见时跳过去（自动切源后高亮跟随，不打断正在看的位置）。
    LaunchedEffect(selectedId) {
        if (selectedIndex >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: -1
            val last = visible.lastOrNull()?.index ?: -1
            if (shouldScrollToIndex(first, last, selectedIndex)) {
                listState.scrollToItem(selectedIndex)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        if (sources.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = if (selectedId == null) "先选频道" else "暂无同名源",
                    fontSize = 12.sp,
                    color = v.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        } else {
            items(sources.withIndex().toList(), key = { "${it.index}_${it.value.id}" }) { (idx, ch) ->
                val selected = ch.id == selectedId
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) v.primaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = { onSelectSource(ch) },
                            onLongClick = { onLongPressSource(ch) },
                        )
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = sourceLabel(idx),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) v.onPrimaryContainer else v.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ch.id in failedIds) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 4.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(v.error),
                        )
                    }
                }
            }
        }
    }
}
