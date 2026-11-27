package com.movieshell.ui.novel

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.movieshell.ui.components.TechDialog
import com.movieshell.domain.model.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelExploreLoader
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import kotlinx.coroutines.launch

/**
 * v0.201.0：发现页（书架子结构内入口：书源管理行末「发现」）。
 * v0.210.0：默认按原网页直渲——顶部仍是分类横滑条，下方直接加载分类
 * 对应的原网页（封面、版式都和网站一致）；点进书的详情页后，底部
 * 「加入书架」走现有入架流程（标题取当前页标题，去掉站名后缀）。
 * 原先的规则解析文字列表留作切换入口（右上角「列表」），原网页不
 * 适合看的源还能切回去；切回列表时才抓第一页，不浪费一次请求。
 * 返回键先退 WebView 历史，退到底才回上一页。
 */
@Composable
fun ExploreScreen(
    sourceKey: String,
    // v0.248.0：发现页胶囊点进来时预选该分类；空则沿用原来默认首分类。
    initialCategory: String? = null,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchVm: NovelSearchViewModel = viewModel()
    val joining by searchVm.joining.collectAsState()
    val sources by BookSourceManager.sources.collectAsState()
    val source = sources.firstOrNull { it.key == sourceKey }

    val categories = remember(source) {
        source?.let { NovelSourceEngine.parseExploreCategories(it.exploreUrl) }.orEmpty()
    }
    var selected by remember(sourceKey) { mutableStateOf<NovelSourceEngine.ExploreCategory?>(null) }
    // v1.4.35：选中分类按地址存进 rememberSaveable——selected 本身是 remember，
    // 进书详情再返回会丢；恢复时按地址反查分类（标题可能重复，地址唯一）。
    var savedCatUrl by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var results by remember(sourceKey) { mutableStateOf<List<NovelSearchResult>>(emptyList()) }
    var page by remember(sourceKey) { mutableStateOf(1) }
    var loading by remember(sourceKey) { mutableStateOf(false) }
    // v0.246.0：加载代际——切分类时旧请求结果不许覆盖新分类
    var loadGen by remember(sourceKey) { mutableStateOf(0) }
    var notice by remember(sourceKey) { mutableStateOf<String?>(null) }
    // v1.4.27 P0.8：发现默认列表——有 ruleExploreList 的源默认进列表（解析成书目），
    // 没有列表规则的源仍默认原网页直渲（列表解析不出东西）。顶部按钮仍可随时切换。
    // key 带上“源是否已载入”：冷启动时书源表还没载入（source=null），载入后按规则重定一次默认。
    // v1.4.35：列表/原网页模式进详情再返回不丢；key 带 source != null，
    // 冷启动源表未载入时先按空默认，载入后仍按规则重定一次默认（与原来一致）。
    var webMode by rememberSaveable(sourceKey, source != null) {
        mutableStateOf(ExploreDefaults.defaultWebMode(source))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember(sourceKey) { mutableStateOf("") }
    var pageTitle by remember(sourceKey) { mutableStateOf("") }
    var webLoading by remember(sourceKey) { mutableStateOf(false) }
    // v0.215.0：列表行详情预览——点行先看预览（封面／书名／作者／来源），确认才入架。
    var previewItem by remember(sourceKey) { mutableStateOf<NovelSearchResult?>(null) }

    val categoryUrl = source?.let { src ->
        selected?.let { NovelSourceEngine.explorePageUrl(src, it.url) }
    }.orEmpty()

    // v1.4.27 解缠⑥：抓取走 NovelExploreLoader（按源/分类/页缓存）；force=“刷新”按钮现抓。
    fun load(src: BookSource, cat: NovelSourceEngine.ExploreCategory, p: Int, append: Boolean, force: Boolean = false) {
        // v0.246.0：代际保护——原来 loading 时新请求直接 return，
        // 切分类后旧请求完成仍把旧分类结果写进 results（selected 已是新分类）。
        // 改每次 load 递增代际，写回时只认最新一代；旧请求结果直接丢弃。
        val myGen = ++loadGen
        loading = true
        notice = null
        scope.launch {
            val list = NovelExploreLoader.load(context, src, cat.url, p, force)
            if (myGen != loadGen) return@launch
            results = if (append) (results + list).distinctBy { it.remoteId } else list
            if (list.isEmpty() && !append) notice = "这一页没有结果（可能撞上验证，稍后重试）"
            loading = false
        }
    }

    // v0.248.0：effect key 带上 initialCategory——同页内初始分类变化时也能重匹配；
    // 已选过分类时守卫（selected != null）拦住，不会覆盖用户点的分类。
    LaunchedEffect(categories.firstOrNull(), initialCategory) {
        val first = categories.firstOrNull() ?: return@LaunchedEffect
        if (selected == null) {
            // v1.4.35：恢复优先按保存的分类地址（返回时），再按入参初始分类，最后首个分类。
            selected = categories.firstOrNull { it.url == savedCatUrl }
                ?: categories.firstOrNull { it.title == initialCategory } ?: first
            savedCatUrl = selected?.url
            page = 1
            // v1.4.27 P0.8：默认列表模式下首个分类自动加载（原来只有切到列表/点分类才抓）。
            val src = source
            val cat = selected
            if (!webMode && src != null && cat != null && results.isEmpty()) load(src, cat, 1, false)
        }
    }

    LaunchedEffect(selected, webMode) {
        if (webMode) {
            pageUrl = categoryUrl
            pageTitle = ""
            webLoading = categoryUrl.isNotBlank()
        }
    }

    // v1.4.27 Step 4b：发现页换 SlimGlassBar（返回＋标题「发现」）＋文字分类导航＋84dp 书卡＋瘦「加载更多」。
    // 返回逻辑、列表/原网页切换、加载/代际保护、预览入架全部照旧，只换外观。
    val goBack: () -> Unit = {
        if (webMode && webView?.canGoBack() == true) webView?.goBack() else onBack()
    }
    fun toggleMode() {
        val toList = webMode
        webMode = !webMode
        if (toList) {
            val src = source ?: return
            val cat = selected ?: return
            page = 1
            if (results.isEmpty()) load(src, cat, 1, false)
        }
    }
    fun reload() {
        val src = source ?: return
        val cat = selected ?: return
        page = 1
        load(src, cat, 1, false, force = true)
    }
    val p = novelPalette()
    NovelBackground(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        SlimGlassBar(title = "发现", onBack = goBack) {
            // 右侧：列表/原网页切换（原「列表/原网页」文字钮）＋刷新。设计稿写「筛选与搜索」，
            // 发现页没有这两项功能，不摆空壳，用现有两项功能占位【自定】。
            GlassIconButton(
                if (webMode) Icons.AutoMirrored.Filled.ViewList else Icons.Outlined.Language,
                if (webMode) "切到列表" else "切到原网页",
                onClick = { toggleMode() },
            )
            Spacer(Modifier.width(NovelTokens.BarActionGap))
            GlassIconButton(
                Icons.Filled.Refresh, "刷新",
                onClick = { if (webMode) webView?.reload() else reload() },
            )
        }
        // 源名：顶栏只写「发现」，源名放导航上方一行 11sp 提示【自定】
        Text(
            source?.name.orEmpty() + if (webMode) " · 原网页" else "",
            fontSize = 11.sp,
            color = p.hint,
            maxLines = 1,
            modifier = Modifier.padding(start = NovelTokens.BarMarginH + 2.dp, top = 6.dp),
        )
        when {
            source == null -> NovelEmptyState(
                icon = Icons.Outlined.Explore, text = "书源不存在或已删除",
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                actionText = "返回", onAction = onBack,
            )
            categories.isEmpty() -> NovelEmptyState(
                icon = Icons.Outlined.Explore, text = "该源没有发现分类",
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                actionText = "返回", onAction = onBack,
            )
            else -> {
                DiscoverCategoryNav(
                    titles = categories.map { it.title },
                    selected = selected?.title,
                    onSelect = { title ->
                        val src = source
                        val cat = categories.firstOrNull { it.title == title }
                        if (cat != null) {
                            selected = cat
                            savedCatUrl = cat.url
                            page = 1
                            if (!webMode) load(src, cat, 1, false)
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                val group = source.let { SourceGroup(it.key, it.name, results) }
                if (webMode) {
                    Row(
                        Modifier.padding(horizontal = NovelTokens.BarMarginH),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (webLoading) {
                            NovelLoading(Modifier.weight(1f))
                        } else {
                            Text(
                                pageTitle,
                                fontSize = 11.sp,
                                color = p.hint,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val canJoin = pageUrl.isNotBlank() &&
                            pageUrl.trimEnd('/') != categoryUrl.trimEnd('/')
                        val isJoining = (group.key + pageUrl) in joining
                        if (canJoin || isJoining) {
                            NovelPillButton(
                                text = if (isJoining) "加入中…" else "加入书架",
                                primary = true,
                                onClick = {
                                    if (!isJoining) searchVm.joinShelf(
                                        group,
                                        NovelSearchResult(
                                            sourceKey = group.key,
                                            remoteId = pageUrl,
                                            title = cleanPageTitle(pageTitle),
                                            author = null,
                                            cover = null,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    key(categoryUrl) {
                        AndroidView(
                            modifier = Modifier.weight(1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    source.headerUa.ifBlank { null }?.let { settings.userAgentString = it }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, url: String) {
                                            pageUrl = url
                                            pageTitle = view.title.orEmpty()
                                            webLoading = false
                                        }
                                    }
                                    loadUrl(categoryUrl)
                                    webView = this
                                }
                            },
                            onRelease = {
                                it.destroy()
                                if (webView == it) webView = null
                            },
                        )
                    }
                } else if (results.isEmpty() && loading) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        NovelLoading()
                    }
                } else if (results.isEmpty()) {
                    NovelEmptyState(
                        icon = Icons.Outlined.Explore,
                        text = notice ?: "这一页没有结果",
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        actionText = "重试",
                        onAction = { reload() },
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = NovelTokens.BarMarginH, end = NovelTokens.BarMarginH, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(DiscoverTokens.CardGap),
                    ) {
                        items(results, key = { it.remoteId }) { item ->
                            val isJoining = (group.key + item.remoteId) in joining
                            DiscoverBookCard(
                                item = item,
                                lines = discoverCardLines(item, selected?.title, source.name),
                                joining = isJoining,
                                onClick = { previewItem = item },
                            )
                        }
                        if (notice != null) {
                            item {
                                Text(notice.orEmpty(), fontSize = 11.sp, color = p.hint, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                        item {
                            SlimLoadMore(
                                loading = loading,
                                onLoadMore = {
                                    val src = source
                                    val cat = selected
                                    if (cat != null) {
                                        val next = page + 1
                                        page = next
                                        load(src, cat, next, true)
                                    }
                                },
                                onRefresh = { reload() },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // v0.215.0：列表行详情预览弹窗（确认才入架；取消不丢列表）。
    previewItem?.let { item ->
        val group = source?.let { SourceGroup(it.key, it.name, results) }
        val isJoining = group != null && (group.key + item.remoteId) in joining
        TechDialog(
            onDismissRequest = { if (!isJoining) previewItem = null },
            title = item.title,
            subtitle = listOfNotNull(
                item.author?.takeIf { it.isNotBlank() },
                source?.name,
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            confirmText = if (isJoining) "加入中…" else "加入书架",
            confirmEnabled = !isJoining,
            onConfirm = {
                if (group != null) searchVm.joinShelf(group, item)
                previewItem = null
            },
        ) {
            if (!item.cover.isNullOrBlank()) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = item.title,
                    modifier = Modifier
                        .size(width = 110.dp, height = 148.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "加入书架后，书籍会立即保存到书架，目录将在后台获取。若获取失败，可在书架中重试；来源：${source?.name.orEmpty()}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 详情页标题常带站名后缀（「书名 - 站名」），入架只取书名一段。 */
private fun cleanPageTitle(raw: String): String {
    var t = raw.trim()
    for (sep in listOf(" - ", " | ")) {
        val i = t.indexOf(sep)
        if (i > 0) t = t.substring(0, i).trim()
    }
    return t
}
