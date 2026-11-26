package com.movieshell.ui.novel

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.domain.model.SourceHealth
import com.movieshell.ui.theme.isDark

/**
 * v1.4.27 Step 4：小说板块 UI 规范单入口（f5bb9b44…md「深色毛玻璃 · Slim UI」）。
 *
 * 数值逐项照设计稿；设计稿没写的地方沿用仓库现有主题（V5 / MaterialTheme），
 * 自定值一律在注释里标【自定】，并汇总在 step4a README「自定值清单」里。
 *
 * 材质说明（如实）：Compose 没有现成的“背景模糊”（backdrop blur）——Modifier.blur 模糊的是
 * 自身内容而不是身后的背景；要真背景模糊得引第三方库（如 Haze），本步不加生产依赖。
 * 所以这里走设计稿“关模糊”降级口径：保留暗蓝半透明底色、1dp 微光描边、顶部 1dp 内侧微光与阴影，
 * 不做 18～24dp 模糊。毛玻璃不画成灰色实心卡片。
 */
object NovelTokens {
    // ---- 颜色系统（深色：设计稿原值） ----
    val PageBg = Color(0xFF0C1424)
    val PageBg2 = Color(0xFF101B2D)
    /** 玻璃填充 rgba(24,40,60,0.68)（=#18283C 约 68%）。 */
    val Glass = Color(0xFF18283C).copy(alpha = 0.68f)
    /** 玻璃描边 #B8D5ED 15%（设计稿 12%～18% 取中）。 */
    val GlassStroke = Color(0xFFB8D5ED).copy(alpha = 0.15f)
    /** 顶部 1dp 内侧微光：半透明白【自定 10%，设计稿只写“半透明白色”】。 */
    val GlassHighlight = Color.White.copy(alpha = 0.10f)
    val Title = Color(0xFFF2F6FC)
    val Body = Color(0xFFC2CDDC)
    val Hint = Color(0xFF8292A9)
    val Accent = Color(0xFF1A8B5C)
    val Success = Color(0xFF48C78E)
    val Warning = Color(0xFFE6B75A)
    val Error = Color(0xFFF07178)
    /** 状态色“未验证 蓝灰”【自定 #7F9CC0，设计稿只给名称】。 */
    val Unverified = Color(0xFF7F9CC0)
    /** 状态色“未配置搜索 灰蓝”【自定，取设计稿辅助色 #8292A9】。 */
    val Unconfigured = Color(0xFF8292A9)

    // ---- 顶栏 SlimGlassBar ----
    val BarHeight: Dp = 48.dp
    val BarMarginH: Dp = 16.dp
    val BarCorner: Dp = 14.dp
    val BarButton: Dp = 34.dp
    val BarIcon: Dp = 18.dp
    val BarTitleSize: TextUnit = 15.sp
    val BarTitleGap: Dp = 10.dp
    val BarActionGap: Dp = 6.dp
    /** 顶栏内左右留白【自定 7dp：(48-34)/2，让 34dp 按钮上下左右等距】。 */
    val BarInnerPad: Dp = 7.dp
    /** 顶栏下方内容间距（书架 12dp / 书源管理“顶栏到统计区”12dp）。 */
    val BarBelowGap: Dp = 12.dp

    // ---- 书源管理 ----
    val StatsHeight: Dp = 48.dp
    val SearchHeight: Dp = 38.dp
    val SearchIcon: Dp = 16.dp
    val SearchClear: Dp = 14.dp
    val SearchCorner: Dp = 11.dp
    val SearchBgAlpha = 0.55f
    val SearchToList: Dp = 10.dp
    val RowHeight: Dp = 44.dp
    val RowGap: Dp = 3.dp
    val RowMarginH: Dp = 16.dp
    val RowCorner: Dp = 10.dp
    val RowDot: Dp = 7.dp
    val RowInnerGap: Dp = 8.dp
    val RowIcon: Dp = 16.dp
    val RowHit: Dp = 36.dp
    val SwitchW: Dp = 30.dp
    val SwitchH: Dp = 18.dp
    /** 行内左右内边距【自定 10dp，设计稿未写】。 */
    val RowPadH: Dp = 10.dp

    // ---- 菜单 ----
    val MenuItem: Dp = 44.dp
    val MenuIcon: Dp = 18.dp
    /** 菜单圆角【自定 14dp，与顶栏同档】。 */
    val MenuCorner: Dp = 14.dp

    // ---- 加载态（总定稿：20dp 转圈 + 11sp「加载中…」） ----
    val Spinner: Dp = 20.dp
    val LoadingText: TextUnit = 11.sp
}

