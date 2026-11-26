package com.movieshell.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.ui.novel.NovelMotion
import com.movieshell.ui.novel.NovelTokens
import com.movieshell.ui.novel.novelClickable
import com.movieshell.ui.novel.rememberReduceMotion

/**
 * v1.4.27 Step 4c：阅读器顶栏 / 底栏统一（f5bb9b44 §阅读页）。
 * 顶栏 48dp：返回 34dp、书名 14sp 单行省略、目录/更多图标 18dp、背景透明度 55%–65%（取 62%，无模糊时降级 92%）、圆角 14dp；
 * 底栏同材质；页码/章节信息 10sp；点按切换只淡入淡出，**栏悬浮在正文上，不占正文高度**，切换时正文不跳动。
 * 颜色跟阅读背景（bg/fg）走，不用小说页的深蓝玻璃色——阅读背景有纸色/护眼/夜间多档。
 */
object ReaderChromeTokens {
    val BarHeight = NovelTokens.BarHeight          // 48dp
    val BarMarginH = NovelTokens.BarMarginH        // 16dp
    val BarCorner = NovelTokens.BarCorner          // 14dp
    val BackButton = NovelTokens.BarButton         // 34dp
    val Icon = NovelTokens.BarIcon                 // 18dp
    val TitleSize = 14.sp
    val InfoSize = 10.sp
    const val BG_ALPHA = 0.62f                     // 设计稿 55%–65%（配背景模糊时的值），取中间偏上
    /**
     * 无模糊降级【自定】：本版不加模糊库，栏下就是正文原样——62% 时正文字透出约四成，
     * 书名/按钮和下面的正文叠在一起看不清（截图实测）。按设计稿“关模糊”降级思路提高不透明度到 92%。
     * 以后接入背景模糊时，把 [barAlpha] 的入参改成 true 即回到设计值。
     */
    const val BG_ALPHA_NO_BLUR = 0.92f
    fun barAlpha(blurAvailable: Boolean = false): Float = if (blurAvailable) BG_ALPHA else BG_ALPHA_NO_BLUR
    const val STROKE_ALPHA = 0.12f                 // 描边 fg × 12%【自定】
    val BarGap = 8.dp                              // 栏与屏幕上/下边距【自定】
    val ActionLabelSize = 10.sp                    // 底栏图标下小字，与信息字号统一
}

/** 段评数角标文案：0 不显示（null），>99 显示 99+。纯函数便于单测。 */
fun readerBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** 栏淡入淡出时长：设计稿页面淡入淡出 150ms；减动效 0。 */
fun readerChromeFadeMs(reduceMotion: Boolean): Int = NovelMotion.pageFadeMs(reduceMotion)

/** 阅读器玻璃材质：阅读背景色 × barAlpha()（无模糊降级 92%）＋ fg × 12% 描边、圆角 14dp。 */
@Composable
fun ReaderGlass(
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ReaderChromeTokens.BarCorner)
    Box(
        modifier
            .clip(shape)
            .background(bg.copy(alpha = ReaderChromeTokens.barAlpha()))
            .border(1.dp, fg.copy(alpha = ReaderChromeTokens.STROKE_ALPHA), shape),
        content = content,
    )
}

/** 顶/底栏的淡入淡出容器（减动效时即时出现/消失）。 */
@Composable
fun ReaderChromeFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ms = readerChromeFadeMs(rememberReduceMotion())
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(ms, easing = NovelMotion.Easing)),
        exit = fadeOut(tween(ms, easing = NovelMotion.Easing)),
    ) { content() }
}

/** 栏内图标钮：热区 34dp、图标 18dp、按压动效统一。 */
@Composable
fun ReaderBarIcon(
    icon: ImageVector,
    desc: String,
    fg: Color,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Box(
        Modifier
            .size(ReaderChromeTokens.BackButton)
            .clip(RoundedCornerShape(10.dp))
            .novelClickable(kind = NovelMotion.Press.SMALL, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = fg, modifier = Modifier.size(ReaderChromeTokens.Icon))
        if (badge != null) {
            Text(
                badge,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(NovelTokens.Accent)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/**
 * 顶栏：返回 34dp ｜ 书名 14sp（下面一行 10sp：章节名 · 进度% · 来源）｜ 右侧动作（段评、目录、更多）。
 */
@Composable
fun ReaderTopBar(
    title: String,
    info: String,
    bg: Color,
    fg: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    ReaderGlass(
        bg, fg,
        modifier
            .padding(horizontal = ReaderChromeTokens.BarMarginH)
            .fillMaxWidth()
            .height(ReaderChromeTokens.BarHeight),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = NovelTokens.BarInnerPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderBarIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回", fg, onBack)
            Spacer(Modifier.width(NovelTokens.BarTitleGap))
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = fg, fontSize = ReaderChromeTokens.TitleSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (info.isNotBlank()) {
                    Text(
                        info, color = fg.copy(alpha = 0.75f), fontSize = ReaderChromeTokens.InfoSize,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/** 底栏玻璃卡（同材质），内容沿用原底栏三行。 */
@Composable
fun ReaderBottomGlass(
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ReaderGlass(
        bg, fg,
        modifier.padding(horizontal = ReaderChromeTokens.BarMarginH).fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}

/**
 * 「查看原文」面板：只读、可选中复制；只进内存，不写盘、不改缓存。
 * [raw] 为 null 表示正在拉取；[error] 非空时显示失败原因。
 */
@Composable
fun ReaderRawTextDialog(
    chapterTitle: String,
    raw: String?,
    error: String?,
    bg: Color,
    fg: Color,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = maxH)
                .clip(RoundedCornerShape(ReaderChromeTokens.BarCorner))
                .background(bg)
                .border(1.dp, fg.copy(alpha = ReaderChromeTokens.STROKE_ALPHA), RoundedCornerShape(ReaderChromeTokens.BarCorner))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("查看原文", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$chapterTitle · 未经净化，仅临时查看，不会保存",
                        color = fg.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "关闭", color = NovelTokens.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .novelClickable(kind = NovelMotion.Press.SMALL, onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                error != null -> Text(error, color = fg.copy(alpha = 0.85f), fontSize = 13.sp)
                raw == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(NovelTokens.Spinner), color = NovelTokens.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在拉取原文…", color = fg.copy(alpha = 0.75f), fontSize = NovelTokens.LoadingText)
                }
                else -> SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Text(
                        raw,
                        color = fg,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/** 「查看原文」对话框状态：raw=null 且 error=null 表示拉取中。 */
data class ReaderRawState(val index: Int, val title: String, val raw: String?, val error: String?)

/** 纯函数（可单测）：引擎原文结果 → 对话框状态。null=引擎不可用或抛异常。 */
fun rawStateOf(
    index: Int,
    title: String,
    result: com.movieshell.domain.model.ContentResult?,
    failText: (com.movieshell.domain.model.ContentFail) -> String,
): ReaderRawState = when (result) {
    is com.movieshell.domain.model.ContentResult.Ok ->
        if (result.text.isBlank()) ReaderRawState(index, title, null, "原文为空：站点这一章没有返回正文。")
        else ReaderRawState(index, title, result.text, null)
    is com.movieshell.domain.model.ContentResult.Failed -> ReaderRawState(index, title, null, failText(result.reason))
    null -> ReaderRawState(index, title, null, "拉取原文失败，请稍后重试。")
}
