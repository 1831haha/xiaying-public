package com.movieshell.ui.reader

import com.movieshell.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.34 D-P2-9：阅读器正文内存窗口（纯函数）。 */
class ReaderWindowTest {

    private fun chapters(n: Int, withContent: Boolean = true): List<Chapter> =
        List(n) { i ->
            Chapter(
                novelId = "book",
                index = i,
                title = "第${i + 1}章",
                content = if (withContent) "正文$i" else "",
            )
        }

    @Test fun keepsWindowAroundCenter() {
        val kept = retainContentWindow(chapters(100), 50)
        assertEquals((48..60).toList(), kept.mapIndexedNotNull { i, c -> i.takeIf { c.content.isNotBlank() } })
        // 目录/标题不动
        assertEquals("第1章", kept.first().title)
        assertEquals("第100章", kept.last().title)
    }

    @Test fun clampsAtBookStartAndEnd() {
        val start = retainContentWindow(chapters(100), 0)
        assertEquals((0..10).toList(), start.mapIndexedNotNull { i, c -> i.takeIf { c.content.isNotBlank() } })
        val end = retainContentWindow(chapters(100), 99)
        assertEquals((97..99).toList(), end.mapIndexedNotNull { i, c -> i.takeIf { c.content.isNotBlank() } })
    }

    @Test fun smallBookKeepsEverythingAndReturnsSameList() {
        val small = chapters(5)
        assertSame(small, retainContentWindow(small, 2))
    }

    @Test fun emptyBookReturnsEmpty() {
        assertTrue(retainContentWindow(emptyList(), 0).isEmpty())
    }

    @Test fun alreadyStrippedOutsideReturnsSameList() {
        val list = chapters(100).mapIndexed { i, c -> if (i in 48..60) c else c.copy(content = "") }
        assertSame(list, retainContentWindow(list, 50))
    }
}
