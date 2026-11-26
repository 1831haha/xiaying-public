package com.movieshell.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.domain.model.NovelSearchResult

/**
 * v1.4.27 Step 4b：发现页组件（f5bb9b44 §发现页）。
 * - DiscoverCategoryNav：文字分类导航——高 34dp、12sp、选中绿字＋2dp 绿下划线、间距 18dp，不用灰胶囊。
 * - DiscoverBookCard：84dp 书卡——封面 56×68 圆角 6、书名 13sp SemiBold、作者 11sp、简介 10sp、
 *   右侧操作 16dp、内边距 9dp、圆角 10dp；三层信息。
 * - SlimLoadMore：瘦「加载更多」——文字链＋20dp 转圈。
 * 只改外观；点击行为与改版前一致（点卡片 → 预览确认入架）。
 */
object DiscoverTokens {
    val NavHeight = 34.dp
    val NavTextSize = 12.sp
    val NavGap = 18.dp
    val NavUnderline = 2.dp
    val CardHeight = 84.dp
    val CoverW = 56.dp
    val CoverH = 68.dp
    val CoverCorner = 6.dp
    val CardPad = 9.dp
    val CardCorner = 10.dp
    val CardGap = 6.dp
    val ActionIcon = 16.dp
}

/**
 * 书卡三层信息。NovelSearchResult 只有书名/作者/封面——没有简介字段，第三层不编造简介，
 * 用「来源 · 分类」兜底；作者缺失写「未知作者」。
 */
data class DiscoverCardLines(val title: String, val meta: String, val intro: String)

fun discoverCardLines(item: NovelSearchResult, category: String?, sourceName: String?): DiscoverCardLines {
    val author = item.author?.trim()?.takeIf { it.isNotEmpty() } ?: "未知作者"
    val cat = category?.trim()?.takeIf { it.isNotEmpty() }
    val src = sourceName?.trim()?.takeIf { it.isNotEmpty() }
    return DiscoverCardLines(
        title = item.title.trim().ifEmpty { "（无书名）" },
        meta = listOfNotNull(author, cat).joinToString(" · "),
        intro = listOfNotNull(src?.let { "来源：$it" }, cat?.let { "分类：$it" }).joinToString(" · "),
    )
}

@Composable
fun DiscoverCategoryNav(
    titles: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = novelPalette()
    val state = rememberLazyListState()
    val selIndex = titles.indexOf(selected)
    LaunchedEffect(selIndex) { if (selIndex >= 0) state.animateScrollToItem(selIndex) }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().height(DiscoverTokens.NavHeight),
        horizontalArrangement = Arrangement.spacedBy(DiscoverTokens.NavGap),
        contentPadding = PaddingValues(horizontal = NovelTokens.BarMarginH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v1.4.29：标题作 key 会撞——同一源的分类表里同一标题可出现两次；
        // 下标拼标题作 key，重复标题不再闪退（与聚合发现页同类修法）。
        itemsIndexed(titles, key = { index, t -> "$index:$t" }) { _, t ->
            val isSel = t == selected
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .semantics { this.selected = isSel }
                    .novelClickable(kind = NovelMotion.Press.SMALL, role = Role.Tab) { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t,
                    fontSize = DiscoverTokens.NavTextSize,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSel) p.accent else p.body,
                    maxLines = 1,
                )
                if (isSel) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            // 下划线宽 16dp、距底 3dp【自定】
                            .padding(bottom = 3.dp)
                            .width(16.dp)
                            .height(DiscoverTokens.NavUnderline)
                            .clip(RoundedCornerShape(1.dp))
                            .background(p.accent),
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoverBookCard(
    item: NovelSearchResult,
    lines: DiscoverCardLines,
    joining: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = novelPalette()
    val shape = RoundedCornerShape(DiscoverTokens.CardCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DiscoverTokens.CardHeight)
            .clip(shape)
            .background(p.glass.copy(alpha = p.glass.alpha * 0.45f))
            .border(1.dp, p.glassStroke.copy(alpha = p.glassStroke.alpha * 0.6f), shape)
            .novelClickable(kind = NovelMotion.Press.CARD, enabled = !joining, onClick = onClick)
            .padding(horizontal = DiscoverTokens.CardPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DiscoverTokens.CoverW, DiscoverTokens.CoverH)
                .clip(RoundedCornerShape(DiscoverTokens.CoverCorner))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.cover.isNullOrBlank()) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    lines.title.firstOrNull()?.toString() ?: "书",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.hint,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                lines.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.title,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(lines.meta, fontSize = 11.sp, color = p.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                if (joining) "加入中…" else lines.intro,
                fontSize = 10.sp,
                color = if (joining) p.accent else p.hint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DiscoverTokens.CardGap))
        // 右侧操作 16dp 图标（热区 36dp）：同点卡片——先看预览再确认入架
        Box(
            Modifier
                .size(NovelTokens.RowHit)
                .novelClickable(kind = NovelMotion.Press.SMALL, enabled = !joining, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (joining) {
                CircularProgressIndicator(Modifier.size(DiscoverTokens.ActionIcon), color = p.accent, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.LibraryAdd, "加入书架", tint = p.accent, modifier = Modifier.size(DiscoverTokens.ActionIcon))
            }
        }
    }
}

/** 瘦「加载更多」：文字链；加载中换 20dp 转圈＋11sp 文案。右侧同排一个「刷新」文字链（原有功能）。 */
@Composable
fun SlimLoadMore(
    loading: Boolean,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = novelPalette()
    Row(
        modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            NovelLoading()
        } else {
            Text(
                "加载更多",
                fontSize = 12.sp,
                color = p.accent,
                modifier = Modifier
                    .novelClickable(kind = NovelMotion.Press.SMALL, role = Role.Button, onClick = onLoadMore)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text("·", fontSize = 12.sp, color = p.hint)
            Text(
                "刷新",
                fontSize = 12.sp,
                color = p.body,
                modifier = Modifier
                    .novelClickable(kind = NovelMotion.Press.SMALL, role = Role.Button, onClick = onRefresh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 缺段提示文案（纯函数，便于单测）：标题一行＋明细一行。 */
fun tocGapLines(gap: com.movieshell.data.novel.TocGap.Gap): Pair<String, String> =
    "目录可能不全" to "第${gap.fromNo}章后直接到第${gap.toNo}章，约缺 ${gap.missing} 章"

/**
 * v1.4.27 Step 4b：详情页缺段提示条。总定稿只写“缺段提示要醒目但不吓人”，具体样式【自定】：
 * 琥珀警示色（NovelTokens.Warning）12% 底＋30% 描边、圆角 10dp、内边距 10×8dp；
 * 16dp 警示图标、标题 12sp SemiBold、明细 11sp；右侧文字链「展开全部」12sp 主强调绿，展开中换 20dp 转圈。
 */
@Composable
fun TocGapHint(
    gap: com.movieshell.data.novel.TocGap.Gap,
    expanding: Boolean,
    canExpand: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = novelPalette()
    val (title, detail) = tocGapLines(gap)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.warning.copy(alpha = 0.12f))
            .border(1.dp, p.warning.copy(alpha = 0.30f), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.WarningAmber, null,
            tint = p.warning, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.warning)
            Text(detail, fontSize = 11.sp, color = p.body)
        }
        if (canExpand) {
            Spacer(Modifier.width(6.dp))
            if (expanding) {
                NovelLoading(text = "展开中…")
            } else {
                Text(
                    "展开全部",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.accent,
                    modifier = Modifier
                        .novelClickable(kind = NovelMotion.Press.SMALL, role = Role.Button, onClick = onExpand)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }
}
