package com.movieshell.ui.novel

import com.movieshell.data.store.NovelStore
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.data.novel.BookProbe
import com.movieshell.data.novel.BookProbeStore
import com.movieshell.data.novel.BookRuleEngine
import com.movieshell.data.novel.toSourceHealth
import com.movieshell.domain.model.shortLabel
import com.movieshell.domain.model.SourceHealth
import com.movieshell.domain.model.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelContentService
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.NovelSourceTrash
import com.movieshell.data.novel.PurifyRuleStore
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.source.CmsApi
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * v0.61.0 书源管理：列表 / 启用开关 / 删除 / 粘贴 JSON 导入。
 * 兼容"阅读"App 导出的 bookSource.json（单条或数组）。
 * v0.66.0：点本地 PY 源 → 打开该源专属找书页（onOpenPySource）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookSourceScreen(
    onBack: () -> Unit = {},
    onOpenPySource: (ruleName: String) -> Unit = {},
    onOpenExplore: (sourceKey: String) -> Unit = {},
    // v0.214.0：发现聚合页入口
    onOpenExploreAggregate: () -> Unit = {},
    onOpenPurifyRules: () -> Unit = {}, // v0.95.0：净化规则管理
    embedded: Boolean = false, // v0.79.0：嵌入书架子 tab 时隐藏头部
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v0.109.0 A9：亮色主题白底青字按底色明度自适应
    val accent = MaterialTheme.colorScheme.onSurface

    val sources by BookSourceManager.sources.collectAsState()
    // v1.1.3：连通状态只显示、不新增探测——读发现页已持久化的探测结果。
    val probeMap by BookProbeStore.map.collectAsState()
    // v0.64.0：本地 PY 书源
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    // v0.81.0：书源 URL 导入 + 本地书源文件导入
    var showUrlImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var importingUrl by remember { mutableStateOf(false) }
    // v0.84.0：订阅预览——URL 下载后先列出全部书源，勾选再导入
    var urlPreview by remember { mutableStateOf<List<BookSource>?>(null) }
    var urlChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteTarget by remember { mutableStateOf<BookSource?>(null) }
    var deletePyTarget by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }
    // v0.211.0 审计修复：删源确认要显示受影响的在架本数（原来文案
    // 说“书架里已有的书不受影响”，与实际断源行为相反）。
    var deleteAffectedCount by remember { mutableStateOf<Int?>(null) }
    var deletePyAffectedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(deleteTarget) {
        deleteAffectedCount = deleteTarget?.let {
            withContext(Dispatchers.IO) {
                // v1.4.27 解缠⑧：在架 sourceKey 统一从行模型取（SourceRow.shelfKey）。
                NovelStore.countBySourceKey(context, SourceRow.Json(it).shelfKey)
            }
        }
    }
    LaunchedEffect(deletePyTarget) {
        deletePyAffectedCount = deletePyTarget?.let {
            withContext(Dispatchers.IO) {
                NovelStore.countBySourceKey(context, SourceRow.Py(it).shelfKey)
            }
        }
    }
    // v0.196.0：长按卡片编辑（仿阅读书源列表）——JSON 改名/地址/分组，PY 改文件名
    var editTarget by remember { mutableStateOf<BookSource?>(null) }
    var editPyTarget by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }
    // v0.206.0：长按书源文本编辑——JSON 整条文本、PY 整文件文本
    var textEditJson by remember { mutableStateOf<BookSource?>(null) }
    var textEditPy by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }
    var textEditPyText by remember { mutableStateOf("") }
    // v0.197.0：阅读式书源列表——顶部搜索过滤 + 批量选择（全选/反选/删除）
    // v1.4.35：筛选项/搜索词/批量态离页返回不丢——原来全是 remember，进发现页再返回
    // 组合重建就回全部；改 rememberSaveable（退栈恢复），与书架/详情页同口径。
    var srcQuery by rememberSaveable { mutableStateOf("") }
    // v1.4.29：点统计区一格按档筛列表（全部/启用/失效/未验证），再点回全部；与搜索词叠加。
    var statFilter by rememberSaveable(
        stateSaver = Saver<StatFilter, String>(
            save = { it.name },
            restore = { runCatching { StatFilter.valueOf(it) }.getOrDefault(StatFilter.ALL) },
        ),
    ) { mutableStateOf(StatFilter.ALL) }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var selectedSrcKeys by rememberSaveable(
        stateSaver = Saver<Set<String>, List<String>>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    // v0.217.0（F45）：批量删除确认框也要报受影响的在架本数——原来只有
    // 单删确认框有，批量删完用户才知道书架断了多少本。
    var batchAffectedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(batchDeleteConfirm) {
        batchAffectedCount = if (!batchDeleteConfirm) null
        else withContext(Dispatchers.IO) {
            selectedSrcKeys.sumOf { k ->
                NovelStore.countBySourceKey(context, SourceRow.shelfKeyOf(k))
            }
        }
    }
    // v1.4.27 解缠⑧：过滤与选择 key 统一走行模型 SourceRow（口径逐字不变，界面不动）。
    // v1.4.29：统计筛选叠加在搜索词上；PY 行只认启用档，失效/未验证只看 JSON（与统计口径一致）。
    val shownRows = remember(pyRules, sources, srcQuery, statFilter, probeMap, pyEnabled) {
        SourceRow.rows(pyRules, sources).filter { row ->
            if (!row.matches(srcQuery)) return@filter false
            when (row) {
                is SourceRow.Py -> statFilter.matchesRow(true, row.rule.name in pyEnabled, SourceHealth.Untested)
                is SourceRow.Json -> statFilter.matchesRow(false, row.source.enabled, probeMap[row.source.key].toSourceHealth())
            }
        }
    }
    val shownPy = remember(shownRows) { shownRows.filterIsInstance<SourceRow.Py>().map { it.rule } }
    val shownJson = remember(shownRows) { shownRows.filterIsInstance<SourceRow.Json>().map { it.source } }
    val allShownKeys = remember(shownRows) { shownRows.map { it.selectKey } }
    // v1.4.27 P0.2：JSON 书源按分组分段（SourceGroups.group），折叠集合只在本页内存里。
    val jsonGroups = remember(shownJson) { SourceGroups.group(shownJson) }
    var collapsedGroups by rememberSaveable(
        stateSaver = Saver<Set<String>, List<String>>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    // 源多（>100）时首次进页默认全部折叠，2000+ 条不用一路滑到底；之后以用户点选为准。
    // v1.4.35：首次折叠初始化标记也进 rememberSaveable——源超 100 条时
    // 进页默认全折叠；原来 remember，进发现页返回会重跑一次全折叠，
    // 把恢复的折叠态清掉。
    var collapseInit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sources.size) {
        if (!collapseInit && sources.isNotEmpty()) {
            collapseInit = true
            if (sources.size > SourceGroups.AUTO_COLLAPSE_OVER) {
                collapsedGroups = SourceGroups.group(sources).map { it.first }.toSet()
            }
        }
    }
    // 批量“全选/反选”只作用于看得见的行——折叠组里的源不被一并勾上误删。
    // v1.4.29：统计筛选时同样全部展开（与搜索时一致），筛出来的行都算看得见。
    val visibleSelectKeys = remember(shownRows, jsonGroups, collapsedGroups, srcQuery, statFilter) {
        val hidden = if (jsonGroups.size > 1 && srcQuery.isBlank() && statFilter == StatFilter.ALL) {
            jsonGroups.filter { it.first in collapsedGroups }
                .flatMap { g -> g.second.map { SourceRow.Json(it).selectKey } }.toSet()
        } else emptySet()
        allShownKeys.filter { it !in hidden }
    }
    // v0.67.0：PY 源连通性测试；v0.217.0（F42）一次只测一个、按点击顺序排队。
    // v1.4.27 搜索测试：PY 与 JSON 共用 SourceTestRunner（共用一把锁、按行 key 记排队/执行态、
    // 复位一律 finally、删源时取消该行任务），结果改结构化 TestResultDisplay。
    val testRunner = remember(scope) { SourceTestRunner(scope) }
    // 搜索测试关键词输入弹窗的目标源（null=不弹）与输入值
    var testInputFor by remember { mutableStateOf<BookSource?>(null) }
    var testKeyword by remember { mutableStateOf("") }
    // v1.4.27 P0.7：书源回收站（软删除＋恢复）。条目随书源表 / PY 规则变化（删、恢复）重读。
    var showTrash by remember { mutableStateOf(false) }
    var trashClearConfirm by remember { mutableStateOf(false) }
    var trashPurgeTarget by remember { mutableStateOf<NovelSourceTrash.Entry?>(null) }
    var trashEntries by remember { mutableStateOf<List<NovelSourceTrash.Entry>>(emptyList()) }
    var trashTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sources, pyRules, trashTick) {
        trashEntries = withContext(Dispatchers.IO) { NovelSourceTrash.of(context).list() }
    }
    // v0.96.1：净化规则生效数（按钮上展示）
    var purifyCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        purifyCount = runCatching {
            PurifyRuleStore.list(context).count { it.enable }
        }.getOrDefault(-1)
    }
    // v0.76.0：导进来的是影视 spider——弹窗提示，可一键转去影视源导入
    // v0.294.5：rememberSaveable——转屏/进程重建后“导入为影视源”确认不丢
    var videoSpiderUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    // v1.3.25 合并 2：扫出高危的 PY 书源待确认导入（uri + 报告）。
    var pendingRiskyPyImport by remember { mutableStateOf<Pair<android.net.Uri, PyBookSourceManager.ImportResult.Risky>?>(null) }

    LaunchedEffect(Unit) {
        BookSourceManager.load(context)
        PyBookSourceManager.load(context)
        BookProbeStore.load(context)
    }

    // v1.4.29：进页不再自动探测书源——1700 个源每次进页都跑一遍，又慢又耗电；
    // 只读上次存下来的探测记录，点“更多 → 检测全部”才手动跑一遍（24 小时内已探过的跳过）。
    val probeAllOnce: () -> Unit = {
        scope.launch {
            val now = System.currentTimeMillis()
            sources.filter { it.enabled && it.exploreUrl.isNotBlank() }.forEach { src ->
                if (src.url.isBlank()) return@forEach
                val rec = probeMap[src.key]
                if (rec != null && rec.url == src.url && now - rec.time < 24L * 60 * 60 * 1000) return@forEach
                launch {
                    val r = BookProbe.probeSource(src)
                    BookProbeStore.update(context, src.key, r)
                }
            }
        }
    }

    val pyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = PyBookSourceManager.import(context, uri)) {
                is PyBookSourceManager.ImportResult.Ok ->
                    AppToast.show(context, "已导入 ${r.fileName}", long = false)
                PyBookSourceManager.ImportResult.WrongExt ->
                    AppToast.show(context, "请选择 .py 文件", long = false)
                PyBookSourceManager.ImportResult.InvalidRule ->
                    AppToast.show(context, "不是有效的书源文件（需要实现 bookSearch 方法）", long = false)
                // v0.76.0：是影视 spider——弹窗，不再只是一句 toast 打发
                PyBookSourceManager.ImportResult.IsVideoSpider -> videoSpiderUri = uri
                is PyBookSourceManager.ImportResult.Failed ->
                    AppToast.show(context, "导入失败：${r.msg}", long = false)
                // v1.3.25 合并 2：高危先弹风险确认，确认后重调一次。
                is PyBookSourceManager.ImportResult.Risky ->
                    pendingRiskyPyImport = uri to r
            }
        }
    }

    // v0.81.0：本地 bookSource.json 文件导入
    val jsonFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (json.isNullOrBlank()) {
                AppToast.show(context, "读取文件失败", long = false)
                return@launch
            }
            // v0.186.0：import 返回 0 有三种含义——没识别、已存在、写盘失败；
            // 先解析一次：有新 key 却返回 0 只可能是写盘失败（失败时内存已回滚）。
            val parsed = BookSource.listFromJson(json)
            // v1.3.33：jsLib URL 形式明确拒绝——不在这里拦，导进来也是静默失效。
            val badJsLib = firstJsLibUrlSourceName(parsed)
            if (badJsLib != null) {
                AppToast.show(context, "书源「${badJsLib}」的 jsLib 暂不支持 URL，请改成内联代码再导入", long = true)
                return@launch
            }
            val hasNew = parsed.any { p -> BookSourceManager.sources.value.none { it.key == p.key } }
            val n = BookSourceManager.import(context, json)
            AppToast.show(context, when {
                    n > 0 -> "导入 $n 个书源"
                    parsed.isEmpty() -> "没有识别出书源，检查 JSON 格式"
                    hasNew -> "保存失败，书源未导入，请重试"
                    else -> "书源已存在，无需重复导入"
                }, long = false)
        }
    }

    // v0.76.0：影视 spider 识别弹窗
    videoSpiderUri?.let { uri ->
        TechDialog(
            onDismissRequest = { videoSpiderUri = null },
            title = "这是影视源，不是书源",
            confirmText = "导入为影视源",
            onConfirm = {
                videoSpiderUri = null
                scope.launch {
                    // v0.294.5：拷文件放 IO 线程，不卡主线程
                    val msg = when (
                        val r = withContext(Dispatchers.IO) {
                            com.movieshell.ui.sources.importRuleFile(
                                context, uri,
                                com.movieshell.ui.sources.IMPORT_PY,
                            )
                        }
                    ) {
                        is com.movieshell.ui.sources.ImportResult.Ok ->
                            "已导入为影视源 ${r.fileName}，去站源页查看"
                        com.movieshell.ui.sources.ImportResult.WrongExt ->
                            "请选择 .py 文件"
                        is com.movieshell.ui.sources.ImportResult.Failed ->
                            "导入失败：${r.reason}"
                        // v1.3.25 合并 2：此流没有风险确认弹窗，高危直接拒收
                        //（想导请到源管理页手动导入，那里可确认放行）。
                        is com.movieshell.ui.sources.ImportResult.Risky ->
                            "该源有高危风险，已拒绝导入：" +
                                (r.report.findings.firstOrNull()?.title ?: "未知风险") +
                                "；确认要导请到源管理页手动导入"
                    }
                    AppToast.show(context, msg, long = true)
                }
            },
        ) {
            Text(
                "检测到这个 .py 是影视 spider（有 homeContent/searchContent 等影视方法，" +
                    "没有 bookSearch），放进书源管理用不了。要直接导入为影视源吗？",
            )
        }
    }

    // v1.3.25 合并 2：PY 书源高危风险确认（确认后重调一次，跳过扫描）。
    pendingRiskyPyImport?.let { (uri, risky) ->
        com.movieshell.ui.sources.SourceRiskDialog(
            fileName = risky.fileName,
            report = risky.report,
            onDismissRequest = { pendingRiskyPyImport = null },
            onConfirm = {
                pendingRiskyPyImport = null
                scope.launch {
                    when (val r = PyBookSourceManager.import(context, uri, skipRiskScan = true)) {
                        is PyBookSourceManager.ImportResult.Ok ->
                            AppToast.show(context, "已导入 ${r.fileName}", long = false)
                        is PyBookSourceManager.ImportResult.Failed ->
                            AppToast.show(context, "导入失败：${r.msg}", long = false)
                        else -> Unit
                    }
                }
            },
        )
    }

    // v1.4.27 Step 4：整页按 f5bb9b44「书源管理」——SlimGlassBar（返回＋标题＋搜索/添加/更多）、
    // 顶栏到统计区 12dp、统计区 48dp、搜索框 38dp（点搜索图标展开）、搜索框到列表 10dp、
    // 书源行 44dp/间隔 3dp/左右 16dp。发现聚合、净化规则、恢复默认、批量、回收站收进「更多」浮层菜单。
    val palette = novelPalette()
    var showSearch by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val stats = remember(pyRules, pyEnabled, sources, probeMap) { SourceStats.of(pyRules, pyEnabled, sources, probeMap) }
    val restoreBuiltinAction: () -> Unit = {
        scope.launch {
            // v0.186.0：restoreBuiltin 返回 false 有两种含义——已存在、写盘失败；
            // 调用前已有内置源才是已存在，否则就是写盘失败（失败时内存已回滚）。
            val hadBuiltin = BookSourceManager.sources.value.any { it.key == "www.quanben.io" }
            val added = BookSourceManager.restoreBuiltin(context)
            AppToast.show(context, when {
                    added -> "已恢复内置书源（全本小说网）"
                    hadBuiltin -> "内置书源已存在，无需恢复"
                    else -> "保存失败，内置书源未恢复，请重试"
                }, long = false)
        }
    }
    @Composable
    fun MoreMenuButton() {
        Box {
            GlassIconButton(Icons.Outlined.MoreVert, "更多管理", { showMoreMenu = true })
            GlassMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                GlassMenuItem(Icons.Outlined.Checklist, if (batchMode) "退出批量" else "批量管理", {
                    showMoreMenu = false
                    batchMode = !batchMode
                    // v1.4.29：进批量就把分组全展开——原来 100+ 源默认全折叠，行藏在组里看不见通断。
                    if (batchMode) collapsedGroups = emptySet()
                    if (!batchMode) selectedSrcKeys = emptySet()
                })
                GlassMenuItem(Icons.Outlined.Sync, "检测全部", { showMoreMenu = false; probeAllOnce() })
                GlassMenuItem(Icons.Outlined.Explore, "发现聚合", { showMoreMenu = false; onOpenExploreAggregate() })
                GlassMenuItem(
                    Icons.Outlined.AutoFixHigh, "净化规则",
                    { showMoreMenu = false; onOpenPurifyRules() },
                    trailing = if (purifyCount >= 0) "$purifyCount" else null,
                )
                GlassMenuItem(Icons.Outlined.Refresh, "恢复默认", { showMoreMenu = false; restoreBuiltinAction() })
                GlassMenuItem(
                    Icons.Outlined.Delete, "回收站",
                    { showMoreMenu = false; showTrash = true },
                    trailing = if (trashEntries.isNotEmpty()) "${trashEntries.size}" else null,
                )
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    if (!embedded) NovelBackground(Modifier.fillMaxSize())
    Column(modifier = Modifier.fillMaxSize().padding(vertical = if (embedded) 8.dp else 16.dp)) {
        if (!embedded) {
            SlimGlassBar(title = "书源管理", onBack = onBack) {
                GlassIconButton(Icons.Outlined.Search, "搜索书源", {
                    showSearch = !showSearch
                    if (!showSearch) srcQuery = ""
                })
                GlassIconButton(Icons.Outlined.Add, "导入书源", { showImportMenu = true })
                MoreMenuButton()
            }
            Spacer(Modifier.height(NovelTokens.BarBelowGap))
        } else {
            // 嵌入书架子页：外层已有顶栏，这里只留一行“搜索框＋添加＋更多”【自定】
            Row(
                Modifier.fillMaxWidth().padding(horizontal = NovelTokens.RowMarginH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovelTokens.BarActionGap),
            ) {
                NovelSearchField(srcQuery, { srcQuery = it }, "搜索书源", Modifier.weight(1f))
                GlassIconButton(Icons.Outlined.Add, "导入书源", { showImportMenu = true })
                MoreMenuButton()
            }
            Spacer(Modifier.height(NovelTokens.SearchToList))
        }
        SourceStatsStrip(stats, Modifier.padding(horizontal = NovelTokens.RowMarginH), selected = statFilter, onSelect = { statFilter = it })
        if (!embedded) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showSearch || srcQuery.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn(tween(NovelMotion.pageFadeMs(rememberReduceMotion()))) +
                    androidx.compose.animation.expandVertically(tween(NovelMotion.pageFadeMs(rememberReduceMotion()))),
                exit = androidx.compose.animation.fadeOut(tween(NovelMotion.pageFadeMs(rememberReduceMotion()))) +
                    androidx.compose.animation.shrinkVertically(tween(NovelMotion.pageFadeMs(rememberReduceMotion()))),
            ) {
                // 统计区到搜索框【自定 8dp】
                NovelSearchField(
                    srcQuery, { srcQuery = it }, "搜索书源（名称 / 地址）",
                    Modifier.fillMaxWidth().padding(start = NovelTokens.RowMarginH, end = NovelTokens.RowMarginH, top = 8.dp),
                    focusModifier = Modifier.focusRequester(searchFocus),
                )
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            }
        }
        Spacer(Modifier.height(NovelTokens.SearchToList))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NovelTokens.RowGap),
            contentPadding = PaddingValues(horizontal = NovelTokens.RowMarginH),
        ) {
            // v0.64.0：本地 PY 书源区
            if (shownPy.isNotEmpty()) {
                item(key = "py_header") {
                    SourceSectionTitle("本地 PY 书源 · ${SourceRow.enabledPyCount(shownPy, pyEnabled)} 已启用")
                }
            }
            items(shownPy, key = { "py_${it.name}" }) { r ->
                val row = SourceRow.Py(r)
                SourceCompactRow(
                    name = r.title,
                    statusText = "PY",
                    statusColor = palette.hint,
                    dotColor = palette.unconfigured,
                    enabled = r.name in pyEnabled,
                    onEnabledChange = {
                        scope.launch {
                            // v1.3.33 B-P2-7：写盘改 IO 线程（suspend），失败提示；管理器已回滚内存表。
                            if (!PyBookSourceManager.setEnabled(context, r.name, it)) {
                                AppToast.show(context, "保存失败", long = false)
                            }
                        }
                    },
                    testPhase = testRunner.phaseOf(row.selectKey),
                    onTest = {
                        // v0.67.0：连通性测试（跑一遍 bookSearch）；v1.4.27 走共用排队器，PY 不弹输入框。
                        testRunner.result = null
                        testRunner.submit(row.selectKey) { SourceRowActions.testPy(context, row) }
                    },
                    onEdit = { editPyTarget = r },
                    onDelete = { deletePyTarget = r },
                    onClick = { onOpenPySource(r.name) },
                    onLongClick = {
                        scope.launch {
                            val text = PyBookSourceManager.readContent(context, r.name)
                            if (text == null) {
                                AppToast.show(context, "读取失败，请重试", long = false)
                            } else {
                                textEditPyText = text
                                textEditPy = r
                            }
                        }
                    },
                    batchMode = batchMode,
                    selected = row.selectKey in selectedSrcKeys,
                    onSelect = { selectedSrcKeys = SourceRow.toggled(selectedSrcKeys, row.selectKey, it) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (shownJson.isNotEmpty()) {
                item(key = "json_header") { SourceSectionTitle("JSON 书源") }
            }
            // v1.4.27 P0.2：书源分组展示——按 group 字段（取第一个分组名）分段，组头可折叠；
            // 只有一个分组（含全是“未分组”）时不出组头，与改版前扁平列表一样。搜索时全部展开。
            jsonGroups.forEach { (groupName, groupList) ->
                val showHeaders = jsonGroups.size > 1
                val collapsed = showHeaders && srcQuery.isBlank() && statFilter == StatFilter.ALL && groupName in collapsedGroups
                if (showHeaders) {
                    item(key = "jgrp_$groupName") {
                        SourceGroupHeader(groupName, groupList.size, collapsed, onToggle = {
                            collapsedGroups = if (groupName in collapsedGroups) collapsedGroups - groupName
                            else collapsedGroups + groupName
                        })
                    }
                }
                if (!collapsed) items(groupList, key = { "json_${it.key}" }) { s ->
                    // 点卡进字段编辑，长按进文本编辑（与原来点行/长按一致）。
                    val row = SourceRow.Json(s)
                    // v1.1.3：连通状态点＋耗时（发现页探测结果；没去过发现页即“未测”）。
                    val health = probeMap[s.key].toSourceHealth()
                    SourceCompactRow(
                        name = s.name,
                        statusText = health.shortLabel,
                        statusColor = palette.healthColor(health),
                        dotColor = palette.healthColor(health),
                        enabled = s.enabled,
                        onEnabledChange = {
                            scope.launch {
                                // v0.188.0：写盘失败提示“保存失败”；管理器已回滚，开关随 StateFlow 旧值弹回。
                                if (!BookSourceManager.setEnabled(context, s.key, it)) {
                                    AppToast.show(context, "保存失败", long = false)
                                }
                            }
                        },
                        testPhase = testRunner.phaseOf(row.selectKey),
                        onTest = {
                            // v1.4.27 搜索测试：播放图标放开关之前（定稿布局 A 的同组位置）；点后弹关键词输入框。
                            testKeyword = s.effectiveCheckKeyWord
                            testInputFor = s
                        },
                        onEdit = { editTarget = s },
                        onDelete = { deleteTarget = s },
                        onClick = { editTarget = s },
                        onLongClick = { textEditJson = s },
                        batchMode = batchMode,
                        selected = row.selectKey in selectedSrcKeys,
                        onSelect = { selectedSrcKeys = SourceRow.toggled(selectedSrcKeys, row.selectKey, it) },
                        // v0.201.0：有发现分类的源加「发现」入口
                        onExplore = if (s.exploreUrl.isNotBlank()) ({ onOpenExplore(s.key) }) else null,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (shownPy.isEmpty() && shownJson.isEmpty()) {
                item(key = "empty") {
                    NovelEmptyState(
                        icon = if (srcQuery.isBlank()) Icons.Outlined.Inbox else Icons.Outlined.SearchOff,
                        text = if (srcQuery.isBlank()) "还没有书源" else "没有匹配的书源",
                        modifier = Modifier.fillMaxWidth(),
                        actionText = if (srcQuery.isBlank()) "导入书源" else null,
                        onAction = if (srcQuery.isBlank()) ({ showImportMenu = true }) else null,
                    )
                }
            }
        }
        // v0.197.0：底部批量栏（阅读式：全选 / 反选 / 删除）；Step 4 换玻璃底栏 48dp【自定高度，与顶栏同档】。
        if (batchMode) {
            GlassSurface(
                Modifier.padding(horizontal = NovelTokens.RowMarginH).padding(top = 8.dp).fillMaxWidth().height(NovelTokens.BarHeight),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = NovelTokens.BarInnerPad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // v1.4.27 Step4 审稿 A4：全选改并集（保留折叠组里已勾的行）；计数直接显示已选数。
                    TextButton(onClick = { selectedSrcKeys = SourceRow.selectAllVisible(selectedSrcKeys, visibleSelectKeys) }) {
                        Text("全选（已选 ${selectedSrcKeys.size}）", fontSize = 12.sp, color = palette.title)
                    }
                    TextButton(onClick = {
                        selectedSrcKeys = SourceRow.invertVisible(selectedSrcKeys, visibleSelectKeys)
                    }) { Text("反选", fontSize = 12.sp, color = palette.title) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { batchMode = false; selectedSrcKeys = emptySet() }) {
                        Text("取消", fontSize = 12.sp, color = palette.body)
                    }
                    TextButton(
                        enabled = selectedSrcKeys.isNotEmpty(),
                        onClick = { batchDeleteConfirm = true },
                    ) {
                        Text("删除", fontSize = 12.sp, color = if (selectedSrcKeys.isNotEmpty()) palette.error else palette.hint)
                    }
                }
            }
        }
    }
    }

    // v0.197.0：批量删除确认
    if (batchDeleteConfirm) {
        TechDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = "删除 ${selectedSrcKeys.size} 个书源？",
            confirmText = "删除",
            onConfirm = {
                batchDeleteConfirm = false
                val keys = selectedSrcKeys
                scope.launch {
                    // v0.217.0（F45）：报实删数——原来直接报勾选数，个别源
                    // 写盘失败也算“已删除”；JSON 删除无返回值，按单删同款
                    // “删完 key 还在即失败”逐个验。
                    // v1.4.27 解缠⑧：批量删除与单删共用 SourceRowActions。
                    keys.forEach { testRunner.cancel(it) }
                    val okCount = withContext(Dispatchers.IO) {
                        SourceRowActions.deleteBySelectKeys(context, keys)
                    }
                    selectedSrcKeys = emptySet()
                    batchMode = false
                    AppToast.show(
                        context,
                        when {
                            okCount == keys.size -> "已删除 $okCount 个书源"
                            okCount > 0 -> "已删除 $okCount/${keys.size} 个书源，${keys.size - okCount} 个删除失败"
                            else -> "删除失败，书源未删除"
                        },
                        long = false,
                    )
                }
            },
        ) {
            Text(
                "删除后找书不再用这些源；书架里用这些源的书将无法追更，阅读时需要换源。删除的书源进回收站，可恢复。" +
                    (batchAffectedCount?.let { n ->
                        if (n > 0) " 本次删除影响书架上 $n 本书。" else ""
                    } ?: ""),
            )
        }
    }

    // v0.227.0：导入方式二选一变四选一弹窗——原来四个大钮占一整行，
    // 并成标题行一个“导入书源”入口，点开再选方式，原四路动作原样接线。
    if (showImportMenu) {
        TechDialog(
            onDismissRequest = { showImportMenu = false },
            title = "导入书源",
            onConfirm = null,
        ) {
            ImportMenuItem(Icons.Filled.ContentPaste, "粘贴导入") {
                showImportMenu = false; importText = ""; showImport = true
            }
            ImportMenuItem(Icons.Filled.Link, "URL导入") {
                showImportMenu = false; importUrl = ""; showUrlImport = true
            }
            ImportMenuItem(Icons.Filled.FolderOpen, "文件导入") {
                showImportMenu = false; jsonFileLauncher.launch("*/*")
            }
            ImportMenuItem(Icons.Filled.Code, "导入本地PY") {
                showImportMenu = false; pyImportLauncher.launch("*/*")
            }
        }
    }
    // v0.84.0：换科技感 TechDialog
    if (showImport) {
        TechDialog(
            onDismissRequest = { showImport = false },
            title = "导入书源",
            subtitle = "粘贴阅读 App 的 bookSource.json（单条或数组）",
            confirmText = "导入",
            onConfirm = {
                scope.launch {
                    // v0.186.0：import 返回 0 有三种含义——没识别、已存在、写盘失败；
                    // 先解析一次：有新 key 却返回 0 只可能是写盘失败（失败时内存已回滚）。
                    val parsed = BookSource.listFromJson(importText)
                    // v1.3.33：jsLib URL 形式明确拒绝——不在这里拦，导进来也是静默失效。
                    val badJsLib = firstJsLibUrlSourceName(parsed)
                    if (badJsLib != null) {
                        AppToast.show(context, "书源「${badJsLib}」的 jsLib 暂不支持 URL，请改成内联代码再导入", long = true)
                        return@launch
                    }
                    val hasNew = parsed.any { p -> BookSourceManager.sources.value.none { it.key == p.key } }
                    val n = BookSourceManager.import(context, importText)
                    showImport = false
                    AppToast.show(context, when {
                            n > 0 -> "导入 $n 个书源"
                            parsed.isEmpty() -> "没有识别出书源，检查 JSON 格式"
                            hasNew -> "保存失败，书源未导入，请重试"
                            else -> "书源已存在，无需重复导入"
                        }, long = false)
                }
            },
        ) {
            // v1.2.22：粘贴框是多行文本域，用 12dp 小圆角——之前全胶囊硬拉 200dp 高，
            // 框成了大鸡蛋、提示文字顶着边框像被裁掉。
            TechTextField(
                value = importText,
                onValueChange = { importText = it },
                placeholder = "粘贴 bookSource.json",
                modifier = Modifier.fillMaxWidth().height(200.dp),
                singleLine = false,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }

    // v0.81.0：书源 URL 导入；v0.84.0：换科技感 TechDialog
    if (showUrlImport) {
        TechDialog(
            onDismissRequest = { showUrlImport = false },
            title = "书源 URL 导入",
            subtitle = "输入 bookSource.json 的订阅地址，先预览再导入",
            confirmText = if (importingUrl) "下载中…" else "下一步",
            onConfirm = {
                val url = importUrl.trim()
                if (url.isBlank()) return@TechDialog
                importingUrl = true
                scope.launch {
                    val json = withContext(Dispatchers.IO) {
                        runCatching {
                            val req = okhttp3.Request.Builder().url(url).build()
                            // v0.112.0 B2：书源 URL 导入用共享单例，不再每请求 new 一个 client。
                            CmsApi.defaultClient().newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) null
                                else resp.body?.string()
                            }
                        }.getOrNull()
                    }
                    importingUrl = false
                    if (json.isNullOrBlank()) {
                        AppToast.show(context, "下载失败，检查 URL", long = false)
                        return@launch
                    }
                    // v0.84.0：先解析预览，勾选再导入（对标阅读3.0网络导入）
                    val parsed = BookSource.listFromJson(json)
                    if (parsed.isEmpty()) {
                        AppToast.show(context, "没有识别出书源，检查 JSON 格式", long = false)
                        return@launch
                    }
                    // v0.133.0：外部订阅文件可含同址重复条目（http 与 https、同址尾斜杠
                    // 归一化后 key 相同），预览渲染前按 key 去重防 LazyColumn key 撞车；
                    // 勾选态本来就按 key 记，去重不丢信息
                    urlPreview = parsed.distinctBy { it.key }
                    urlChecked = parsed.map { it.key }.toSet()
                    showUrlImport = false
                }
            },
            confirmEnabled = !importingUrl,
        ) {
            TechTextField(
                value = importUrl,
                onValueChange = { importUrl = it },
                placeholder = "https://…/bookSource.json",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // v0.84.0：订阅预览——列出 URL 里全部书源，勾选再导入；换科技感 TechDialog
    urlPreview?.let { preview ->
        TechDialog(
            onDismissRequest = { urlPreview = null },
            title = "订阅预览",
            // v1.3.33：书源长列表走宽松模式。
            compact = false,
            // v1.2.15：内容含 LazyColumn 自己滚，TechDialog 内容区不套滚动。
            scrollable = false,
            subtitle = "共 ${preview.size} 个书源，已选 ${urlChecked.size} 个",
            confirmText = "导入选中",
            onConfirm = {
                scope.launch {
                    val selected = preview.filter { it.key in urlChecked }
                    // v0.186.0：importSources 返回 0 可能是全已存在，也可能是写盘失败；
                    // 选中里有新 key 却返回 0 只可能是写盘失败（失败时内存已回滚）。
                    val hasNew = selected.any { s -> BookSourceManager.sources.value.none { it.key == s.key } }
                    val n = BookSourceManager.importSources(context, selected)
                    urlPreview = null
                    AppToast.show(context, when {
                            n > 0 -> "导入 $n 个书源"
                            hasNew -> "保存失败，书源未导入，请重试"
                            else -> "所选书源都已存在，无需重复导入"
                        }, long = false)
                }
            },
            confirmEnabled = urlChecked.isNotEmpty(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { urlChecked = preview.map { it.key }.toSet() },
                ) { Text("全选", fontSize = 12.sp) }
                TextButton(
                    onClick = { urlChecked = emptySet() },
                ) { Text("全不选", fontSize = 12.sp) }
            }
            Box(modifier = Modifier.height(300.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(preview, key = { it.key }) { s ->
                        val checked = s.key in urlChecked
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    urlChecked = if (on) urlChecked + s.key
                                    else urlChecked - s.key
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    s.name.ifBlank { s.key },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    s.url,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { s ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除书源",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    // v0.186.0：JSON 删除无 Boolean 返回值，写盘失败时内存回滚、
                    // 删完 key 还在就是保存失败；成功时保持原来不弹提示。
                    testRunner.cancel(SourceRow.Json(s).selectKey) // 删源取消该行在测/排队的测试（C4/C5）
                    if (!SourceRowActions.delete(context, SourceRow.Json(s))) {
                        AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                    }
                }
                deleteTarget = null
            },
        ) {
            Text(
                "确定删除「${s.name}」？删除后进回收站，可恢复。" +
                    (deleteAffectedCount?.let { n ->
                        if (n > 0) " 书架上有 $n 本书在用这个源，删后这些书将无法追更，阅读时需要换源。" else ""
                    } ?: ""),
            )
        }
    }

    // v0.64.0：删除本地 PY 书源
    deletePyTarget?.let { r ->
        TechDialog(
            onDismissRequest = { deletePyTarget = null },
            title = "删除书源",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    // v0.186.0：PY 删除返回 false 即文件删除失败，弹保存失败提示；
                    // 成功时保持原来不弹提示。
                    testRunner.cancel(SourceRow.Py(r).selectKey)
                    val ok = SourceRowActions.delete(context, SourceRow.Py(r))
                    if (!ok) {
                        AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                    }
                }
                deletePyTarget = null
            },
        ) {
            Text(
                "确定删除本地 PY 书源「${r.title}」？文件会移入回收站，可恢复。" +
                    (deletePyAffectedCount?.let { n ->
                        if (n > 0) " 书架上有 $n 本书在用这个源，删后这些书将无法追更，阅读时需要换源。" else ""
                    } ?: ""),
            )
        }
    }

    // v0.214.0：✎进字段编辑——按基础／搜索／详情／目录／正文／发现六页签拆开的
    // 表单（原来只有名称／地址／分组三框，规则一律改不了；全文 JSON 仍走长按文本编辑）。
    // 保存走 updateSourceJson（整条 JSON 回写，字段全量 round-trip；地址变了自动迁书架）。
    editTarget?.let { s ->
        var draft by remember(s.key) { mutableStateOf(s) }
        var tab by remember(s.key) { mutableStateOf(0) }
        // v1.4.28：书源调试状态（随弹窗走；切页签不丢，关弹窗即弃）。
        var debugKeyword by remember(s.key) { mutableStateOf("") }
        var debugSteps by remember(s.key) { mutableStateOf<List<DebugStep>>(emptyList()) }
        var debugRunning by remember(s.key) { mutableStateOf(false) }
        var debugJob by remember(s.key) { mutableStateOf<Job?>(null) }
        // 调试用弹窗内 scope：关弹窗时组合退出，scope 随之取消，在途调试自动停。
        val debugScope = rememberCoroutineScope()

        fun updateStep(index: Int, update: (DebugStep) -> DebugStep) {
            debugSteps = debugSteps.mapIndexed { i, st -> if (i == index) update(st) else st }
        }

        // v1.4.28：书源调试——对未保存的 draft 跑四步（搜索→详情→目录→正文），每步 30 秒超时；
        // 只做 JSON 源（本弹窗本就是 JSON 书源编辑）；目录步直调 chapters、正文步用 fetchPurified，
        // 不污染正式缓存；结果不持久化。
        fun runDebug() {
            val keyword = debugKeyword.trim().ifBlank { draft.effectiveCheckKeyWord }
            debugSteps = listOf("搜索", "详情", "目录", "正文").map { DebugStep(name = it) }
            debugRunning = true
            debugJob?.cancel()
            debugJob = debugScope.launch {
                val engine = NovelSourceEngine.novelEngineFor(context, draft)
                try {
                    // 第 1 步：搜索（带轨迹；不写失败名单、不记死站）。
                    updateStep(0) { it.copy(status = DebugStatus.RUNNING) }
                    val trace = withTimeoutOrNull(DEBUG_STEP_TIMEOUT_MS) {
                        NovelSourceEngine.searchWithTrace(draft, keyword, allowGate = false, recordFailures = false)
                    }
                    if (trace == null) {
                        updateStep(0) { it.copy(status = DebugStatus.TIMEOUT, summary = "30 秒超时") }
                        return@launch
                    }
                    updateStep(0) {
                        it.copy(
                            status = if (trace.results.isNotEmpty()) DebugStatus.OK else DebugStatus.FAILED,
                            requestUrl = trace.requestUrl,
                            httpCode = trace.httpCode,
                            bodyLength = trace.bodyLength,
                            bodyPreview = trace.bodyPreview,
                            summary = if (trace.results.isEmpty()) "搜到 0 本"
                            else "搜到 ${trace.results.size} 本：" + trace.results.take(3).joinToString("、") { b -> b.title },
                        )
                    }
                    val first = trace.results.firstOrNull() ?: return@launch
                    // 第 2 步：详情（解析走 bookDetail；原始返回另抓一次详情页，默认 UA，仅供参考）。
                    updateStep(1) { it.copy(status = DebugStatus.RUNNING) }
                    val detailPair = withTimeoutOrNull(DEBUG_STEP_TIMEOUT_MS) {
                        val info = engine.bookDetail(first)
                        val raw = BookRuleEngine.fetchPageOutcome(first.remoteId)
                        info to raw
                    }
                    if (detailPair == null) {
                        updateStep(1) { it.copy(status = DebugStatus.TIMEOUT, summary = "30 秒超时") }
                        return@launch
                    }
                    val (info, rawDetail) = detailPair
                    val rawDetailBody = rawDetail.page?.body.orEmpty()
                    updateStep(1) {
                        it.copy(
                            status = if (info.name.isNotBlank() || info.author.isNotBlank()) DebugStatus.OK else DebugStatus.FAILED,
                            requestUrl = rawDetail.page?.url ?: first.remoteId,
                            httpCode = rawDetail.httpCode,
                            bodyLength = rawDetailBody.length,
                            bodyPreview = foldPreview(rawDetailBody),
                            note = "原始返回为默认 UA 复抓，仅供参考",
                            summary = "书名：${info.name.ifBlank { "—" }} / 作者：${info.author.ifBlank { "—" }}",
                        )
                    }
                    // 第 3 步：目录（直调 chapters，不走 NovelTocService；③-R1 方案 A：
                    // 另抓一次 remoteId 详情页作轨迹，行内标注；目录本身的原始返回记 "—"，多页跳转不复抓）。
                    updateStep(2) { it.copy(status = DebugStatus.RUNNING) }
                    val tocPair = withTimeoutOrNull(DEBUG_STEP_TIMEOUT_MS) {
                        val list = engine.chapters(first)
                        val raw = BookRuleEngine.fetchPageOutcome(first.remoteId)
                        list to raw
                    }
                    if (tocPair == null) {
                        updateStep(2) { it.copy(status = DebugStatus.TIMEOUT, summary = "30 秒超时") }
                        return@launch
                    }
                    val (tocList, rawToc) = tocPair
                    val rawTocBody = rawToc.page?.body.orEmpty()
                    updateStep(2) {
                        it.copy(
                            status = if (tocList.isNotEmpty()) DebugStatus.OK else DebugStatus.FAILED,
                            requestUrl = rawToc.page?.url ?: first.remoteId,
                            httpCode = rawToc.httpCode,
                            bodyLength = rawTocBody.length,
                            bodyPreview = foldPreview(rawTocBody),
                            previewLabel = "详情页原始返回（目录页由规则推导）",
                            note = "目录原始返回：—（解析可能多页跳转，不复抓）",
                            summary = if (tocList.isEmpty()) "目录解析为空"
                            else "共 ${tocList.size} 章：" + tocList.take(3).joinToString("、") { c -> c.title },
                        )
                    }
                    val firstChapter = tocList.firstOrNull()
                    if (firstChapter == null) {
                        updateStep(3) { it.copy(status = DebugStatus.FAILED, summary = "目录为空，无章节可试") }
                        return@launch
                    }
                    // 第 4 步：正文（fetchPurified：拉取＋净化，不写盘）。
                    updateStep(3) { it.copy(status = DebugStatus.RUNNING) }
                    val outcome = withTimeoutOrNull(DEBUG_STEP_TIMEOUT_MS) {
                        NovelContentService.fetchPurified(context, engine, first.remoteId, draft.key, firstChapter, allowGate = false)
                    }
                    if (outcome == null) {
                        updateStep(3) { it.copy(status = DebugStatus.TIMEOUT, summary = "30 秒超时") }
                        return@launch
                    }
                    when (outcome) {
                        is NovelContentService.Outcome.Ok -> updateStep(3) {
                            it.copy(
                                status = DebugStatus.OK,
                                requestUrl = firstChapter.url,
                                bodyLength = outcome.text.length,
                                bodyPreview = foldPreview(outcome.text),
                                summary = "正文 ${outcome.text.length} 字",
                            )
                        }
                        is NovelContentService.Outcome.PurifyEmpty -> updateStep(3) {
                            it.copy(
                                status = DebugStatus.FAILED,
                                requestUrl = firstChapter.url,
                                bodyLength = outcome.raw.length,
                                bodyPreview = foldPreview(outcome.raw),
                                summary = "净化后为空（原文 ${outcome.raw.length} 字）",
                            )
                        }
                        is NovelContentService.Outcome.Failed -> updateStep(3) {
                            it.copy(
                                status = DebugStatus.FAILED,
                                requestUrl = firstChapter.url,
                                summary = "失败：${outcome.reason}",
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // 某步抛了未预期的异常（多为破损 JS 规则）：记到当前进行中的步上，不闪退。
                    debugSteps = debugSteps.map {
                        if (it.status == DebugStatus.RUNNING) it.copy(status = DebugStatus.FAILED, summary = "异常：${e.message ?: e::class.simpleName}") else it
                    }
                } finally {
                    debugRunning = false
                    // 被取消（点停止/关弹窗）时，把还停在“进行中”的步标成已停止。
                    debugSteps = debugSteps.map {
                        if (it.status == DebugStatus.RUNNING) it.copy(status = DebugStatus.FAILED, summary = "已停止") else it
                    }
                }
            }
        }
        val tabs = listOf("基础", "搜索", "详情", "目录", "正文", "发现", "调试")
        TechDialog(
            onDismissRequest = { editTarget = null },
            title = "编辑书源",
            // v1.3.33：页签长表单走宽松模式。
            compact = false,
            subtitle = "按页签改规则；想改整条 JSON 请长按源进文本编辑",
            confirmText = "保存",
            onConfirm = {
                // v1.3.33：jsLib URL 形式明确拒绝——不保存、不关弹窗，让用户当场改掉。
                if (LegadoJsEngine.isJsLibUrl(draft.jsLib)) {
                    AppToast.show(context, "jsLib 暂不支持 URL，请贴内联代码", long = true)
                } else {
                    scope.launch {
                        val ok = BookSourceManager.updateSourceJson(context, s.key, draft.toJson().toString())
                        if (!ok) {
                            AppToast.show(context, "保存失败：名称或地址为空、地址与已有书源重复或写盘失败", long = false)
                        }
                    }
                    editTarget = null
                }
            },
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                tabs.forEachIndexed { i, t ->
                    TextButton(onClick = { tab = i }) {
                        Text(
                            t,
                            fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> {
                        SourceField("名称", draft.name) { draft = draft.copy(name = it) }
                        SourceField("地址", draft.url, hint = "书源唯一标识由此算出，改地址会同步迁书架上的书") { draft = draft.copy(url = it) }
                        SourceField("分组", draft.group, hint = "选填") { draft = draft.copy(group = it) }
                        SourceField("自定义 UA", draft.headerUa, hint = "空则用默认 UA") { draft = draft.copy(headerUa = it) }
                        SourceField("登录地址", draft.loginUrl) { draft = draft.copy(loginUrl = it) }
                        SourceField("登录校验 JS", draft.loginCheckJs, hint = "阅读式 loginCheckJs，空表示不需要", multiLine = true) { draft = draft.copy(loginCheckJs = it) }
                    }
                    1 -> {
                        SourceField("搜索地址", draft.searchUrl, hint = "{{key}} 按 UTF-8 编码，{{page}} 恒为 1") { draft = draft.copy(searchUrl = it) }
                        SourceField("搜索列表", draft.ruleSearchList, hint = "ruleSearch.bookList") { draft = draft.copy(ruleSearchList = it) }
                        SourceField("书名", draft.ruleSearchName, hint = "ruleSearch.name") { draft = draft.copy(ruleSearchName = it) }
                        SourceField("作者", draft.ruleSearchAuthor, hint = "ruleSearch.author") { draft = draft.copy(ruleSearchAuthor = it) }
                        SourceField("封面", draft.ruleSearchCover, hint = "ruleSearch.coverUrl") { draft = draft.copy(ruleSearchCover = it) }
                        SourceField("详情地址", draft.ruleSearchBookUrl, hint = "ruleSearch.bookUrl") { draft = draft.copy(ruleSearchBookUrl = it) }
                        // v1.4.27 搜索测试：测试关键词（ruleSearch.checkKeyWord），保存走原 updateSourceJson。
                        SourceField("测试关键词", draft.checkKeyWord, hint = "空则用「${BookSource.DEFAULT_CHECK_KEYWORD}」") { draft = draft.copy(checkKeyWord = it) }
                    }
                    2 -> {
                        SourceField("书名", draft.ruleBookName, hint = "ruleBookInfo.name") { draft = draft.copy(ruleBookName = it) }
                        SourceField("作者", draft.ruleBookAuthor, hint = "ruleBookInfo.author") { draft = draft.copy(ruleBookAuthor = it) }
                        SourceField("简介", draft.ruleBookIntro, hint = "ruleBookInfo.intro", multiLine = true) { draft = draft.copy(ruleBookIntro = it) }
                        SourceField("封面", draft.ruleBookCover, hint = "ruleBookInfo.coverUrl") { draft = draft.copy(ruleBookCover = it) }
                        SourceField("目录地址", draft.ruleBookTocUrl, hint = "ruleBookInfo.tocUrl") { draft = draft.copy(ruleBookTocUrl = it) }
                    }
                    3 -> {
                        SourceField("目录列表", draft.ruleTocList, hint = "ruleToc.chapterList") { draft = draft.copy(ruleTocList = it) }
                        SourceField("章节名", draft.ruleTocTitle, hint = "ruleToc.chapterName") { draft = draft.copy(ruleTocTitle = it) }
                        SourceField("章节地址", draft.ruleTocUrl, hint = "ruleToc.chapterUrl") { draft = draft.copy(ruleTocUrl = it) }
                        SourceField("目录下一页", draft.ruleTocNext, hint = "ruleToc.nextTocUrl，跟目录分页（最多 50 页）") { draft = draft.copy(ruleTocNext = it) }
                    }
                    4 -> {
                        SourceField("正文", draft.ruleContent, hint = "ruleContent.content，空则走通用正文兜底") { draft = draft.copy(ruleContent = it) }
                        SourceField("正文替换", draft.ruleContentReplace, hint = "replaceRegex：每行一条，行内用 ## 分隔正则与替换", multiLine = true) { draft = draft.copy(ruleContentReplace = it) }
                        SourceField("正文下一页", draft.ruleContentNext, hint = "ruleContent.nextContentUrl") { draft = draft.copy(ruleContentNext = it) }
                        SourceField("共享 JS 库", draft.jsLib, hint = "阅读式 jsLib：只支持内联代码，填 URL 会拒绝保存", multiLine = true) { draft = draft.copy(jsLib = it) }
                    }
                    // v1.4.28 ③-R2：发现与调试必须显式分支——原来发现走 else，加第 7 页签后
                    // 5（发现）和 6（调试）都会落进 else，调试面板永不渲染。
                    5 -> {
                        SourceField("发现地址", draft.exploreUrl, hint = "阅读式 exploreUrl：分类 JSON 数组或对象") { draft = draft.copy(exploreUrl = it) }
                        SourceField("发现列表", draft.ruleExploreList, hint = "ruleExplore.bookList") { draft = draft.copy(ruleExploreList = it) }
                        SourceField("书名", draft.ruleExploreName, hint = "ruleExplore.name") { draft = draft.copy(ruleExploreName = it) }
                        SourceField("作者", draft.ruleExploreAuthor, hint = "ruleExplore.author") { draft = draft.copy(ruleExploreAuthor = it) }
                        SourceField("详情地址", draft.ruleExploreBookUrl, hint = "ruleExplore.bookUrl") { draft = draft.copy(ruleExploreBookUrl = it) }
                        SourceField("封面", draft.ruleExploreCover, hint = "ruleExplore.coverUrl") { draft = draft.copy(ruleExploreCover = it) }
                        SourceField("简介", draft.ruleExploreIntro, hint = "ruleExplore.intro", multiLine = true) { draft = draft.copy(ruleExploreIntro = it) }
                    }
                    6 -> {
                        SourceDebugPanel(
                            draft = draft,
                            keyword = debugKeyword,
                            onKeywordChange = { debugKeyword = it },
                            steps = debugSteps,
                            running = debugRunning,
                            onRun = { runDebug() },
                            onStop = { debugJob?.cancel() },
                        )
                    }
                }
            }
        }
    }

    // v0.196.0：✎重命名——只改文件名，不改文件内容；v0.206.0：长按进文本编辑
    // v0.206.0：长按 JSON 书源文本编辑——整条 JSON 可改，格式不对不保存
    textEditJson?.let { s ->
        SourceTextEditorDialog(
            title = "编辑书源 · ${s.name}",
            initialText = s.toJson().toString(2),
            onDismiss = { textEditJson = null },
            onSave = { text ->
                val jsonOk = runCatching { JSONObject(text) }.isSuccess
                if (!jsonOk) {
                    "JSON 格式不对，未保存"
                } else if (BookSource.fromJson(text)?.jsLib?.let { LegadoJsEngine.isJsLibUrl(it) } == true) {
                    "jsLib 暂不支持 URL，请贴内联代码，未保存"
                } else if (BookSourceManager.updateSourceJson(context, s.key, text)) {
                    null
                } else {
                    "保存失败：地址与已有书源重复或写盘失败"
                }
            },
        )
    }

    // v0.206.0：长按 PY 书源文本编辑——整文件可改，保存即生效
    textEditPy?.let { r ->
        SourceTextEditorDialog(
            title = "编辑书源 · ${r.title}",
            initialText = textEditPyText,
            onDismiss = { textEditPy = null },
            onSave = { text ->
                val ok = PyBookSourceManager.writeContent(context, r.name, text)
                // v1.4.28 A3（⑤-R1）：PY 文本编辑保存成功同样作废该源的目录/发现缓存，
                // 否则改完规则 10 分钟内目录/发现仍读旧缓存（JSON 文本编辑走 updateSourceJson 已有）。
                if (ok) {
                    BookSourceManager.invalidateCaches(PyBookSourceManager.SOURCE_KEY_PREFIX + r.name)
                    null
                } else "保存失败，请重试"
            },
        )
    }

    editPyTarget?.let { r ->
        var newBase by remember(r.name) { mutableStateOf(r.title) }
        TechDialog(
            onDismissRequest = { editPyTarget = null },
            title = "重命名书源",
            subtitle = "只改文件名，不改文件内容",
            confirmText = "保存",
            onConfirm = {
                scope.launch {
                    val ok = PyBookSourceManager.rename(context, r.name, newBase.trim())
                    if (!ok) {
                        AppToast.show(context, "重命名失败：名称为空、含非法字符或已存在同名文件", long = false)
                    }
                }
                editPyTarget = null
            },
        ) {
            TechTextField(value = newBase, onValueChange = { newBase = it }, placeholder = "新名称，不用写 .py")
            Spacer(Modifier.height(8.dp))
            Text(
                "保存后文件将改名为“${newBase.trim().removeSuffix(".py")}.py”，卡片标题跟着变。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // v1.4.27 搜索测试：关键词输入弹窗（标题「搜索测试」＋副标题源名；预填源 checkKeyWord／「斗罗」；
    // 自动弹键盘、回车即测；空白回落「斗罗」；词只用于本次，不回写源文件）。
    testInputFor?.let { src ->
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        fun confirm() {
            val key = src.key
            val kw = testKeyword
            testInputFor = null
            testRunner.result = null
            testRunner.submit(SourceRow.Json(src).selectKey) { SourceRowActions.testJson(key, kw) }
        }
        TechDialog(
            onDismissRequest = { testInputFor = null },
            title = "搜索测试",
            subtitle = src.name,
            confirmText = "测试",
            onConfirm = { confirm() },
        ) {
            TechTextField(
                value = testKeyword,
                onValueChange = { testKeyword = it },
                // v1.4.29（用户 2026-11-27 要求）：不传 placeholder——TechTextField 有值时
                // 会把 placeholder 缩成框内顶部小标签，预填「斗罗」时框里上下两个「斗罗」
                // 重复；空白回落本来就走 resolveKeyword，不靠 placeholder。
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { confirm() }),
            )
        }
        LaunchedEffect(src.key) { runCatching { focus.requestFocus() } }
    }

    // v1.4.27 P0.7：书源回收站弹窗（最小 UI）：逐条恢复 / 彻底删除，底部清空。
    if (showTrash) {
        TechDialog(
            onDismissRequest = { showTrash = false },
            title = "书源回收站",
            subtitle = if (trashEntries.isEmpty()) null else "${trashEntries.size} 个书源",
            confirmText = "清空",
            confirmEnabled = trashEntries.isNotEmpty(),
            confirmDanger = true,
            onConfirm = { trashClearConfirm = true },
            dismissText = "关闭",
        ) {
            if (trashEntries.isEmpty()) {
                Text("回收站是空的。删除的书源会先放在这里，可以恢复。", fontSize = 13.sp)
            } else {
                Column {
                    trashEntries.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title.ifBlank { e.originKey }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (e.kind == NovelSourceTrash.KIND_PY) "PY · " else "JSON · ") +
                                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.deletedAt)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val r = SourceRowActions.restore(context, e)
                                    trashTick++
                                    AppToast.show(context, SourceRowActions.restoreMessage(e.title, r), long = false)
                                }
                            }) { Text("恢复", fontSize = 12.sp) }
                            TextButton(onClick = { trashPurgeTarget = e }) {
                                Text("彻底删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    trashPurgeTarget?.let { e ->
        TechDialog(
            onDismissRequest = { trashPurgeTarget = null },
            title = "彻底删除",
            confirmText = "彻底删除",
            confirmDanger = true,
            onConfirm = {
                trashPurgeTarget = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { NovelSourceTrash.of(context).drop(e.id) }
                    trashTick++
                    if (!ok) AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                }
            },
        ) { Text("「${e.title.ifBlank { e.originKey }}」将被永久删除，不能再恢复。") }
    }
    if (trashClearConfirm) {
        TechDialog(
            onDismissRequest = { trashClearConfirm = false },
            title = "清空回收站？",
            confirmText = "清空",
            confirmDanger = true,
            onConfirm = {
                trashClearConfirm = false
                scope.launch {
                    val n = withContext(Dispatchers.IO) { NovelSourceTrash.of(context).clear() }
                    trashTick++
                    AppToast.show(context, if (n > 0) "已清空 $n 个书源" else "清空失败，请重试", long = false)
                }
            },
        ) { Text("回收站里的 ${trashEntries.size} 个书源将被永久删除，不能再恢复。") }
    }

    // v0.67.0：测试结果；v1.4.27 结构化；Step 4 换 88%×60% 五档结果弹窗（与搜索验证弹窗同款）。
    testRunner.result?.let { r ->
        SourceTestResultDialog(r) { testRunner.result = null }
    }
}

@Composable
private fun ImportMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp)
    }
}

/**
 * v0.214.0：字段编辑器单行（标签 + 输入框 + 字段 key 提示）。
 */
/** v1.4.28：书源调试每步 30 秒超时。JS 源 java.ajax 取消不掉，该步可能超过 30 秒才返回。 */
private const val DEBUG_STEP_TIMEOUT_MS = 30_000L

private enum class DebugStatus(val label: String) {
    IDLE("未开始"),
    RUNNING("进行中"),
    OK("成功"),
    FAILED("失败"),
    TIMEOUT("超时"),
}

private data class DebugStep(
    val name: String,
    val status: DebugStatus = DebugStatus.IDLE,
    val requestUrl: String = "",
    val httpCode: Int? = null,
    val bodyLength: Int = 0,
    val bodyPreview: String = "",
    val previewLabel: String = "预览",
    val note: String = "",
    val summary: String = "",
)

/** v1.4.28：调试预览折叠——与引擎 bodyPreview 同口径（空白折叠成单空格，再截 200 字）。 */
private fun foldPreview(body: String): String =
    body.replace(Regex("\\s+"), " ").trim().take(200)

/** v1.4.28：书源调试面板（编辑弹窗第 7 页签）。状态由弹窗持有，切页签不丢。 */
@Composable
private fun SourceDebugPanel(
    draft: BookSource,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    steps: List<DebugStep>,
    running: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "对当前未保存的草稿跑四步：搜索 → 详情 → 目录 → 正文；每步 30 秒超时；只做 JSON 源；结果不保存。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceField("调试关键词", keyword, hint = "空则用「${draft.effectiveCheckKeyWord}」") { onKeywordChange(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRun, enabled = !running, modifier = Modifier.weight(1f)) {
                Text(if (running) "调试中…" else "开始调试")
            }
            if (running) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
        steps.forEachIndexed { index, step ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "第 ${index + 1} 步 ${step.name}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        step.status.label,
                        fontSize = 12.sp,
                        color = when (step.status) {
                            DebugStatus.OK -> MaterialTheme.colorScheme.primary
                            DebugStatus.FAILED, DebugStatus.TIMEOUT -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (step.status != DebugStatus.IDLE) {
                    if (step.requestUrl.isNotBlank()) {
                        Text("请求：${step.requestUrl}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "状态：${step.httpCode?.toString() ?: "—"} · 长度：${step.bodyLength} 字",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (step.bodyPreview.isNotBlank()) {
                        Text("${step.previewLabel}：${step.bodyPreview}", fontSize = 12.sp)
                    }
                    if (step.note.isNotBlank()) {
                        Text(step.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (step.summary.isNotBlank()) {
                        Text(step.summary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceField(
    label: String,
    value: String,
    hint: String? = null,
    multiLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        TechTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = hint ?: label,
            singleLine = !multiLine,
            minLines = if (multiLine) 3 else 1,
        )
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

/**
 * v0.206.0：书源文本编辑——全屏对话框，等宽多行文本，保存回调返回 null 表示成功、
 * 非空表示失败原因（弹 Toast 后留在编辑器里，不丢已改的文本）。
 * v0.210.0：正文改 12.sp 等宽 + 圆角容器，字小一点、看着干净一点。
 */
@Composable
private fun SourceTextEditorDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) { // v0.238.0：顶部贴顶，去掉多余的状态栏内边距
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val err = onSave(text)
                            if (err == null) {
                                AppToast.show(context, "已保存", long = false)
                                onDismiss()
                            } else {
                                AppToast.show(context, err, long = false)
                            }
                        }
                    }) { Text("保存") }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        // v0.294.52：多行编辑区也描边，与其他输入框同口径。
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Default,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** v1.3.33：书源列表里有没有 URL 形式的 jsLib（纯函数，可单测）——有则返回第一个命中的书源名，供导入/保存入口明确拒绝。 */
internal fun firstJsLibUrlSourceName(sources: List<BookSource>): String? =
    sources.firstOrNull { LegadoJsEngine.isJsLibUrl(it.jsLib) }?.name
