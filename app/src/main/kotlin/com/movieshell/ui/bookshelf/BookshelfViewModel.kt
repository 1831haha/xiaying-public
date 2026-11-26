package com.movieshell.ui.bookshelf

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.movieshell.data.store.BookGroup
import com.movieshell.data.store.BookshelfGroupStore
import com.movieshell.data.store.NovelStore
import com.movieshell.data.store.NovelUpdateInfo
import com.movieshell.data.store.NovelUpdateStore
import com.movieshell.data.store.ReadingProgressStore
import com.movieshell.domain.model.Novel
import com.movieshell.domain.model.ReadingProgress
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.NovelUpdateChecker
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.ui.market.PluginUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 书架分组 chip 的数据（原 BookshelfScreen 里的局部类，v1.4.27 提到顶层供 VM 计算用，字段不变）。 */
data class ShelfGroup(val id: String, val name: String, val count: Int)

/**
 * v1.4.27 解缠⑦：书架抽 ViewModel——**只搬状态，界面不动**。
 *
 * 原 BookshelfScreen 顶部 25 个 remember / rememberSaveable 状态搬到这里，用 Compose
 * snapshot 状态承载；界面里 `var books by vm::books` 原名原用法，UI 代码一行没改。
 * - 原 rememberSaveable 的两个（[shelfTab]、[searching]）落 SavedStateHandle，进程重建照样恢复；
 * - 原 remember 的状态在每次进入书架时由 [onScreenEnter] 复位到初值（与 remember 的生命周期一致：
 *   离开再回来弹窗、多选、进行态都不残留；视图/排序/分组/失败 id 照旧从 BookshelfGroupStore 重读）；
 *   书目/进度/角标/分组这四份数据不清空，回来先显示上次的、[reload] 读完再替换（少一次空白闪）。
 * - [reload] 搬进来（viewModelScope）；列表筛选排序、分组计数提成纯函数 [visibleBooks]/[shelfGroups]，
 *   与原 remember 块逐字等价，可单测。
 * - v1.4.27 Step 3：检查更新、批量换源两个联网长操作搬进来（见下方说明）；导出、删书等短的本地操作
 *   仍在界面。BookshelfGroupStore 只做持久化。
 */
class BookshelfViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    // ---- 数据 ----
    var books by mutableStateOf<List<Novel>>(emptyList())
    var progressMap by mutableStateOf<Map<String, ReadingProgress>>(emptyMap())
    var updates by mutableStateOf<Map<String, NovelUpdateInfo>>(emptyMap())
    var customGroups by mutableStateOf<List<BookGroup>>(emptyList())

    // ---- 进行态 ----
    var importing by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var checkingIds by mutableStateOf<Set<String>>(emptySet())
    var batchSwitching by mutableStateOf(false)

    // ---- 偏好（持久化在 BookshelfGroupStore，这里是内存镜像） ----
    var viewMode by mutableIntStateOf(BookshelfGroupStore.viewMode(app))
    var sortMode by mutableIntStateOf(BookshelfGroupStore.sortMode(app))
    var selectedGroup by mutableStateOf(BookshelfGroupStore.selectedGroup(app))
    var failedIds by mutableStateOf(BookshelfGroupStore.failedIds(app))

    // ---- 原 rememberSaveable：落 SavedStateHandle ----
    private val shelfTabState = mutableIntStateOf(saved.get<Int>(KEY_TAB) ?: 0)
    var shelfTab: Int
        get() = shelfTabState.intValue
        set(v) { shelfTabState.intValue = v; saved[KEY_TAB] = v }

    private val searchingState = mutableStateOf(saved.get<Boolean>(KEY_SEARCHING) ?: false)
    var searching: Boolean
        get() = searchingState.value
        set(v) { searchingState.value = v; saved[KEY_SEARCHING] = v }

    // ---- 多选 ----
    var selectionMode by mutableStateOf(false)
    var selectedIds by mutableStateOf<Set<String>>(emptySet())

    // ---- 弹窗 ----
    var showBatchSource by mutableStateOf(false)
    var deleteTarget by mutableStateOf<Novel?>(null)
    var batchDelete by mutableStateOf(false)
    var detailTarget by mutableStateOf<Novel?>(null)
    var groupPickerFor by mutableStateOf<List<String>?>(null)
    var showCreateGroup by mutableStateOf(false)
    var renameTarget by mutableStateOf<BookGroup?>(null)
    var groupMenuTarget by mutableStateOf<BookGroup?>(null)

    /** v0.217.0（F40）逐本回写互斥锁（原界面 remember { Mutex() }）。 */
    val checkWriteMutex = Mutex()

    /**
     * 进入书架（组合首次进入）时调用：原 remember 状态复位到初值，偏好从盘重读。
     * 原 rememberSaveable 的 [shelfTab]/[searching] 不动。
     */
    fun onScreenEnter() {
        importing = false
        // v1.4.27 Step 3：检查更新/批量换源在 VM 里跑，回来时任务还在就保留进行态，不复位。
        if (checkJob?.isActive != true) {
            checking = false
            checkingIds = emptySet()
        }
        val switching = switchJob?.isActive == true
        if (!switching) batchSwitching = false
        viewMode = BookshelfGroupStore.viewMode(ctx)
        sortMode = BookshelfGroupStore.sortMode(ctx)
        selectedGroup = BookshelfGroupStore.selectedGroup(ctx)
        failedIds = BookshelfGroupStore.failedIds(ctx)
        if (!switching) {
            selectionMode = false
            selectedIds = emptySet()
            showBatchSource = false
        }
        deleteTarget = null
        batchDelete = false
        detailTarget = null
        groupPickerFor = null
        showCreateGroup = false
        renameTarget = null
        groupMenuTarget = null
    }

    /** 重读书架（原界面 reload()，逐行搬来）。 */
    fun reload() {
        viewModelScope.launch {
            val context = ctx
            val all = withContext(Dispatchers.IO) { NovelStore.list(context) }
            // v0.270.0（T19）：进度一次读全表，不再逐本各读一遍文件。
            val allProgress = withContext(Dispatchers.IO) { ReadingProgressStore.loadAll(context) }
            val pm = all.associate { novel ->
                novel.id to (allProgress[novel.id] ?: ReadingProgress(novelId = novel.id))
            }
            val ups = withContext(Dispatchers.IO) { NovelUpdateStore.getAll(context) }
            val gs = withContext(Dispatchers.IO) { BookshelfGroupStore.list(context) }
            progressMap = pm
            updates = ups
            customGroups = gs
            // 分组被删了就回全部
            val validIds = setOf(BookshelfGroupStore.GROUP_ALL) + gs.map { it.id }
            if (selectedGroup !in validIds) {
                selectedGroup = BookshelfGroupStore.GROUP_ALL
                BookshelfGroupStore.setSelectedGroup(context, selectedGroup)
            }
            books = all
            // 删书后多选集合里可能残留不存在的 id，顺手清理
            selectedIds = selectedIds.filter { id -> all.any { it.id == id } }.toSet()
        }
    }

    // ---------------- v1.4.27 Step 3：联网长操作搬进 VM ----------------
    // 结论（按定稿 §三.7“书架抽 ViewModel”）：检查更新、批量换源是联网长操作，跑在 viewModelScope——
    // 书架路由还在返回栈上时（进阅读器/书详情/目录再回来）任务不取消、逐本结果照常回写；
    // 书架路由真正出栈（VM onCleared）才取消，与改版前离开书架即取消同口径。
    // 删书/移组/收藏/导出/导入 TXT 是短的本地操作（导出要 Activity 上下文分享、导入要 ActivityResult），留在界面。
    private var checkJob: Job? = null
    private var switchJob: Job? = null

    // v0.215.0：批量换源执行——本地 TXT 跳过（没有源可换），逐本走限流，
    // 搜不到或目录拉取失败的那本不动，最后统一报成功／失败本数。
    fun runBatchSwitch(targetKey: String) {
        val context = ctx
        val targets = books.filter { selectedIds.contains(it.id) && it.sourceKey != "local" }
        switchJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.movieshell.data.novel.BookSourceManager.load(context)
                com.movieshell.data.novel.PyBookSourceManager.load(context)
            }
            var ok = 0
            var fail = 0
            for (n in targets) {
                val done = NovelSourceEngine.withSearchThrottle {
                    NovelUpdateChecker.switchToSource(context, n, targetKey)
                }
                if (done) ok++ else fail++
            }
            batchSwitching = false
            showBatchSource = false
            selectionMode = false
            selectedIds = emptySet()
            PluginUi.toast(context, "批量换源完成：成功 $ok 本，失败 $fail 本")
            reload()
        }
    }

    // v0.217.0（F40）：逐本回写的互斥锁——每本查完的回调里都有“读 failedIds／
    // updates → 改 → 写盘”三步，并发回调互相覆盖，失败态会丢；
    // 锁把每本的回写串行化，逐本随查随写盘的好处（中途退出不丢）保留。

    // v0.84.0：检查全部在线书的更新（本地 TXT 跳过）。
    // v0.211.0：逐本状态——原来 checkAllDetailed 只在全部跑完才回结果，
    // 刷新时每本书后面没有自己的状态，只剩顶部一行“正在检查更新…”。
    // 改成每本书一个 async，查完立刻把自己从 checkingIds 摘掉：
    // 圈圈还在转 = 这本还没查完；圈圈停了 + 数字角标 = 有更新；
    // 圈圈停了 + 红色“失败” = 这本没查到。写盘也在逐本回调里
    // 随查随做（NovelUpdateStore 自带锁串行化，不怕互相覆盖）。
    fun checkUpdates() {
        if (checking) return
        checking = true
        val context = ctx
        checkJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                BookSourceManager.load(context)
                PyBookSourceManager.load(context)
            }
            val online = books.filter { it.sourceKey != "local" && it.updateEnabled }
            checkingIds = online.map { it.id }.toSet()
            val results = online.map { novel ->
                async {
                    // v0.211.0 回归修复（流程审计 H2）：逐本 async 时丢了
                    // checkAllDetailed 里包的 Semaphore(4) 限流，几百本书会
                    // 齐发搜索+拉目录重蹈 v0.109 前被反爬封 IP 的覆辙；逐本
                    // 完成回调里包一层 withSearchThrottle，限流和逐本状态兼得。
                    val r = NovelSourceEngine.withSearchThrottle {
                        NovelUpdateChecker.check(context, novel)
                    }
                    // 回写段整体加锁：读-改-写三步不可被另一本的回调插进来。
                    checkWriteMutex.withLock {
                        checkingIds = checkingIds - novel.id
                        when (r) {
                            is NovelUpdateChecker.Result.Updated -> {
                                val total = (updates[novel.id]?.newChapters ?: 0) + r.newCount
                                updates = updates + (novel.id to NovelUpdateInfo(novel.id, total, System.currentTimeMillis()))
                                failedIds = failedIds - novel.id
                                NovelUpdateStore.set(context, novel.id, r.newCount)
                                BookshelfGroupStore.setFailedIds(context, failedIds)
                            }
                            is NovelUpdateChecker.Result.Failed -> {
                                failedIds = failedIds + novel.id
                                BookshelfGroupStore.setFailedIds(context, failedIds)
                            }
                            else -> {
                                failedIds = failedIds - novel.id
                                BookshelfGroupStore.setFailedIds(context, failedIds)
                            }
                        }
                    }
                    novel.id to r
                }
            }.awaitAll()
            val result = results.mapNotNull { (id, r) ->
                (r as? NovelUpdateChecker.Result.Updated)?.let { id to it.newCount }
            }.toMap()
            val failed = results.count { (_, r) -> r is NovelUpdateChecker.Result.Failed }
            // 角标与失败态已在逐本回调里随查随写盘，这里不再批量重写
            // （批量重写会把中途已写盘的结果再覆盖一遍，还丢累加语义）。
            checking = false
            checkingIds = emptySet()
            reload()
            PluginUi.toast(context,
                when {
                    result.isNotEmpty() -> "${result.size} 本有更新"
                    online.isNotEmpty() && failed == online.size -> "检查失败，稍后再试"
                    failed > 0 -> "已检查，$failed 本检查失败"
                    else -> "都追上了，没有新章节"
                },
            )
        }
    }


    companion object {
        private const val KEY_TAB = "shelf_tab"
        private const val KEY_SEARCHING = "shelf_searching"

        /** 分组 chip：全部（最前）+ 自定义分组，各带本数。 */
        fun shelfGroups(books: List<Novel>, customGroups: List<BookGroup>): List<ShelfGroup> =
            listOf(ShelfGroup(BookshelfGroupStore.GROUP_ALL, "全部", books.size)) +
                customGroups.map { g -> ShelfGroup(g.id, g.name, books.count { g.novelIds.contains(it.id) }) }

        /** 当前页签/分组/排序下可见的书（原 visibleBooks 的 remember 块）。 */
        fun visibleBooks(
            books: List<Novel>,
            selectedGroup: String,
            sortMode: Int,
            progressMap: Map<String, ReadingProgress>,
            updates: Map<String, NovelUpdateInfo>,
            customGroups: List<BookGroup>,
            shelfTab: Int,
            failedIds: Set<String>,
        ): List<Novel> {
            // v0.197.0：更新失败页签只列上次检查失败的书（不看分组）；全部页签沿用分组筛选
            val preFiltered = if (shelfTab == 1) books.filter { it.id in failedIds } else books
            val filtered = if (shelfTab == 1) preFiltered else when (selectedGroup) {
                BookshelfGroupStore.GROUP_ALL -> preFiltered
                else -> {
                    val ids = customGroups.find { it.id == selectedGroup }?.novelIds?.toSet()
                        ?: emptySet()
                    preFiltered.filter { it.id in ids }
                }
            }
            return when (sortMode) {
                BookshelfGroupStore.SORT_UPDATED ->
                    filtered.sortedWith(
                        compareByDescending<Novel> { updates[it.id]?.checkedAt ?: 0L }
                            .thenByDescending { progressMap[it.id]?.updatedAt ?: 0L },
                    )
                BookshelfGroupStore.SORT_TITLE -> filtered.sortedBy { it.title }
                BookshelfGroupStore.SORT_PROGRESS -> filtered.sortedByDescending { it.readFraction() }
                else -> filtered.sortedByDescending { progressMap[it.id]?.updatedAt ?: 0L }
            }
        }
    }
}
