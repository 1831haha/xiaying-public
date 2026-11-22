package com.movieshell.ui.live



import androidx.compose.foundation.lazy.rememberLazyListState


import com.movieshell.ui.theme.V5

import com.movieshell.ui.components.dockClearance
import android.os.SystemClock
import com.movieshell.ui.components.AppToast
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.PlayArrow
import com.movieshell.data.store.PlayerSettings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.source.EpgFetcher
import com.movieshell.data.store.LiveChannel
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.store.LiveStore
import com.movieshell.core.log.LogBus
import com.movieshell.data.store.TrashStore
import com.movieshell.player.EngineHost
import com.movieshell.player.PlaySession
import com.movieshell.player.PlaybackDiagnostic
import com.movieshell.player.PlayerScreen
import org.json.JSONObject
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

// v0.160.0：直播选中状态放内存单例，不放 remember——切 tab 离页重组、聚合刷新
// 都不会丢选中频道和按台名选的源；聚合刷新本身只在 selected==null 时才取首台，
// 所以 TRAE 说的“聚合一刷新就重置”在原代码里并不成立，真正丢状态的是离页重组
// v1.3.33：四项选中态再落盘（live_prefs 下 live_sel_*），进程被杀重进也能
// 恢复；恢复时按当前频道列表校验，不存在回落首个有效频道（见 LaunchedEffect
// (channels) 与 (groupNames) 的兜底）。userStarted 继续只留进程内——持久化
// 它会绕过“直播自动播放”设置，点过播放的记忆不能跨进程。
private object LiveSelection {
    var channelId: String? = null
    val sourceByName = mutableMapOf<String, String>()
    // v0.171.0：分组与源行选中态也进单例，切 tab 离页重组不再回首组/全部
    var selectedGroup: String? = null
    var selectedLiveSource: String? = null
    // v1.4.14：左列“更新时间”入口的上次刷新时间（毫秒，0=从未刷新）。
    var lastRefreshMs: Long = 0
    // v0.294.5：本次进程里用户是否已主动点过播放（点过就不再受“直播自动播放”设置拦）
    var userStarted = false
    /** 本进程是否已从磁盘恢复过选中态（只恢复一次，之后以内存为准）。 */
    var hydrated = false
}

private const val LIVE_SEL_CHANNEL = "live_sel_channel_id"
private const val LIVE_SEL_SOURCE_BY_NAME = "live_sel_source_by_name"
private const val LIVE_SEL_GROUP = "live_sel_group"
private const val LIVE_SEL_LIVE_SOURCE = "live_sel_live_source"
private const val LIVE_SEL_LAST_REFRESH = "live_sel_last_refresh_ms"

/** 进程启动后第一次进直播页时，把磁盘上的选中态读进单例（只读一次）。 */
private fun loadLiveSelection(context: Context) {
    if (LiveSelection.hydrated) return
    LiveSelection.hydrated = true
    val prefs = context.getSharedPreferences("live_prefs", Context.MODE_PRIVATE)
    LiveSelection.channelId = prefs.getString(LIVE_SEL_CHANNEL, null)
    LiveSelection.selectedGroup = prefs.getString(LIVE_SEL_GROUP, null)
    LiveSelection.selectedLiveSource = prefs.getString(LIVE_SEL_LIVE_SOURCE, null)
    LiveSelection.lastRefreshMs = prefs.getLong(LIVE_SEL_LAST_REFRESH, 0L)
    val raw = prefs.getString(LIVE_SEL_SOURCE_BY_NAME, null)
    if (!raw.isNullOrBlank()) {
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { k ->
                obj.optString(k).takeIf { it.isNotBlank() }?.let { LiveSelection.sourceByName[k] = it }
            }
        }
    }
}

/** 把单例里的选中态写盘（apply 异步落盘；userStarted 故意不写）。 */
private fun saveLiveSelection(context: Context) {
    val obj = JSONObject()
    LiveSelection.sourceByName.forEach { (k, v) -> obj.put(k, v) }
    context.getSharedPreferences("live_prefs", Context.MODE_PRIVATE).edit()
        .putString(LIVE_SEL_CHANNEL, LiveSelection.channelId)
        .putString(LIVE_SEL_SOURCE_BY_NAME, obj.toString())
        .putString(LIVE_SEL_GROUP, LiveSelection.selectedGroup)
        .putString(LIVE_SEL_LIVE_SOURCE, LiveSelection.selectedLiveSource)
        .putLong(LIVE_SEL_LAST_REFRESH, LiveSelection.lastRefreshMs)
        .apply()
}

