package com.movieshell.ui.bookshelf

import com.movieshell.domain.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 Step4 审稿 A7：书架行卡第三行文案。 */
class ShelfProgressLineTest {

    @Test fun unreadWithoutLatestShowsOnce() {
        assertEquals("还没读过", shelfProgressLine(Novel(title = "书", totalChapters = 10), 0L))
    }

    @Test fun unreadWithLatest() {
        assertEquals("还没读过 · 最新：第10章", shelfProgressLine(Novel(totalChapters = 10, latestChapterTitle = "第10章"), 0L))
    }

    @Test fun readingShowsCurrentChapterTitleAndLatest() {
        val n = Novel(totalChapters = 100, lastReadChapter = 4, hasStartedReading = true,
            lastReadChapterTitle = "第五章 夜行", latestChapterTitle = "第100章 尾声")
        assertEquals("已读 5/100 章 · 第五章 夜行 · 最新：第100章 尾声", shelfProgressLine(n, 0L))
    }

    @Test fun readingWithoutTitlesNeverSaysNotRead() {
        val n = Novel(totalChapters = 100, lastReadChapter = 0, hasStartedReading = true)
        assertEquals("已读 1/100 章", shelfProgressLine(n, 0L))
        val withTime = shelfProgressLine(n, System.currentTimeMillis() - 5 * 60_000L)
        assertTrue(withTime, withTime.startsWith("已读 1/100 章 · ") && !withTime.contains("还没读过"))
    }
}
