package com.movieshell.data.novel

import com.movieshell.domain.model.Chapter

/**
 * v1.4.28 书内全文搜索（P1）：纯函数，只搜已传入章节的正文，不拉网、不读盘、不写盘。
 *
 * 口径与章内搜索（ReaderScreen.kt:1174-1230）逐字一致：indexOf ignoreCase、不重叠
 * （下一处从上一处末尾开始找）、摘要为命中处前后各 12 字、\n 折成空格。
 * 只搜 content 非空的章节（未缓存不拉取）；每章最多出 [PER_CHAPTER_CAP] 条结果行，
 * 全书最多出 [TOTAL_CAP] 条结果行；章头与总计显示真实命中总数（TA 审方案 ①-R3：
 * 单章 cap 10 静默截断时，章头仍显示真实总数）。
 */
object NovelBookSearch {

    /** 每章最多列出的结果行数。 */
    const val PER_CHAPTER_CAP = 10

    /** 全书最多列出的结果行数。 */
    const val TOTAL_CAP = 200

    /** 摘要半径：命中处前后各取这么多字。 */
    const val SNIPPET_RADIUS = 12

    data class BookHit(
        val chapterIndex: Int,
        val chapterTitle: String,
        val offset: Int,
        val snippet: String,
    )

    data class BookChapterHits(
        val chapterIndex: Int,
        val chapterTitle: String,
        /** 本章真实命中总数（含被 cap 截掉的）。 */
        val total: Int,
        val hits: List<BookHit>,
    )

    data class BookSearchResult(
        val chapters: List<BookChapterHits>,
        /** 全书真实命中总数（含被 cap 截掉的）。 */
        val totalHits: Int,
        /** 有命中的章节数。 */
        val chaptersWithHits: Int,
        /** 实际列出的结果行数。 */
        val listedHits: Int,
    )

    fun searchInBook(
        chapters: List<Chapter>,
        query: String,
        perChapterCap: Int = PER_CHAPTER_CAP,
        totalCap: Int = TOTAL_CAP,
    ): BookSearchResult {
        val q = query.trim()
        if (q.isEmpty()) return BookSearchResult(emptyList(), 0, 0, 0)
        // 先算每章的全部命中位置（只做 indexOf 扫描，不拼摘要，2000 章也在毫秒级），
        // 总数才真实；摘要只给列出的行拼。
        val perChapter = chapters.mapNotNull { ch ->
            val content = ch.content
            if (content.isBlank()) return@mapNotNull null
            val offsets = findAll(content, q)
            if (offsets.isEmpty()) null else ch to offsets
        }
        val totalHits = perChapter.sumOf { (_, offsets) -> offsets.size }
        val groups = mutableListOf<BookChapterHits>()
        var listed = 0
        for ((ch, offsets) in perChapter) {
            if (listed >= totalCap) break
            val take = minOf(perChapterCap, totalCap - listed, offsets.size)
            val hits = offsets.take(take).map { off ->
                BookHit(ch.index, ch.title, off, snippet(ch.content, off, q))
            }
            listed += hits.size
            groups += BookChapterHits(ch.index, ch.title, offsets.size, hits)
        }
        return BookSearchResult(groups, totalHits, perChapter.size, listed)
    }

    private fun findAll(content: String, q: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = content.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            hits += i
            from = i + q.length.coerceAtLeast(1)
        }
        return hits
    }

    private fun snippet(content: String, offset: Int, q: String): String {
        val start = (offset - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (offset + q.length + SNIPPET_RADIUS).coerceAtMost(content.length)
        return "…${content.substring(start, end).replace("\n", " ")}…"
    }
}
