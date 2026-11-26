package com.movieshell.ui.novel

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.TocGap
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.ui.bookshelf.ShelfRowTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4.27 Step 4b：书架行卡 / 发现书卡尺寸对照设计稿，书卡信息层与缺段提示文案。 */
class DiscoverUiTest {

    @Test
    fun shelfRowTokensMatchDesign() {
        assertEquals(76.dp, ShelfRowTokens.Height)
        assertEquals(46.dp, ShelfRowTokens.CoverW)
        assertEquals(60.dp, ShelfRowTokens.CoverH)
        assertEquals(13.sp, ShelfRowTokens.TitleSize)
        assertEquals(10.sp, ShelfRowTokens.AuthorSize)
        assertEquals(10.sp, ShelfRowTokens.ProgressSize)
        assertEquals(3.dp, ShelfRowTokens.ProgressBar)
        assertEquals(10.dp, ShelfRowTokens.Corner)
        assertEquals(6.dp, ShelfRowTokens.Gap)
    }

    @Test
    fun discoverTokensMatchDesign() {
        assertEquals(34.dp, DiscoverTokens.NavHeight)
        assertEquals(12.sp, DiscoverTokens.NavTextSize)
        assertEquals(18.dp, DiscoverTokens.NavGap)
        assertEquals(2.dp, DiscoverTokens.NavUnderline)
        assertEquals(84.dp, DiscoverTokens.CardHeight)
        assertEquals(56.dp, DiscoverTokens.CoverW)
        assertEquals(68.dp, DiscoverTokens.CoverH)
        assertEquals(6.dp, DiscoverTokens.CoverCorner)
        assertEquals(9.dp, DiscoverTokens.CardPad)
        assertEquals(10.dp, DiscoverTokens.CardCorner)
        assertEquals(6.dp, DiscoverTokens.CardGap)
        assertEquals(16.dp, DiscoverTokens.ActionIcon)
    }

    @Test
    fun cardLinesFullInfo() {
        val l = discoverCardLines(NovelSearchResult(title = " 诡秘之主 ", author = "爱潜水的乌贼"), "玄幻", "番茄小说")
        assertEquals("诡秘之主", l.title)
        assertEquals("爱潜水的乌贼 · 玄幻", l.meta)
        assertEquals("来源：番茄小说 · 分类：玄幻", l.intro)
    }

    @Test
    fun cardLinesMissingFieldsNoInventedIntro() {
        val l = discoverCardLines(NovelSearchResult(title = "", author = "  "), null, null)
        assertEquals("（无书名）", l.title)
        assertEquals("未知作者", l.meta)
        assertEquals("", l.intro)
    }

    @Test
    fun tocGapLinesText() {
        val (t, d) = tocGapLines(TocGap.Gap(afterIndex = 9, fromNo = 10, toNo = 120))
        assertEquals("目录可能不全", t)
        assertEquals("第10章后直接到第120章，约缺 109 章", d)
    }
}
