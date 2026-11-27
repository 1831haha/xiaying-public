package com.movieshell.data.source

import com.movieshell.data.source.CmsApi
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.DEFAULT_UA_SHORT
import com.movieshell.core.net.ProxyRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * XMLTV 节目单尽力解析（v0.24.0）。
 *
 * 输入 epgUrl + 频道名：按分层规则匹配 display-name（归一化精确、
 * 单向包含），取其 id 下的 <programme>，算出当前节目与下一个节目。
 * 任何一步失败返回 (null, null)，不崩。
 */
object EpgFetcher {

    private const val TAG = "EpgFetcher"

    /** v1.4.34 B-P1-11：带状态码的 HTTP 失败，供熔断按错误类型区分权重。 */
    internal class EpgHttpException(val code: Int) : IllegalStateException("HTTP $code")

    data class Program(
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        val desc: String = "",
    )

    /** v1.4.11 第 1 步：节目单命中层——本源 / 全局兜底 / 都没命中。 */
    enum class EpgLayer { CHANNEL, GLOBAL, NONE }

    /** v1.4.11 第 1 步：节目单 + 命中层。 */
    data class EpgResult(
        val programs: Pair<Program?, Program?>,
        val layer: EpgLayer,
    )

    /**
     * v1.4.13 第 3 步：逐频道模板占位符——{name} 频道名、{date} 当天日期。
     * 不含占位符仍走全量 XMLTV（老地址不用改）。
     */
    const val TEMPLATE_NAME = "{name}"
    const val TEMPLATE_DATE = "{date}"

    /** 是否含模板占位符（{name}/{date} 任一）。 */
    fun isTemplate(url: String): Boolean =
        url.contains(TEMPLATE_NAME) || url.contains(TEMPLATE_DATE)

