package com.movieshell.data.novel

import com.movieshell.core.net.SourceThrottle
import com.movieshell.core.net.buildSearchRequest
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.TestSearchResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.4.27 取消修法②（BookRuleEngine 可取消抓取）+ 搜索测试 testSearch 五档 + 失败名单按调用隔离。
 *
 * D1–D7 对应 gpt-step1-implementation.md 的验收判据：
 * D1 超时墙钟 / D2 底层连接确实断开 / D3 取消不记死站 / D4 不污染失败名单（含 searchAllFlow）
 * D5 超时后同源重测 / D6 取消语义（两个 Outcome 函数、throttle 中取消、普通慢源不受影响）/ D7 1MB 响应体。
 *
 * 注意：
 * - 全部用 runBlocking（真时钟），不用 runTest——runTest 的虚拟时间会让 withTimeout 立刻到点。
 * - 死站表（NovelSourceEngine.deadHosts）是进程级、按 host 记，本类所有桩都在本机；
 *   任何用例都不制造连接拒绝/DNS 失败这类连通性错误，免得把本机 host 记成死站连累其它用例。
 * - 慢响应用 Dispatcher 里 await 闸门实现（不用 setHeadersDelay 长睡），@After 先开闸再 shutdown，
 *   不会卡住 MockWebServer 关闭。
 */
class NovelSearchCancelTest {

    private lateinit var server: MockWebServer
    private val gate = CountDownLatch(1)

    /** 路径 → 行为。默认 /ok 出 2 本书。 */
    private enum class Mode { BOOKS, SLOW, EMPTY, ERROR_500, SLOW_1500MS, BIG }

    private val modes = java.util.concurrent.ConcurrentHashMap<String, Mode>()

    private fun booksHtml(vararg titles: String): String = buildString {
        append("<html><body>")
        titles.forEachIndexed { i, t -> append("<div class=\"book\"><a href=\"/book/$i\">$t</a></div>") }
        append("</body></html>")
    }

