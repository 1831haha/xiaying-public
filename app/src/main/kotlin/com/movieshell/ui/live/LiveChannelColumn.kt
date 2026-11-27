package com.movieshell.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.movieshell.data.store.LiveChannel
import com.movieshell.ui.components.AppSearchBar
import com.movieshell.ui.components.V5Tag
import com.movieshell.ui.components.V5TagKind

/**
 * v1.4.14 三列中列：顶部当前台条（台名 + 搜索 + 齿轮 + 爱心）+ 频道行。
 * 点行直接播；行右端 + 只收藏不播放；行内不显示 EPG 摘要（只在节目卡）。
 * 搜索默认只滤当前分组（对 groupChannelsDistinct 滤，不动左列分组）。
 */
@Composable
internal fun LiveChannelColumn(
    channels: List<LiveChannel>,
    selectedId: String?,
    currentChannel: LiveChannel?,
    isFavorite: Boolean,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    showSearch: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    refreshing: Boolean,
    groupKey: String?,
    onSelectChannel: (LiveChannel) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onLongClickChannel: (LiveChannel) -> Unit,
    listState: LazyListState,
    // v1.4.16：中列副行“共 N 个源”的计数与主行显示名（短名优先），
    // 均按归一化 key 索引，由 LiveScreen.kt 算好传进来。
    sourceCounts: Map<String, Int>,
    displayNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    // 分组切换回顶（首帧也回顶，无害）。
    LaunchedEffect(groupKey) {
        listState.scrollToItem(0)
    }
    Column(modifier = modifier) {
        CurrentChannelBar(
            currentChannel = currentChannel,
            isFavorite = isFavorite,
            onSearchClick = onSearchClick,
            onSettingsClick = onSettingsClick,
            onFavoriteClick = onFavoriteClick,
        )
        if (showSearch) {
            // v0.294.6：统一 AppSearchBar；展开即聚焦
            AppSearchBar(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                placeholder = "搜索频道",
                autoFocus = true,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
        ) {
            if (channels.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (refreshing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在拉取直播源…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            Text(
                                text = if (query.isNotBlank()) "没有匹配的频道" else "暂无频道",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                // v0.133.0：频道 id 由 sourceKey 与 url 拼接，同源重复条目或同名订阅可撞，加下标复合
                items(
                    items = channels.withIndex().toList(),
                    key = { "${it.index}_${it.value.id}" },
                ) { (idx, ch) ->
                    ChannelRow(
                        index = idx + 1,
                        channel = ch,
                        displayName = displayNames[normalizeChannelKey(ch.name)] ?: ch.name,
                        sourceCount = sourceCounts[normalizeChannelKey(ch.name)] ?: 1,
                        isPlaying = isSameChannel(selectedId, ch.id),
                        onClick = { onSelectChannel(ch) },
                        onLongClick = { onLongClickChannel(ch) },
                        onFavorite = { onToggleFavorite(ch) },
                    )
                }
            }
        }
    }
}

/** 中列顶部当前台条：台名 + 搜索 + 齿轮 + 爱心（从节目卡移过来）。 */
@Composable
private fun CurrentChannelBar(
    currentChannel: LiveChannel?,
    isFavorite: Boolean,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFavoriteClick: () -> Unit,
) {
    val v = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(v.primaryContainer)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = currentChannel?.name ?: "未选台",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = v.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearchClick, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "搜索频道",
                tint = v.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "直播设置",
                tint = v.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onFavoriteClick, modifier = Modifier.size(36.dp)) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                tint = v.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * v1.4.14 三列频道行（紧凑版，原 ChannelCell 72dp → 48dp）：
 * 序号 + 28dp 台标 + 台名 13sp + 第二行 11sp（共 N 个源）；正在播行右端播放图标，
 * 右端 + 只收藏不播放（已收藏变实心爱心）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    index: Int,
    channel: LiveChannel,
    displayName: String,
    sourceCount: Int,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val v = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPlaying) v.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString(),
            fontSize = 11.sp,
            color = v.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isPlaying) v.primaryContainer else v.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            // v1.4.8 A3(a)：台标只显示不读像素；加载中/失败先显示首字块，
            // 图到后再换图片，不留空白块。
            // 1.3.2 的教训：只读 state 的画笔必须给 ImageRequest 显式尺寸，
            // 否则 coil 不发请求、state 永远到不了 Success。
            val logoRequest = remember(channel.logo) {
                ImageRequest.Builder(context)
                    .data(channel.logo)
                    .size(Size.ORIGINAL)
                    .build()
            }
            val logoPainter = rememberAsyncImagePainter(model = logoRequest)
            val logoReady = channel.logo.isNotBlank() &&
                logoPainter.state is AsyncImagePainter.State.Success
            if (logoReady) {
                Image(
                    painter = logoPainter,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = displayName.replace(Regex("[\\s\\-]"), "").take(2).ifBlank { "%02d".format(index) },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) v.onPrimaryContainer else v.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontSize = 13.sp,
                fontWeight = if (isPlaying) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) v.onSecondaryContainer else v.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // v1.4.16：副行不再写单个 channel.group——一行横跨多个订阅，
                    // 写其中一个会骗人；改写该台的源总数。
                    text = "共 $sourceCount 个源",
                    fontSize = 11.sp,
                    color = v.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.stale) {
                    Spacer(Modifier.width(6.dp))
                    V5Tag("过期", V5TagKind.Live)
                }
            }
        }
        if (isPlaying) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "正在播放",
                tint = v.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        // v1.4.14 审稿 S1：点按区保持 48dp（与旧 ChannelCell 的 IconButton 同口径），
        // 28dp 视觉、点按区缩水容易误触成播放。
        IconButton(
            onClick = onFavorite,
            modifier = Modifier.size(48.dp),
        ) {
            if (channel.favorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "取消收藏",
                    tint = v.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "+",
                    fontSize = 18.sp,
                    color = v.onSurfaceVariant,
                )
            }
        }
    }
}