    /**
     * 展开模板：{name}→URL 编码后的频道名，{date}→当天 yyyy-MM-dd（设备本地日期）。
     * 无占位符原样返回；已展开过的再展开是无操作（幂等）。
     * dateMs 是测试缝：单测注入固定日期验 {date}。
     */
    fun expandTemplate(
        url: String,
        channelName: String,
        dateMs: Long = System.currentTimeMillis(),
    ): String {
        if (!isTemplate(url)) return url
        var out = url
        if (out.contains(TEMPLATE_NAME)) {
            // v1.4.13 审稿 S1：URLEncoder 把空格编成 +，query 位合法、路径位
            // 会走样（台名带空格时），统一换成 %20，两边都对。
            out = out.replace(
                TEMPLATE_NAME,
                URLEncoder.encode(channelName.trim(), "UTF-8").replace("+", "%20"),
            )
        }
        if (out.contains(TEMPLATE_DATE)) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date(dateMs))
            out = out.replace(TEMPLATE_DATE, day)
        }
        return out
    }

    /** v0.188.0 M29：EPG 单文件下载上限 20MB，超限直接失败，不全量进内存。 */
    private const val MAX_EPG_BYTES = 20L * 1024L * 1024L

    /**
     * v0.188.0 M29：磁盘缓存目录（LiveStore.init 时注入 filesDir 下的
     * epg_cache；未注入时不缓存、只走网络，与旧行为一致）。
     * 文件名按 URL + 日期：epg_<url 哈希>_<yyyyMMdd>.xml，当天内重复查
     * 同一 EPG 直接读盘，不重复下载；写新缓存时顺手清掉同 URL 的旧日期文件。
     */
    @Volatile
    private var cacheDir: File? = null

    /** 仅 LiveStore.init 调用（同包），不要在别处乱设。 */
    internal fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    /** v1.4.11 熔断：连续 5 次下载失败后，10 分钟内跳过该 URL 的网络请求。
     *  v1.4.34 B-P1-11：3 次/30 分钟过激——EPG 只是节目单 enrichment，
     *  源端抖动半小时无节目单得不偿失；放宽到 5 次/10 分钟。
     *  永久错（HTTP 4xx，见 recordDownloadFailure）权重 2，死链更快熔断。 */
    internal const val BREAKER_MAX_FAILURES = 5
    internal const val BREAKER_BLOCK_MS = 10 * 60 * 1000L
    /** 失败计数衰减窗口：距上次失败超 1 小时，计数归零重计（残留不永久累积）。 */
    internal const val BREAKER_FAILURE_DECAY_MS = 60 * 60 * 1000L
    internal const val PERMANENT_FAILURE_WEIGHT = 2
    private val breakerLock = Any()
    /** URL →（连续失败次数，上次失败时间）。 */
    private val breakerFailures = mutableMapOf<String, Pair<Int, Long>>()
    private val breakerBlockedUntil = mutableMapOf<String, Long>()

    // v0.217.0（F21）：SimpleDateFormat 不是线程安全的，原来是共享单例，
    // 直播页与频道详情并发查节目单时会互相干扰、个别节目时间算错或被
    // 静默过滤（外层 runCatching 把错吞成 0L）；每次新建解析器，代价可忽略。
    private fun parseTime(s: String): Long =
        runCatching {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(s.trim())?.time ?: 0L
        }.getOrDefault(0L)

    private fun cacheFileFor(epgUrl: String): File? {
        val dir = cacheDir ?: return null
        val hash = (epgUrl.hashCode().toLong() and 0xffffffffL).toString(16)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(System.currentTimeMillis())
        return File(dir, "epg_${hash}_$day.xml")
    }

    private fun readCache(epgUrl: String): ByteArray? {
        val f = cacheFileFor(epgUrl) ?: return null
        return runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()
    }

    private fun writeCache(epgUrl: String, bytes: ByteArray) {
        val f = cacheFileFor(epgUrl) ?: return
        runCatching {
            val dir = f.parentFile ?: return
            dir.mkdirs()
            // 同 URL 的旧日期缓存清掉，目录不随天数无限长。
            val prefix = "epg_${(epgUrl.hashCode().toLong() and 0xffffffffL).toString(16)}_"
            dir.listFiles { it -> it.name.startsWith(prefix) && it != f }
                .orEmpty().forEach { runCatching { it.delete() } }
            // 二进制（可能含 gzip）不能走 AtomicFile.writeText 的字符串路径：
            // 同目录临时文件 + 改名，写一半被杀也只留无害 tmp，不污染正式缓存。
            val tmp = File(dir, "${f.name}.${UUID.randomUUID()}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    suspend fun nowAndNext(epgUrl: String, channelName: String): Pair<Program?, Program?> =
        withContext(Dispatchers.IO) {
            runCatching {
                // v1.4.13 第 3 步：模板先展开（{name}/{date}），缓存与熔断都按
                // 展开后的地址算——同一模板按频道+日期各存一份缓存。
                val url = expandTemplate(epgUrl, channelName)
                // 当天缓存命中直接解析，不发网络请求。
                val bytes = fetchBytes(url) ?: return@runCatching null to null
                // v0.158.0：.xml.gz 这类压缩包尽力解压（gzip 魔数 1f 8b），
                // 普通 XML 直接按 UTF-8 解；解不开返回 (null, null)，不崩。
                val xml = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    runCatching {
                        java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@runCatching null to null
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
                parse(xml, channelName.trim())
                // v1.4.11 审稿 M1：取消必须透传——runCatching 会吞
                // CancellationException，切台取消在途拉取时，旧 effect 须直接
                // 退出、不把过期状态写回；超时仍按原样返回 (null, null)。
            }.getOrElse { if (it is CancellationException) throw it else null to null }
        }

    /**
     * v1.4.11 第 1 步：本源失效自动回落全局。
     * 本源地址非空先试本源：下载失败或匹配不到（两档都没有）都算本源失效，
     * 再试全局；全局也无结果返回 (null, null)。本源未配直接试全局。
     * 返回命中层，供调用方只在兜底命中时打“全局”小标。
     * fetch 参数是测试缝：单测注入假拉取、验分层选择；线上默认走 nowAndNext。
     */
    suspend fun nowAndNextWithFallback(
        channelEpgUrl: String?,
        globalEpgUrl: String,
        channelName: String,
        fetch: suspend (String, String) -> Pair<Program?, Program?> = { url, name -> nowAndNext(url, name) },
    ): EpgResult {
        // v1.4.13 第 3 步：模板先展开再拉取——fetch 缝拿到的是最终地址，
        // 单测可直接断言展开结果；展开后相同的两条只拉一次。
        val channelUrl = channelEpgUrl?.takeIf { it.isNotBlank() }
            ?.let { expandTemplate(it, channelName) }
        if (channelUrl != null) {
            val r = fetch(channelUrl, channelName)
            if (r.first != null || r.second != null) return EpgResult(r, EpgLayer.CHANNEL)
        }
        val globalUrl = globalEpgUrl.takeIf { it.isNotBlank() }
            ?.let { expandTemplate(it, channelName) }
            ?.takeIf { it != channelUrl }
        if (globalUrl != null) {
            val r = fetch(globalUrl, channelName)
            if (r.first != null || r.second != null) return EpgResult(r, EpgLayer.GLOBAL)
        }
        return EpgResult(null to null, EpgLayer.NONE)
    }

    /**
     * v1.4.11 第 1 步：取 EPG 字节——先读当日缓存，再看熔断，最后才联网。
     * 熔断时跳过网络、只用缓存（无缓存即返回 null）；下载失败记一次，
     * 成功清零。取消异常原样重抛，不记失败。
     */
    private suspend fun fetchBytes(epgUrl: String): ByteArray? {
        readCache(epgUrl)?.let { return it }
        if (isBlocked(epgUrl)) {
            LogBus.d(TAG, "EPG 熔断跳过网络 host=${epgHost(epgUrl)}")
            return null
        }
        return try {
            withTimeoutOrNull(10_000) { downloadBytes(epgUrl) }?.also {
                recordDownloadSuccess(epgUrl)
                writeCache(epgUrl, it)
            } ?: run {
                recordDownloadFailure(epgUrl)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpgHttpException) {
            // v1.4.34 B-P1-11：4xx（除 408/429）是永久错，权重加倍、死链更快熔断；
            // 5xx/429/超时/IO 异常是瞬时错，按 1 计。
            recordDownloadFailure(
                epgUrl,
                permanent = e.code in 400..499 && e.code != 408 && e.code != 429,
            )
            null
        } catch (e: Exception) {
            recordDownloadFailure(epgUrl)
            null
        }
    }

    /** v1.4.11 熔断：只记 host，不打完整 URL。 */
    private fun epgHost(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

    /**
     * v1.4.11 熔断：按 EPG URL 记连续下载失败，5 次后 10 分钟内跳过网络。
     * 只算下载失败（超时/HTTP 错/异常），不算“下载成功但没匹配到台名”——
     * 后者可能是这台名不在该 EPG 里，不能因此封掉整条地址；且下载成功已写
     * 当日缓存，后续查直接读盘，不重复耗网络。
     * v1.4.34 B-P1-11：按错误类型区分——permanent=true（HTTP 4xx 永久错，
     * 地址本身已死）权重 2；超时/5xx/IO 异常是瞬时错，权重 1。
     * 距上次失败超 1 小时，计数归零重计，旧残留不永久累积。
     */
    internal fun recordDownloadFailure(
        url: String,
        nowMs: Long = System.currentTimeMillis(),
        permanent: Boolean = false,
    ) {
        synchronized(breakerLock) {
            val (prev, lastMs) = breakerFailures[url] ?: (0 to 0L)
            val base = if (lastMs > 0 && nowMs - lastMs > BREAKER_FAILURE_DECAY_MS) 0 else prev
            val n = base + if (permanent) PERMANENT_FAILURE_WEIGHT else 1
            if (n >= BREAKER_MAX_FAILURES) {
                breakerFailures.remove(url)
                breakerBlockedUntil[url] = nowMs + BREAKER_BLOCK_MS
                LogBus.d(TAG, "EPG 熔断 host=${epgHost(url)} 连续失败 $n 次，${BREAKER_BLOCK_MS / 60000} 分钟内跳过网络")
            } else {
                breakerFailures[url] = n to nowMs
            }
        }
    }

    internal fun recordDownloadSuccess(url: String) {
        synchronized(breakerLock) {
            breakerFailures.remove(url)
            breakerBlockedUntil.remove(url)
        }
    }

    internal fun isBlocked(url: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(breakerLock) {
            val until = breakerBlockedUntil[url] ?: return@synchronized false
            if (until <= nowMs) {
                breakerBlockedUntil.remove(url)
                false
            } else {
                true
            }
        }

    /** 单测用：清空熔断状态，测例间互不干扰。 */
    internal fun resetCircuitBreakerForTest() {
        synchronized(breakerLock) {
            breakerFailures.clear()
            breakerBlockedUntil.clear()
        }
    }

    /**
     * v1.4.34 B-P1-11：源删除时清掉该源 EPG 地址的熔断残留——两 Map 原来
     * 只在成功时 remove，删源后条目永久残留。LiveStore.removeSource /
     * removeManual 调用。
     */
    fun purgeUrls(urls: Collection<String>) {
        if (urls.isEmpty()) return
        synchronized(breakerLock) {
            urls.forEach { breakerFailures.remove(it); breakerBlockedUntil.remove(it) }
        }
    }

    /**
     * v1.4.8 A3(b)：频道名归一化——去空白、去 -、去“高清/HD/超清”
     * 后缀、拉丁字母大小写折叠。
     */
    internal fun normalizeChannelName(name: String): String {
        var n = name.replace("\\s".toRegex(), "").replace("-", "").lowercase()
        var changed: Boolean
        do {
            changed = false
            for (suffix in listOf("超清", "高清", "hd")) {
                if (n.length > suffix.length && n.endsWith(suffix)) {
                    n = n.dropLast(suffix.length)
                    changed = true
                }
            }
        } while (changed)
        return n
    }

    /**
     * v1.4.8 A3(b)：匹配分层，只此两层，第三层（双向包含）已删除。
     * 第一层归一化精确相等；第二层单向包含——EPG 名须为频道名的前缀，
     * 且剩余部分仅为数字/分隔符后缀（含至少一位数字）。
     */
    internal fun epgNameMatches(epgName: String, channelName: String): Boolean {
        val e = normalizeChannelName(epgName)
        val c = normalizeChannelName(channelName)
        if (e.isEmpty() || c.isEmpty()) return false
        if (e == c) return true
        if (!c.startsWith(e)) return false
        val rest = c.removePrefix(e)
        return rest.isNotEmpty() && rest.any { it.isDigit() } &&
            rest.all { it.isDigit() || it in "-_./·:" }
    }

    internal fun parse(xml: String, channelName: String): Pair<Program?, Program?> {
        if (channelName.isBlank()) return null to null
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val p = factory.newPullParser().apply { setInput(xml.reader()) }
        // channel id -> display-name 列表：多语言名各一条，逐个比，不拼串
        val names = mutableMapOf<String, MutableList<String>>()
        var curId = ""
        var curText = StringBuilder()
        var inDisplayName = false
        data class Raw(val channel: String, val start: Long, val stop: Long, val title: String, val desc: String)
        val programmes = mutableListOf<Raw>()
        var ev = p.eventType
        var pChannel = ""
        var pStart = 0L
        var pStop = 0L
        var pTitle = StringBuilder()
        var pDesc = StringBuilder()
        var inTitle = false
        var inDesc = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> curId = p.getAttributeValue(null, "id").orEmpty()
                    "display-name" -> { inDisplayName = true; curText = StringBuilder() }
                    "programme" -> {
                        pChannel = p.getAttributeValue(null, "channel").orEmpty()
                        pStart = parseTime(p.getAttributeValue(null, "start").orEmpty())
                        pStop = parseTime(p.getAttributeValue(null, "stop").orEmpty())
                        pTitle = StringBuilder(); pDesc = StringBuilder()
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                }
                XmlPullParser.TEXT -> {
                    val t = p.text
                    if (inDisplayName) curText.append(t)
                    if (inTitle) pTitle.append(t)
                    if (inDesc) pDesc.append(t)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "display-name" -> {
                        if (curId.isNotBlank()) {
                            names.getOrPut(curId) { mutableListOf() }.add(curText.toString())
                        }
                        inDisplayName = false
                    }
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        if (pChannel.isNotBlank() && pStart > 0) {
                            programmes.add(
                                Raw(pChannel, pStart, pStop, pTitle.toString().trim(), pDesc.toString().trim()),
                            )
                        }
                    }
                }
            }
            ev = p.next()
        }
        // 频道匹配：分层规则逐个 display-name 过，任一命中即该 channel id 命中
        val matched = names.filter { (_, list) ->
            list.any { epgNameMatches(it, channelName) }
        }.keys
        if (matched.isEmpty()) return null to null
        val now = System.currentTimeMillis()
        val list = programmes.filter { it.channel in matched && it.stop > now }
            .sortedBy { it.start }
        val cur = list.firstOrNull { now >= it.start && now < it.stop }
            ?: list.firstOrNull { it.start <= now }
        val next = list.firstOrNull { it.start > now && it.title != cur?.title }
            ?: list.firstOrNull { it.start >= (cur?.stop ?: now) && it != cur }
        fun Raw.toProgram() = Program(title, start, stop, desc)
        return cur?.toProgram() to next?.toProgram()
    }

    /**
     * v0.188.0 M29：下载改分块限量读，超 20MB 即抛错，不再 body.bytes()
     * 全量进内存；外层 10 秒超时此前取消不掉阻塞中的同步 execute，
     * 现注册取消即 cancel 底层 call，超时真正起效。
     */
    private suspend fun downloadBytes(url: String): ByteArray {
        // v1.4.8 A3(e)：EPG 拉取记直连/代理（供真机验确认实际路由）。
        // 脱敏：只记 host + 路由 + 结果，不打完整 URL、query 参数、敏感串。
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val viaProxy = host.isNotBlank() && ProxyRouting.proxyAddress() != null &&
            ProxyRouting.shouldProxyHost(host)
        val route = if (viaProxy) "代理" else "直连"
        LogBus.d(TAG, "EPG 拉取 host=$host 路由=$route")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA_SHORT)
            .build()
        val call = CmsApi.defaultClient().newCall(req)
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw EpgHttpException(resp.code)
                LogBus.d(TAG, "EPG 拉取完成 host=$host 路由=$route HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw IllegalStateException("空响应")
                stream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_EPG_BYTES) {
                            throw IllegalStateException("EPG 超过 20MB 上限，已放弃")
                        }
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            }
        } catch (e: CancellationException) {
            LogBus.d(TAG, "EPG 拉取取消 host=$host 路由=$route ${e::class.simpleName}")
            throw e
        } catch (e: Exception) {
            // 取消时抛回 job 自身的取消异常：超时触发的是
            // TimeoutCancellationException，withTimeoutOrNull 认它才返回 null
            // （抛普通 CancellationException 会穿出 withTimeoutOrNull）；
            // 外部取消的则是 JobCancellationException，原样透传。
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            LogBus.d(TAG, "EPG 拉取失败 host=$host 路由=$route ${e::class.simpleName}")
            throw e
        }
    }
}