/** 直播 Tab：DsPlayer 式布局（v0.80.0 重做）——顶部 16:9 播放器 + 节目信息卡 + 三列（分组/频道/源） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(
    onFullscreenChannel: (LiveChannel) -> Unit,
    onManageClick: () -> Unit,
    // v1.1.31：libmpv 未安装时跳插件市场装插件
    onOpenPluginCenter: () -> Unit = {},
) {
    val context = LocalContext.current
    val channels by LiveStore.channels.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var detailChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // v0.176.0：顶栏已去掉，设置入口改到节目卡齿轮 → DS 式直播设置弹层
    var showLiveSettings by remember { mutableStateOf(false) }
    // v0.79.0：顶部播放器当前频道；进场自动选中第一个
    // v0.160.0：初值从单例恢复，离页回来也不回首台
    var selected by remember { mutableStateOf<LiveChannel?>(null) }
    // v0.277.0（T10）：同台再点重拉计数——PlayerScreen 的起播只跟
    // url 变化，selected 不变时重组不会重拉；同台卡住/失败后再点，
    // 靠这个计数 key 重建播放器、重新走一遍起播链路。
    var livePlayNonce by remember { mutableStateOf(0) }
    // v0.277.0（T10）：最近一次切台时间——同台 3 秒内连点仍按
    // v0.160.0 不重建（防起播瞬间连点把刚起的流又掐掉）。
    var lastPlayStartMs by remember { mutableLongStateOf(0L) }
    // v0.80.0：左侧分组选中态
    // v0.171.0：初值从单例恢复，切 tab 回来不回首组
    // v1.3.33：进程首次进页先从磁盘恢复单例（loadLiveSelection 只读一次），
    // 下面三个初值拿到的就是进程被杀前选的台/分组/源行。
    loadLiveSelection(context)
    // v1.4.14：上次刷新时间（左列“更新时间”第二行小字；只在刷新成功时更新）——
    // 声明必须放在 loadLiveSelection 之后，否则初值读到的是 hydration 之前的 0，
    // 首进恒显示「未刷新」。
    var lastRefreshMs by remember { mutableStateOf(LiveSelection.lastRefreshMs) }
    var selectedGroup by remember { mutableStateOf(LiveSelection.selectedGroup) }
    // v0.158.0：直播页内换源（null=全部，DsPlayer 切换直播源对标）；
    // 直播失败已试过的同名源 id（自动切源防循环，手动点频道/源时清空）
    // v0.171.0：初值从单例恢复，切 tab 回来不回"全部"
    var selectedLiveSource by remember { mutableStateOf(LiveSelection.selectedLiveSource) }
    var liveTriedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.4.9：本会话失败过的源 id（源按钮打红点）；成功播出后清掉该条，不持久化。
    var liveFailedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.4.9：长按源 N 弹出的源详情（null=不弹）。
    var sourceDetail by remember { mutableStateOf<LiveChannel?>(null) }
    // v1.4.9：设置弹层里的已隐藏流 URL（打开设置时读一次，恢复时同步减）。
    var hiddenStreams by remember { mutableStateOf<List<String>>(emptyList()) }
    // v0.159.0：按台名记住上次选的源（源1/源2）——中列点台不再永远取同名第一条（源1）
    var liveSourceByName by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val globalEpg by LiveStore.globalEpg.collectAsState()
    // v1.4.12 第 2 步：跟随电视源的 EPG 开关＋订阅列表（订阅级 EPG 用）。
    val followSourceEpg by LiveStore.followSourceEpg.collectAsState()
    val liveSources by LiveStore.sources.collectAsState()

    // v0.219.0：切 tab 真离开时停播的开关——照详情页 goingFullscreen 的套路：
    // 跳全屏独立播放页／直播管理页是页内跳转（会话延续），先置 true，
    // dispose 时跳过停播；其他离开路径（切 tab 等）为 false，dispose 时
    // 停引擎、清会话、关可见标记。否则切到小说页后直播还在后台响。
    // plain remember 不随 saveState 恢复，回页重组自动变回 false，不用手动复位。
    var goingToLivePage by remember { mutableStateOf(false) }
    // v0.96.2：横屏全屏播放（手动横屏按钮 / 物理横屏）且有在播频道时，播放器占满全屏
    val fullscreenPlayer = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        selected != null
    // v0.96.2：离页时恢复系统方向——手动横屏后切 tab / 跳独立播放页，不能把整个 App 锁在横屏
    // v0.219.0：同 effect 内补真离开停播（见 goingToLivePage 注释）。
    DisposableEffect(Unit) {
        onDispose {
            if (!goingToLivePage) {
                EngineHost.stop()
                // v1.4.7：真离开直播页才收尾诊断会话——跳全屏独立页时会话延续，
                // 不走这里；切 tab 等真离开才收尾。
                PlaybackDiagnostic.endSession()
                PlaySession.clear()
                PlaySession.playerVisible = false
            }
            (context as? Activity)?.let {
                if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    // v1.4.2：直播刷新改走 SubSiteStore.appScope——刷新结果进 LiveStore 单例，
    // 用户切走也应跑完；原来用组合 scope，切 tab 即取消，runCatching 还把取消
    // 当失败弹 Toast（1.4.1 实证：图 2 那条英文提示就是这么飘到音乐页上的）。
    // 取消异常重抛，复位放 finally。
    fun doRefresh() {
        if (refreshing) return
        refreshing = true
        SubSiteStore.appScope.launch {
            try {
                runCatching { LiveStore.refresh(context) }
                    // v0.107.0 P0-9：订阅 0 频道不再静默，给可见提示
                    .onSuccess { empties ->
                        // v1.4.14：刷新成功才记时间（失败不记，左列第二行小字如实显示）。
                        lastRefreshMs = System.currentTimeMillis()
                        LiveSelection.lastRefreshMs = lastRefreshMs
                        saveLiveSelection(context)
                        if (empties.isNotEmpty()) {
                            AppToast.show(context, "以下订阅未解析出频道：${empties.joinToString("、")}", long = true)
                        }
                    }
                    .onFailure {
                        if (it is CancellationException) throw it
                        AppToast.show(context, "刷新失败：${it.message}", long = true)
                    }
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (channels.isEmpty()) doRefresh()
    }
    // 频道列表就绪后自动选中第一个（无选中时）；已有选中时按 id 换成刷新后的新对象，
    // 不回首台——聚合刷新不重置选中，离页重组靠单例恢复
    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) {
            val restored = LiveSelection.channelId?.let { id ->
                channels.firstOrNull { it.id == id }
            }
            if (selected == null) {
                selected = restored ?: channels.first()
                LiveSelection.channelId = selected?.id
                // v1.3.33：磁盘恢复的台已不在列表里时回落首台，并把回落写盘，
                // 下次不再拿着失效 id 恢复；源行同理，失效的源行清掉。
                if (restored == null) saveLiveSelection(context)
            } else if (channels.none { it.id == selected?.id }) {
                selected = restored ?: channels.first()
            } else {
                // v0.217.0（F19）：选中台还在列表里时，换成刷新后的新对象——
                // 否则顶部卡拿着旧拷贝，收藏／过期状态刷新后不同步。
                channels.firstOrNull { it.id == selected?.id }?.let { selected = it }
            }
            if (LiveSelection.selectedLiveSource != null &&
                channels.none { it.sourceKey == LiveSelection.selectedLiveSource }
            ) {
                LiveSelection.selectedLiveSource = null
                selectedLiveSource = null
                saveLiveSelection(context)
            }
            // 按台名选的源从单例恢复一次（切 tab 回来不丢源2）
            if (liveSourceByName.isEmpty() && LiveSelection.sourceByName.isNotEmpty()) {
                liveSourceByName = LiveSelection.sourceByName.toMap()
            }
        }
    }

    // v0.294.5：直播自动播放设置——不允许自动起播时，自动选中的频道先不起播，显示“点击播放”
    var autoplayHeld by remember {
        mutableStateOf(!LiveSelection.userStarted && !PlayerSettings.liveAutoplayAllowed(context))
    }

    fun playChannel(ch: LiveChannel) {
        autoplayHeld = false
        LiveSelection.userStarted = true
        // v0.277.0（T10）：同台再点不再一律 return——原来 selected
        // 不变、PlayerScreen 的 url 也不变，卡住/最终失败后用户再点
        // 同台完全没反应，只能切别的台再切回来。改：正在播就照旧
        // 不动（保 v0.160.0 的连点不重建）；不在播且离上次切台已
        // 超过 3 秒，就清已试源、递增重拉计数重建播放器再拉一次。
        if (selected?.id == ch.id) {
            if (EngineHost.isPlayingNow()) return
            if (SystemClock.elapsedRealtime() - lastPlayStartMs < 3000L) return
            liveTriedIds = emptySet()
            LogBus.i("Live", "同台再点重拉：${ch.name}")
            AppToast.show(context, "正在重新拉流", long = false)
            livePlayNonce++
            return
        }
        liveTriedIds = emptySet()
        // v1.4.16：按台名记源的 key 改归一化 key——“CCTV-1HD”与“CCTV1”是同一台，
        // 记一次就够；旧持久化键是精确台名，查不到自然失效、回落首条。
        val nameKey = normalizeChannelKey(ch.name)
        liveSourceByName = liveSourceByName + (nameKey to ch.id)
        LiveSelection.sourceByName[nameKey] = ch.id
        LiveSelection.channelId = ch.id
        saveLiveSelection(context)
        selected = ch
    }

    // v1.4.9：按 URL 隐藏一条流（同名源可删单条）：写盘后内存与缓存里已清掉，
    // 刷新不复活。被藏的恰好是当前选中源时，回落到同名第一条（核对
    // groupChannelsDistinct 的回落口径：上次选的源 id 失效即取第一条），
    // 同名全藏完了再回落到剩余首台；选中态与单例同步写盘。
    fun hideSourceByUrl(url: String) {
        val wasSelected = selected?.url == url
        val fallback = if (wasSelected) {
            // v1.4.16：同名按归一化 key 认（“CCTV-1HD”与“CCTV1”是同一台）。
            val nameKey = normalizeChannelKey(selected?.name.orEmpty())
            channels.filter { normalizeChannelKey(it.name) == nameKey }.firstOrNull { it.url != url }
                ?: channels.firstOrNull { it.url != url }
        } else {
            null
        }
        if (!LiveStore.hideStream(context, url)) {
            AppToast.show(context, "该源已隐藏", long = false)
            return
        }
        // v1.4.9 审稿：设置弹层开着时隐藏列表也要跟上，不用关掉重开。
        hiddenStreams = LiveStore.hiddenStreamUrls(context)
        if (wasSelected) {
            if (fallback != null) {
                // v1.4.9 DeepSeek 审稿：跨台回落时清掉旧台名的源映射，
                // 否则按台名记源会指到已隐藏的流。
                val oldName = selected?.name
                if (oldName != null && normalizeChannelKey(fallback.name) != normalizeChannelKey(oldName)) {
                    liveSourceByName = liveSourceByName - normalizeChannelKey(oldName) - oldName
                    LiveSelection.sourceByName.remove(normalizeChannelKey(oldName))
                    LiveSelection.sourceByName.remove(oldName)
                }
                val fallbackKey = normalizeChannelKey(fallback.name)
                liveSourceByName = liveSourceByName + (fallbackKey to fallback.id)
                LiveSelection.sourceByName[fallbackKey] = fallback.id
                LiveSelection.channelId = fallback.id
                // v1.4.17：回落规则推广为通用规则——回落的台不在当前左列范围
                // （tab／分组）里才切到全部频道，否则不动；收藏 tab 旧行为
                // （回落到未收藏的台才切）是这条规则的特例。
                if (shouldFallbackToAll(selectedGroup, fallback)) {
                    selectedGroup = LIVE_TAB_ALL
                    LiveSelection.selectedGroup = LIVE_TAB_ALL
                }
                saveLiveSelection(context)
                selected = fallback
            } else {
                selected = null
                LiveSelection.channelId = null
                saveLiveSelection(context)
            }
            liveTriedIds = emptySet()
        }
        AppToast.show(context, "已隐藏该源", long = false)
    }

    // v0.277.0（T10）：切台时间跟 selected 走——正常切台、自动切
    // 同名源、初始选中都经过这里，同台再点的 3 秒连点守卫才准。
    LaunchedEffect(selected) {
        if (selected != null) lastPlayStartMs = SystemClock.elapsedRealtime()
    }

    // v1.4.14 三列：搜索只滤当前 tab（在中列里对 groupChannelsDistinct 滤），
    // 不再参与整个频道列表的过滤——左列 tab 搜的时候不能变。
    val filtered = remember(channels, selectedLiveSource) {
        channels.filter { selectedLiveSource == null || it.sourceKey == selectedLiveSource }
    }
    val sourceKeys = remember(channels) { channels.map { it.sourceKey }.distinct() }
    // v0.158.0：换源后自动播该源第一个频道（DsPlayer 切源即播该源）；全部则不动当前播放
    LaunchedEffect(selectedLiveSource) {
        if (selectedLiveSource != null) {
            val first = filtered.firstOrNull()
            if (first != null && selected?.sourceKey != selectedLiveSource) {
                liveTriedIds = emptySet()
                LiveSelection.channelId = first.id
                saveLiveSelection(context)
                selected = first
            }
        }
    }
    // v1.4.17：左列恢复分组——全部频道（默认）／我的收藏（仅有收藏时）／
    // 真实分组按 group-title 首次出现顺序（M3U 自身顺序，与 DS 左列一致）／
    // 未分组（仅有无分组频道时，排最后）。运营商名分组不再过滤、原样呈现
    // （CCSH 的省名也是合法分类，与运营商名分不开，硬滤会误伤）。
    val groupNames = remember(filtered) { liveGroupNames(filtered) }
    // 选中态：保留旧选（若仍在），否则取首项——1.4.17 恢复分组后，旧持久化
    // 的运营商分组名若仍在列表里即恢复选中；不在时靠这里重置兜底并写回。
    LaunchedEffect(groupNames) {
        if (selectedGroup !in groupNames) {
            selectedGroup = groupNames.firstOrNull()
            // v1.3.33：分组回落同样写回单例与磁盘，别下次又拿失效分组恢复。
            LiveSelection.selectedGroup = selectedGroup
            saveLiveSelection(context)
        }
    }
    // v1.4.17：tabChannels 改名 scopeChannels——中列先取当前左列选中项
    // 的范围再全局归一化去重；收藏 tab 只对收藏子集去重，代表行必是已收
    // 藏的变体；分组只对该组去重；全部频道对 filtered 全量去重。
    // selectedGroup 首帧为 null 时走全部频道，不闪退。
    val scopeChannels = remember(filtered, selectedGroup) {
        liveScopeChannels(filtered, selectedGroup)
    }
    // v0.159.0：中列按台名去重，每台只显示一条——显示的是该台上次选的源，
    // 没选过才取第一条；否则中列下面会再出现一遍同名台，点它又回源1。
    // v1.4.16：去重改全局归一化——“CCTV-1HD”与“CCTV1”是同一台，
    // 各订阅的版本全部进右列当源，不再按分组拆行。
    val groupChannelsDistinct = remember(scopeChannels, liveSourceByName) {
        distinctChannelsGlobally(scopeChannels, liveSourceByName)
    }
    // v1.4.14 三列：搜索只滤当前 tab 内的台（中列搜索框），左列不动；
    // v1.4.16：同时匹配显示名与归一化 key，搜“CCTV1”能找到“CCTV-1HD”的行。
    val displayChannels = remember(groupChannelsDistinct, query) {
        if (query.isBlank()) groupChannelsDistinct
        else {
            val q = normalizeChannelKey(query)
            groupChannelsDistinct.filter {
                it.name.contains(query, ignoreCase = true) ||
                    normalizeChannelKey(it.name).contains(q)
            }
        }
    }
    // v1.4.16：右列取数范围与中列副行共用——收藏 tab 下取该频道全部源
    // （不受订阅筛选影响），其余取 filtered 范围内的同名源。
    val sourceScope = remember(channels, filtered, selectedGroup) {
        if (selectedGroup == LIVE_TAB_FAVORITES) channels else filtered
    }
    // 同名多线路 = 源1..N（右列；v0.160.0 起放播放器下方，1.4.14 回右列）；
    // v1.4.16：同名按归一化 key 认。
    val sources = remember(sourceScope, selected) {
        val key = selected?.name?.takeIf { it.isNotBlank() }?.let { normalizeChannelKey(it) }
        if (key == null) emptyList()
        else sourceScope.filter { normalizeChannelKey(it.name) == key }
    }
    // v1.4.16：中列副行“共 N 个源”的计数，与右列取数范围一致。
    val sourceCounts = remember(sourceScope) {
        sourceScope.groupBy { normalizeChannelKey(it.name) }.mapValues { it.value.size }
    }
    // v1.4.16：中列主行显示名——短名优先（CCTV1 优于 CCTV-1HD），同级取首次出现。
    val displayNames = remember(sourceScope) {
        sourceScope.groupBy { normalizeChannelKey(it.name) }.mapValues { (key, list) ->
            displayNameForKey(key, list)
        }
    }
    // v1.4.17：左列逐项的台数（去重后，与中列行数一致）——按 groupNames
    // 逐项算，全部频道／我的收藏／各分组／未分组都在内。
    val tabCounts = remember(filtered, groupNames) {
        liveGroupCounts(filtered, groupNames)
    }

    // EPG：当前节目 + 下一档（节目信息卡用）；
    // v1.4.11 第 1 步：本源失效自动回落全局（用户自填，不内置源），仍无则 null；
    // 兜底命中时节目卡打“全局”小标。
    // v1.4.12 第 2 步：本源地址先按四级解析（频道属性＞订阅＞列表＞全局前三级），
    // 再进回落；开关/订阅改了要重拉，故都进 effect key。
    val sourceEpgUrl = remember(selected, liveSources, followSourceEpg) {
        selected?.let { LiveStore.resolveSourceEpgUrl(it, liveSources, followSourceEpg) }.orEmpty()
    }
    // v1.4.12 第 2 步：有节目单的源 key 集合（频道属性或列表地址任一非空即算）。
    // 审稿 S1：只填订阅级 EPG 的源也要算，否则管理页显示已生效、源行却没“已配”，前后矛盾。
    val epgSourceKeys = remember(channels, liveSources) {
        (channels.filter { it.epgUrl.isNotBlank() || it.playlistEpgUrl.isNotBlank() }
            .map { it.sourceKey }
            + liveSources.filter { it.epg.isNotBlank() }.map { "m3u:${it.name}" }).toSet()
    }
    var epg by remember { mutableStateOf<Pair<EpgFetcher.Program?, EpgFetcher.Program?>?>(null) }
    var epgFromGlobal by remember { mutableStateOf(false) }
    LaunchedEffect(selected, globalEpg, followSourceEpg, sourceEpgUrl) {
        val ch = selected
        if (ch == null) {
            epg = null
            epgFromGlobal = false
        } else {
            val result = EpgFetcher.nowAndNextWithFallback(sourceEpgUrl, globalEpg, ch.name)
            epg = result.programs.takeIf { it.first != null || it.second != null }
            epgFromGlobal = result.layer == EpgFetcher.EpgLayer.GLOBAL
        }
    }

    // v0.176.0：直播页顶栏整行去掉——播放器直接贴顶（系统状态栏下方第一行），
    // 不再留 48dp“直播”标题栏；顶栏四个功能不删，全部移位：
    // 搜索/收藏/设置 → 节目卡右侧三个图标（对标 DS 频道头齿轮+爱心），
    // 刷新 → 直播设置弹层右上角；收藏另加左列“我的收藏”分组。
    Scaffold {
        Column(
            // v0.124.0：横屏全屏时不套状态栏内边距——窗口层全出血在
            // PlayerScreen 的 applyImmersiveFullscreen 一并处理；
            // 竖屏只留状态栏高度，播放器贴在状态栏正下方，不压系统时间电量。
            // v0.238.0：顶部贴顶——竖屏分支去掉多余的状态栏内边距；横屏全屏分支不动
            modifier = if (fullscreenPlayer) Modifier.fillMaxSize()
            else Modifier.fillMaxSize(),
        ) {
            // 顶部 16:9 播放器；v0.96.2：横屏全屏时占满（PlayerScreen 调用点唯一，不重建播放器）
            val playing = selected
            // v0.292.0 V5（design-tokens 5.3）：直播页没有顶栏也没有接口 chip 行——
            // 播放器预览卡就是第一个元素，状态栏下 8dp、左右 16dp、24dp 圆角；横屏全屏仍占满不裁角。
            Box(
                modifier = (if (fullscreenPlayer) Modifier.fillMaxSize()
                            else Modifier
                                .padding(start = 16.dp, end = 16.dp, top = V5.StatusGap)
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(V5.CornerCard)))
                    .background(Color.Black),
            ) {
                if (playing != null && autoplayHeld) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                autoplayHeld = false
                                LiveSelection.userStarted = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "播放",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                "${playing.name} · 点击播放",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else if (playing != null) {
                    // v0.277.0（T10）：重拉计数作 key——同台再点递增时
                    // 播放器整棵重建，起播链路重新跑一遍；正常切台时
                    // 计数不变，走原来的 url 变化起播，不多重建。
                    key(livePlayNonce) {
                    PlayerScreen(
                        url = playing.url,
                        title = playing.name,
                        isLive = true,
                        // v1.4.8 A2：嵌入页传在播频道 id，内核/比例偏好跟台走。
                        channelId = playing.id,
                        onBack = {},
                        embedded = true,
                        onFullscreen = {
                            goingToLivePage = true
                            PlaySession.returnToEmbedded = true
                            onFullscreenChannel(playing)
                        },
                        onOpenPluginCenter = onOpenPluginCenter,
                        // v0.158.0：最终失败自动切同名源（同内核重拉+换内核已在播放器内走完）；
                        // 切到其他源时把换源过滤放回全部，避免列表与在播频道对不上
                        onLiveError = {
                            val cur = selected
                            // v1.4.9：失败过的源先打上本会话标记（源按钮红点）。
                            if (cur != null) liveFailedIds = liveFailedIds + cur.id
                            val same = if (cur == null) emptyList()
                            else channels.filter { normalizeChannelKey(it.name) == normalizeChannelKey(cur.name) }
                            val tried = liveTriedIds + (cur?.id.orEmpty())
                            val next = same.firstOrNull { it.id !in tried }
                            if (next == null) {
                                AppToast.show(context, "同名源均播放失败", long = false)
                            } else {
                                liveTriedIds = tried
                                if (selectedLiveSource != null && next.sourceKey != selectedLiveSource) {
                                    selectedLiveSource = null
                                    LiveSelection.selectedLiveSource = null
                                }
                                // v1.4.16 审稿 M1：Toast 的源号必须按右列实际显示的范围算——
                                // 右列取 sourceScope（收藏 tab 取全量，其余取 filtered），而
                                // same 取全量；筛单订阅＋跨订阅失败史＋重设筛选时两者会错一位。
                                // 清筛选后按现值重算范围（filtered 是旧 remember 值，不能复用）；
                                // 切换候选仍用全量 same（右列外的源也得能切过去）。
                                val shownScope = if (selectedGroup == LIVE_TAB_FAVORITES) channels
                                else channels.filter { selectedLiveSource == null || it.sourceKey == selectedLiveSource }
                                val shownSame = if (cur == null) emptyList()
                                else shownScope.filter { normalizeChannelKey(it.name) == normalizeChannelKey(cur.name) }
                                val nextNum = shownSame.indexOf(next) + 1
                                // v1.4.9：点名失败的那条（源号 + 来源显示名 + host + 路径尾段），
                                // 不只说切到了源几，否则删之前认不出哪条死了；
                                // 同 host 多路径时只看 host 也分不出（1695 与 1697 就是一例）。
                                val failedDesc = cur?.let {
                                    val tail = it.url.substringAfterLast('/').substringBefore('?')
                                        .takeIf { s -> s.isNotBlank() }?.let { t -> "…/$t" }
                                    // 失败源号：右列看得见就按右列编；看不见（极少）回落全量，
                                    // indexOfFirst 找不到是 -1，直接 +1 会报“源0”（1.4.9 R1 同类）。
                                    val shownIdx = shownSame.indexOfFirst { c -> c.id == it.id }
                                    val failedNum = if (shownIdx >= 0) shownIdx + 1
                                    else same.indexOfFirst { c -> c.id == it.id } + 1
                                    listOfNotNull(
                                        "源$failedNum",
                                        liveSourceLabel(it.sourceKey).takeIf { s -> s.isNotBlank() },
                                        LiveStore.hostOfUrl(it.url).takeIf { s -> s.isNotBlank() },
                                        tail,
                                    ).joinToString(" · ")
                                }.orEmpty()
                                AppToast.show(context, "播放失败（${failedDesc}），已自动切到源$nextNum", long = false)
                                val nextKey = normalizeChannelKey(next.name)
                                liveSourceByName = liveSourceByName + (nextKey to next.id)
                                LiveSelection.sourceByName[nextKey] = next.id
                                LiveSelection.channelId = next.id
                                saveLiveSelection(context)
                                selected = next
                            }
                        },
                        // v1.4.9：首次真正播出，清掉该源的本会话失败标记。
                        onLiveFirstPlayed = {
                            selected?.id?.let { liveFailedIds = liveFailedIds - it }
                        },
                        // v0.160.0：频道自带 ua 才传（cctv-live 目前 ua 为空，传空 map，不编 header）
                        liveHeaders = playing.ua.takeIf { it.isNotBlank() }
                            ?.let { mapOf("User-Agent" to it) } ?: emptyMap(),
                    )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Text(
                                "选择下方频道开始播放",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            // v0.96.2：横屏全屏只留播放器，其他 UI 隐藏
            if (!fullscreenPlayer) {
            // v0.292.0 V5（design-tokens 5.3）：「正在播」卡——surfaceContainer 24dp 圆角；
            // 节目进度条、下一档；头部只留展开/收起箭头。
            // v1.4.14 三列：搜索/收藏/设置图标移到中列当前台条，同名源分段按钮移到右列；
            ProgramInfoCard(
                channel = playing,
                epg = epg,
                epgFromGlobal = epgFromGlobal,
                hasEpgSource = (sourceEpgUrl.isNotBlank() || globalEpg.isNotBlank()),
            )
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (refreshing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在拉取直播源…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.Tv,
                            title = "还没有直播频道",
                            // v0.96.1：插件更新/重装瞬间聚合会短暂为 0，给明确指引而不是干等
                            hint = "插件更新或重装时请稍候，在直播设置里点刷新重试；\n或在直播设置里进订阅管理添加 M3U 订阅、去插件市场安装 live 插件",
                        )
                    }
                }
                return@Column
            }
            // 三列：左分组（84dp）/ 中频道（flex）/ 右同名源（64dp），间距 8dp；
            // 窄屏 <340dp 时左 76dp / 右 56dp。点频道直接播，点右列源直接切，
            // 不再翻节目卡里的源分段按钮。
            val isNarrow = LocalConfiguration.current.screenWidthDp < 340
            val leftWidth = if (isNarrow) 76.dp else 84.dp
            val rightWidth = if (isNarrow) 56.dp else 64.dp
            val groupListState = rememberLazyListState()
            val channelListState = rememberLazyListState()
            val sourceListState = rememberLazyListState()
            val lastRefreshText = remember(lastRefreshMs) {
                if (lastRefreshMs <= 0L) "未刷新"
                else "上次 " + timeFmt.format(Date(lastRefreshMs))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp, bottom = dockClearance() + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveGroupColumn(
                    groupNames = groupNames,
                    selectedGroup = selectedGroup,
                    onSelectGroup = { g ->
                        selectedGroup = g
                        LiveSelection.selectedGroup = g
                        saveLiveSelection(context)
                    },
                    lastRefreshText = lastRefreshText,
                    refreshing = refreshing,
                    onRefresh = { doRefresh() },
                    listState = groupListState,
                    counts = tabCounts,
                    modifier = Modifier.width(leftWidth).fillMaxHeight(),
                )
                LiveChannelColumn(
                    channels = displayChannels,
                    selectedId = selected?.id,
                    currentChannel = playing,
                    isFavorite = channels.firstOrNull { it.id == playing?.id }?.favorite == true,
                    onSearchClick = { showSearch = !showSearch },
                    onSettingsClick = { showLiveSettings = true },
                    onFavoriteClick = {
                        playing?.let { LiveStore.toggleFavorite(context, it.id) }
                    },
                    showSearch = showSearch,
                    query = query,
                    onQueryChange = { query = it },
                    refreshing = refreshing,
                    groupKey = selectedGroup,
                    onSelectChannel = { playChannel(it) },
                    onToggleFavorite = { LiveStore.toggleFavorite(context, it.id) },
                    onLongClickChannel = { detailChannel = it },
                    listState = channelListState,
                    sourceCounts = sourceCounts,
                    displayNames = displayNames,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                LiveSourceColumn(
                    sources = sources,
                    selectedId = selected?.id,
                    failedIds = liveFailedIds,
                    onSelectSource = { playChannel(it) },
                    onLongPressSource = { sourceDetail = it },
                    listState = sourceListState,
                    modifier = Modifier.width(rightWidth).fillMaxHeight(),
                )
            }
            } // v0.96.2 if (!fullscreenPlayer)
        }
    }

    // v0.176.0：DS 式直播设置弹层（顶栏设置+刷新移进来，订阅管理仍走原整页）
    // v1.4.9：打开设置时读一次已隐藏流，供弹层里的恢复列表用。
    LaunchedEffect(showLiveSettings) {
        if (showLiveSettings) hiddenStreams = LiveStore.hiddenStreamUrls(context)
    }
    if (showLiveSettings) {
        LiveSettingsSheet(
            sourceKeys = sourceKeys,
            selectedLiveSource = selectedLiveSource,
            globalEpg = globalEpg,
            followSourceEpg = followSourceEpg,
            currentEpgUrl = sourceEpgUrl,
            hasSelected = selected != null,
            epgSourceKeys = epgSourceKeys,
            hiddenStreams = hiddenStreams,
            onUnhideStream = { u ->
                LiveStore.unhideStream(context, u)
                hiddenStreams = hiddenStreams - u
                doRefresh()
            },
            onDismiss = { showLiveSettings = false },
            onRefresh = { doRefresh() },
            onSelectLiveSource = { key ->
                selectedLiveSource = key
                LiveSelection.selectedLiveSource = key
                saveLiveSelection(context)
            },
            onManageClick = {
                showLiveSettings = false
                goingToLivePage = true
                onManageClick()
            },
        )
    }

    // 频道详情（长按）：台标/信息/EPG 当前+后续/收藏/删除
    detailChannel?.let { ch ->
        ChannelDetailSheet(
            channel = ch,
            onDismiss = { detailChannel = null },
            onPlay = { playChannel(ch); detailChannel = null },
            onFavorite = { LiveStore.toggleFavorite(context, ch.id) },
            onDelete = {
                detailChannel = null
                TrashStore.trashLiveManual(context, ch)
                LiveStore.removeManual(context, ch.id)
                // v1.4.2：走 doRefresh（appScope）——删台后切走，刷新也要跑完。
                doRefresh()
                AppToast.show(context, "已移入回收站", long = true)
            },
        )
    }

    // v1.4.9：源详情（长按右列源行）：来源显示名 + host + 完整 URL，
    // 确认即按 URL 隐藏该条流（不删订阅、不删台）。
    sourceDetail?.let { ch ->
        val detailIndex = sources.indexOfFirst { it.id == ch.id }
        TechDialog(
            onDismissRequest = { sourceDetail = null },
            title = if (detailIndex >= 0) "源${detailIndex + 1}详情" else "源详情",
            confirmText = "隐藏该源",
            confirmDanger = true,
            onConfirm = {
                sourceDetail = null
                hideSourceByUrl(ch.url)
            },
        ) {
            Column {
                Text(
                    text = "来源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = liveSourceLabel(ch.sourceKey),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = LiveStore.hostOfUrl(ch.url).ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "完整地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ch.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


// v0.158.0：直播源显示名（m3u:订阅名 / plugin:文件名 / 手动添加）
internal fun liveSourceLabel(key: String): String = when {
    key == "manual" -> "手动添加"
    key.startsWith("m3u:") -> key.removePrefix("m3u:")
    key.startsWith("plugin:") -> key.removePrefix("plugin:")
    else -> key
}


/**
 * v1.4.8 A3(f)：频道身份按稳定 id 比较，不按 name——同名多源时只给
 * 选中台显示在播节目名。
 */
