package com.movieshell.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** v1.4.8 A3：EPG 匹配分层（归一化精确 + 单向包含，第三层已删）与地址解析。 */
class EpgFetcherTest {

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))

    /** 每台一个当前节目 + 一个下一档；display-name 列表按条目逐个比。 */
    private fun xmlFor(channels: List<Triple<String, List<String>, String>>): String {
        val now = System.currentTimeMillis()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
            for ((id, names, _) in channels) {
                append("<channel id=\"").append(id).append("\">")
                for (n in names) append("<display-name>").append(n).append("</display-name>")
                append("</channel>")
            }
            for ((id, _, title) in channels) {
                append("<programme start=\"").append(fmt(now - 30 * 60 * 1000))
                    .append("\" stop=\"").append(fmt(now + 30 * 60 * 1000))
                    .append("\" channel=\"").append(id).append("\"><title>")
                    .append(title).append("</title></programme>")
                append("<programme start=\"").append(fmt(now + 30 * 60 * 1000))
                    .append("\" stop=\"").append(fmt(now + 90 * 60 * 1000))
                    .append("\" channel=\"").append(id).append("\"><title>")
                    .append(title).append("-下一档</title></programme>")
            }
            append("</tv>")
        }
    }

    @Test
    fun normalizeEqual() {
        assertTrue(EpgFetcher.epgNameMatches("湖南卫视 HD", "湖南卫视"))
        assertTrue(EpgFetcher.epgNameMatches("湖南卫视", "湖南卫视高清"))
        assertTrue(EpgFetcher.epgNameMatches("湖南卫视超清", "湖南卫视"))
        assertTrue(EpgFetcher.epgNameMatches("CCTV-1", "cctv1"))
        assertTrue(EpgFetcher.epgNameMatches("CCTV-1 高清", "CCTV-1"))
        assertEquals("cctv1", EpgFetcher.normalizeChannelName("CCTV-1"))
        assertEquals("湖南卫视", EpgFetcher.normalizeChannelName("湖南卫视 HD"))
    }

    @Test
    fun cctvValidationTable() {
        val cases = listOf(
            Triple("CCTV", "CCTV-1", true),
            Triple("CCTV", "CCTV-5", true),
            Triple("CCTV", "CCTV-新闻", false),
            Triple("CCTV", "CCTV-体育", false),
            Triple("CCTV", "湖南卫视", false),
            Triple("卫视", "CCTV-1", false),
            Triple("卫视", "CCTV-5", false),
            Triple("卫视", "CCTV-新闻", false),
            Triple("卫视", "CCTV-体育", false),
            Triple("卫视", "湖南卫视", false),
        )
        for ((epg, channel, expected) in cases) {
            assertEquals(
                "EPG=$epg 频道=$channel",
                expected,
                EpgFetcher.epgNameMatches(epg, channel),
            )
        }
    }

    @Test
    fun namingDifferenceReturnsNull() {
        val xml = xmlFor(listOf(Triple("cctv1", listOf("央视一套"), "新闻联播")))
        assertEquals(null to null, EpgFetcher.parse(xml, "CCTV-1"))
        val xml2 = xmlFor(listOf(Triple("zj", listOf("浙江卫视"), "中国好声音")))
        assertEquals(null to null, EpgFetcher.parse(xml2, "湖南卫视"))
    }

    @Test
    fun multiDisplayNameMatchedPerName() {
        // 拼串旧口径下 "CCTV 新闻" 会整体含 "CCTV-新闻" 而误命中；
        // 逐个比后 "CCTV" 与 "新闻综合" 单独都不命中，返回 null。
        val xml = xmlFor(listOf(Triple("c1", listOf("CCTV", "新闻综合"), "新闻联播")))
        assertEquals(null to null, EpgFetcher.parse(xml, "CCTV-新闻"))
        // 任一名字命中即该 channel id 命中。
        val xml2 = xmlFor(listOf(Triple("c1", listOf("CCTV-1", "CCTV1 综合"), "新闻联播")))
        assertEquals("新闻联播", EpgFetcher.parse(xml2, "CCTV-1").first?.title)
        assertEquals("新闻联播-下一档", EpgFetcher.parse(xml2, "CCTV-1").second?.title)
    }

    @Test
    fun multiChannelMultiDisplayName() {
        val xml = xmlFor(
            listOf(
                Triple("cctv1", listOf("CCTV-1", "CCTV 1"), "新闻联播"),
                Triple("hunan", listOf("湖南卫视", "Hunan TV"), "快乐大本营"),
            ),
        )
        val cctv = EpgFetcher.parse(xml, "CCTV-1")
        assertEquals("新闻联播", cctv.first?.title)
        assertEquals("新闻联播-下一档", cctv.second?.title)
        val hunan = EpgFetcher.parse(xml, "湖南卫视")
        assertEquals("快乐大本营", hunan.first?.title)
        assertEquals("快乐大本营-下一档", hunan.second?.title)
        // 英文 display-name 只命中自己的台。
        assertEquals("快乐大本营", EpgFetcher.parse(xml, "Hunan TV").first?.title)
        assertEquals(null to null, EpgFetcher.parse(xml, "CCTV-2"))
    }

    @Test
    fun noCrossSourceMergeAtParseLevel() {
        // 同一 channel id 在两个源里各有节目：每次 parse 只取当次 XML 的结果，不合并。
        val xmlA = xmlFor(listOf(Triple("cctv1", listOf("CCTV-1"), "源A节目")))
        val xmlB = xmlFor(listOf(Triple("cctv1", listOf("CCTV-1"), "源B节目")))
        assertEquals("源A节目", EpgFetcher.parse(xmlA, "CCTV-1").first?.title)
        assertEquals("源B节目", EpgFetcher.parse(xmlB, "CCTV-1").first?.title)
    }

    // v1.4.11：resolveEpgUrl 已删，地址选择收进 nowAndNextWithFallback，
    // 旧口径“本源已配但失败时不回落全局”随之作废，见下方回落分层用例。

    @Before
    fun setUp() {
        EpgFetcher.resetCircuitBreakerForTest()
    }

    @Test
    fun emptyChannelNameReturnsNull() {
        val xml = xmlFor(listOf(Triple("cctv1", listOf("CCTV-1"), "新闻联播")))
        assertEquals(null to null, EpgFetcher.parse(xml, ""))
        assertEquals(null to null, EpgFetcher.parse(xml, "   "))
        assertFalse(EpgFetcher.epgNameMatches("", "CCTV-1"))
        assertFalse(EpgFetcher.epgNameMatches("CCTV", ""))
    }

    // v1.4.11 第 1 步：熔断——按 URL 记连续下载失败，5 次后 10 分钟内跳过网络。
    // v1.4.34 B-P1-11：3 次/30 分钟过激，放宽到 5 次/10 分钟。

    @Test
    fun breakerBlocksAfterFiveConsecutiveFailures() {
        val url = "https://dead.example.com/epg.xml"
        val t = 1_000_000L
        repeat(4) { EpgFetcher.recordDownloadFailure(url, t) }
        assertFalse(EpgFetcher.isBlocked(url, t))
        EpgFetcher.recordDownloadFailure(url, t)
        assertTrue(EpgFetcher.isBlocked(url, t))
        // 别的 URL 不受影响。
        assertFalse(EpgFetcher.isBlocked("https://other.example.com/epg.xml", t))
    }

    @Test
    fun breakerSuccessResetsCount() {
        val url = "https://flaky.example.com/epg.xml"
        val t = 1_000_000L
        repeat(4) { EpgFetcher.recordDownloadFailure(url, t) }
        EpgFetcher.recordDownloadSuccess(url)
        repeat(4) { EpgFetcher.recordDownloadFailure(url, t) }
        assertFalse(EpgFetcher.isBlocked(url, t))
        EpgFetcher.recordDownloadFailure(url, t)
        assertTrue(EpgFetcher.isBlocked(url, t))
    }

    @Test
    fun breakerExpiresAfterTenMinutes() {
        val url = "https://dead.example.com/epg.xml"
        val t = 1_000_000L
        repeat(5) { EpgFetcher.recordDownloadFailure(url, t) }
        assertTrue(EpgFetcher.isBlocked(url, t + 9 * 60 * 1000L))
        assertFalse(EpgFetcher.isBlocked(url, t + 10 * 60 * 1000L))
        // 过期后下一次失败重新从 1 开始计，不立即再封。
        EpgFetcher.recordDownloadFailure(url, t + 10 * 60 * 1000L)
        assertFalse(EpgFetcher.isBlocked(url, t + 10 * 60 * 1000L))
    }

    // v1.4.34 B-P1-11：永久错权重 2（3 次 404 即熔断），失败计数 1 小时衰减，删源清理。

    @Test
    fun breakerPermanentFailuresWeighDouble() {
        val url = "https://gone.example.com/epg.xml"
        val t = 1_000_000L
        EpgFetcher.recordDownloadFailure(url, t, permanent = true)
        EpgFetcher.recordDownloadFailure(url, t, permanent = true)
        assertFalse(EpgFetcher.isBlocked(url, t))
        EpgFetcher.recordDownloadFailure(url, t, permanent = true)
        assertTrue(EpgFetcher.isBlocked(url, t))
    }

    @Test
    fun breakerFailureCountDecaysAfterOneHour() {
        val url = "https://flaky2.example.com/epg.xml"
        val t = 1_000_000L
        val hour = 60 * 60 * 1000L
        repeat(4) { EpgFetcher.recordDownloadFailure(url, t) }
        assertFalse(EpgFetcher.isBlocked(url, t))
        // 距上次失败超 1 小时，计数归零：再连 fail 4 次仍不封。
        repeat(4) { EpgFetcher.recordDownloadFailure(url, t + hour + 1000L) }
        assertFalse(EpgFetcher.isBlocked(url, t + hour + 1000L))
        EpgFetcher.recordDownloadFailure(url, t + hour + 1000L)
        assertTrue(EpgFetcher.isBlocked(url, t + hour + 1000L))
    }

    @Test
    fun purgeUrlsClearsBreakerResidue() {
        val url = "https://dead2.example.com/epg.xml"
        val t = 1_000_000L
        repeat(5) { EpgFetcher.recordDownloadFailure(url, t) }
        assertTrue(EpgFetcher.isBlocked(url, t))
        EpgFetcher.purgeUrls(listOf(url))
        assertFalse(EpgFetcher.isBlocked(url, t))
        // 清掉后失败计数也归零：再 fail 4 次仍不封。
        repeat(4) { EpgFetcher.recordDownloadFailure(url, t) }
        assertFalse(EpgFetcher.isBlocked(url, t))
    }

    // v1.4.11 第 1 步：回落分层——本源命中不碰全局，本源空结果才试全局。

    private val nowProgram = EpgFetcher.Program("新闻联播", 1_000L, 2_000L)
    private val nextProgram = EpgFetcher.Program("天气预报", 2_000L, 3_000L)
    private val channelUrl = "https://a.example.com/epg.xml"
    private val globalUrl = "https://g.example.com/epg.xml"

    @Test
    fun fallbackChannelHitDoesNotTouchGlobal() = runBlocking {
        val calls = mutableListOf<String>()
        val result = EpgFetcher.nowAndNextWithFallback(channelUrl, globalUrl, "CCTV-1") { url, _ ->
            calls += url
            nowProgram to nextProgram
        }
        assertEquals(EpgFetcher.EpgLayer.CHANNEL, result.layer)
        assertEquals("新闻联播", result.programs.first?.title)
        assertEquals(listOf(channelUrl), calls)
    }

    @Test
    fun fallbackChannelMissTriesGlobal() = runBlocking {
        val calls = mutableListOf<String>()
        val result = EpgFetcher.nowAndNextWithFallback(channelUrl, globalUrl, "CCTV-1") { url, _ ->
            calls += url
            if (url == channelUrl) null to null else nowProgram to nextProgram
        }
        assertEquals(EpgFetcher.EpgLayer.GLOBAL, result.layer)
        assertEquals("新闻联播", result.programs.first?.title)
        assertEquals(listOf(channelUrl, globalUrl), calls)
    }

    @Test
    fun fallbackBothMissReturnsNone() = runBlocking {
        val calls = mutableListOf<String>()
        val result = EpgFetcher.nowAndNextWithFallback(channelUrl, globalUrl, "CCTV-1") { url, _ ->
            calls += url
            null to null
        }
        assertEquals(EpgFetcher.EpgLayer.NONE, result.layer)
        assertEquals(null to null, result.programs)
        assertEquals(listOf(channelUrl, globalUrl), calls)
    }

    @Test
    fun fallbackBlankChannelTriesGlobalDirectly() = runBlocking {
        // 空串、空白、null 三种“未配”都直接试全局（旧 resolveEpgUrl 单测锁过的口径）。
        for (blank in listOf("", "   ", null)) {
            val calls = mutableListOf<String>()
            val result = EpgFetcher.nowAndNextWithFallback(blank, globalUrl, "CCTV-1") { url, _ ->
                calls += url
                nowProgram to nextProgram
            }
            assertEquals("本源=$blank", EpgFetcher.EpgLayer.GLOBAL, result.layer)
            assertEquals(listOf(globalUrl), calls)
        }
    }

    @Test
    fun fallbackSameUrlFetchedOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val result = EpgFetcher.nowAndNextWithFallback(channelUrl, channelUrl, "CCTV-1") { url, _ ->
            calls += url
            nowProgram to nextProgram
        }
        assertEquals(EpgFetcher.EpgLayer.CHANNEL, result.layer)
        assertEquals(listOf(channelUrl), calls)
    }

    @Test
    fun fallbackChannelOnlyNowCountsAsHit() = runBlocking {
        // 只有当前节目、没有下一档，也算本源命中，不再试全局。
        val calls = mutableListOf<String>()
        val result = EpgFetcher.nowAndNextWithFallback(channelUrl, globalUrl, "CCTV-1") { url, _ ->
            calls += url
            nowProgram to null
        }
        assertEquals(EpgFetcher.EpgLayer.CHANNEL, result.layer)
        assertEquals(listOf(channelUrl), calls)
    }

    // v1.4.13 第 3 步：逐频道模板 {name}/{date}。

    @Test
    fun expandTemplateNoPlaceholderReturnsSame() {
        val url = "https://a.example.com/epg.xml"
        assertEquals(url, EpgFetcher.expandTemplate(url, "CCTV-1"))
        assertFalse(EpgFetcher.isTemplate(url))
    }

    @Test
    fun expandTemplateEncodesChannelName() {
        val out = EpgFetcher.expandTemplate("https://x/epg?ch={name}", "湖南卫视")
        assertEquals("https://x/epg?ch=%E6%B9%96%E5%8D%97%E5%8D%AB%E8%A7%86", out)
        assertTrue(EpgFetcher.isTemplate("https://x/epg?ch={name}"))
    }

    @Test
    fun expandTemplateSpaceBecomesPercent20() {
        // 审稿 S1：台名带空格时，路径位与 query 位都得是 %20，不能是 +。
        val out = EpgFetcher.expandTemplate("https://x/{name}.xml?ch={name}", "CCTV-1 综合")
        assertEquals("https://x/CCTV-1%20%E7%BB%BC%E5%90%88.xml?ch=CCTV-1%20%E7%BB%BC%E5%90%88", out)
    }

    @Test
    fun expandTemplateDateIsLocalDay() {
        // 固定一个本地时刻：2026-11-22 08:00（设备时区），{date} 必须是当天。
        val ms = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.parse("2026-11-22 08:00")!!.time
        val out = EpgFetcher.expandTemplate("https://x/epg/{date}/{name}.xml", "CCTV-1", ms)
        assertEquals("https://x/epg/2026-11-22/CCTV-1.xml", out)
    }

    @Test
    fun expandTemplateIsIdempotent() {
        val once = EpgFetcher.expandTemplate("https://x/{name}/{date}", "CCTV-1", 1_000_000L)
        assertEquals(once, EpgFetcher.expandTemplate(once, "CCTV-1", 1_000_000L))
    }

    @Test
    fun fallbackExpandsTemplateBeforeFetch() = runBlocking {
        val calls = mutableListOf<String>()
        val tpl = "https://x/epg?ch={name}&d={date}"
        val result = EpgFetcher.nowAndNextWithFallback(tpl, globalUrl, "湖南卫视") { url, _ ->
            calls += url
            nowProgram to nextProgram
        }
        assertEquals(EpgFetcher.EpgLayer.CHANNEL, result.layer)
        assertEquals(1, calls.size)
        assertTrue(calls[0].startsWith("https://x/epg?ch=%E6%B9%96%E5%8D%97%E5%8D%AB%E8%A7%86&d="))
        assertFalse(calls[0].contains("{name}") || calls[0].contains("{date}"))
    }

    @Test
    fun fallbackSameTemplateFetchedOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val tpl = "https://x/epg?ch={name}&d={date}"
        val result = EpgFetcher.nowAndNextWithFallback(tpl, tpl, "CCTV-1") { url, _ ->
            calls += url
            nowProgram to nextProgram
        }
        assertEquals(EpgFetcher.EpgLayer.CHANNEL, result.layer)
        assertEquals(1, calls.size)
    }
}
