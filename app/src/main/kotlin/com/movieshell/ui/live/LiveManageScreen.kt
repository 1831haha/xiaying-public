package com.movieshell.ui.live

import android.content.ContentResolver
import com.movieshell.ui.components.AppToast
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.movieshell.ui.components.TechDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.store.LiveChannel
import com.movieshell.data.store.LiveSource
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.store.LiveStore
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.StoragePaths
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
import kotlinx.coroutines.launch
import java.io.File

private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? {
    resolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return null
}

internal fun buildM3uExport(channels: List<LiveChannel>, globalEpg: String): String {
    fun attr(v: String): String = v.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()
    val sb = StringBuilder()
    val epg = globalEpg.trim()
    if (epg.isNotEmpty()) sb.append("#EXTM3U x-tvg-url=\"${attr(epg)}\"\n")
    else sb.append("#EXTM3U\n")
    channels.filter { it.url.isNotBlank() }.forEach { ch ->
        sb.append("#EXTINF:-1")
        if (ch.id.isNotBlank()) sb.append(" tvg-id=\"${attr(ch.id)}\"")
        if (ch.name.isNotBlank()) sb.append(" tvg-name=\"${attr(ch.name)}\"")
        if (ch.logo.isNotBlank()) sb.append(" tvg-logo=\"${attr(ch.logo)}\"")
        if (ch.group.isNotBlank()) sb.append(" group-title=\"${attr(ch.group)}\"")
        sb.append(",${ch.name.ifBlank { "未知频道" }.replace('\n', ' ').trim()}\n")
        sb.append("${ch.url.trim()}\n")
    }
    return sb.toString()
}


