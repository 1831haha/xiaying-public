package com.movieshell.core.net

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v1.4.23：明文 HTTP 授权网关纯函数测试。
 * 只测 CleartextGate（不碰 Android、不碰 SharedPreferences、不发真实请求）；
 * CleartextAuth 的 SharedPreferences 部分按 1.4.21 CredentialMigrationTest
 * 的做法不测。MockWebServer 测试不要：回环恒放行，测不出拦截；拦截器本身
 * 用 FakeChain 直接测（每调一次即重定向链上一跳，不依赖真实网络）。
 * 拦截器测试调 CleartextAuth.authorize/revoke 只改内存快照（未 init 时
 * prefs 为空、写盘是空操作），用完在 finally 里 revoke，不污染其它测试。
 */
class CleartextGateTest {

    // ---------------- decide ----------------

    @Test
    fun `https always allowed`() {
        assertTrue(CleartextGate.decide("https", "example.com", emptySet()))
        assertTrue(CleartextGate.decide("HTTPS", "example.com", emptySet()))
    }

    @Test
    fun `http preauthorized host allowed`() {
        assertTrue(CleartextGate.decide("http", "sspa8.top", emptySet()))
        assertTrue(CleartextGate.decide("http", "mobilecdn.kugou.com", emptySet()))
    }

    @Test
    fun `http unauthorized public host blocked`() {
        assertFalse(CleartextGate.decide("http", "example.com", emptySet()))
        assertFalse(CleartextGate.decide("http", "evil.example.com", emptySet()))
    }

    @Test
    fun `http authorized host allowed`() {
        assertTrue(CleartextGate.decide("http", "user-sub.example.com", setOf("user-sub.example.com")))
    }

    @Test
    fun `http loopback allowed`() {
        assertTrue(CleartextGate.decide("http", "127.0.0.1", emptySet()))
        assertTrue(CleartextGate.decide("http", "127.9.8.7", emptySet()))
        assertTrue(CleartextGate.decide("http", "localhost", emptySet()))
        assertTrue(CleartextGate.decide("http", "::1", emptySet()))
    }

    @Test
    fun `http private ip allowed`() {
        assertTrue(CleartextGate.decide("http", "10.0.0.5", emptySet()))
        assertTrue(CleartextGate.decide("http", "172.16.0.1", emptySet()))
        assertTrue(CleartextGate.decide("http", "172.31.255.255", emptySet()))
        assertTrue(CleartextGate.decide("http", "192.168.1.10", emptySet()))
        assertTrue(CleartextGate.decide("http", "169.254.1.2", emptySet()))
    }

    @Test
    fun `http public ip blocked`() {
        assertFalse(CleartextGate.decide("http", "8.8.8.8", emptySet()))
        assertFalse(CleartextGate.decide("http", "172.32.0.1", emptySet()))
    }

    @Test
    fun `subdomain of preauthorized allowed, lookalike blocked`() {
        assertTrue(CleartextGate.decide("http", "a.sspa8.top", emptySet()))
        assertTrue(CleartextGate.decide("http", "deep.a.sspa8.top", emptySet()))
        assertFalse(CleartextGate.decide("http", "badsspa8.top", emptySet()))
        assertFalse(CleartextGate.decide("http", "sspa8.top.evil.com", emptySet()))
    }

    @Test
    fun `subdomain of authorized allowed`() {
        assertTrue(
            CleartextGate.decide("http", "cdn.user.com", setOf("user.com")),
        )
        assertFalse(
            CleartextGate.decide("http", "notuser.com", setOf("user.com")),
        )
    }

    @Test
    fun `case and port normalized in decide`() {
        assertTrue(CleartextGate.decide("http", "SSPA8.TOP", emptySet()))
        assertTrue(CleartextGate.decide("http", "sspa8.top:8100", emptySet()))
        assertTrue(CleartextGate.decide("http", "User-Sub.Example.COM.", setOf("user-sub.example.com")))
    }

    // ---------------- normalizeHost ----------------

    @Test
    fun `normalizeHost lowercases trims strips trailing dot and port`() {
        assertEquals("example.com", CleartextGate.normalizeHost(" Example.COM. "))
        assertEquals("example.com", CleartextGate.normalizeHost("example.com:8080"))
        assertEquals("example.com", CleartextGate.normalizeHost("EXAMPLE.COM:80"))
    }