/** 当前深浅下的小说板块配色：深色=设计稿原值；浅色【自定】从现有 M3 主题取，保证浅色主题可读。 */
@Immutable
data class NovelPalette(
    val dark: Boolean,
    val pageBg: Color,
    val pageBg2: Color,
    val glass: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val title: Color,
    val body: Color,
    val hint: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val unverified: Color,
    val unconfigured: Color,
)

@Composable
fun novelPalette(): NovelPalette {
    val cs = MaterialTheme.colorScheme
    return if (cs.isDark) {
        NovelPalette(
            true, NovelTokens.PageBg, NovelTokens.PageBg2, NovelTokens.Glass, NovelTokens.GlassStroke,
            NovelTokens.GlassHighlight, NovelTokens.Title, NovelTokens.Body, NovelTokens.Hint,
            NovelTokens.Accent, NovelTokens.Success, NovelTokens.Warning, NovelTokens.Error,
            NovelTokens.Unverified, NovelTokens.Unconfigured,
        )
    } else {
        NovelPalette(
            dark = false,
            pageBg = cs.surface,
            pageBg2 = cs.surfaceContainerHigh,
            glass = cs.surfaceContainerLow.copy(alpha = 0.86f),
            glassStroke = cs.outline.copy(alpha = 0.18f),
            glassHighlight = Color.White.copy(alpha = 0.6f),
            title = cs.onSurface,
            body = cs.onSurfaceVariant,
            hint = cs.onSurfaceVariant.copy(alpha = 0.8f),
            accent = NovelTokens.Accent,
            success = Color(0xFF15803D),
            warning = Color(0xFF9A6B0F),
            error = cs.error,
            unverified = Color(0xFF4F6B8F),
            unconfigured = Color(0xFF6B7385),
        )
    }
}

/** 状态色（设计稿“正常绿、未验证蓝灰、失败红、超时橙黄、未配置搜索灰蓝”；慢=警告色、未测=辅助色【自定】）。 */
fun NovelPalette.healthColor(h: SourceHealth): Color = when (h) {
    is SourceHealth.Ok -> success
    is SourceHealth.Slow -> warning
    SourceHealth.Fail -> error
    SourceHealth.Timeout -> warning
    SourceHealth.Unverified -> unverified
    SourceHealth.Unconfigured -> unconfigured
    SourceHealth.Untested -> hint
}

// ======================= 动效 NovelMotion =======================

/**
 * 动效分档（f5bb9b44 §动效）：小按钮 0.94（按压 100ms、回弹 140ms）；卡片 0.98（按压 110ms，
 * 回弹【自定 150ms】）；页面淡入淡出 150ms；弹出菜单 180ms；按压透明度 0.88；缓动 FastOutSlowIn
 * （原提案口径，GPT 认可方向）。减动效：取消缩放，保留透明度/颜色反馈。
 */
object NovelMotion {
    const val SMALL_SCALE = 0.94f
    const val SMALL_PRESS_MS = 100
    const val SMALL_RELEASE_MS = 140
    const val CARD_SCALE = 0.98f
    const val CARD_PRESS_MS = 110
    const val CARD_RELEASE_MS = 150
    const val PAGE_FADE_MS = 150
    const val MENU_MS = 180
    const val PRESS_ALPHA = 0.88f
    val Easing = FastOutSlowInEasing

    enum class Press(val scale: Float, val pressMs: Int, val releaseMs: Int) {
        SMALL(SMALL_SCALE, SMALL_PRESS_MS, SMALL_RELEASE_MS),
        CARD(CARD_SCALE, CARD_PRESS_MS, CARD_RELEASE_MS),
    }

    /** 纯函数（可单测）：按压目标缩放与透明度。 */
    fun target(pressed: Boolean, kind: Press, reduceMotion: Boolean): Pair<Float, Float> =
        (if (pressed && !reduceMotion) kind.scale else 1f) to (if (pressed) PRESS_ALPHA else 1f)

    /** 页面淡入时长：减动效时“即时或极短”【自定 0ms】。 */
    fun pageFadeMs(reduceMotion: Boolean) = if (reduceMotion) 0 else PAGE_FADE_MS
}

/** 系统“移除动画”（开发者选项/无障碍）= 动画时长缩放为 0 时视为减动效。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember(cr) {
        runCatching { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** 按压缩放 + 透明度（统一动效）。 */
