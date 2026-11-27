package com.movieshell.ui.bookshelf

import com.movieshell.ui.theme.V5
import com.movieshell.ui.novel.NovelEmptyState
import com.movieshell.ui.novel.NovelMotion
import com.movieshell.ui.novel.NovelTokens
import com.movieshell.ui.novel.novelClickable
import com.movieshell.ui.novel.novelPalette

import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.domain.model.Novel
import com.movieshell.data.store.NovelStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v0.98.0：书架卡片组件。
 * - bookPressable：按压缩放（0.96）微交互，复用音乐四榜卡片的做法。
 * - NovelListCard / NovelGridCard：双视图卡片。
 * - BookshelfEmptyState：趣味空状态。
 * v0.178.0：阅读数据条已删，不再留死组件。
 */

/** 按压缩放。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bookPressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        label = "bookPress",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

/** 书籍阅读进度（0..1）。
 * v0.217.0（F46）：按“是否动过书”算——正在读第 1 章时进度是 1/total，
 * 不再因下标 0 被算成没读过。 */
fun Novel.readFraction(): Float {
    val total = totalChapters.coerceAtLeast(1)
    val read = lastReadChapter.coerceIn(0, total - 1)
    return if (!hasStartedReading) 0f else (read + 1).toFloat() / total
}

/** v1.4.27 Step 4：书架行卡尺寸（f5bb9b44 书架列表逐项照做）。 */
object ShelfRowTokens {
    val Height = 76.dp
    val CoverW = 46.dp
    val CoverH = 60.dp
    val TitleSize = 13.sp
    val AuthorSize = 10.sp
    val ProgressSize = 10.sp
    val ProgressBar = 3.dp
    val Corner = 10.dp
    val Gap = 6.dp
}

/**
 * v1.4.27 Step 4：书架行卡 ShelfCompactRow（f5bb9b44 §发现页与书架）——76dp、封面 46×60dp、
 * 书名 13sp、作者 10sp、进度 10sp、进度条 3dp、圆角 10dp、行间距 6dp（列表处 spacedBy）。
 * 信息与改版前一致（书名＋更新数角标 / 作者 / 已读章节＋总章数或目录状态 / 进度条），只换版式；
 * 下拉刷新逐本状态（转圈 / 失败）留在行尾。不做大统计卡片。
 * 原 v0.197.0～v0.248.0 的四行 plain 行（64×96 封面、15sp）整体换掉，函数名保留（调用点不动）。
 */