    @Test
    fun `normalizeHost handles ipv6 with brackets and port`() {
        assertEquals("::1", CleartextGate.normalizeHost("[::1]:8080"))
        assertEquals("::1", CleartextGate.normalizeHost("::1"))
        assertEquals("2001:db8::1", CleartextGate.normalizeHost("[2001:db8::1]"))
    }

    // ---------------- isLoopbackOrPrivateIp ----------------

    @Test
    fun `loopback and private ip detection`() {
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("127.0.0.1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("127.255.255.255"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("localhost"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("LOCALHOST"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("::1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("10.1.2.3"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("172.16.0.1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("172.31.0.1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("192.168.0.1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("169.254.0.1"))
    }

    @Test
    fun `public hosts are not loopback or private`() {
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("8.8.8.8"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("172.32.0.1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("193.168.0.1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("example.com"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("1.2.3.256"))
    }

    @Test
    fun `ipv6 ula and link-local are private`() {
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("fc00::1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("fd00::1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("fdff:ffff::1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("fe80::1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("febf::1"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("FE80::ABCD"))
        assertTrue(CleartextGate.isLoopbackOrPrivateIp("[fe80::1]:8080"))
    }

    @Test
    fun `ipv6 global and out-of-range ula link-local are not private`() {
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("fbff::1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("fe00::1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("fe7f::1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("fec0::1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("2001:db8::1"))
        assertFalse(CleartextGate.isLoopbackOrPrivateIp("::ffff:192.168.0.1"))
    }

    @Test
    fun `decide allows ipv6 ula and link-local http`() {
        assertTrue(CleartextGate.decide("http", "fc00::1", emptySet()))
        assertTrue(CleartextGate.decide("http", "fe80::1", emptySet()))
        assertTrue(CleartextGate.decide("http", "[fe80::1]:8080", emptySet()))
        assertFalse(CleartextGate.decide("http", "2001:db8::1", emptySet()))
    }

    // ---------------- extractHttpHost ----------------

    @Test
    fun `extractHttpHost returns host for http urls only`() {
        assertEquals("sub.example.com", CleartextGate.extractHttpHost("http://sub.example.com:8080/api"))
        assertEquals("example.com", CleartextGate.extractHttpHost("http://EXAMPLE.com/a"))
        assertEquals("127.0.0.1", CleartextGate.extractHttpHost("http://127.0.0.1:19978/xxx.php"))
        assertEquals("host.example.com", CleartextGate.extractHttpHost("http://user:pass@host.example.com:81/x"))
        assertEquals("::1", CleartextGate.extractHttpHost("http://[::1]:8080/play"))
        assertEquals(null, CleartextGate.extractHttpHost("https://secure.example.com/x"))
        assertEquals(null, CleartextGate.extractHttpHost("not a url"))
        assertEquals(null, CleartextGate.extractHttpHost(""))
        assertEquals(null, CleartextGate.extractHttpHost("http://"))
    }

    // ---------------- extractHttpHostsFromJson (P1-6) ----------------

    @Test
    fun `structured extraction only takes whitelisted fields, ignores description urls`() {
        val text = """
            [
              {"name": "测试源", "description": "备用地址 http://attacker.example.com/api", "url": "http://real.example.com/api", "note": "见 http://note.example.com/x"},
              {"name": "二号", "url": "https://secure.example.com/api", "parentUrl": "http://parent.example.com/sub"}
            ]
        """.trimIndent()
        assertEquals(
            setOf("real.example.com", "parent.example.com"),
            CleartextGate.extractHttpHostsFromJson(text, setOf("url", "parentUrl")),
        )
    }

    @Test
    fun `structured extraction reads nested fields and skips non-whitelisted keys`() {
        val text = """
            {"caches": [{"subUrl": "http://sub.example.com/list.json",
              "sites": [{"api": "http://cms.example.com/api.php", "note": "http://note.example.com/",
                         "ext": "http://ext.example.com/rule.js"}],
              "parses": [{"url": "http://parse.example.com/jx/?url="}],
              "spiderJar": "https://jar.example.com/spider.jar"}]}
        """.trimIndent()
        assertEquals(
            setOf("sub.example.com", "cms.example.com", "parse.example.com", "ext.example.com"),
            CleartextGate.extractHttpHostsFromJson(
                text,
                setOf("subUrl", "baseUrl", "api", "origApi", "playUrl", "url", "spiderJar", "ext", "origExt"),
            ),
        )
    }

    @Test
    fun `structured extraction takes ext http url, skips inline js and relative ext`() {
        val text = """
            {"sites": [{"api": "http://cms.example.com/api.php",
                         "ext": "http://rule.example.com/x.js",
                         "origExt": "http://orig.example.com/y.js"}]}
        """.trimIndent()
        assertEquals(
            setOf("cms.example.com", "rule.example.com", "orig.example.com"),
            CleartextGate.extractHttpHostsFromJson(
                text,
                setOf("subUrl", "baseUrl", "api", "origApi", "playUrl", "url", "spiderJar", "ext", "origExt"),
            ),
        )
        val inline = """
            {"sites": [{"api": "http://cms.example.com/api.php",
                         "ext": "var rule = {title: 'x'}",
                         "origExt": "./js/xxx.js"}]}
        """.trimIndent()
        assertEquals(
            setOf("cms.example.com"),
            CleartextGate.extractHttpHostsFromJson(
                inline,
                setOf("subUrl", "baseUrl", "api", "origApi", "playUrl", "url", "spiderJar", "ext", "origExt"),
            ),
        )
    }

    @Test
    fun `structured extraction covers live channels and emby servers, ignores remarks`() {
        val channels = """
            [{"name": "CCTV1", "url": "http://live.example.com/cctv1.m3u8",
              "logo": "http://img.example.com/logo.png", "epgUrl": "http://epg.example.com/e.xml",
              "group": "央视 http://group.example.com/"}]
        """.trimIndent()
        assertEquals(
            setOf("live.example.com", "img.example.com", "epg.example.com"),
            CleartextGate.extractHttpHostsFromJson(
                channels,
                setOf("url", "logo", "epgUrl", "playlistEpgUrl"),
            ),
        )
        val servers = """
            [{"name": "家里群晖", "baseUrl": "http://192.168.1.10:8096",
              "remark": "备用 http://remark.example.com/"}]
        """.trimIndent()
        assertEquals(
            setOf("192.168.1.10"),
            CleartextGate.extractHttpHostsFromJson(servers, setOf("baseUrl")),
        )
    }

    @Test
    fun `structured extraction returns null on malformed json, empty on valid json without http`() {
        assertEquals(null, CleartextGate.extractHttpHostsFromJson("{not json", setOf("url")))
        assertEquals(null, CleartextGate.extractHttpHostsFromJson("", setOf("url")))
        assertEquals(null, CleartextGate.extractHttpHostsFromJson("no urls here", setOf("url")))
        assertEquals(
            emptySet<String>(),
            CleartextGate.extractHttpHostsFromJson(
                """[{"url": "https://only-secure.example.com/x"}]""",
                setOf("url"),
            ),
        )
    }

    @Test
    fun `structured extraction handles ipv6 loopback url`() {
        assertEquals(
            setOf("::1"),
            CleartextGate.extractHttpHostsFromJson(
                """[{"url": "http://[::1]:8080/play"}]""",
                setOf("url"),
            ),
        )
    }

    @Test
    fun `structured extraction does not descend into description note remark or ext objects`() {
        val text = """
            {"name": "测试源",
             "description": {"note": {"url": "http://wrong1.example.com/api"}},
             "note": {"url": "http://wrong2.example.com/api"},
             "remark": {"url": "http://wrong3.example.com/api"},
             "ext": {"url": "http://wrong4.example.com/rule.js"},
             "url": "http://real.example.com/api"}
        """.trimIndent()
        assertEquals(
            setOf("real.example.com"),
            CleartextGate.extractHttpHostsFromJson(
                text,
                setOf("subUrl", "baseUrl", "api", "origApi", "playUrl", "url", "spiderJar", "ext", "origExt"),
            ),
        )
        // ext 对象在不含 ext 的字段集里同样不钻
        assertEquals(
            setOf("real.example.com"),
            CleartextGate.extractHttpHostsFromJson(text, setOf("url")),
        )
    }

    @Test
    fun `structured extraction still reads urls inside legit containers`() {
        val text = """
            {"data": {"items": [{"url": "http://item.example.com/x"},
                                 {"url": "http://item2.example.com/y"}]},
             "caches": [{"sites": [{"api": "http://cms.example.com/api.php"}]}]}
        """.trimIndent()
        assertEquals(
            setOf("item.example.com", "item2.example.com", "cms.example.com"),
            CleartextGate.extractHttpHostsFromJson(
                text,
                setOf("subUrl", "baseUrl", "api", "origApi", "playUrl", "url", "spiderJar", "ext", "origExt"),
            ),
        )
    }

    // ---------------- capHosts (limit) ----------------

    @Test
    fun `capHosts caps at limit and keeps first seen`() {
        val hosts = (0 until 250).map { "host$it.example.com" }
        val out = CleartextGate.capHosts(hosts)
        assertEquals(200, out.size)
        assertTrue(out.contains("host0.example.com"))
        assertTrue(out.contains("host199.example.com"))
        assertFalse(out.contains("host200.example.com"))
    }

    @Test
    fun `capHosts dedups`() {
        val out = CleartextGate.capHosts(listOf("a.com", "b.com", "a.com"))
        assertEquals(setOf("a.com", "b.com"), out)
    }

    // ---------------- networkInterceptor (no real network) ----------------

    /**
     * 伪造的 Chain：不发真实请求，只让拦截器对给定的 URL 做判定。
     * MockWebServer 测不出拦截（回环恒放行），所以直接测拦截器本身：
     * 每调一次就是 OkHttp 重定向链上的一跳，跳到新 host 同样被拦。
     */
    private class FakeChain(private val url: String) : Interceptor.Chain {
        override fun request(): Request = Request.Builder().url(url).build()
        override fun proceed(request: Request): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").build()
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException("not used in test")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun `networkInterceptor blocks unauthorized http and lets authorized through`() {
        val host = "gate-test-a.example.com"
        try {
            val blocked = assertThrows(CleartextBlockedException::class.java) {
                CleartextGate.networkInterceptor.intercept(FakeChain("http://$host/x"))
            }
            assertEquals(host, blocked.host)
            CleartextAuth.authorize(host)
            val ok = CleartextGate.networkInterceptor.intercept(FakeChain("http://$host/x"))
            assertEquals(200, ok.code)
        } finally {
            CleartextAuth.revoke(host)
        }
    }

    @Test
    fun `networkInterceptor blocks cross-domain redirect hop`() {
        val allowedHost = "gate-test-allowed.example.com"
        val evilHost = "gate-test-evil.example.com"
        try {
            CleartextAuth.authorize(allowedHost)
            // 第一跳：已授权 host，放行。
            val first = CleartextGate.networkInterceptor.intercept(FakeChain("http://$allowedHost/x"))
            assertEquals(200, first.code)
            // 第二跳：重定向到未授权 host，拦截器逐跳重判，必须拦。
            val blocked = assertThrows(CleartextBlockedException::class.java) {
                CleartextGate.networkInterceptor.intercept(FakeChain("http://$evilHost/y"))
            }
            assertEquals(evilHost, blocked.host)
            // https 跳不拦（scheme 不是 http）。
            val https = CleartextGate.networkInterceptor.intercept(FakeChain("https://$evilHost/y"))
            assertEquals(200, https.code)
        } finally {
            CleartextAuth.revoke(allowedHost)
        }
    }

    @Test
    fun `revoke re-blocks subdomain`() {
        val parent = "user-revoke.example.com"
        try {
            CleartextAuth.authorize(parent)
            val ok = CleartextGate.networkInterceptor.intercept(
                FakeChain("http://cdn.$parent/x"),
            )
            assertEquals(200, ok.code)
            CleartextAuth.revoke(parent)
            assertThrows(CleartextBlockedException::class.java) {
                CleartextGate.networkInterceptor.intercept(FakeChain("http://cdn.$parent/x"))
            }
        } finally {
            CleartextAuth.revoke(parent)
        }
    }

    // ---------------- exception ----------------

    @Test
    fun `blocked exception carries host and message`() {
        val e = CleartextBlockedException("example.com")
        assertEquals("example.com", e.host)
        assertEquals("明文 HTTP 未授权：example.com", e.message)
    }
}