@Composable
fun Modifier.novelPress(interaction: MutableInteractionSource, kind: NovelMotion.Press): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val (ts, ta) = NovelMotion.target(pressed, kind, reduce)
    val dur = if (pressed) kind.pressMs else kind.releaseMs
    val scale by animateFloatAsState(ts, tween(dur, easing = NovelMotion.Easing), label = "press-scale")
    val alpha by animateFloatAsState(ta, tween(dur, easing = NovelMotion.Easing), label = "press-alpha")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/** 可点击（带统一按压动效、低透明度绿色涟漪）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.novelClickable(
    kind: NovelMotion.Press = NovelMotion.Press.SMALL,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val ripple = ripple(color = novelPalette().accent)
    return this
        .novelPress(interaction, kind)
        .then(
            if (onLongClick != null) {
                Modifier.combinedClickable(
                    interactionSource = interaction, indication = ripple, enabled = enabled,
                    role = role, onLongClick = onLongClick, onClick = onClick,
                )
            } else {
                Modifier.clickable(interaction, ripple, enabled = enabled, role = role, onClick = onClick)
            },
        )
}

// ======================= 材质 =======================

/** 小说页底：#0C1424 → #101B2D 纵向渐变 + 顶部局部绿色柔光（静态，不流动；光斑流动属 P2，本步不做）。 */
@Composable
fun NovelBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val p = novelPalette()
    Box(
        modifier
            .background(Brush.verticalGradient(0f to p.pageBg, 1f to p.pageBg2))
            .background(
                // 局部绿光【自定：主强调绿 8%，自上而下 40% 处淡出】
                Brush.verticalGradient(0f to p.accent.copy(alpha = if (p.dark) 0.08f else 0.05f), 0.4f to Color.Transparent),
            ),
        content = content,
    )
}

/**
 * 玻璃面：半透明填充 + 1dp 描边 + 顶部 1dp 内侧微光 + 阴影（黑 20%，设计稿“模糊 12dp、Y 偏移 4dp”；
 * Compose 阴影按 elevation 出，取【自定 4dp elevation】近似）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NovelTokens.BarCorner),
    fill: Color? = null,
    shadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = novelPalette()
    Box(
        modifier
            .then(
                if (shadow) Modifier.shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                else Modifier,
            )
            .clip(shape)
            .background(fill ?: p.glass)
            .border(1.dp, p.glassStroke, shape)
            .drawWithContent {
                drawContent()
                // 顶部 1dp 内侧微光（横向两端淡出，避免压在圆角上出硬角）
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent, 0.15f to p.glassHighlight,
                        0.85f to p.glassHighlight, 1f to Color.Transparent,
                    ),
                    topLeft = Offset(0f, 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                )
            },
        content = content,
    )
}

/** 玻璃图标钮：34×34dp（圆形）、图标 18dp、按压 0.94。 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = NovelTokens.BarButton,
    iconSize: Dp = NovelTokens.BarIcon,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val p = novelPalette()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .novelClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = (tint ?: p.title).copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(iconSize))
    }
}

/**
 * SlimGlassBar：48dp（不含状态栏）、左右外边距 16dp、14dp 圆角横向圆角矩形；返回钮 34dp 圆形
 * （不再套灰色圆角块）；标题 15sp SemiBold、垂直居中、与按钮间距 10dp、超长省略；右侧按钮 34dp、间距 6dp。
 */
@Composable
fun SlimGlassBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    titleSize: TextUnit = NovelTokens.BarTitleSize,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = novelPalette()
    GlassSurface(
        modifier
            .padding(horizontal = NovelTokens.BarMarginH)
            .fillMaxWidth()
            .height(NovelTokens.BarHeight),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = NovelTokens.BarInnerPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
                Spacer(Modifier.width(NovelTokens.BarTitleGap))
            } else {
                // 无返回键时标题左边与按钮同档内缩【自定：补 BarTitleGap-BarInnerPad 对齐】
                Spacer(Modifier.width(NovelTokens.BarTitleGap - NovelTokens.BarInnerPad + 4.dp))
            }
            Text(
                title,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                color = p.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NovelTokens.BarTitleGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(NovelTokens.BarActionGap),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** NovelSearchField：38dp、图标 16dp、提示 12sp、清空 14dp、背景透明度 55%、圆角 11dp（独立暗色玻璃输入区）。 */
@Composable
fun NovelSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val p = novelPalette()
    val shape = RoundedCornerShape(NovelTokens.SearchCorner)
    Row(
        modifier
            .height(NovelTokens.SearchHeight)
            .clip(shape)
            .background(p.glass.copy(alpha = NovelTokens.SearchBgAlpha))
            .border(1.dp, p.glassStroke, shape)
            // 左右内边距【自定 10dp，与书源行同档】
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = p.hint, modifier = Modifier.size(NovelTokens.SearchIcon))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 12.sp, color = p.hint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                // 输入文字字号【自定 13sp：设计稿只定了提示 12sp，正文取书源名同档】
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = p.title),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth().then(focusModifier),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).novelClickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, "清空", tint = p.hint, modifier = Modifier.size(NovelTokens.SearchClear))
            }
        }
    }
}

