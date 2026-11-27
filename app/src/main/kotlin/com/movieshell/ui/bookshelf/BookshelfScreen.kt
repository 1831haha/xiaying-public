package com.movieshell.ui.bookshelf

import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import com.movieshell.ui.novel.GlassIconButton
import com.movieshell.ui.novel.GlassMenu
import com.movieshell.ui.novel.GlassMenuItem
import com.movieshell.ui.novel.NovelTokens
import com.movieshell.ui.novel.SlimGlassBar
import androidx.compose.material.icons.filled.Tune
import com.movieshell.core.util.SafeFileName
import com.movieshell.ui.components.AppSearchBar
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.automirrored.filled.Sort
import com.movieshell.ui.theme.V5

import com.movieshell.ui.components.v5Bleed
import com.movieshell.ui.components.v5BottomDivider
import com.movieshell.ui.components.dockClearance
import androidx.compose.foundation.layout.fillMaxHeight
import android.provider.OpenableColumns
import com.movieshell.ui.components.AppDropdownMenu
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.Novel
import com.movieshell.domain.model.ReadingProgress
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.novel.TxtImporter
import com.movieshell.data.novel.EpubImporter
import com.movieshell.data.store.BookGroup
import com.movieshell.data.store.BookshelfGroupStore
import com.movieshell.data.novel.NovelDirectoryFill
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.ui.novel.NovelSearchScreen
import com.movieshell.ui.novel.NovelSearchViewModel
import com.movieshell.ui.novel.SourceGroup
import com.movieshell.data.store.NovelUpdateInfo
import com.movieshell.data.store.NovelUpdateStore
import com.movieshell.data.store.ReadingProgressStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.market.PluginUi
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.preview.shareFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.98.0：书架重设计（专业 + 可玩性）。
 * - 分组：智能分组（全部/在读/养肥/已读完）+ 用户自定义分组（外部映射表，不动 Novel 本体）。
 * - 双视图（列表/网格）+ 排序（最近阅读/更新时间/书名/阅读进度），偏好持久化。
 * - 批量多选管理（删除/移入分组）；长按操作表；书籍详情弹窗。
 * - 趣味空状态；按压缩放/角标脉冲/分组切换动画微交互。
 * v0.178.0：阅读数据条整块去掉；顶部小按钮只留搜索/添加本地/书源管理；
 * 检查更新改成下拉刷新；长按操作表加全选/分组/收藏。
 * v0.179.0：智能分组（在读/养肥/已读完）去掉，只留全部 + 自定义分组；
 * 顶部只留搜索在外，添加本地/书源管理进右上角三点菜单（参考阅读布局）。
 * v0.180.0：全部提真顶行左侧，最近阅读行提顶行下面、自定义分组上面。
 */