    /** 1MB 级正文：头 1 本书 + 约 1MB 填充 + 尾 1 本书（截断就丢尾书）。 */
    private val bigHtml: String by lazy {
        buildString {
            append("<html><body>")
            append("<div class=\"book\"><a href=\"/book/head\">开头的书</a></div>")
            val filler = "<p>" + "填".repeat(100) + "</p>"
            while (length < 1_100_000) append(filler)
            append("<div class=\"book\"><a href=\"/book/tail\">结尾的书</a></div>")
            append("</body></html>")
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val key = path.trim('/').substringBefore('/')
                return when (modes[key] ?: Mode.BOOKS) {
                    Mode.BOOKS -> html(booksHtml("斗罗大陆", "斗罗大陆II"))
                    Mode.EMPTY -> html(booksHtml())
                    Mode.ERROR_500 -> MockResponse().setResponseCode(500).setBody("boom")
                    Mode.SLOW -> {
                        // 收下请求后不回响应头，直到 @After 开闸（最多 60 秒）
                        gate.await(60, TimeUnit.SECONDS)
                        html(booksHtml("迟到的书"))
                    }
                    Mode.SLOW_1500MS -> html(booksHtml("慢但会到的书")).setHeadersDelay(1500, TimeUnit.MILLISECONDS)
                    Mode.BIG -> html(bigHtml)
                }
            }
        }
        server.start()
        NovelSourceEngine.clearSearchFailures()
    }

    @After
    fun tearDown() {
        gate.countDown()
        server.shutdown()
        NovelSourceEngine.clearSearchFailures()
    }

    private fun html(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

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

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    // ───────────────────────── D1 ─────────────────────────

    @Test
    fun d1_testSearchTimeoutIsWallClock() = runBlocking {
        modes["d1"] = Mode.SLOW
        val (r, ms) = timed { NovelSourceEngine.testSearch(source("D1源", "d1"), "斗罗", timeoutMs = 300L) }
        assertEquals(TestSearchResult.Timeout, r)
        assertTrue("300ms 超时实际耗时 ${ms}ms，应 < 2000ms", ms < 2_000)
    }

    // ───────────────────────── D2 ─────────────────────────

    /**
     * 手写 ServerSocket 桩：accept 后读完请求头、不回响应，阻塞读等 EOF。
     * 三条同时成立：墙钟 < 2 秒、服务端观察到连接断开、没有第二次连接/请求（取消后不重试）。
     */
    @Test
    fun d2_underlyingConnectionIsClosedOnCancel() = runBlocking {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        ss.soTimeout = 200
        val accepts = AtomicInteger(0)
        val closedAt = AtomicReference<Long?>(null)
        val headersRead = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val t = Thread {
            while (stop.count > 0) {
                val sock = try { ss.accept() } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                accepts.incrementAndGet()
                Thread {
                    sock.use { s ->
                        val input: InputStream = s.getInputStream()
                        // 读到 \r\n\r\n 为止
                        val tail = IntArray(4)
                        var n = 0
                        while (true) {
                            val b = input.read()
                            if (b < 0) break
                            tail[n % 4] = b; n++
                            if (n >= 4 && tail[(n - 4) % 4] == 13 && tail[(n - 3) % 4] == 10 &&
                                tail[(n - 2) % 4] == 13 && tail[(n - 1) % 4] == 10
                            ) break
                        }
                        headersRead.countDown()
                        // 不回响应，阻塞读：客户端 cancel → 读到 -1（FIN）或抛异常（RST）
                        val eof = try { input.read() } catch (_: Exception) { -1 }
                        if (eof < 0) closedAt.compareAndSet(null, System.nanoTime())
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
        try {
            val base = "http://127.0.0.1:${ss.localPort}"
            val src = BookSource(
                name = "D2源", url = base, searchUrl = "$base/s?q={{key}}",
                ruleSearchList = "div.book", ruleSearchName = "a@text", ruleSearchBookUrl = "a@href",
            )
            val t0 = System.nanoTime()
            val r = NovelSourceEngine.testSearch(src, "斗罗", timeoutMs = 300L)
            val returnedMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(TestSearchResult.Timeout, r)
            assertTrue("墙钟 ${returnedMs}ms 应 < 2000ms", returnedMs < 2_000)
            assertTrue("服务端应收到过请求头", headersRead.await(1, TimeUnit.SECONDS))
            // 服务端 2 秒内必须观察到断开
            val deadline = System.nanoTime() + 2_000_000_000L
            while (closedAt.get() == null && System.nanoTime() < deadline) Thread.sleep(20)
            val closed = closedAt.get()
            assertTrue("服务端未观察到连接断开（Call 没被 cancel）", closed != null)
            assertTrue("断开时刻距发起 ${(closed!! - t0) / 1_000_000}ms，应 < 2000ms", closed - t0 < 2_000_000_000L)
            // 老代码的重试拦截器会在 0.5s/1s 后重连；等 1.6 秒确认没有第二次连接
            Thread.sleep(1_600)
            assertEquals("取消后不应再发请求", 1, accepts.get())
            assertNull(NovelSourceEngine.failedSearchLabel())
        } finally {
            stop.countDown()
            ss.close()
        }
    }

    // ───────────────────────── D3 ─────────────────────────

    @Test
    fun d3_cancelledSearchDoesNotMarkDeadHostOrFailure() = runBlocking {
        modes["d3"] = Mode.SLOW
        val src = source("D3源", "d3")
        val first = withTimeoutOrNull(300L) { NovelSourceEngine.search(src, "斗罗") }
        assertNull("search 应被取消（返回 null），而不是返回空列表", first)
        assertNull("取消不应记失败", NovelSourceEngine.failedSearchLabel())
        // 同 host 切成正常响应再搜：若取消误记了死站，这里会直接空结果 + 记失败
        modes["d3"] = Mode.BOOKS
        val second = NovelSourceEngine.search(src, "斗罗")
        assertEquals(2, second.size)
        assertNull(NovelSourceEngine.failedSearchLabel())
    }

    // ───────────────────────── D4 ─────────────────────────

    @Test
    fun d4_timeoutDoesNotPolluteFailureList() = runBlocking {
        modes["d4slow"] = Mode.SLOW
        val r1 = NovelSourceEngine.testSearch(source("D4慢源", "d4slow"), "斗罗", timeoutMs = 300L)
        assertEquals(TestSearchResult.Timeout, r1)
        assertNull(NovelSourceEngine.failedSearchLabel())
        val r2 = NovelSourceEngine.testSearch(source("D4正常源", "d4ok"), "斗罗", timeoutMs = 5_000L)
        assertEquals(TestSearchResult.Ok(2), r2)
        val normal = NovelSourceEngine.search(source("D4正常源", "d4ok"), "斗罗")
        assertEquals(listOf("斗罗大陆", "斗罗大陆II"), normal.map { it.title })
        assertNull(NovelSourceEngine.failedSearchLabel())
    }

    /**
     * 聚合流：一个源卡死、一个源正常。正常源结果照常发出；卡死源按单源 30 秒预算
     * （SEARCH_SOURCE_TIMEOUT_MS，private const，不为单测改可配）被丢弃，且不算失败。
     * ②之前：阻塞请求不响应取消，要等 15 秒读超时 × 3 次重试（约 46.5 秒）才返回；
     * ②之后：30 秒墙钟即收尾。本用例约耗时 30 秒。
     */
    @Test
    fun d4_searchAllFlowSlowSourceIsNotAFailureAndIsWallClock() = runBlocking {
        modes["d4aslow"] = Mode.SLOW
        val slow = source("D4聚合慢源", "d4aslow")
        val ok = source("D4聚合正常源", "d4aok")
        val (emitted, ms) = timed { NovelSourceEngine.searchAllFlow(listOf(slow, ok), "斗罗").toList() }
        // v1.4.27 Step4 审稿 A8：先锁结果——正常源结果已发出、失败名单为空；
        // 耗时上限从 36 秒放宽到 40 秒（CI/慢机抖动），仍远低于改前的约 46.5 秒，能拦住回退。
        assertEquals(listOf("D4聚合正常源"), emitted.map { it.source.name })
        assertEquals(listOf("斗罗大陆", "斗罗大陆II"), emitted.single().books.map { it.title })
        assertNull("超时源不能被当成普通失败（失败名单应为空）", NovelSourceEngine.failedSearchLabel())
        assertTrue("整轮应在单源 30 秒预算附近收尾，实际 ${ms}ms", ms in 29_000..40_000)
    }

    // ───────────────────────── D5 ─────────────────────────

    @Test
    fun d5_retestAfterTimeoutSucceeds() = runBlocking {
        modes["d5"] = Mode.SLOW
        val src = source("D5源", "d5")
        assertEquals(TestSearchResult.Timeout, NovelSourceEngine.testSearch(src, "斗罗", timeoutMs = 300L))
        modes["d5"] = Mode.BOOKS
        val r = NovelSourceEngine.testSearch(src, "斗罗", timeoutMs = 5_000L)
        assertTrue("第二次应 Ok(count>0)，实际 $r", r is TestSearchResult.Ok && r.count > 0)
    }

    // ───────────────────────── D6 ─────────────────────────

    @Test
    fun d6_fetchSearchPageOutcomeCancellationPropagates() = runBlocking {
        modes["d6s"] = Mode.SLOW
        val req = buildSearchRequest(server.url("/d6s/search?q={{key}}").toString().replace("%7B%7Bkey%7D%7D", "{{key}}"), "斗罗")
        val t0 = System.nanoTime()
        try {
            withTimeout(200L) { BookRuleEngine.fetchSearchPageOutcome(req) }
            fail("应抛 CancellationException，而不是返回 FetchOutcome(null, null)")
        } catch (_: TimeoutCancellationException) {
            // 预期
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("取消耗时 ${ms}ms 应 < 1000ms", ms < 1_000)
        Thread.sleep(1_600) // 老代码的重试拦截器 0.5s/1s 后会重发
        assertEquals("取消后不应再发请求", 1, server.requestCount)
    }

    @Test
    fun d6_fetchPageOutcomeCancellationPropagates() = runBlocking {
        modes["d6p"] = Mode.SLOW
        val url = server.url("/d6p/page").toString()
        val t0 = System.nanoTime()
        try {
            withTimeout(200L) { BookRuleEngine.fetchPageOutcome(url) }
            fail("应抛 CancellationException，而不是返回 FetchOutcome(null, null, error)")
        } catch (_: TimeoutCancellationException) {
            // 预期
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("取消耗时 ${ms}ms 应 < 1000ms", ms < 1_000)
        Thread.sleep(1_600)
        assertEquals("取消后不应再发请求", 1, server.requestCount)
    }

    @Test
    fun d6_cancelDuringThrottleDelaySendsNoRequest() = runBlocking {
        val host = server.url("/").host
        // 先在 gap=0 时盖一次 lastHit（不延迟），再设 5 秒间隔：下一次同域请求必须在 throttle 里等
        SourceThrottle.throttle(host)
        SourceThrottle.setGap(host, 5_000L)
        try {
            val t0 = System.nanoTime()
            val r = withTimeoutOrNull(200L) { BookRuleEngine.fetchPageOutcome(server.url("/d6t/page").toString()) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertNull("throttle 延迟中取消应抛取消（withTimeoutOrNull 得 null）", r)
            assertTrue("应在 delay 里立即响应取消，实际 ${ms}ms", ms < 1_000)
            assertEquals("throttle 期间取消不应建 Call", 0, server.requestCount)
        } finally {
            SourceThrottle.setGap(host, 0L)
        }
    }

    @Test
    fun d6_ordinarySlowSourceStillWorks() = runBlocking {
        modes["d6slow"] = Mode.SLOW_1500MS
        val r = NovelSourceEngine.testSearch(source("D6慢源", "d6slow"), "斗罗") // 默认 30 秒
        assertEquals(TestSearchResult.Ok(1), r)
        val books = NovelSourceEngine.search(source("D6慢源", "d6slow"), "斗罗")
        assertEquals("慢但会到的书", books.single().title)
    }

    // ───────────────────────── D7 ─────────────────────────

    @Test
    fun d7_largeBodyReadFullyAndResponseClosed() = runBlocking {
        modes["d7"] = Mode.BIG
        val url = server.url("/d7/page").toString()
        val outcome = BookRuleEngine.fetchPageOutcome(url)
        assertEquals(200, outcome.httpCode)
        assertEquals("正文应完整读取不截断", bigHtml.length, outcome.page!!.body.length)
        assertTrue(outcome.page!!.body.endsWith("</body></html>"))
        // 响应正确关闭 → 连接回池复用：第二个请求落在同一连接上（sequenceNumber 递增）
        val r = NovelSourceEngine.testSearch(source("D7源", "d7"), "斗罗", timeoutMs = 10_000L)
        assertEquals(TestSearchResult.Ok(2), r)
        val books = NovelSourceEngine.search(source("D7源", "d7"), "斗罗")
        assertEquals(listOf("开头的书", "结尾的书"), books.map { it.title })
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(0, first.sequenceNumber)
        assertEquals("连接未复用，说明前一个响应没关干净", 1, second.sequenceNumber)
    }

    // ───────────────────────── 五档 / 关键词 ─────────────────────────

    @Test
    fun tier_zeroWhenPageOkButNoBooks() = runBlocking {
        modes["zero"] = Mode.EMPTY
        assertEquals(TestSearchResult.Zero, NovelSourceEngine.testSearch(source("零条源", "zero"), "斗罗", 5_000L))
    }

    @Test
    fun tier_failedOnHttp500() = runBlocking {
        modes["f500"] = Mode.ERROR_500
        assertEquals(TestSearchResult.Failed, NovelSourceEngine.testSearch(source("失败源", "f500"), "斗罗", 5_000L))
        assertNull("测试失败不写全局失败名单", NovelSourceEngine.failedSearchLabel())
    }

    @Test
    fun tier_failedWhenSearchUrlCannotBeBuilt() = runBlocking {
        // 相对地址 + 源地址不是 http(s)：buildSearchReq 回 null → 记失败（search() 里的既有判断）
        val src = BookSource(name = "坏地址源", url = "ftp://nowhere", searchUrl = "/s?q={{key}}", ruleSearchList = "div.book")
        assertEquals(TestSearchResult.Failed, NovelSourceEngine.testSearch(src, "斗罗", 5_000L))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tier_notConfiguredWhenEitherIsBlank() = runBlocking {
        val noList = source("缺列表规则", "nc1") { it.copy(ruleSearchList = "") }
        val noUrl = source("缺搜索地址", "nc2") { it.copy(searchUrl = "  ") }
        assertEquals(TestSearchResult.NotConfigured, NovelSourceEngine.testSearch(noList, "斗罗"))
        assertEquals(TestSearchResult.NotConfigured, NovelSourceEngine.testSearch(noUrl, "斗罗"))
        assertEquals("未配置不发请求", 0, server.requestCount)
    }

    @Test
    fun keyword_blankFallsBackToCheckKeyWordThenDefault() = runBlocking {
        val withKw = source("关键词源", "kw") { it.copy(checkKeyWord = " 凡人 ") }
        NovelSourceEngine.testSearch(withKw, "   ", 5_000L)
        assertEquals("凡人", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("q"))
        NovelSourceEngine.testSearch(source("默认词源", "kw"), "", 5_000L)
        assertEquals("斗罗", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("q"))
        NovelSourceEngine.testSearch(withKw, " 遮天 ", 5_000L)
        assertEquals("遮天", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("q"))
    }

    // ───────────────────────── 并发隔离（未决点 2） ─────────────────────────

    /** testSearch 不清、不写全局失败名单；聚合搜索的失败也不会被测试读成本源失败。 */
    @Test
    fun isolation_testSearchAndSearchAllFlowDoNotShareFailures() = runBlocking {
        modes["iso_agg_fail"] = Mode.ERROR_500
        modes["iso_test_fail"] = Mode.ERROR_500
        // 先跑一轮聚合，留下 1 条全局失败
        NovelSourceEngine.searchAllFlow(listOf(source("聚合失败源", "iso_agg_fail")), "斗罗").toList()
        assertEquals("1 个书源失败", NovelSourceEngine.failedSearchLabel())
        // 测试一个失败源：全局汇总不被清、也不 +1
        assertEquals(
            TestSearchResult.Failed,
            NovelSourceEngine.testSearch(source("测试失败源", "iso_test_fail"), "斗罗", 5_000L),
        )
        assertEquals("1 个书源失败", NovelSourceEngine.failedSearchLabel())
        // 测试一个正常源：全局里有别的失败，也不能把本源判成 Failed
        assertEquals(
            TestSearchResult.Ok(2),
            NovelSourceEngine.testSearch(source("测试正常源", "iso_ok"), "斗罗", 5_000L),
        )
        // 并发：两边同时跑，各记各的
        NovelSourceEngine.clearSearchFailures()
        val (testResult, _) = coroutineScope {
            val a = async { NovelSourceEngine.testSearch(source("并发测试失败源", "iso_test_fail"), "斗罗", 5_000L) }
            val b = async {
                NovelSourceEngine.searchAllFlow(
                    listOf(source("并发聚合失败源", "iso_agg_fail"), source("并发聚合正常源", "iso_ok")),
                    "斗罗",
                ).toList()
            }
            a.await() to b.await()
        }
        assertEquals(TestSearchResult.Failed, testResult)
        assertEquals("1 个书源失败", NovelSourceEngine.failedSearchLabel())
    }

    /** 调用方取消（删源/退页）：原样抛取消，不返回任何档位。 */
    @Test
    fun callerCancellationPropagates() = runBlocking {
        modes["cc"] = Mode.SLOW
        val r = withTimeoutOrNull(300L) { NovelSourceEngine.testSearch(source("取消源", "cc"), "斗罗", 30_000L) }
        assertNull(r)
        assertNull(NovelSourceEngine.failedSearchLabel())
    }
}
