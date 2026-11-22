package com.movieshell.ui.live

import com.movieshell.ui.components.sheetListScrollGuard
import com.movieshell.ui.components.V5Segmented
import com.movieshell.ui.theme.V5
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.store.LiveStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.TechTextField

/**
 * v0.176.0：DS 式直播设置弹层——对标 DS“直播设置”底部弹层：
 * 标题右上角刷新、两胶囊“按订阅筛选 / EPG 设置”、源单选列表
 * （手动/接口标签、“切回接口默认直播源”）。订阅的增删、本地导入、
 * 手动加频道仍走原“直播订阅管理”整页，本弹层只给入口，不重复造。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveSettingsSheet(
    sourceKeys: List<String>,
    selectedLiveSource: String?,
    globalEpg: String,
    // v1.4.12 第 2 步：跟随开关状态、当前生效的本源 EPG 地址、有节目单的源 key 集合。
    followSourceEpg: Boolean,
    currentEpgUrl: String,
    hasSelected: Boolean,
    epgSourceKeys: Set<String> = emptySet(),
    // v1.4.9：已隐藏的流 URL 与逐条恢复回调（恢复后调用方刷新拉回）。
    hiddenStreams: List<String> = emptyList(),
    onUnhideStream: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelectLiveSource: (String?) -> Unit,
    onManageClick: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var epgText by remember(globalEpg) { mutableStateOf(globalEpg) }
    // v0.294.2：弹窗内滚动不在顶时下滑只滚内容，不拖动/收起弹窗
    val sheetScroll = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = V5.CornerXl,
            topEnd = V5.CornerXl,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetListScrollGuard { sheetScroll.canScrollBackward }
                .verticalScroll(sheetScroll)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "直播设置",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新直播源")
                }
            }
            Text(
                text = "按订阅筛选 / 配置 EPG 节目单兜底源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // v0.292.0 V5：两页签改 M3 分段按钮——第一个「按订阅筛选」（接口/源切换，原主屏 chip 行的同一功能），第二个「EPG 设置」
            // v1.4.14：页签改名——它筛的是整个频道列表（按订阅），不是给当前台换同名源（那是右列），旧名容易混淆。
            V5Segmented(
                options = listOf("按订阅筛选", "EPG 设置"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLiveSource(null) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "切回接口默认直播源",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedLiveSource == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedLiveSource == null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (sourceKeys.isEmpty()) {
                    Text(
                        text = "暂无直播源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sourceKeys.forEach { key ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLiveSource(key) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedLiveSource == key,
                            onClick = { onSelectLiveSource(key) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = liveSourceLabel(key),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedLiveSource == key) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // v1.4.12 第 2 步：该源有自带节目单（频道属性或列表地址）时打“已配”，
                        // 只看有没有、不探活（没探活不能打 ✓）。
                        if (key in epgSourceKeys) {
                            Text(
                                text = "已配",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (key == "manual") "手动" else "接口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // v1.4.9：已隐藏的源（长按右列源行藏起来的单条流），
                // 可逐条恢复；恢复后刷新拉回。
                if (hiddenStreams.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "已隐藏的源",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "单条死流藏在这里，恢复后刷新拉回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    hiddenStreams.forEach { hiddenUrl ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = LiveStore.hostOfUrl(hiddenUrl).ifBlank { hiddenUrl },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = hiddenUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onUnhideStream(hiddenUrl) }) {
                                Text("恢复")
                            }
                        }
                    }
                }
            } else {
                // v1.4.12 第 2 步：跟随开关（默认开）＋当前生效的本源 EPG 地址。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "跟随电视源的 EPG",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "开：先用电视源自带的节目单，失效再回落全局；关：只用全局节目单",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(
                        checked = followSourceEpg,
                        onCheckedChange = { LiveStore.setFollowSourceEpg(context, it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "当前生效源的 EPG",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when {
                        !hasSelected -> "未选台"
                        !followSourceEpg -> "跟随已关，只用全局节目单"
                        currentEpgUrl.isNotBlank() -> currentEpgUrl
                        else -> "未配置"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "全局节目单（兜底）",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "本源无节目单时用它查正在播 / 下一档；不填则不兜底，不内置任何源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TechTextField(
                    value = epgText,
                    onValueChange = { epgText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://…/xxx.xml",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = "保存",
                        onClick = {
                            LiveStore.setGlobalEpgUrl(context, epgText)
                            AppToast.show(context, if (epgText.isBlank()) "已清除全局节目单地址" else "已保存全局节目单地址", long = false)
                        },
                        variant = AppButtonVariant.Primary,
                    )
                    AppButton(
                        text = "清除",
                        onClick = {
                            epgText = ""
                            LiveStore.setGlobalEpgUrl(context, "")
                        },
                        variant = AppButtonVariant.Secondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            AppButton(
                text = "直播订阅管理",
                onClick = onManageClick,
                variant = AppButtonVariant.Secondary,
            )
            Text(
                text = "添加/删除订阅、导入本地、手动加频道走整页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