/** 直播订阅管理：M3U 订阅 URL / 本地 M3U 导入 / 手动添加频道 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveManageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by LiveStore.sources.collectAsState()
    // v1.4.13 第 3 步：套用候选要看频道 epg 属性与全局地址。
    val channels by LiveStore.channels.collectAsState()
    val globalEpg by LiveStore.globalEpg.collectAsState()
    var showAddSub by remember { mutableStateOf(false) }
    var showAddChannel by remember { mutableStateOf(false) }
    // v0.158.0：全局节目单兜底地址（用户自填，不内置源）
    var globalEpgText by remember { mutableStateOf(LiveStore.globalEpgUrl(context)) }
    var refreshing by remember { mutableStateOf(false) }
    // v1.3.33：M3U 导出——点“导出 M3U”时先拼文本，再弹系统创建文件选择器。
    var pendingM3uText by remember { mutableStateOf<String?>(null) }
    val exportM3u = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        val text = pendingM3uText
        pendingM3uText = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)
        if (!written) {
            AppToast.show(context, "导出失败：写文件失败", long = true)
            return@rememberLauncherForActivityResult
        }
        AppToast.show(context, "已导出 M3U", long = false)
        // 存好后再调系统分享（照 FilePreviewScreen.shareFile 的 ACTION_SEND 口径）。
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/x-mpegurl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "分享 M3U")) }
    }

    fun doExportM3u() {
        scope.launch {
            // 管理页进来时频道流可能还是空的（直播页没刷新过），导出前先刷一次订阅；
            // 已有频道时不重复刷，直接用当前列表。
            if (LiveStore.channels.value.isEmpty()) {
                refreshing = true
                runCatching { LiveStore.refresh(context) }
                refreshing = false
            }
            val list = LiveStore.channels.value
            if (list.isEmpty()) {
                AppToast.show(context, "没有可导出的频道，先添加订阅或手动频道", long = true)
                return@launch
            }
            pendingM3uText = buildM3uExport(list, LiveStore.globalEpgUrl(context))
            exportM3u.launch("movieshell-live.m3u")
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            refreshing = true
            val ok = try {
                val name = displayNameOf(context.contentResolver, uri)
                    ?.substringBeforeLast('.')?.ifBlank { null } ?: "本地M3U"
                val tmp = File(context.cacheDir, "m3u_import_${System.currentTimeMillis()}.m3u")
                val ins = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取文件")
                ins.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val done = LiveStore.importLocalFile(context, tmp, name)
                tmp.delete()
                done
            } catch (e: Exception) {
                LogBus.e("Live", "导入本地 M3U 失败: ${e.message}")
                false
            }
            AppToast.show(context, if (ok) "已导入本地 M3U" else "导入失败", long = false)
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "直播订阅管理",
                onBack = onBack,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            item {
                // v0.174.0：11 条一键导入入口已删（用户要求：本地导入本来就有，
                // 不需要这个定制入口）。已导入的订阅不动，仍在下面列表里逐条可删。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "添加订阅",
                        onClick = { showAddSub = true },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Primary,
                    )
                    // v0.174.0：三按钮统一四字，单行不换行。之前“导入本地 M3U”
                    // （7 字）和“手动加频道”（5 字）在三分之一宽的按钮里被
                    // AppButton 默认内边距挤成两行（截图里“手动加频/道”断行），
                    // “添加订阅”四字本来就是单行；改短后三格齐平，含义不变
                    // （点开仍是本地 M3U 文件导入 / 手动添加频道弹窗）。
                    AppButton(
                        text = "导入本地",
                        onClick = {
                            // v1.1.21：无授权时不打开文件选择器，先提示去授权。
                            if (!StoragePaths.hasAccess(context)) {
                                AppToast.show(context, "需要先授予存储权限，才能导入本地 M3U", long = true)
                            } else {
                                filePicker.launch("*/*")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                    AppButton(
                        text = "手动添加",
                        onClick = { showAddChannel = true },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                }
                // v1.3.33：导出 M3U——订阅解析后频道 + 手动频道，系统创建文件
                // 选择器保存，再调系统分享。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "导出 M3U",
                        onClick = { doExportM3u() },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                }
                if (refreshing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                }
            }
            item {
                // v0.158.0：全局节目单兜底——本源无节目单时用它查正在播/下一档；
                // 填 XMLTV 地址，.xml.gz 尽力解压，解析失败显示暂无节目数据
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "全局节目单（兜底）",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "本源无节目单时用它查正在播 / 下一档；不填则不兜底，不内置任何源",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TechTextField(
                            value = globalEpgText,
                            onValueChange = { globalEpgText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "https://…/xxx.xml",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = "保存",
                                onClick = {
                                    LiveStore.setGlobalEpgUrl(context, globalEpgText)
                                    AppToast.show(context, if (globalEpgText.isBlank()) "已清除全局节目单地址" else "已保存全局节目单地址", long = false)
                                },
                                variant = AppButtonVariant.Primary,
                            )
                            AppButton(
                                text = "清除",
                                onClick = {
                                    globalEpgText = ""
                                    LiveStore.setGlobalEpgUrl(context, "")
                                },
                                variant = AppButtonVariant.Secondary,
                            )
                        }
                    }
                }
            }
            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Tv,
                        title = "暂无订阅",
                        hint = "可添加 M3U 订阅地址（如公开 IPTV 源）",
                    )
                }
            }
            // v0.133.0：订阅源写侧按 url 查重，但持久化文件读侧无去重，渲染前兜底防同 url 撞 key
            items(sources.distinctBy { it.url }, key = { it.url }) { s ->
                // v1.4.12 第 2 步：订阅级节目单地址输入框（点保存即生效，下次切台用它）。
                var epgText by remember(s.url, s.epg) { mutableStateOf(s.epg) }
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    s.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    if (s.type == LiveSource.LOCAL_FILE) "本地文件" else s.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                LiveStore.removeSource(context, s.url)
                                // v1.4.2：走 appScope——删源后切走，刷新也要跑完
                                //（doRefresh 是 LiveScreen 的局部函数，这里直接走 appScope）。
                                SubSiteStore.appScope.launch { LiveStore.refresh(context) }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除订阅")
                            }
                        }
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "节目单地址",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TechTextField(
                                    value = epgText,
                                    onValueChange = { epgText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = "https://…/xxx.xml（空则用列表自带的）",
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    LiveStore.setSourceEpg(context, s.url, epgText)
                                    AppToast.show(
                                        context,
                                        if (epgText.isBlank()) "已清除该订阅的节目单地址" else "已保存该订阅的节目单地址",
                                        long = false,
                                    )
                                }) {
                                    Text("保存")
                                }
                            }
                        }
                        // v1.4.13 第 3 步：套用其它源下发的模板（点一下写进本订阅的
                        // EPG 字段，不搞临时覆盖；填完跟“保存”一样，下次切台用它）。
                        val applyTemplates = remember(sources, channels, globalEpg, s.epg) {
                            LiveStore.collectEpgTemplates(sources, channels, globalEpg, s.epg)
                        }
                        if (applyTemplates.isNotEmpty()) {
                            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                                Text(
                                    text = "套用其他源的模板",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                applyTemplates.forEach { t ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "来自${t.fromLabel}：${t.template}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        TextButton(onClick = {
                                            LiveStore.setSourceEpg(context, s.url, t.template)
                                            AppToast.show(context, "已套用「${t.fromLabel}」的模板", long = false)
                                        }) { Text("套用") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showAddSub) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        TechDialog(
            onDismissRequest = { showAddSub = false },
            title = "添加 M3U 订阅",
            confirmText = "确定",
            onConfirm = {
                val u = url.trim()
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    err = true
                    return@TechDialog
                }
                if (LiveStore.addSource(context, name.ifBlank { "M3U订阅" }, LiveSource.M3U_URL, u)) {
                    showAddSub = false
                    // v1.4.2：走 appScope——加源后切走，刷新也要跑完
                    //（doRefresh 是 LiveScreen 的局部函数，这里直接走 appScope）。
                    SubSiteStore.appScope.launch { LiveStore.refresh(context) }
                } else {
                    AppToast.show(context, "该订阅已存在", long = false)
                }
            },
        ) {
                Column {
                    Text(
                        text = "名称",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TechTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "订阅地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TechTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/xxx.m3u",
                    )
                    if (err) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入 http(s) 开头的地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
        }
    }

    if (showAddChannel) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var logo by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        TechDialog(
            onDismissRequest = { showAddChannel = false },
            title = "手动添加频道",
            confirmText = "确定",
            onConfirm = {
                val u = url.trim()
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    err = true
                    return@TechDialog
                }
                if (LiveStore.addManual(context, name.ifBlank { "未命名频道" }, u, logo)) {
                    showAddChannel = false
                    // v1.4.2：走 appScope——加台后切走，刷新也要跑完
                    //（doRefresh 是 LiveScreen 的局部函数，这里直接走 appScope）。
                    SubSiteStore.appScope.launch { LiveStore.refresh(context) }
                } else {
                    AppToast.show(context, "该频道已存在", long = false)
                }
            },
        ) {
                Column {
                    Text(
                        text = "频道名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TechTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "播放地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TechTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/xxx.m3u8",
                    )
                    if (err) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入 http(s) 开头的地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "台标地址（可选）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TechTextField(
                        value = logo, onValueChange = { logo = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
        }
    }
}