@Composable
fun NovelListCard(
    novel: Novel,
    lastReadAt: Long,
    updateCount: Int = 0,
    // v0.211.0：下拉刷新逐本状态——checking 转圈、checkFailed 红色“失败”，转圈优先于失败。
    checking: Boolean = false,
    checkFailed: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = novelPalette()
    val shape = RoundedCornerShape(ShelfRowTokens.Corner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ShelfRowTokens.Height)
            .clip(shape)
            // 行底：玻璃填充 × 45%（与书源行同口径【自定】）
            .background(p.glass.copy(alpha = p.glass.alpha * 0.45f))
            .border(1.dp, p.glassStroke.copy(alpha = p.glassStroke.alpha * 0.6f), shape)
            .novelClickable(kind = NovelMotion.Press.CARD, onLongClick = onLongClick, onClick = onClick)
            // 行内边距【自定 9dp，与发现书卡左右内边距同档】
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.size(36.dp))
        }
        Box(
            modifier = Modifier
                .size(width = ShelfRowTokens.CoverW, height = ShelfRowTokens.CoverH)
                // 封面圆角【自定 6dp，与发现书卡封面同档】
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (!novel.cover.isNullOrBlank()) {
                AsyncImage(
                    model = novel.cover,
                    contentDescription = novel.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = novel.title.firstOrNull()?.toString() ?: "书",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    novel.title,
                    fontSize = ShelfRowTokens.TitleSize,
                    fontWeight = FontWeight.SemiBold,
                    color = p.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (updateCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    // 更新数角标：10sp 白字主强调绿底【自定】
                    Text(
                        text = "$updateCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(p.accent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                novel.author?.takeIf { it.isNotBlank() } ?: "未知作者",
                fontSize = ShelfRowTokens.AuthorSize, color = p.hint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val isDirFailed = novel.dirState == NovelStore.DIR_FAILED
            Text(
                text = when (novel.dirState) {
                    // v0.249.0：目录未到时不显示假的章数/最新章节
                    NovelStore.DIR_LOADING -> "正在获取目录…"
                    NovelStore.DIR_FAILED -> "目录获取失败，点击重试"
                    else -> shelfProgressLine(novel, lastReadAt)
                },
                fontSize = ShelfRowTokens.ProgressSize,
                color = if (isDirFailed) p.error else p.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            // 进度条 3dp：轨道描边色、填充主强调绿【自定】
            Box(
                Modifier.fillMaxWidth().height(ShelfRowTokens.ProgressBar).clip(RoundedCornerShape(2.dp)).background(p.glassStroke),
            ) {
                Box(
                    Modifier.fillMaxWidth(novel.readFraction()).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(p.accent),
                )
            }
        }
        if (checking) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(NovelTokens.Spinner), color = p.accent, strokeWidth = 2.dp)
        } else if (checkFailed) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "失败",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(p.error, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** v0.98.0：网格卡片（封面墙）。
 * v0.294.6：Material You 紧凑重做——封面 2:3、12dp 圆角；书名最多两行（固定两行高，
 * 网格对齐）；副标题「读到第 x 章」/作者/目录状态；角标：更新数（primary）、失败（error）、
 * 未读（tertiaryContainer）；进度改封面底部细进度条，去掉黑色圆底进度环与黑圆多选底。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelGridCard(
    novel: Novel,
    updateCount: Int = 0,
    checking: Boolean = false,
    checkFailed: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val progress = novel.readFraction()
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)
    val coverShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.bookPressable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(coverShape)
                .background(cs.surfaceContainerHigh)
                .then(
                    if (selectionMode && selected) Modifier.border(2.dp, cs.primary, coverShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!novel.cover.isNullOrBlank()) {
                AsyncImage(
                    model = novel.cover,
                    contentDescription = novel.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = novel.title.firstOrNull()?.toString() ?: "书",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurfaceVariant.copy(alpha = 0.8f), // v1.2.14 E片：占位首字 0.5 只有 2.50:1，改 0.8（5.14:1）
                )
            }
            // 进度：封面底部 3dp 细条（读过才显示）
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                            .background(cs.primary),
                    )
                }
            }
            // 左上角标：失败 > 更新数 > 未读
            val badge: Triple<String, Color, Color>? = when {
                checkFailed && !checking -> Triple("失败", cs.error, cs.onError)
                updateCount > 0 -> Triple("$updateCount", cs.primary, cs.onPrimary)
                !novel.hasStartedReading -> Triple("未读", cs.tertiaryContainer, cs.onTertiaryContainer)
                else -> null
            }
            badge?.let { (t, bg, fg) ->
                Text(
                    text = t,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(bg, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
            // 正在检查更新：整张封面浅色蒙层 + 小转圈（不再是黑圆底）
            if (checking) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cs.surface.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = cs.primary,
                        strokeWidth = 2.dp,
                    )
                }
            }
            // 多选勾（右上）：选中=primary 实心勾，未选=浅色描边圆
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) cs.primary else cs.surface.copy(alpha = 0.85f))
                        .then(
                            if (selected) Modifier
                            else Modifier.border(1.5.dp, cs.outline, CircleShape),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = cs.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            novel.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
            maxLines = 2,
            minLines = 2,
            lineHeight = 17.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        val (sub, subColor) = when (novel.dirState) {
            NovelStore.DIR_LOADING -> "正在获取目录…" to cs.onSurfaceVariant
            NovelStore.DIR_FAILED -> "目录获取失败，点击重试" to cs.error
            else -> (
                if (novel.hasStartedReading) "读到第 ${read + 1} 章"
                else novel.author?.takeIf { it.isNotBlank() } ?: "共 ${total} 章"
                ) to cs.onSurfaceVariant
        }
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall,
            color = subColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** v0.98.0：趣味空状态。v1.4.27 Step 4：换统一空态组件（图标＋文案＋“去找书 / 导入 TXT”两个引导按钮）。 */
@Composable
fun BookshelfEmptyState(
    onFindBook: () -> Unit,
    onImportTxt: () -> Unit,
) {
    NovelEmptyState(
        icon = Icons.Outlined.Book,
        text = "书架还是空的",
        hint = "搜本小说，或导入本地 TXT",
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        actionText = "去找书",
        onAction = onFindBook,
        secondaryText = "导入 TXT",
        onSecondary = onImportTxt,
    )
}

/**
 * v1.4.27 Step4 审稿 A7：书架行卡第三行（目录终态 READY 时）。
 * - 未读：有最新章 →「还没读过 · 最新：X」；没有 → 只显示「还没读过」（原来会拼成「还没读过 · 还没读过」）。
 * - 已读：「已读 N/总 章」＋在读章节标题（接回旧行「已读：第 N 章 · <在读章节标题>」的信息）
 *   ＋「最新：X」；没有最新章时补上次阅读时间（有时间才补）。单行，超长尾部省略。
 */
internal fun shelfProgressLine(novel: Novel, lastReadAt: Long): String {
    val latest = novel.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { "最新：$it" }
    if (!novel.hasStartedReading) return listOfNotNull("还没读过", latest).joinToString(" · ")
    val total = novel.totalChapters.coerceAtLeast(1)
    val read = novel.lastReadChapter.coerceIn(0, total - 1)
    return listOfNotNull(
        "已读 ${read + 1}/$total 章",
        novel.lastReadChapterTitle?.takeIf { it.isNotBlank() },
        latest ?: lastReadAt.takeIf { it > 0 }?.let { formatLastReadTs(it) },
    ).joinToString(" · ")
}

/** 上次阅读时间格式化（从原 BookshelfScreen 搬运）。 */
fun formatLastReadTs(ts: Long): String {
    if (ts <= 0) return "还没读过"
    val now = System.currentTimeMillis()
    val diffMin = (now - ts) / 60000
    return when {
        diffMin < 1 -> "刚刚读过"
        diffMin < 60 -> "$diffMin 分钟前"
        diffMin < 60 * 24 -> "${diffMin / 60} 小时前"
        diffMin < 60 * 48 -> "昨天"
        else -> {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            "${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }
}

/** 书架列表项元信息行（从原 BookshelfScreen 搬运）。 */
@Composable
fun NovelMetaRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
