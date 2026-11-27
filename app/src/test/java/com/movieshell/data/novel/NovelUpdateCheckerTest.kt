package com.movieshell.data.novel

import com.movieshell.domain.model.NovelSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.4.34 B-P1-6：更新检查选书必须校验作者——同名不同作者的书只凭标题
 * 互相包含即认同一本，会把别本书的目录整本写进本地书。
 */
class NovelUpdateCheckerTest {

    private fun hit(title: String, author: String?) =
        NovelSearchResult(title = title, author = author)

    @Test
    fun pickBest_rejectsSameTitleDifferentAuthor() {
        val hits = listOf(
            hit("斗破苍穹", "李四"),
            hit("斗破苍穹续", "天蚕土豆"),
        )
        assertNull(NovelUpdateChecker.pickBest(hits, "斗破苍穹", "天蚕土豆"))
    }

    @Test
    fun pickBest_exactTitleAndAuthorWins() {
        val hits = listOf(
            hit("斗破苍穹", "李四"),
            hit("斗破苍穹", "天蚕土豆"),
        )
        assertEquals("天蚕土豆", NovelUpdateChecker.pickBest(hits, "斗破苍穹", "天蚕土豆")?.author)
    }

    @Test
    fun pickBest_containsTitleNeedsAuthorToo() {
        // 同作者续作（“斗破苍穹之无上之境”）也不是同一本，归一化后仍不一致 → 拒绝。
        val hits = listOf(hit("斗破苍穹之无上之境", "天蚕土豆"))
        assertNull(NovelUpdateChecker.pickBest(hits, "斗破苍穹", "天蚕土豆"))
    }

    @Test
    fun pickBest_bracketedSuffixStillMatches() {
        // 括号缀余（“斗破苍穹（完结）”）归一化后与本地标题一致，且作者对得上 → 认。
        val hits = listOf(hit("斗破苍穹（完结）", "天蚕土豆"))
        assertEquals("斗破苍穹（完结）", NovelUpdateChecker.pickBest(hits, "斗破苍穹", "天蚕土豆")?.title)
    }

    @Test
    fun pickBest_blankAuthorStillMatches() {
        // 本地作者空白或命中作者空白时放行（既有 authorOk 口径，不误伤无作者的书）。
        val hits = listOf(hit("斗破苍穹", null))
        assertEquals("斗破苍穹", NovelUpdateChecker.pickBest(hits, "斗破苍穹", "天蚕土豆")?.title)
        assertEquals("斗破苍穹", NovelUpdateChecker.pickBest(hits, "斗破苍穹", "")?.title)
    }

    @Test
    fun pickBest_authorSubstringCountsAsMatch() {
        // 作者“天蚕土豆 著”含本地“天蚕土豆”，算对得上。
        val hits = listOf(hit("斗破苍穹", "天蚕土豆 著"))
        assertEquals("天蚕土豆 著", NovelUpdateChecker.pickBest(hits, "斗破苍穹", "天蚕土豆")?.author)
    }
}