internal fun isSameChannel(selectedId: String?, channelId: String): Boolean =
    selectedId != null && selectedId == channelId

internal fun nowTitleForChannel(selectedId: String?, channelId: String, epgTitle: String?): String? =
    if (isSameChannel(selectedId, channelId)) epgTitle else null

/** v1.4.16：左列首两项 tab 名；v1.4.17：其后接 M3U group-title 真实分组。 */
internal const val LIVE_TAB_ALL = "全部频道"
internal const val LIVE_TAB_FAVORITES = "我的收藏"

/** v1.4.17：恢复分组后左列多一项“未分组”（group-title 为空串的频道归到这里）。 */
internal const val LIVE_GROUP_UNGROUPED = "未分组"

/**
 * v1.4.14 三列：中列按台名去重，每台只显示一条——显示的是该台上次选的源，
 * 没选过才取第一条；记的源 id 已失效也回落第一条。纯函数，可单测。
 * v1.4.16：去重改全局归一化——按 normalizeChannelKey 分组，不再按精确台名；
 * 顺序保持 key 首次出现顺序。
 */
internal fun distinctChannelsGlobally(
    channels: List<LiveChannel>,
    sourceByName: Map<String, String>,
): List<LiveChannel> = channels.groupBy { normalizeChannelKey(it.name) }.values.map { list ->
    sourceByName[normalizeChannelKey(list.first().name)]?.let { id ->
        list.firstOrNull { it.id == id }
    } ?: list.first()
}

