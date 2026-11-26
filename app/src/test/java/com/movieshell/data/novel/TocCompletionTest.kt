package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.4.27 P0.3 长书目录补全：缺段检测、展开链接识别、非 JS 源分页与“展开全部”。 */
class TocCompletionTest {

    // ---------------- TocGap ----------------

    private fun numbered(range: IntRange) = range.map { "第${it}章 标题$it" }

    @Test
    fun gap_detected_for_head_tail_toc() {
        // 全本小说网形态：头 23 章 + 尾 24 章，中段约 1577 章缺
        val titles = numbered(1..23) + numbered(1601..1624)
        val g = TocGap.detect(titles)!!
        assertEquals(22, g.afterIndex)
        assertEquals(23, g.fromNo)
        assertEquals(1601, g.toNo)
        assertEquals(1577, g.missing)
    }

    @Test
    fun no_gap_for_continuous_small_gap_volumes_or_unnumbered() {
        assertNull(TocGap.detect(numbered(1..200)))
        assertNull("缺 10 章不报", TocGap.detect(numbered(1..30) + numbered(41..60)))
        // 分卷书：每卷从第一章重来，卷内连续
        assertNull(TocGap.detect(numbered(1..30) + numbered(1..30) + numbered(1..30)))
        // 大部分章名没章号
        assertNull(TocGap.detect(List(40) { "楔子$it" } + numbered(1..5) + numbered(500..505)))
        assertNull("太短不判", TocGap.detect(numbered(1..3) + numbered(900..902)))
    }

    @Test
    fun chapter_number_parsing() {
        assertEquals(105, TocGap.chapterNumber("第一百零五章 风起"))
        assertEquals(21, TocGap.chapterNumber("第二十一回"))
        assertEquals(10, TocGap.chapterNumber("第十章"))
        assertEquals(12000, TocGap.chapterNumber("第一万二千章"))
        assertEquals(1578, TocGap.chapterNumber("第1578章"))
        assertEquals(12, TocGap.chapterNumber("第１２章"))
        assertEquals(37, TocGap.chapterNumber("37. 标题"))
        assertNull(TocGap.chapterNumber("楔子"))
        assertNull(TocGap.chapterNumber("2024年记事"))
    }

    // ---------------- 展开链接 ----------------

    @Test
    fun expand_link_only_plain_links() {
        val base = "https://x.com/book/1/"
        assertEquals(
            "https://x.com/book/1/all.html",
            BookRuleEngine.findExpandTocLink("""<a href="all.html">展开全部章节</a>""", base),
        )
        assertNull(BookRuleEngine.findExpandTocLink("""<a href="javascript:void(0)">展开全部</a>""", base))
        assertNull(BookRuleEngine.findExpandTocLink("""<a href="#more">展开全部</a>""", base))
        assertNull(BookRuleEngine.findExpandTocLink("""<a href="/book/1/">完整目录</a>""", base))
        assertNull(BookRuleEngine.findExpandTocLink("""<a href="/x">下一页</a>""", base))
    }

    @Test
    fun dedup_by_url_and_reindex() {
        val l = listOf(
            ChapterMeta(0, "一", "u1"), ChapterMeta(1, "二", "u2"),
            ChapterMeta(0, "一", "u1"), ChapterMeta(1, "三", "u3"),
        )
        assertEquals(listOf("u1", "u2", "u3"), NovelSourceEngine.dedupToc(l).map { it.url })
        assertEquals(listOf(0, 1, 2), NovelSourceEngine.dedupToc(l).map { it.index })
    }

    // ---------------- 引擎：非 JS 源分页 / 展开全部 ----------------

    private lateinit var server: MockWebServer
    private val pages = HashMap<String, String>()

    private fun tocHtml(chs: List<Pair<Int, String>>, extra: String = "") = buildString {
        append("<html><body><ul class=\"toc\">")
        chs.forEach { (n, href) -> append("<li><a href=\"$href\">第${n}章 标题$n</a></li>") }
        append("</ul>$extra</body></html>")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = pages[request.requestUrl?.encodedPath.orEmpty()]
                    ?: return MockResponse().setResponseCode(404).setBody("nf")
                return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun src(next: String = "") = BookSource(
        name = "测",
        url = server.url("/").toString(),
        ruleTocList = "ul.toc a",
        ruleTocNext = next,
    )

    private fun book(path: String) = NovelSearchResult(remoteId = server.url(path).toString(), title = "书")

    @Test
    fun plain_source_follows_ruleTocNext_and_stops_on_loop() = runBlocking {
        pages["/b1"] = tocHtml((1..3).map { it to "/b1/c$it" }, """<a class="next" href="/b1/p2">下一页</a>""")
        pages["/b1/p2"] = tocHtml((4..5).map { it to "/b1/c$it" }, """<a class="next" href="/b1/p3">下一页</a>""")
        // 第 3 页的“下一页”指回第 2 页：访问过不再抓；重复章节按 URL 去重
        pages["/b1/p3"] = tocHtml(listOf(5 to "/b1/c5", 6 to "/b1/c6"), """<a class="next" href="/b1/p2">下一页</a>""")
        val withNext = NovelSourceEngine.chapters(src("a.next@href"), book("/b1"))
        assertEquals((1..6).map { "第${it}章 标题$it" }, withNext.map { it.title })
        assertEquals((0..5).toList(), withNext.map { it.index })
        // 没配 ruleTocNext：与改版前一样只取首页
        assertEquals(3, NovelSourceEngine.chapters(src(), book("/b1")).size)
    }

    @Test
    fun expand_follows_plain_expand_link_only_when_requested() = runBlocking {
        val head = (1..12).map { it to "/b2/c$it" }
        val tail = (1500..1511).map { it to "/b2/c$it" }
        pages["/b2"] = tocHtml(head + tail, """<a href="/b2/all">展开全部</a>""")
        pages["/b2/all"] = tocHtml((1..1511).map { it to "/b2/c$it" })
        val first = NovelSourceEngine.chapters(src(), book("/b2"))
        assertEquals(24, first.size)
        val st = NovelTocService.classify(first)
        assertTrue(st is NovelTocService.TocState.Partial)
        assertEquals(1487, (st as NovelTocService.TocState.Partial).gap.missing)
        val full = NovelSourceEngine.chapters(src(), book("/b2"), expand = true)
        assertEquals(1511, full.size)
        assertTrue(NovelTocService.classify(full) is NovelTocService.TocState.Ready)
    }

    @Test
    fun expand_without_link_keeps_partial_result() = runBlocking {
        // 只有 javascript: 展开按钮（要网页脚本）——拿不到，原样返回头尾
        pages["/b3"] = tocHtml(
            (1..12).map { it to "/b3/c$it" } + (900..911).map { it to "/b3/c$it" },
            """<a href="javascript:loadAll()">展开全部</a>""",
        )
        val r = NovelSourceEngine.chapters(src(), book("/b3"), expand = true)
        assertEquals(24, r.size)
        assertNotNull(TocGap.detect(r.map { it.title }))
    }
}
