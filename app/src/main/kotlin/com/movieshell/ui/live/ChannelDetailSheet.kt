package com.movieshell.ui.live

import com.movieshell.ui.theme.V5
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.movieshell.data.source.EpgFetcher
import com.movieshell.data.store.LiveChannel
import com.movieshell.data.store.LiveStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import java.util.Date


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelDetailSheet(
    channel: LiveChannel,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var epg by remember(channel.id) { mutableStateOf<Pair<EpgFetcher.Program?, EpgFetcher.Program?>?>(null) }
    // v1.4.11 第 1 步：节目单来自全局兜底时打“全局”小标（仅兜底命中时）。
    var epgFromGlobal by remember(channel.id) { mutableStateOf(false) }
    var epgLoading by remember(channel.id) { mutableStateOf(false) }
    val globalEpgDetail by LiveStore.globalEpg.collectAsState()
    val followSourceEpgDetail by LiveStore.followSourceEpg.collectAsState()
    val liveSourcesDetail by LiveStore.sources.collectAsState()
    // v1.4.12 第 2 步：详情弹层也走四级解析后的本源地址。
    val sourceEpgUrlDetail = remember(channel.id, liveSourcesDetail, followSourceEpgDetail) {
        LiveStore.resolveSourceEpgUrl(channel, liveSourcesDetail, followSourceEpgDetail)
    }

    LaunchedEffect(channel.id, globalEpgDetail, followSourceEpgDetail, sourceEpgUrlDetail) {
        if (sourceEpgUrlDetail.isNotBlank() || globalEpgDetail.isNotBlank()) {
            epgLoading = true
            val result = EpgFetcher.nowAndNextWithFallback(sourceEpgUrlDetail, globalEpgDetail, channel.name)
            epg = result.programs.takeIf { it.first != null || it.second != null }
            epgFromGlobal = result.layer == EpgFetcher.EpgLayer.GLOBAL
            epgLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = V5.CornerMd,
            topEnd = V5.CornerMd,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    // v1.4.8 A3(a)：与 ChannelRow 同口径，加载中/失败落 Tv 图标块。
                    // 画笔同 ChannelRow：带 size(Size.ORIGINAL) 的 ImageRequest，
                    // 否则 coil 不发请求、state 永远到不了 Success（1.3.2 教训）。
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
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Filled.Tv, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        (channel.group.ifBlank { "未分组" }) + " · " +
                            when {
                                channel.sourceKey.startsWith("manual") -> "手动添加"
                                channel.sourceKey.startsWith("m3u:") -> "订阅：" + channel.sourceKey.removePrefix("m3u:")
                                else -> "本地源"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "收藏",
                        tint = if (channel.favorite) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // EPG：当前 + 后续（尽力展示）
            if (sourceEpgUrlDetail.isNotBlank() || globalEpgDetail.isNotBlank()) {
                Text(
                    if (epgFromGlobal) "节目单 · 全局" else "节目单",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    epgLoading -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    epg == null || (epg?.first == null && epg?.second == null) ->
                        Text("暂无节目数据", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        epg?.first?.let { p ->
                            Text(
                                "正在播：${p.title}（${timeFmt.format(Date(p.startMs))}–${timeFmt.format(Date(p.stopMs))}）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        epg?.second?.let { p ->
                            Text(
                                "接下来：${p.title}（${timeFmt.format(Date(p.startMs))}）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (epg?.first != null && epg?.second == null) {
                            Text(
                                "暂无下一档信息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "播放",
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Primary,
                )
                AppButton(
                    text = "复制地址",
                    onClick = {
                        clipboard.setText(AnnotatedString(channel.url))
                        AppToast.show(context, "播放地址已复制", long = false)
                    },
                    variant = AppButtonVariant.Secondary,
                )
                if (channel.manual) {
                    AppButton(
                        text = "删除",
                        onClick = onDelete,
                        variant = AppButtonVariant.Ghost,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
