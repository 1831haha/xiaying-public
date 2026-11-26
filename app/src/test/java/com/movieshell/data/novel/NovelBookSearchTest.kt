package com.movieshell.data.novel

import com.movieshell.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.28 书内全文搜索：纯函数单测。 */
class NovelBookSearchTest {

    private fun ch(index: Int, content: String, title: String = "第${index + 1}章") =
        Chapter(novelId = "b", index = index, title = title, content = content)

    @Test fun findsAcrossChapters() {
        val chapters = listOf(
            ch(0, "唐三拿起昊天锤，昊天锤很重。"),
            ch(1, "这一章没有关键词。"),
            ch(2, "昊天锤再次出现。"),
        )
        val r = NovelBookSearch.searchInBook(chapters, "昊天锤")
        assertEquals(2, r.chaptersWithHits)
        assertEquals(3, r.totalHits)
        assertEquals(3, r.listedHits)
        assertEquals(listOf(0, 2), r.chapters.map { it.chapterIndex })
        assertEquals(2, r.chapters[0].total)
        assertEquals(2, r.chapters[0].hits.size)
    }

    @Test fun perChapterCapTen() {
        val content = (1..25).joinToString("。") { "第${it}个昊天锤" } + "。"
        val r = NovelBookSearch.searchInBook(listOf(ch(0, content)), "昊天锤")
        // 章头总数真实 25，只列出 10 行。
        assertEquals(25, r.totalHits)
        assertEquals(25, r.chapters[0].total)
        assertEquals(10, r.chapters[0].hits.size)
        assertEquals(10, r.listedHits)
    }

    @Test fun totalCapTwoHundred() {
        val chapters = (0 until 30).map { i ->
            ch(i, (1..10).joinToString("。") { "昊天锤$i-$it" } + "。")
        }
        val r = NovelBookSearch.searchInBook(chapters, "昊天锤")
        assertEquals(300, r.totalHits)
        assertEquals(200, r.listedHits)
        assertEquals(20, r.chapters.size)
    }

    @Test fun blankChaptersSkipped() {
        val chapters = listOf(ch(0, ""), ch(1, "   "), ch(2, "有昊天锤的章。"))
        val r = NovelBookSearch.searchInBook(chapters, "昊天锤")
        assertEquals(1, r.chaptersWithHits)
        assertEquals(1, r.totalHits)
        assertEquals(2, r.chapters[0].chapterIndex)
    }

    @Test fun emptyQueryReturnsEmpty() {
        val r = NovelBookSearch.searchInBook(listOf(ch(0, "昊天锤。")), "   ")
        assertEquals(0, r.totalHits)
        assertTrue(r.chapters.isEmpty())
    }

    @Test fun caseInsensitive() {
        val r = NovelBookSearch.searchInBook(listOf(ch(0, "Hello hello HELLO world")), "hello")
        assertEquals(3, r.totalHits)
    }

    @Test fun nonOverlapping() {
        // 与章内搜索同口径：下一处从上一处末尾开始找，"aaa" 里 "aa" 只算 1 处。
        val r = NovelBookSearch.searchInBook(listOf(ch(0, "aaa")), "aa")
        assertEquals(1, r.totalHits)
    }

    @Test fun snippetRadius() {
        val content = "0123456789ab昊天锤cdefghijklmnop"
        val r = NovelBookSearch.searchInBook(listOf(ch(0, content)), "昊天锤")
        assertEquals(1, r.listedHits)
        val snippet = r.chapters[0].hits[0].snippet
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("昊天锤"))
        // 前后各 12 字：命中前是 "0123456789ab"（12 字），命中后是 "cdefghijklmn"（12 字）。
        assertEquals("…0123456789ab昊天锤cdefghijklmn…", snippet)
    }

    @Test fun snippetNewlineFolded() {
        val content = "第一行\n第二行昊天锤\n第三行"
        val r = NovelBookSearch.searchInBook(listOf(ch(0, content)), "昊天锤")
        assertEquals(1, r.listedHits)
        assertTrue(!r.chapters[0].hits[0].snippet.contains("\n"))
    }

    @Test fun largeBookFast() {
        val paragraph = "唐三拿起昊天锤，史莱克七怪走进星斗大森林，魂兽在远处低吼。".repeat(20)
        val chapters = (0 until 2000).map { i -> ch(i, "第${i + 1}章 $paragraph") }
        val start = System.currentTimeMillis()
        val r = NovelBookSearch.searchInBook(chapters, "昊天锤")
        val elapsed = System.currentTimeMillis() - start
        assertEquals(2000, r.chaptersWithHits)
        assertTrue("2000 章搜索应在 5 秒内完成，实际 ${elapsed}ms", elapsed < 5_000)
    }
}