/** 迷你开关 30×18dp（书源行；轨道开=主强调绿，关=辅助色 35%【自定】；拇指 14dp【自定】，位移 140ms）。 */
@Composable
fun NovelMiniSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = novelPalette()
    val reduce = rememberReduceMotion()
    val pos by animateFloatAsState(
        if (checked) 1f else 0f,
        tween(if (reduce) 0 else NovelMotion.SMALL_RELEASE_MS, easing = NovelMotion.Easing),
        label = "switch",
    )
    val track = if (checked) p.accent else p.hint.copy(alpha = 0.35f)
    // 点击热区补到 36dp 高（设计稿“点击热区至少 36×36dp”）
    Box(
        modifier
            .size(width = NovelTokens.RowHit, height = NovelTokens.RowHit)
            .novelClickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(NovelTokens.SwitchW, NovelTokens.SwitchH)
                .clip(CircleShape)
                .background(track.copy(alpha = if (enabled) track.alpha else 0.3f)),
        ) {
            Box(
                Modifier
                    .offset(x = 2.dp + (NovelTokens.SwitchW - 18.dp) * pos, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (checked) 1f else 0.85f)),
            )
        }
    }
}

/** 行内小图标钮：图标 16dp、热区 36×36dp、按压 0.94。 */
@Composable
fun RowIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(NovelTokens.RowHit).clip(CircleShape).novelClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(NovelTokens.RowIcon))
    }
}

/** 深色玻璃浮层菜单（菜单项 44dp、图标 18dp）。弹出动画沿用 M3 DropdownMenu 自带（无法改到 180ms，README 已注）。 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = novelPalette()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(NovelTokens.MenuCorner),
        // 浮层不透明度【自定 0.96】：菜单压在列表上，太透读不清
        containerColor = (if (p.dark) Color(0xFF18283C) else MaterialTheme.colorScheme.surfaceContainer).copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, p.glassStroke),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

@Composable
fun GlassMenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    trailing: String? = null,
    danger: Boolean = false,
) {
    val p = novelPalette()
    val c = if (danger) p.error else p.title
    Row(
        Modifier
            .height(NovelTokens.MenuItem)
            // 菜单最小宽【自定 168dp】、左右内边距【自定 14dp】
            .width(184.dp)
            .novelClickable(kind = NovelMotion.Press.CARD, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.size(NovelTokens.MenuIcon))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 11.sp, color = p.hint)
    }
}

/** 统一加载态：20dp 转圈 + 11sp「加载中…」。 */
@Composable
fun NovelLoading(modifier: Modifier = Modifier, text: String = "加载中…") {
    val p = novelPalette()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(NovelTokens.Spinner), color = p.accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = NovelTokens.LoadingText, color = p.hint)
    }
}

/** 统一空态：图标 + 文案 + 可选按钮（总定稿“空态统一组件”）。图标 40dp、文案 13sp【自定】。 */
@Composable
fun NovelEmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val p = novelPalette()
    Column(modifier.padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = p.hint, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, fontSize = 13.sp, color = p.body)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 11.sp, color = p.hint)
        }
        if (actionText != null && onAction != null || secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (actionText != null && onAction != null) NovelPillButton(actionText, primary = true, onClick = onAction)
                if (secondaryText != null && onSecondary != null) NovelPillButton(secondaryText, primary = false, onClick = onSecondary)
            }
        }
    }
}

/** 胶囊按钮：高 36dp（总定稿“按钮高 36–40dp”取下限）、12sp【自定】。 */
@Composable
fun NovelPillButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = novelPalette()
    val shape = CircleShape
    Box(
        modifier
            .height(36.dp)
            .clip(shape)
            .then(if (primary) Modifier.background(p.accent) else Modifier.border(1.dp, p.glassStroke, shape))
            .novelClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, color = if (primary) Color.White else p.title, fontWeight = FontWeight.Medium)
    }
}