/**
 * v1.4.17：左列顺序——全部频道（默认首选）／我的收藏（仅有收藏时）／
 * 真实分组按 group-title 在 filtered 里首次出现的顺序（即 M3U 自身顺序，
 * 与 DS 左列一致）／未分组（仅有无分组频道时，排最后）。不排序、不把
 * “央视”特殊置顶。纯函数，可单测。
 */
internal fun liveGroupNames(channels: List<LiveChannel>): List<String> {
    val names = mutableListOf(LIVE_TAB_ALL)
    if (channels.any { it.favorite }) names += LIVE_TAB_FAVORITES
    val groups = channels.map { it.group.ifBlank { LIVE_GROUP_UNGROUPED } }.distinct()
    names += groups.filter { it != LIVE_GROUP_UNGROUPED }
    if (LIVE_GROUP_UNGROUPED in groups) names += LIVE_GROUP_UNGROUPED
    return names
}

/**
 * v1.4.17：中列范围按当前左列选中项取——全部频道取全量、我的收藏取收藏
 * 子集、真实分组取该组、未分组取无分组频道；selectedGroup 首帧为 null
 * 时走全部频道，不闪退。纯函数，可单测。
 */
internal fun liveScopeChannels(
    channels: List<LiveChannel>,
    selectedGroup: String?,
): List<LiveChannel> = when (selectedGroup) {
    LIVE_TAB_FAVORITES -> channels.filter { it.favorite }
    LIVE_TAB_ALL, null -> channels
    LIVE_GROUP_UNGROUPED -> channels.filter { it.group.isBlank() }
    else -> channels.filter { it.group.ifBlank { LIVE_GROUP_UNGROUPED } == selectedGroup }
}

/**
 * v1.4.17：左列逐项去重后台数——每项取其范围内的归一化 key 去重数，
 * 与中列行数一致。纯函数，可单测。
 */
internal fun liveGroupCounts(
    channels: List<LiveChannel>,
    groupNames: List<String>,
): Map<String, Int> = groupNames.associateWith { g ->
    liveScopeChannels(channels, g).map { normalizeChannelKey(it.name) }.toSet().size
}

/**
 * v1.4.17：hideSourceByUrl 回落规则——回落的台不在当前左列范围里才切
 * 到全部频道，否则不动；收藏 tab 旧行为（回落到未收藏的台才切）是这条
 * 规则的特例。纯函数，可单测。
 */
internal fun shouldFallbackToAll(selectedGroup: String?, fallback: LiveChannel): Boolean {
    if (selectedGroup == null || selectedGroup == LIVE_TAB_ALL) return false
    return when (selectedGroup) {
        LIVE_TAB_FAVORITES -> !fallback.favorite
        LIVE_GROUP_UNGROUPED -> fallback.group.isNotBlank()
        else -> fallback.group.ifBlank { LIVE_GROUP_UNGROUPED } != selectedGroup
    }
}

