package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v1.4.26：探测 404 回落——根地址 404 时改探该源实际用的接口再判，
 * 其他状态码与异常保持原语义，不回落；取消仍原样抛（见 probe 的旧约定）。
 */
class BookProbeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun probeRoot404FallsBackToCategory() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            exploreUrl = """[{"title":"都市","url":"${base()}/cat"}]""",
        )
        val rec = BookProbe.probeSource(src)
        assertEquals("OK", rec.status)
        assertEquals(src.url, rec.url)
        assertEquals(2, server.requestCount)
        server.takeRequest(5, TimeUnit.SECONDS) // 根地址
        val fallback = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/cat", fallback.path)
    }

    @Test fun probeRoot200DoesNotFallBack() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            exploreUrl = """[{"title":"都市","url":"${base()}/cat"}]""",
        )
        val rec = BookProbe.probeSource(src)
        assertEquals("OK", rec.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun probeRoot404WithoutFallbackIsFail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val src = BookSource(name = "t", url = "${base()}/")
        val rec = BookProbe.probeSource(src)
        assertEquals("FAIL", rec.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun probeRoot500DoesNotFallBack() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            exploreUrl = """[{"title":"都市","url":"${base()}/cat"}]""",
        )
        val rec = BookProbe.probeSource(src)
        assertEquals("FAIL", rec.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun probeRoot404FallsBackToSearchUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            searchUrl = "${base()}/s?key={{key}}&page={{page}}",
        )
        val rec = BookProbe.probeSource(src)
        assertEquals("OK", rec.status)
        assertEquals(2, server.requestCount)
        server.takeRequest(5, TimeUnit.SECONDS) // 根地址
        val fallback = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(fallback.path!!.contains("key=%E6%96%97%E7%BD%97")) // 「斗罗」URL 编码后
        assertTrue(fallback.path!!.contains("page=1"))
    }

    // v1.4.26 修（GPT 推前审稿 P1）：搜索回落保留完整请求语义，POST 搜索源按 POST 探。
    @Test fun probeRoot404FallsBackToPostSearch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            searchUrl = "${base()}/s,{\"method\":\"POST\",\"body\":\"key={{key}}&page={{page}}\"}",
        )
        val rec = BookProbe.probeSource(src)
        assertEquals("OK", rec.status)
        assertEquals(2, server.requestCount)
        server.takeRequest(5, TimeUnit.SECONDS) // 根地址
        val fallback = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", fallback.method)
        val body = fallback.body.readUtf8()
        assertTrue(body.contains("key=%E6%96%97%E7%BD%97")) // 「斗罗」URL 编码后
        assertTrue(body.contains("page=1"))
    }

    // v1.4.26 修（GPT 推前审稿 P2）：回落耗时按整个探测流程计，根地址慢也记慢。
    @Test fun probeFallbackMsCountsTotalTime() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeadersDelay(3500, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            exploreUrl = """[{"title":"都市","url":"${base()}/cat"}]""",
        )
        val rec = BookProbe.probeSource(src)
        assertEquals("SLOW", rec.status)
        assertTrue(rec.ms >= 3000)
    }

    // 1.4.27 闪退修：2117 包“精华书阁”的 bookSourceUrl 是一段鲁迅名言（非 URL），
    // probeSource 拿它建请求会抛 IllegalArgumentException。根地址无效时应跳过
    // 根探、直接走搜索回落，绝不抛。
    @Test fun probeGarbageRootUrlFallsBackToSearch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "精华书阁",
            url = "读书无嗜好，就能尽其多。不先泛览群书，则会无所适从或失之偏好，广然后深，博然后专。——鲁迅",
            searchUrl = "${base()}/s?key={{key}}&page={{page}}",
        )
        val rec = BookProbe.probeSource(src) // 不抛
        assertEquals("OK", rec.status)
        assertEquals(1, server.requestCount) // 只探了回落，根地址没发请求
    }

    @Test fun probeGarbageRootUrlWithoutFallbackIsFail() = runBlocking {
        val src = BookSource(name = "t", url = "读书无嗜好，就能尽其多。")
        val rec = BookProbe.probeSource(src) // 不抛
        assertEquals("FAIL", rec.status)
        assertEquals(0, server.requestCount)
    }

    @Test fun probeGarbageCategoryUrlFallsBackToSearch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        val src = BookSource(
            name = "t",
            url = "${base()}/",
            // “http:notaurl”会被 resolveUrl 原样返回（以 http 开头），但不是合法
            // http(s) 地址——分类回落必须跳过它改走搜索，否则建请求会抛。
            exploreUrl = """[{"title":"都市","url":"http:notaurl"}]""",
            searchUrl = "${base()}/s?key={{key}}&page={{page}}",
        )
        val rec = BookProbe.probeSource(src) // 不抛
        assertEquals("OK", rec.status)
        assertEquals(2, server.requestCount)
        server.takeRequest(5, TimeUnit.SECONDS) // 根地址
        val fallback = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(fallback.path!!.contains("key=%E6%96%97%E7%BD%97")) // 走的是搜索回落
    }
}
