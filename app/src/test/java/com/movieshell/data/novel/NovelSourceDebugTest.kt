package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.4.28 书源调试第 1 步：searchWithTrace 的轨迹字段。
 *
 * 失败路径字段值（③-R3，逐一固定）：
 * - 搜索未配置（searchUrl/ruleSearchList 任一空）：全部默认——requestUrl ""、httpCode null、
 *   bodyLength 0、bodyPreview ""、results 空；
 * - req 构造失败：requestUrl ""、httpCode null；
 * - 死站：requestUrl = req.url、httpCode null；
 * - 页没抓到：requestUrl = req.url、httpCode = 最后一次抓取的状态（可能 null）；
 * - 200 空体（⑦-R1）：httpCode 200、bodyLength = 实际体长（0 字节体为 0）、results 空、
 *   requestUrl 不丢；空白体（只有空白字符）同理，bodyPreview 折叠后为空串；
 * - 取消：CancellationException 原样抛，不返回 trace。
 */
class NovelSourceDebugTest {

    private lateinit var server: MockWebServer

    private enum class Mode { BOOKS, EMPTY, BLANK, ZERO, SLOW }

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
                    Mode.SLOW -> {
                        Thread.sleep(5_000)
                        MockResponse().setResponseCode(200).setBody(booksHtml("斗罗大陆"))
                    }
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

    @Test fun traceNormalJsonSearch() = runBlocking {
        modes["t1"] = Mode.BOOKS
        // ③-R4：requestUrl 含关键词的断言用 ASCII 关键词最稳。
        val trace = NovelSourceEngine.searchWithTrace(source("正常源", "t1"), "test", recordFailures = false)
        assertTrue("requestUrl 应含关键词，实际 ${trace.requestUrl}", trace.requestUrl.contains("test"))
        assertEquals(200, trace.httpCode)
        assertTrue(trace.bodyLength > 0)
        assertTrue("bodyPreview 不应含换行", !trace.bodyPreview.contains("\n"))
        assertTrue(trace.bodyPreview.contains("斗罗大陆"))
        assertEquals(2, trace.results.size)
        assertEquals("斗罗大陆", trace.results[0].title)
    }

    @Test fun traceEmptyBodyStillComplete() = runBlocking {
        modes["t2"] = Mode.EMPTY
        val trace = NovelSourceEngine.searchWithTrace(source("空体源", "t2"), "test", recordFailures = false)
        assertEquals(200, trace.httpCode)
        assertEquals(0, trace.bodyLength)
        assertEquals("", trace.bodyPreview)
        assertTrue(trace.results.isEmpty())
        assertTrue("requestUrl 不丢，实际 ${trace.requestUrl}", trace.requestUrl.contains("search"))
    }

    @Test fun traceBlankBodyStillComplete() = runBlocking {
        modes["t3"] = Mode.BLANK
        val trace = NovelSourceEngine.searchWithTrace(source("空白体源", "t3"), "test", recordFailures = false)
        assertEquals(200, trace.httpCode)
        assertEquals(5, trace.bodyLength)
        assertEquals("", trace.bodyPreview)
        assertTrue(trace.results.isEmpty())
    }

    @Test fun traceNotConfigured() = runBlocking {
        val src = source("未配置源", "t4") { it.copy(searchUrl = "") }
        val trace = NovelSourceEngine.searchWithTrace(src, "test", recordFailures = false)
        assertEquals("", trace.requestUrl)
        assertNull(trace.httpCode)
        assertEquals(0, trace.bodyLength)
        assertEquals("", trace.bodyPreview)
        assertTrue(trace.results.isEmpty())
    }

    @Test fun traceDoesNotRecordFailureWhenDisabled() = runBlocking {
        modes["t5"] = Mode.EMPTY
        NovelSourceEngine.searchWithTrace(source("空体源", "t5"), "test", recordFailures = false)
        assertNull("调试跑 draft 不应污染正式失败名单", NovelSourceEngine.failedSearchLabel())
        // 对照：同一条路，recordFailures=true 时记失败。
        NovelSourceEngine.searchWithTrace(source("空体源", "t5"), "test", recordFailures = true)
        assertTrue(NovelSourceEngine.failedSearchLabel() != null)
    }

    @Test fun searchDelegatesToTrace() = runBlocking {
        modes["t6"] = Mode.BOOKS
        val src = source("正常源", "t6")
        val viaSearch = NovelSourceEngine.search(src, "test")
        val viaTrace = NovelSourceEngine.searchWithTrace(src, "test", recordFailures = false)
        assertEquals(viaTrace.results.map { it.title }, viaSearch.map { it.title })
    }

    @Test fun traceJsSearch() = runBlocking {
        modes["t7"] = Mode.BOOKS
        // JS 列表规则用 <js> 前缀形：JS 先对整页求值（这里原样返回），再按 CSS 选元素。
        // 注意 "div.book@js:result" 不行——链式求值里 js: 段只产文本，elems() 为空。
        val js = source("JS 源", "t7") { it.copy(ruleSearchList = "<js>result</js>div.book") }
        val trace = NovelSourceEngine.searchWithTrace(js, "test", recordFailures = false)
        assertEquals(200, trace.httpCode)
        assertEquals(2, trace.results.size)
        assertTrue(trace.requestUrl.contains("test"))
    }

    @Test fun traceCancellationThrows() = runBlocking {
        modes["t8"] = Mode.SLOW
        val deferred = async { NovelSourceEngine.searchWithTrace(source("慢源", "t8"), "test", recordFailures = false) }
        delay(300)
        deferred.cancel()
        val err = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue("取消应原样抛 CancellationException，实际 $err", err is CancellationException)
    }
}