@OptIn(ExperimentalMaterial3Api::class)
// v0.211.0 审计修复：不随组合销毁的写盘作用域——点开书清角标等
// 写盘动作导航后仍要跑完，不能绑在书架 scope 上。
private val shelfIoScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onNovelClick: (String) -> Unit = {},
    onManageSourcesClick: () -> Unit = {},
    // v0.98.0：书籍详情里的净化规则快捷入口
    onOpenPurifyRules: () -> Unit = {},
    // v0.229.0：内嵌找书点结果卡片进书籍信息整页（由宿主导航）。
    onOpenDetail: (SourceGroup, NovelSearchResult) -> Unit = { _, _ -> },
    // v1.2.25：书籍详情里的目录行点进目录整页（书 id，由宿主导航）。
    onOpenToc: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v1.4.27 解缠⑦：书架状态搬进 BookshelfViewModel（只搬状态，界面不动）——
    // 下面各 var 原名原用法，直接委托到 VM 属性；进入书架时复位原 remember 状态。
    val vm: BookshelfViewModel = viewModel()
    remember(vm) { vm.onScreenEnter(); true }
    var books by vm::books
    var progressMap by vm::progressMap
    var updates by vm::updates
    var customGroups by vm::customGroups
    var importing by vm::importing
    var checking by vm::checking
    // v0.211.0：下拉刷新逐本状态——正在查的书 id 集合，查完一本摘一本；
    // 本地 TXT 不联网，永不进这个集合、不转圈。
    var checkingIds by vm::checkingIds

    // v0.98.0：视图/排序/分组偏好
    var viewMode by vm::viewMode
    var sortMode by vm::sortMode
    var selectedGroup by vm::selectedGroup

    // v0.197.0：阅读式页签（全部 / 更新失败）+ 上次检查失败的书 id（持久化在 prefs）
    // v0.294.5：页签与找书态原为 rememberSaveable——v1.4.27 落 VM 的 SavedStateHandle，进书详情/阅读再返回不丢
    var shelfTab by vm::shelfTab
    var failedIds by vm::failedIds

    // v0.98.0：多选模式
    var selectionMode by vm::selectionMode
    var selectedIds by vm::selectedIds
    // v0.215.0：批量换源弹窗与进行态
    var showBatchSource by vm::showBatchSource
    var batchSwitching by vm::batchSwitching
    val batchJsonSources by com.movieshell.data.novel.BookSourceManager.sources.collectAsState()
    val batchPyRules by com.movieshell.data.novel.PyBookSourceManager.rules.collectAsState()
    val batchPyEnabled by com.movieshell.data.novel.PyBookSourceManager.enabled.collectAsState()

    // v0.195.0：书架内直接搜（不用单独找书页）
    var searching by vm::searching
    // v0.243.0：找书态按系统返回先回书架——找书只是书架内的一个状态、
    // 不是导航路由，不拦截时返回会直接 pop 书架路由落到首页；与“取消”同义。
    BackHandler(enabled = searching) { searching = false }

    // 弹窗状态
    var deleteTarget by vm::deleteTarget
    var batchDelete by vm::batchDelete
    var detailTarget by vm::detailTarget
    var groupPickerFor by vm::groupPickerFor
    var showCreateGroup by vm::showCreateGroup
    var renameTarget by vm::renameTarget
    var groupMenuTarget by vm::groupMenuTarget

    fun reload() = vm.reload()
    LaunchedEffect(Unit) {
        reload()
        // v0.249.0：后台补取完成即重读，行状态（LOADING→READY/FAILED）实时跟上。
        NovelDirectoryFill.completed.collect { reload() }
    }
    // v0.249.0：进程死亡后残留的 LOADING 壳——进书架时自动再补取一次（只触发，不等结果）。
    val loadingIds = remember(books) {
        books.filter { it.dirState == NovelStore.DIR_LOADING }.map { it.id }.toSet()
    }
    LaunchedEffect(loadingIds) {
        if (loadingIds.isNotEmpty()) {
            val appCtx = context.applicationContext
            books.filter { it.id in loadingIds }.forEach { novel ->
                NovelDirectoryFill.refillFromShelf(appCtx, novel)
            }
        }
    }
    // v0.249.0 收尾：孤儿书——索引是 READY 但章节文件丢了／空了（历史异常数据），
    // 落成 FAILED 并重读一次，给用户重试入口；修完后不再是孤儿，不循环。
    LaunchedEffect(books) {
        if (books.isEmpty()) return@LaunchedEffect
        val orphans = withContext(Dispatchers.IO) {
            books.filter {
                it.dirState == NovelStore.DIR_READY && !NovelStore.hasChaptersFile(context, it.id)
            }
        }
        if (orphans.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            orphans.forEach {
                NovelStore.markDirectoryState(context, it.id, NovelStore.DIR_FAILED)
            }
        }
        reload()
    }

    // v1.3.33：单本 TXT 导出——选中恰好 1 本时可用；章节拼成 TXT 写缓存文件
    // 后走系统分享（FilePreviewScreen.shareFile 同款写法）。EPUB 不做。
    fun exportSelectedTxt() {
        val novel = books.firstOrNull { it.id == selectedIds.firstOrNull() } ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val chapters = NovelStore.chapters(context, novel.id)
                    if (chapters.isEmpty()) return@runCatching false
                    val safe = novel.title.ifBlank { "小说" }
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val file = File(context.cacheDir, "$safe.txt")
                    file.writeText(buildTxtExport(novel, chapters))
                    shareFile(context, file)
                }.getOrDefault(false)
            }
            PluginUi.toast(context, if (ok) "已导出 TXT" else "导出失败：没有可导出的章节")
        }
    }

    // v1.4.27 Step 3：两个联网长操作（批量换源、检查更新）搬进 BookshelfViewModel（viewModelScope）——
    // 检查中进阅读器/书详情再返回，任务不再被组合销毁取消、逐本结果照常回写（行为变化见 README）。
    fun runBatchSwitch(targetKey: String) = vm.runBatchSwitch(targetKey)
    fun checkUpdates() = vm.checkUpdates()

    fun openNovel(novel: Novel) {
        // v0.249.0：目录未到不进空阅读页——LOADING 只提示，FAILED 点一次即重试。
        if (novel.dirState == NovelStore.DIR_LOADING) {
            PluginUi.toast(context, "正在获取目录…")
            return
        }
        if (novel.dirState == NovelStore.DIR_FAILED) {
            NovelDirectoryFill.refillFromShelf(context.applicationContext, novel)
            PluginUi.toast(context, "正在重新获取目录")
            return
        }
        // v0.211.0 审计修复：原来清角标放在书架 scope 里，导航一走
        // 组合处置就可能取消写盘，返回书架角标复活。清角标走独立
        // 的应用级作用域，不随书架组合销毁；内存角标先摘掉。
        updates = updates - novel.id
        shelfIoScope.launch { NovelUpdateStore.clear(context, novel.id) }
        onNovelClick(novel.id)
    }

    fun deleteNovels(targets: List<Novel>) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                // v0.186.0 第 9 组：走删书唯一入口，与搜索页清理范围一致
                // （本体 + 进度 + 书签 + 分组 + 追更角标）
                // v0.211.0 审计修复：原来丢弃返回值无条件报“已删除 N 本”，
                // 删失败的书还在架上。按实际删掉的本数报，失败的如实说。
                targets.count { NovelStore.deleteNovelCascade(context, it.id) }
            }
            selectionMode = false
            selectedIds = emptySet()
            PluginUi.toast(
                context,
                if (deleted == targets.size) "已删除 $deleted 本"
                else "已删除 $deleted 本，${targets.size - deleted} 本删除失败",
            )
            reload()
        }
    }

    fun moveToGroup(group: BookGroup, novelIds: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                BookshelfGroupStore.addNovels(context, group.id, novelIds)
            }
            groupPickerFor = null
            selectionMode = false
            selectedIds = emptySet()
            PluginUi.toast(context, "已移入「${group.name}」")
            reload()
        }
    }

    // v0.178.0：收藏不另建表，就是自定义分组里一个叫“收藏”的分组，
    // 没有就现建，已在里面就取消，删书时随分组映射一起清。
    fun toggleFavorite(novel: Novel) {
        scope.launch {
            var fav = BookshelfGroupStore.list(context).find { it.name == "收藏" }
            if (fav == null) {
                fav = BookshelfGroupStore.create(context, "收藏")
                if (fav.id.isBlank()) {
                    PluginUi.toast(context, "收藏失败")
                    return@launch
                }
            }
            val isFav = fav.novelIds.contains(novel.id)
            if (isFav) {
                BookshelfGroupStore.removeNovels(context, fav.id, listOf(novel.id))
            } else {
                BookshelfGroupStore.addNovels(context, fav.id, listOf(novel.id))
            }
            PluginUi.toast(context, if (isFav) "已取消收藏" else "已收藏")
            reload()
        }
    }

    // ---------------- 数据计算 ----------------

    // v1.4.27 解缠⑦：分组计数与筛选排序提成 BookshelfViewModel 的纯函数（逐字等价）。
    val shelfGroups = remember(books, customGroups) {
        BookshelfViewModel.shelfGroups(books, customGroups)
    }

    val visibleBooks = remember(books, selectedGroup, sortMode, progressMap, updates, customGroups, shelfTab, failedIds) {
        BookshelfViewModel.visibleBooks(
            books, selectedGroup, sortMode, progressMap, updates, customGroups, shelfTab, failedIds,
        )
    }

    // v0.239.0：继续读 Hero 已撤——刚读过的书不再放大领头（用户原话“太丑了”），
    // 所有书一律按普通卡/行排，刚读过的那本回到它在列表里的正常位置。

    fun toggleSelect(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }

    val sortNames = listOf("最近阅读", "更新时间", "书名", "阅读进度")
    // v0.179.0：右上角三点菜单（添加本地/书源管理）。
    var showTopMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    // v0.178.0：下拉刷新状态，复用首页同款官方 PullToRefreshBox。
    val pullRefreshState = rememberPullToRefreshState()

    val pickTxt = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        // v1.4.34 D-P2-6：本次 TXT 导入的截断提示（runCatching 内写、外面拼进 Toast）
        var importWarnings: List<String> = emptyList()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // v1.4.28：.txt 与 .epub 都认——选择器已是 */*，但部分文件管理器
                    // 不按 MIME 过滤，这里按文件名后缀再拦一次（沿用 v1.3.33 的口径）。
                    val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                    val namedIsEpub = displayName?.endsWith(".epub", ignoreCase = true) == true
                    val namedIsTxt = displayName?.endsWith(".txt", ignoreCase = true) == true
                    if (displayName != null && !namedIsEpub && !namedIsTxt) {
                        return@runCatching "not_txt"
                    }
                    // v0.294.5：文件名清洗 + 规范路径校验，防路径穿越写出缓存目录
                    val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.txt")
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        // v0.218.0（S19）：原来直接返回 failed，临时文件留在缓存里。
                        tmp.delete()
                        return@runCatching "failed"
                    }
                    inputStream.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    // v1.4.28（②-R7）：displayName 为空时默认名不能写死 .txt——
                    // 按内容嗅探：epub 是 zip（PK 开头）就默认 .epub，否则默认 .txt。
                    val sniffEpub = displayName == null && runCatching {
                        val head = ByteArray(2)
                        tmp.inputStream().use { it.read(head) == 2 } &&
                            head.contentEquals(byteArrayOf(0x50, 0x4B))
                    }.getOrDefault(false)
                    val name = SafeFileName.importName(
                        displayName,
                        if (sniffEpub) "导入小说.epub" else "导入小说.txt",
                    )
                    val named = SafeFileName.childInside(context.cacheDir, name)
                        ?: run { tmp.delete(); return@runCatching "failed" }
                    // v0.218.0（S19）：先清掉上次残留的同名文件，否则改名
                    // 可能失败；改名失败直接判失败并删 tmp，不再拿着旧文件
                    // 往下走（会读错文件、提示却还是“已加入书架”）。
                    if (named.exists()) named.delete()
                    if (!tmp.renameTo(named)) {
                        tmp.delete()
                        return@runCatching "failed"
                    }
                    val imported = if (name.endsWith(".epub", ignoreCase = true)) {
                        // v1.4.28：epub 走新解析器，封面落 filesDir/epub_covers。
                        EpubImporter.import(named, File(context.filesDir, "epub_covers"))
                    } else {
                        TxtImporter.import(named)
                    }
                    named.delete()
                    if (imported == null) return@runCatching "invalid"
                    // v0.216.0（C10）：本地判重只看书名会把同名不同书当成同一本。
                    // 改为书名＋章节数＋首章标题三项全对才算重复。
                    val firstTitle = imported.chapters.firstOrNull()?.title
                    val exists = NovelStore.list(context).any { shelf ->
                        shelf.sourceKey == "local" && shelf.title == imported.novel.title &&
                            shelf.totalChapters == imported.chapters.size &&
                            runCatching {
                                NovelStore.chapters(context, shelf.id).firstOrNull()?.title
                            }.getOrNull() == firstTitle
                    }
                    if (exists) return@runCatching "exists"
                    if (!NovelStore.addNovel(context, imported.novel, imported.chapters)) {
                        return@runCatching "failed"
                    }
                    // v1.4.34 D-P2-6：TXT 导入的截断提示随成功 Toast 一起出
                    importWarnings = imported.warnings
                    "added"
                }.getOrDefault("failed")
            }
            importing = false
            PluginUi.toast(context, when (result) {
                "added" -> "已加入书架" + importWarnings.joinToString("") { "（${it}）" }
                "exists" -> "已在书架，跳过重复导入"
                "not_txt" -> "只支持 .txt / .epub 文件，请重新选择"
                "invalid" -> "导入失败：读不到有效文本"
                else -> "导入失败：保存失败"
            })
            if (result == "added") reload()
        }
    }

    // ---------------- 界面 ----------------

    // v0.177.0：书架页正常模式顶部标题栏整行去掉——数据条直接贴顶（仅留状态栏高度）；
    // 多选模式的“已选 N 本”标题栏保留，那是批量操作的操作栏，不是正常顶栏。
    Column(modifier = Modifier.fillMaxSize()) { // v0.238.0：顶部贴顶，去掉多余的状态栏内边距
        if (selectionMode) {
            TabTopBar(
                title = "已选 ${selectedIds.size} 本",
                actions = {
                    TextButton(
                        onClick = {
                            selectedIds = visibleBooks.map { it.id }.toSet()
                        },
                    ) { Text("全选") }
                    // v0.214.0：多选反选（只对当前可见书取反，其余已选不动）。
                    TextButton(
                        onClick = {
                            val visible = visibleBooks.map { it.id }.toSet()
                            selectedIds = (selectedIds - visible) + (visible - selectedIds)
                        },
                    ) { Text("反选") }
                    TextButton(
                        onClick = { if (selectedIds.isNotEmpty()) batchDelete = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            if (selectedIds.isNotEmpty()) groupPickerFor = selectedIds.toList()
                        },
                        enabled = selectedIds.isNotEmpty(),
                    ) { Text("分组") }
                    // v0.215.0：批量换源（逐本在目标源搜书名、按追更同款匹配换目录）。
                    TextButton(
                        onClick = {
                            if (selectedIds.isNotEmpty()) showBatchSource = true
                        },
                        enabled = selectedIds.isNotEmpty(),
                    ) { Text("换源") }
                    // v1.3.33：单本 TXT 导出——只在恰好选中 1 本时可用；EPUB 不做。
                    TextButton(
                        onClick = { exportSelectedTxt() },
                        enabled = selectedIds.size == 1,
                    ) { Text("导出") }
                    IconButton(onClick = {
                        selectionMode = false
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Filled.Close, "取消多选")
                    }
                },
            )
        }
        // v0.180.0：顶行左侧放“全部”，右侧放搜索 + 三点菜单；
        // 最近阅读那一行同步提上来，放在顶行下面、自定义分组上面。
        // 检查更新改下拉刷新、批量管理改长按进多选；
        // 多选模式仍用上面那个“已选 N 本”操作栏。

        // v0.279.0 旧版：12dp 页边距；一级标题改 扁平文字（15/600 + 强调色下划线），
        // 右侧本数（等宽 t3）+ 线性图标，整行底部通栏 1px 分隔线。
        // v1.2.27：发现页签已并进书源管理，这里只剩“书架”一个标题。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = V5.Gutter),
        ) {

            // v0.249.0：头区方案 A——第 1 行只留书架标题（字号稍大），
            // 找书与更多菜单移到本行右侧。
            // v1.2.27：发现页签已并进书源管理。
            // v0.251.0：顶栏合并——第 2 行工具栏拿掉，本数＋新建分组＋视图切换收进本行右侧；
            // 排序与更新失败筛选收进更多菜单，功能不丢；窄屏（<360dp）仍藏本数。
            // v1.4.27 Step 4：顶栏换 SlimGlassBar（f5bb9b44：48dp 玻璃条、无返回、标题「我的书架」15sp、
            // 右侧搜索＋更多；顶栏下方 12dp）。原顶栏的导入/排序/视图切换/本数收进「更多」菜单，功能不丢。
            // 多选模式时上面的“已选 N 本”操作栏照旧，本栏不显示。
            if (!selectionMode) {
                Spacer(Modifier.height(8.dp))
                SlimGlassBar(
                    title = "我的书架",
                    modifier = Modifier.v5Bleed(V5.Gutter),
                ) {
                    GlassIconButton(Icons.Filled.Search, "找书", onClick = { searching = true })
                    Spacer(Modifier.width(NovelTokens.BarActionGap))
                    Box {
                        GlassIconButton(Icons.Filled.MoreVert, "更多", onClick = { showTopMenu = true })
                        GlassMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                        ) {
                            GlassMenuItem(Icons.Outlined.UploadFile, if (importing) "导入中…" else "添加本地书籍", onClick = {
                                showTopMenu = false
                                if (!importing) pickTxt.launch("*/*")
                            })
                            GlassMenuItem(
                                Icons.AutoMirrored.Filled.Sort, "排序",
                                trailing = sortNames.getOrNull(sortMode),
                                onClick = {
                                    showTopMenu = false
                                    showSortMenu = true
                                },
                            )
                            GlassMenuItem(
                                if (viewMode == BookshelfGroupStore.VIEW_LIST) Icons.Filled.GridView
                                else Icons.AutoMirrored.Filled.FormatListBulleted,
                                "书架布局",
                                trailing = if (viewMode == BookshelfGroupStore.VIEW_LIST) "列表" else "网格",
                                onClick = {
                                    showTopMenu = false
                                    viewMode = if (viewMode == BookshelfGroupStore.VIEW_LIST)
                                        BookshelfGroupStore.VIEW_GRID else BookshelfGroupStore.VIEW_LIST
                                    BookshelfGroupStore.setViewMode(context, viewMode)
                                },
                            )
                            GlassMenuItem(Icons.Filled.Checklist, "书架管理", trailing = "${visibleBooks.size} 本", onClick = {
                                showTopMenu = false
                                selectionMode = true
                            })
                            GlassMenuItem(Icons.Filled.Add, "新建分组", onClick = {
                                showTopMenu = false
                                showCreateGroup = true
                            })
                            GlassMenuItem(
                                Icons.Filled.FilterAlt,
                                if (failedIds.isNotEmpty()) "筛选：更新失败(${failedIds.size})" else "筛选：更新失败",
                                trailing = if (shelfTab == 1) "开" else null,
                                onClick = {
                                    showTopMenu = false
                                    shelfTab = if (shelfTab == 1) 0 else 1
                                },
                            )
                            GlassMenuItem(Icons.Filled.Tune, "净化规则", onClick = {
                                showTopMenu = false
                                onOpenPurifyRules()
                            })
                            GlassMenuItem(Icons.Filled.Storage, "书源管理", onClick = {
                                showTopMenu = false
                                onManageSourcesClick()
                            })
                        }
                        // 排序二级菜单：从「更多 → 排序」打开，锚在同一按钮下
                        GlassMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            sortNames.forEachIndexed { i, name ->
                                GlassMenuItem(
                                    Icons.AutoMirrored.Filled.Sort, name,
                                    trailing = if (sortMode == i) "✓" else null,
                                    onClick = {
                                        showSortMenu = false
                                        sortMode = i
                                        BookshelfGroupStore.setSortMode(context, i)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(NovelTokens.BarBelowGap))
            }
        // v0.249.0：全部／更新失败不再占一整行页签，收进第 2 行工具栏的筛选菜单。
        // v0.196.0：搜索框上移到与“找书”同一行——框直接绑活动级搜索 VM
        // （与内嵌找书页同一份 keyword），回车即搜；VM 与焦点提到分支外，避免条件组合。
        val searchActivity = LocalContext.current as ComponentActivity
        val searchVm: NovelSearchViewModel = viewModel(searchActivity)
        val searchKeyword by searchVm.keyword.collectAsState()
        val searchFocus = remember { FocusRequester() }
        // v0.294.55：退出找书态（取消/返回）即复位搜索 VM——活动级 VM 原来只清框词，
        // 结果留着，下次进找书先看到上一次的结果。
        LaunchedEffect(searching) { if (!searching) searchVm.resetSearch() }
        if (searching) {
            LaunchedEffect(Unit) { searchFocus.requestFocus() }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // v0.294.6：统一 AppSearchBar（48dp 胶囊；回车/放大镜即搜并收键盘），
                    // 去掉左侧「找书」大字，框更宽；仍绑活动级 NovelSearchViewModel。
                    AppSearchBar(
                        value = searchKeyword,
                        onValueChange = { searchVm.setKeyword(it) },
                        placeholder = "找书：书名 / 作者",
                        onSearch = { searchVm.doSearch() },
                        focusRequester = searchFocus,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { searching = false }) { Text("取消") }
                }
                // v0.279.0：内嵌找书结果不跟浮动底栏抢位置——底部让出底栏高度
                Box(modifier = Modifier.weight(1f).padding(bottom = dockClearance())) {
                    NovelSearchScreen(
                        embedded = true,
                        autoFocus = false,
                        hideSearchField = true,
                        onManageSources = onManageSourcesClick,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        } else {
        // v0.251.0：分组横滑只在已有自定义分组时出现（没有时不空占一行；新建分组入口在顶栏右侧）。
        // v0.197.0：更新失败页签不看分组，横滑行隐藏。
        if (shelfTab == 0 && shelfGroups.any { it.id != BookshelfGroupStore.GROUP_ALL }) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(shelfGroups.filter { it.id != BookshelfGroupStore.GROUP_ALL }, key = { it.id }) { g ->
                val selected = g.id == selectedGroup
                GroupChip(
                    name = g.name,
                    count = g.count,
                    selected = selected,
                    onClick = {
                        selectedGroup = g.id
                        BookshelfGroupStore.setSelectedGroup(context, g.id)
                    },
                    onLongClick = {
                        // 自定义分组长按：重命名/删除
                        customGroups.find { it.id == g.id }?.let { groupMenuTarget = it }
                    },
                )
            }
        }
        } // v0.197.0：闭合 shelfTab==0 的分组横滑
        Spacer(Modifier.height(8.dp))

        // v0.178.0：下拉刷新 = 原检查更新（获取最新章节），
        // checking 既是刷新指示也是防重入，空书架无书可查不触发。
        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = checking,
            onRefresh = { checkUpdates() },
            modifier = Modifier.fillMaxSize(),
        ) {
        // v0.98.0：内容区（分组切换小动画）
        AnimatedContent(
            targetState = "$selectedGroup|$viewMode|$shelfTab",
            label = "shelfContent",
            modifier = Modifier.fillMaxSize(),
        ) {
            if (books.isEmpty()) {
                // 趣味空状态
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = dockClearance()),
                ) {
                    item {
                        BookshelfEmptyState(
                            onFindBook = { searching = true },
                            onImportTxt = { pickTxt.launch("*/*") },
                        )
                    }
                }
            } else if (visibleBooks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 全量重做：分组空态走共享 EmptyState（文案与原来逐字一致，
                    // v0.197.0 更新失败页签单独说明保留）。
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.MenuBook,
                        title = if (shelfTab == 1) "还没有更新失败的书" else "这个分组还没有书",
                        hint = if (shelfTab == 1) "下拉刷新检查后，失败的会列在这里" else "长按书籍看书籍信息",
                    )
                }
            } else if (viewMode == BookshelfGroupStore.VIEW_GRID) {
                // v0.294.6：列数随宽度自适应（每格 ≥96dp：手机 3 列、宽屏/平板更多）
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = dockClearance() + 8.dp),
                ) {
                    if (checking) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CheckingRow()
                        }
                    }
                    items(visibleBooks, key = { it.id }) { novel ->
                        NovelGridCard(
                            novel = novel,
                            updateCount = updates[novel.id]?.newChapters ?: 0,
                            checking = checkingIds.contains(novel.id),
                            checkFailed = failedIds.contains(novel.id),
                            selectionMode = selectionMode,
                            selected = selectedIds.contains(novel.id),
                            onClick = {
                                if (selectionMode) toggleSelect(novel.id)
                                else openNovel(novel)
                            },
                            onLongClick = {
                                // v0.211.0：长按直接进书籍详情（用户截图口径），
                                // 不再弹长按操作表；分组/换源/删除/收藏都在详情里。
                                if (selectionMode) toggleSelect(novel.id)
                                else detailTarget = novel
                            },
                        )
                    }
                }
            } else {
                // v0.197.0：阅读式 plain 行自带底部分隔线，列表不再额外留 10dp 间距
                // v1.4.27 Step 4：行卡 76dp、行间距 6dp（f5bb9b44 书架列表）。
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ShelfRowTokens.Gap),
                    contentPadding = PaddingValues(top = 0.dp, bottom = dockClearance() + 8.dp),
                ) {
                    if (checking) {
                        item(key = "checking") { CheckingRow() }
                    }
                    items(visibleBooks, key = { it.id }) { novel ->
                        NovelListCard(
                            novel = novel,
                            lastReadAt = progressMap[novel.id]?.updatedAt ?: 0L,
                            updateCount = updates[novel.id]?.newChapters ?: 0,
                            checking = checkingIds.contains(novel.id),
                            checkFailed = failedIds.contains(novel.id),
                            selectionMode = selectionMode,
                            selected = selectedIds.contains(novel.id),
                            onClick = {
                                if (selectionMode) toggleSelect(novel.id)
                                else openNovel(novel)
                            },
                            onLongClick = {
                                // v0.211.0：长按直接进书籍详情（用户截图口径），
                                // 不再弹长按操作表；分组/换源/删除/收藏都在详情里。
                                if (selectionMode) toggleSelect(novel.id)
                                else detailTarget = novel
                            },
                        )
                    }
                }
            }
        }
        } // v0.178.0：闭合 PullToRefreshBox
        } // 闭合 searching else 分支（v0.248.0 修复：原 } else 误配给 searching，属第二重 else）
        }
    }

    // ---------------- 弹窗 ----------------

    // 单删确认
    deleteTarget?.let { target ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除《${target.title}》？",
            confirmText = "删除",
            onConfirm = {
                deleteTarget = null
                deleteNovels(listOf(target))
            },
        ) {
            Text("将从书架移除，阅读进度、书签与追更标记也会清除，本书已缓存的章节正文与段评缓存也会一并清除。")
        }
    }

    // 批量删除确认
    if (batchDelete) {
        TechDialog(
            onDismissRequest = { batchDelete = false },
            title = "删除 ${selectedIds.size} 本书？",
            confirmText = "删除",
            onConfirm = {
                batchDelete = false
                deleteNovels(books.filter { selectedIds.contains(it.id) })
            },
        ) {
            Text("将从书架移除，阅读进度、书签与追更标记也会清除，所选书本已缓存的章节正文与段评缓存也会一并清除。")
        }
    }

    // v0.215.0：批量换源——选目标源，逐本搜书名换目录（本地 TXT 不进换源）。
    if (showBatchSource) {
        TechDialog(
            onDismissRequest = { if (!batchSwitching) showBatchSource = false },
            title = "批量换源",
            // v1.3.33：源长列表走宽松模式。
            compact = false,
            subtitle = "对已选的 ${selectedIds.size} 本逐本在目标源搜书名换目录；搜不到或目录拉取失败的那本不动",
            dismissText = "取消",
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (batchSwitching) {
                    Text("换源中…", fontSize = 13.sp)
                } else {
                    batchJsonSources.filter { it.enabled }.forEach { s ->
                        Text(
                            s.name,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    batchSwitching = true
                                    runBatchSwitch(s.key)
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                    batchPyRules.filter { it.name in batchPyEnabled }.forEach { r ->
                        Text(
                            "${r.title}（本地PY）",
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    batchSwitching = true
                                    runBatchSwitch(com.movieshell.data.novel.PyBookSourceManager.SOURCE_KEY_PREFIX + r.name)
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    // 书籍详情
    detailTarget?.let { target ->
        // 用最新数据（换源后 totalChapters 可能变）
        val fresh = books.find { it.id == target.id } ?: target
        BookDetailDialog(
            novel = fresh,
            lastReadAt = progressMap[fresh.id]?.updatedAt ?: 0L,
            onDismiss = { detailTarget = null },
            onRead = { detailTarget = null; openNovel(fresh) },
            // v0.211.0 审计修复：详情目录点某章直达该章（一次性请求，
            // 阅读器载入时消费，别的入口不受影响）。
            onReadChapter = { idx ->
                detailTarget = null
                com.movieshell.ui.reader.ReaderJumpRequest.chapter = idx
                openNovel(fresh)
            },
            // v1.2.25：目录行点进目录整页（先关详情弹窗再导航）。
            onOpenToc = { detailTarget = null; onOpenToc(fresh.id) },
            onMoveGroup = { detailTarget = null; groupPickerFor = listOf(fresh.id) },
            onDelete = { detailTarget = null; deleteTarget = fresh },
            onOpenPurifyRules = { detailTarget = null; onOpenPurifyRules() },
            // v0.211.0：详情页显示真实分组名与收藏态；收藏切换后不关详情，reload 后状态翻过来。
            groupName = customGroups.find { fresh.id in it.novelIds }?.name ?: "未分组",
            isFavorite = customGroups.find { it.name == "收藏" }?.novelIds?.contains(fresh.id) == true,
            onToggleFavorite = { toggleFavorite(fresh) },
            onSourceSwitched = {
                PluginUi.toast(context, "已换源")
                reload()
            },
        )
    }

    // 分组选择（移入）
    groupPickerFor?.let { ids ->
        GroupPickerDialog(
            groups = customGroups,
            onDismiss = { groupPickerFor = null },
            onPick = { g ->
                val group = customGroups.find { it.id == g.id } ?: g
                moveToGroup(group, ids)
            },
            onCreateNew = { showCreateGroup = true },
        )
    }

    // 新建分组
    if (showCreateGroup) {
        GroupNameDialog(
            title = "新建分组",
            onDismiss = { showCreateGroup = false },
            onConfirm = { name ->
                showCreateGroup = false
                scope.launch {
                    val g = withContext(Dispatchers.IO) {
                        BookshelfGroupStore.create(context, name)
                    }
                    if (g.id.isNotBlank()) {
                        // 从分组选择器进来时，建完直接移入
                        val ids = groupPickerFor
                        reload()
                        if (ids != null) moveToGroup(g, ids) else PluginUi.toast(context, "已创建「${g.name}」")
                    }
                }
            },
        )
    }

    // 自定义分组长按菜单：重命名/删除
    groupMenuTarget?.let { g ->
        TechDialog(
            onDismissRequest = { groupMenuTarget = null },
            title = "「${g.name}」",
            onConfirm = null,
            dismissText = "取消",
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            groupMenuTarget = null
                            renameTarget = g
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("重命名", fontSize = 14.sp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            groupMenuTarget = null
                            scope.launch {
                                // v0.247.0 第 2 轮：delete 返回 Boolean——原来忽略，
                                // 写盘失败也弹“已删除”；现在按结果提示，失败不挪选中态。
                                val deleted = withContext(Dispatchers.IO) {
                                    BookshelfGroupStore.delete(context, g.id)
                                }
                                if (deleted) {
                                    if (selectedGroup == g.id) {
                                        selectedGroup = BookshelfGroupStore.GROUP_ALL
                                        BookshelfGroupStore.setSelectedGroup(context, selectedGroup)
                                    }
                                    PluginUi.toast(context, "已删除分组（书籍保留）")
                                } else {
                                    PluginUi.toast(context, "删除分组失败，请重试")
                                }
                                reload()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("删除分组", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "删除分组不会删除书籍",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    // 重命名分组
    renameTarget?.let { g ->
        GroupNameDialog(
            title = "重命名分组",
            initial = g.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        BookshelfGroupStore.rename(context, g.id, name)
                    }
                    reload()
                }
            },
        )
    }
}

/** v0.98.0：分组 chip（长按自定义分组弹出重命名/删除）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChip(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val borderBrush = Brush.horizontalGradient(LocalBrandGradient.current)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (selected) Modifier.border(
                    BorderStroke(1.5.dp, borderBrush),
                    RoundedCornerShape(16.dp),
                )
                else Modifier.border(
                    BorderStroke(
                        1.dp,
                        // v1.2.14 E片：未选分组 Chip 描边 onSurfaceVariant 0.25 只有约 1.5:1，改 outline（4.27/5.86）。
                        MaterialTheme.colorScheme.outline,
                    ),
                    RoundedCornerShape(16.dp),
                ),
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count >= 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                "$count",
                fontSize = 11.sp,
                color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), // v1.2.14 E片：分组计数 0.7 浅色只有 3.98:1，改 0.8（5.14:1）
            )
        }
    }
}

/** v0.84.0：检查中提示条（搬运）。 */
@Composable
private fun CheckingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "正在检查更新…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * v1.3.33：单本 TXT 导出拼文本（纯函数，可单测）——书名 + 作者 + 每章
 * “标题 + 正文”，章与章之间空一行；正文为空的章只留标题。
 */
internal fun buildTxtExport(novel: Novel, chapters: List<Chapter>): String {
    val sb = StringBuilder()
    sb.append(novel.title.ifBlank { "未知书名" }).append("\n")
    novel.author?.takeIf { it.isNotBlank() }?.let { sb.append("作者：$it\n") }
    chapters.forEach { ch ->
        sb.append("\n")
        sb.append(ch.title.ifBlank { "未命名章节" }).append("\n")
        val body = ch.content.trim()
        if (body.isNotEmpty()) sb.append(body).append("\n")
    }
    return sb.toString()
}
