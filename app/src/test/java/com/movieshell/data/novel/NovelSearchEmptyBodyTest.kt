package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.model.TestSearchResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * v1.4.28 B2：HTTP 200 空体判失败，不判“未验证”。
 *
 * 判定点在 search() 里（testSearch 只拿到结果列表，分不出空体与真 0 条），
 * 两条路径（非 JS / JS）都要覆盖；真正 0 条（body 非空、解析出 0 条）仍判 Zero。
 */
class NovelSearchEmptyBodyTest {

    private lateinit var server: MockWebServer

    private enum class Mode { BOOKS, EMPTY, BLANK, ZERO }

    private val modes = java.util.concurrent.ConcurrentHashMap<String, Mode>()

    private fun booksHtml(vararg titles: String): String = buildString {
        append("<html><body>")
        titles.forEachIndexed { i, t -> append("<div class=\"book\"><a href=\"/book/$i\">$t</a></div>") }
        append("</body></html>")
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val key = path.trim('/').substringBefore('/')
                return when (modes[key] ?: Mode.BOOKS) {
                    Mode.BOOKS -> MockResponse().setResponseCode(200).setBody(booksHtml("斗罗大陆", "斗罗大陆II"))
                    Mode.EMPTY -> MockResponse().setResponseCode(200).setBody("")
                    Mode.BLANK -> MockResponse().setResponseCode(200).setBody("  \n\t ")
                    Mode.ZERO -> MockResponse().setResponseCode(200).setBody(booksHtml())
                }
            }
        }
        server.start()
        NovelSourceEngine.clearSearchFailures()
    }

    @After fun tearDown() {
        server.shutdown()
        NovelSourceEngine.clearSearchFailures()
    }

    /** 搜索地址 http://host:port/<key>/search?q={{key}}，CSS 规则（走非 JS 分支）。 */
    private fun source(name: String, key: String, block: (BookSource) -> BookSource = { it }): BookSource {
        val base = server.url("/").toString().trimEnd('/')
        return block(
            BookSource(
                name = name,
                url = base,
                searchUrl = "$base/$key/search?q={{key}}",
                ruleSearchList = "div.book",
                ruleSearchName = "a@text",
                ruleSearchBookUrl = "a@href",
            ),
        )
    }

    @Test fun testSearch200EmptyBodyIsFailed() = runBlocking {
        modes["e1"] = Mode.EMPTY
        assertEquals(TestSearchResult.Failed, NovelSourceEngine.testSearch(source("空体源", "e1"), "斗罗", 5_000L))
    }

    @Test fun testSearch200BlankBodyIsFailed() = runBlocking {
        modes["e2"] = Mode.BLANK
        assertEquals(TestSearchResult.Failed, NovelSourceEngine.testSearch(source("空白体源", "e2"), "斗罗", 5_000L))
    }

    @Test fun testSearch200EmptyBodyIsFailedForJsSource() = runBlocking {
        modes["e3"] = Mode.EMPTY
        // ruleSearchList 含 @js: 即走 JS 分支；空体在解析前就判失败，JS 求值本身跑不到。
        val js = source("JS 空体源", "e3") { it.copy(ruleSearchList = "div.book@js:result") }
        assertEquals(TestSearchResult.Failed, NovelSourceEngine.testSearch(js, "斗罗", 5_000L))
    }

    @Test fun genuineZeroStaysZero() = runBlocking {
        modes["z1"] = Mode.ZERO
        assertEquals(TestSearchResult.Zero, NovelSourceEngine.testSearch(source("零条源", "z1"), "斗罗", 5_000L))
    }

    @Test fun okUnaffected() = runBlocking {
        modes["o1"] = Mode.BOOKS
        assertEquals(TestSearchResult.Ok(2), NovelSourceEngine.testSearch(source("正常源", "o1"), "斗罗", 5_000L))
    }

    @Test fun findBooksFailureLabelIncludesEmptyBody() = runBlocking {
        modes["e4"] = Mode.EMPTY
        val results = NovelSourceEngine.search(source("空体源2", "e4"), "斗罗")
        assertEquals(emptyList<NovelSearchResult>(), results)
        // search() 直调（无专属收集器）记进全局失败名单，找书页汇总口径随之变化。
        assertNotNull(NovelSourceEngine.failedSearchLabel())
    }
}
