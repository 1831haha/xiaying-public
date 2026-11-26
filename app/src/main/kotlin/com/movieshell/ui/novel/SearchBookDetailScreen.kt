package com.movieshell.ui.novel

import androidx.activity.ComponentActivity
import com.movieshell.ui.theme.StaticGlowBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.novel.NovelTocService
import com.movieshell.data.novel.TocGap
import com.movieshell.data.store.NovelStore
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.reader.ReaderJumpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.229.0：搜书结果点书进的书籍信息整页（对标图 1：毛玻璃封面头＋标签＋
 * 信息行＋简介展开＋目录＋放入书架/阅读）。书架那个 BookDetailDialog 是
 * 弹窗，这里是整页；数据走详情规则（名/作者/简介/封面/类型）＋目录，
 * 源没给的字段（状态/人气）不编假数据，直接不显示那行。
 * 阅读器只认书架条目，所以“阅读/点某章”在未入架时先入架再打开，
 * 入架失败（joinShelf 自己会 toast）就取消待阅读，不擅自跳转。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchBookDetailScreen(
    group: SourceGroup,
    item: NovelSearchResult,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalContext.current as ComponentActivity
    val vm: NovelSearchViewModel = viewModel(activity)
    val isPy = group.key.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)
    val jsonSources by BookSourceManager.sources.collectAsState()
    val source = remember(group.key, jsonSources) {
        if (isPy) null else jsonSources.firstOrNull { it.key == group.key }
    }

    // v1.4.27 Step4 审稿 A6：非 JS 的 JSON 源才可“展开全部”（源找不到按不可展开）。
    val sourceExpandable = remember(source) { source?.let { !NovelSourceEngine.isJsBookSource(it) } == true }

    var detail by remember { mutableStateOf<BookDetailInfo?>(null) }
    var chapterTitles by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tocExpanded by remember { mutableStateOf(false) }
    var introExpanded by remember { mutableStateOf(false) }
    var tocRetry by remember { mutableIntStateOf(0) }
    var shelfId by remember { mutableStateOf<String?>(null) }
    // 待阅读：-1 = 继续读，>=0 = 跳到该章；入架失败时由 joining 效应清掉。
    var pendingRead by remember { mutableStateOf<Int?>(null) }
    // v0.249.0 收尾：等目录时的按钮反馈（“正在获取目录…”），返回即取消等待、后台补取继续。
    var waitingDir by remember { mutableStateOf(false) }
    // v1.4.27 P0.3：目录缺段（章号中段跳了一大截）与“展开全部”进行中
    var tocGap by remember { mutableStateOf<TocGap.Gap?>(null) }
    var expanding by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val title = detail?.name?.takeIf { it.isNotBlank() } ?: item.title
    // v0.230.0：搜书结果的 author 自带“作者：”前缀（999 搜索规则原样带出），详情页已有“作者”标签列，去掉前缀防“作者 作者：…”重复。
    val author = (detail?.author?.takeIf { it.isNotBlank() } ?: item.author)
        ?.removePrefix("作者：")?.removePrefix("作者:")?.trim()
    val cover = detail?.cover?.takeIf { it.isNotBlank() } ?: item.cover
    val intro = detail?.intro?.takeIf { it.isNotBlank() }
    val kindTags = detail?.kind?.takeIf { it.isNotBlank() }
        ?.split(Regex("[/、，,|\\s]+"))?.map { it.trim() }?.filter { it.isNotBlank() }
        .orEmpty()

    suspend fun resolveShelfId(): String? = withContext(Dispatchers.IO) {
        NovelStore.list(context).firstOrNull { it.id in shelfNovelIds(group, item) }?.id
    }

    suspend fun remoteTitles(): List<String>? = withContext(Dispatchers.IO) {
        // v0.232.0：拉目录失败只该显示“加载目录失败”，绝不能崩 App——
        // 09:46:10 的崩溃就是这条没兜：chapters() 一抛，LaunchedEffect 直接把 App 带走；入架链路有 runCatching 兜着才只变 toast。
        // v1.4.27 解缠⑤：详情页只读目录服务、不自己抓（服务内部已兜异常，取消原样抛）；
        // 用户点“重试”（tocRetry>0）时强制现抓。服务缓存的目录入架时直接复用，不再整本重抓。
        when (val st = NovelTocService.remote(context, item.sourceKey, item, force = tocRetry > 0)) {
            is NovelTocService.TocState.Ready -> st.chapters.map { it.title }
            // v1.4.27 P0.3：缺段照常显示已有章节，另给提示与“展开全部”
            is NovelTocService.TocState.Partial -> st.chapters.map { it.title }
            else -> null
        }
    }

    LaunchedEffect(group.key, item.remoteId, tocRetry, source) {
        loading = true
        val sid = resolveShelfId()
        shelfId = sid
        if (!isPy && source != null) {
            detail = NovelSourceEngine.novelEngineFor(context, source).bookDetail(item)
        }
        chapterTitles = if (sid != null) {
            withContext(Dispatchers.IO) {
                NovelStore.chapters(context, sid).map { it.title }.takeIf { it.isNotEmpty() }
            }
        } else {
            remoteTitles()
        }
        tocGap = chapterTitles?.let { TocGap.detect(it) }
        loading = false
    }

    // 入架完成（joining 清空）后刷新书架态；入架失败则取消待阅读。
    // joinShelf 先写盘再清 joining（finally 里减），这里读到的 shelfId 是准的。
    val joining by vm.joining.collectAsState()
    LaunchedEffect(joining) {
        if (joining.isNotEmpty()) return@LaunchedEffect
        val sid = resolveShelfId()
        if (sid != null && sid != shelfId) {
            shelfId = sid
            chapterTitles = withContext(Dispatchers.IO) {
                NovelStore.chapters(context, sid).map { it.title }.takeIf { it.isNotEmpty() }
            }
        } else if (sid == null) {
            pendingRead = null
        }
    }

    LaunchedEffect(pendingRead, shelfId) {
        val target = pendingRead ?: return@LaunchedEffect
        val sid = shelfId ?: return@LaunchedEffect
        // v0.249.0：刚入架的壳目录还在后台取——等补取终态再进阅读器，不进空阅读页。
        // 等待可感知（按钮变“正在获取目录…”）、可退出（返回即取消本效应，后台补取继续）。
        waitingDir = true
        try {
            if (!NovelTocService.awaitReady(context, sid)) {
                AppToast.show(context, "目录获取失败，请在书架中重试", long = false)
                pendingRead = null
                return@LaunchedEffect
            }
        } finally {
            waitingDir = false
        }
        if (target >= 0) ReaderJumpRequest.chapter = target
        pendingRead = null
        onOpenReader(sid)
    }

    fun requestRead(chapter: Int) {
        if (chapterTitles.isNullOrEmpty()) {
            AppToast.show(context, "目录获取失败", long = false)
            return
        }
        pendingRead = chapter
        if (shelfId == null) vm.joinShelf(group, item)
    }

    // v1.3.33：二级页静态渐变底。
    Box(Modifier.fillMaxSize()) {
        StaticGlowBackground(Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("书籍信息", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    // 图 1 式封面头：封面原图淡化铺底＋一层底色罩，居中放封面卡。
                    Box(
                        modifier = Modifier.fillMaxWidth().height(175.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (!cover.isNullOrBlank()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().alpha(0.35f),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 110.dp, height = 148.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!cover.isNullOrBlank()) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(
                                    title.firstOrNull()?.toString() ?: "书",
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), // v1.2.14 E片：占位首字 0.5 只有 2.50:1，改 0.8（5.14:1）
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    if (kindTags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            kindTags.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(tag, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    // 信息行：只列真实有数据的项（状态/人气源没给就不画，见 211 详情同款注释）。
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DetailInfoRow(
                            icon = Icons.Filled.Person,
                            label = "作者",
                            value = author?.takeIf { it.isNotBlank() } ?: "未知作者",
                        )
                        DetailInfoRow(icon = Icons.Filled.Public, label = "来源", value = group.name)
                        DetailInfoRow(
                            icon = Icons.Filled.Explore,
                            label = "最新",
                            value = chapterTitles?.lastOrNull()?.takeIf { it.isNotBlank() }
                                ?: if (loading) "加载中…" else "暂无目录",
                        )
                        DetailInfoRow(
                            icon = Icons.Filled.Folder,
                            label = "目录",
                            value = chapterTitles?.let { "共 ${it.size} 章" }
                                ?: if (loading) "加载中…" else "加载目录失败",
                            actionText = if (tocExpanded) "收起目录" else "查看目录",
                            onAction = {
                                if (chapterTitles == null) {
                                    AppToast.show(context, "目录获取失败", long = false)
                                    tocRetry++
                                } else {
                                    tocExpanded = !tocExpanded
                                }
                            },
                        )
                        // v1.4.27 P0.3：目录可能不全——如实提示；未入架的 JSON 源给“展开全部”
                        // （只跟页上的普通展开链接与目录分页规则；靠网页脚本展开的站本版拿不到）。
                        // v1.4.27 Step 4b：缺段提示换样式（TocGapHint：琥珀色提示条＋文字链「展开全部」），行为不变。
                        tocGap?.let { gap ->
                            TocGapHint(
                                gap = gap,
                                expanding = expanding,
                                // v1.4.27 Step4 审稿 A6：JS 源（以及源已删）不给「展开全部」——expandAll 对它们只会回 Unsupported。
                                // NovelSourceEngine.isJsSource 是 private，用同文件公开的等价包装 isJsBookSource。
                                canExpand = shelfId == null && !isPy && sourceExpandable,
                                modifier = Modifier.padding(top = 6.dp),
                                onExpand = {
                                    expanding = true
                                    scope.launch {
                                        try {
                                            val before = chapterTitles?.size ?: 0
                                            when (val r = NovelTocService.expandAll(context, item.sourceKey, item, before)) {
                                                is NovelTocService.ExpandResult.Improved -> {
                                                    val st = r.state
                                                    val metas = when (st) {
                                                        is NovelTocService.TocState.Ready -> st.chapters
                                                        is NovelTocService.TocState.Partial -> st.chapters
                                                        else -> null
                                                    }
                                                    if (metas != null) {
                                                        chapterTitles = metas.map { it.title }
                                                        tocGap = (st as? NovelTocService.TocState.Partial)?.gap
                                                    }
                                                    AppToast.show(context, "已展开：${r.before} 章 → ${r.after} 章" + if (tocGap != null) "，仍可能不全" else "", long = false)
                                                }
                                                NovelTocService.ExpandResult.NoMore ->
                                                    AppToast.show(context, "没能展开：本站完整目录要靠网页脚本加载，暂不支持，可换源", long = true)
                                                NovelTocService.ExpandResult.Unsupported ->
                                                    AppToast.show(context, "这个书源暂不支持展开全部，可换源", long = false)
                                                NovelTocService.ExpandResult.Failed ->
                                                    AppToast.show(context, "展开失败，请重试", long = false)
                                            }
                                        } finally {
                                            expanding = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (intro != null) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(
                                intro,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (introExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { introExpanded = !introExpanded }) {
                                Text(if (introExpanded) "[收起]" else "[+展开]", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (tocExpanded && chapterTitles != null) {
                    itemsIndexed(chapterTitles!!, key = { i, _ -> i }) { index, chTitle ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { requestRead(index) }
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chTitle.ifBlank { "第 ${index + 1} 章" },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = shelfId == null,
                    onClick = { vm.joinShelf(group, item) },
                ) {
                    Text(if (shelfId == null) "放入书架" else "已在书架", fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { requestRead(-1) },
                    enabled = !waitingDir,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(if (waitingDir) "正在获取目录…" else "阅读", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionText, fontSize = 13.sp)
            }
        }
    }
}
