package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.core.net.buildSearchRequest
import com.movieshell.core.util.AtomicFile
import com.movieshell.data.source.CmsApi
import com.movieshell.core.net.DEFAULT_UA
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.SourceHealth
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v0.248.0：小说书源轻量连通探测（发现页源列表的状态点）。
 * - 只测“能不能连上站点根地址”，不评质量、不冒充评分；
 * - 结果按书源 key 持久化，发现页进页时只补测未知或超过 24 小时的源；
 * - 失败只记状态不抛异常，探测本身绝不阻塞页面。
 */

/** 单源连通快照：status 为 OK / SLOW / FAIL，无记录即未测；url 用于识别
 * 记录是否仍对应当前源配置（源地址改过后旧记录不再当有效缓存）。 */
@Serializable
data class BookProbeRecord(
    val status: String = "",
    val ms: Long = 0L,
    val time: Long = 0L,
    val url: String = "",
)

/**
 * v1.4.27 解缠②：探测快照 → 统一显示模型（与搜索测试共用色调/文案）。
 * 存储格式（status 字符串）不动，老缓存照读；未知状态与无记录都算未测。
 */
fun BookProbeRecord?.toSourceHealth(): SourceHealth = when (this?.status) {
    "OK" -> SourceHealth.Ok(ms)
    "SLOW" -> SourceHealth.Slow(ms)
    "FAIL" -> SourceHealth.Fail
    else -> SourceHealth.Untested
}

object BookProbeStore {
    private const val TAG = "BookProbeStore"
    private const val FILE_NAME = "book_probe.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), BookProbeRecord.serializer())

    private val _map = MutableStateFlow<Map<String, BookProbeRecord>>(emptyMap())
    val map: StateFlow<Map<String, BookProbeRecord>> = _map

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.isFile) return@withContext
        val m = runCatching {
            json.decodeFromString<Map<String, BookProbeRecord>>(f.readText())
        }.onFailure {
            Log.w(TAG, "读取小说源探测结果失败", it)
            AtomicFile.preserveCorrupt(f)
        }.getOrNull() ?: return@withContext
        _map.value = m
    }

    /** 写盘失败只记日志、不当成功（AtomicFile 写盘失败返回 false 而非抛异常）。 */
    suspend fun update(context: Context, key: String, record: BookProbeRecord) =
        withContext(Dispatchers.IO) {
            _map.value = _map.value + (key to record)
            val ok = AtomicFile.writeText(
                File(context.filesDir, FILE_NAME),
                json.encodeToString(mapSer, _map.value),
            )
            if (!ok) Log.w(TAG, "写入小说源探测结果失败")
        }
}

object BookProbe {
    /** v1.4.27 解缠②：慢阈值与搜索测试共用（SourceHealth.SLOW_MS，值仍是 3000）。 */
    private const val SLOW_MS = SourceHealth.SLOW_MS

    /**
     * v1.4.26：按源探测——根地址只在回 404 时才回落（根地址本身不存在的 API 源，
     * 其 bookSourceUrl 只是一串接口前缀，根 404 不代表源不通）；其他状态码与
     * 异常保持原语义，不回落。回落顺序：发现分类首个 URL → 搜索 URL
     *（{{key}} 换探测词、{{page}} 按现有口径换 1）。回落探到的状态仍记在源地址
     * 名下（缓存按源地址认，不因回落地址失效）。耗时按整个探测流程计（根地址耗时
     * 也算，GPT 推前审稿）。取消原样抛，不记 FAIL。
     */
    suspend fun probeSource(source: BookSource): BookProbeRecord {
        val start = System.currentTimeMillis()
        // 1.4.27 闪退修：源地址可能是非 URL 文本（如 2117 包“精华书阁”的
        // bookSourceUrl 是一段鲁迅名言），Request.Builder().url() 会直接抛
        // IllegalArgumentException——且它发生在下面的 try 之外，书源管理页
        // 一进就崩。根地址建不出请求时跳过根探、直接走回落；回落也没有才记
        // FAIL，绝不抛（取消仍原样抛）。
        val rootRequest = runCatching {
            Request.Builder().url(source.url).header("User-Agent", DEFAULT_UA).build()
        }.getOrNull()
        if (rootRequest != null) {
            val code = try {
                probeCode(rootRequest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return BookProbeRecord("FAIL", 0L, System.currentTimeMillis(), source.url)
            }
            if (code != 404) return recordFor(code, System.currentTimeMillis() - start, source.url)
        }
        val fallback = fallbackProbeRequest(source)
            ?: return BookProbeRecord("FAIL", 0L, System.currentTimeMillis(), source.url)
        val code2 = try {
            probeCode(fallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BookProbeRecord("FAIL", 0L, System.currentTimeMillis(), source.url)
        }
        return recordFor(code2, System.currentTimeMillis() - start, source.url)
    }

    /**
     * v1.4.26：回落探测请求——发现分类首个 URL（模板按第 1 页求值），没有则搜索 URL。
     * v1.4.26 修（GPT 推前审稿 P1）：搜索回落保留 buildSearchRequest 的完整请求语义
     *（方法 + 表单体 + 编码 + 规则内联头），不只取 URL——POST 搜索源按原方法探测，
     * 否则 GET 探 POST 接口会因 404/405 误判 FAIL。分类回落仍是 GET。
     */
    private fun fallbackProbeRequest(source: BookSource): Request? {
        val category = NovelSourceEngine.parseExploreCategories(source.exploreUrl).firstOrNull()
        if (category != null) {
            val categoryUrl = NovelSourceEngine.evalCategoryUrl(source, category.url, 1)
            // 同上：模板求值结果也可能不是 http(s) 地址，建请求前先验 scheme。
            if (categoryUrl != null && (categoryUrl.startsWith("http://") || categoryUrl.startsWith("https://"))) {
                runCatching {
                    Request.Builder().url(categoryUrl).header("User-Agent", DEFAULT_UA).build()
                }.getOrNull()?.let { return it }
            }
        }
        if (source.searchUrl.isBlank()) return null
        val req = runCatching { buildSearchRequest(source.searchUrl.trim(), source.effectiveCheckKeyWord, 1) }.getOrNull()
            ?: return null
        if (!req.url.startsWith("http://") && !req.url.startsWith("https://")) return null
        val builder = Request.Builder().url(req.url).header("User-Agent", DEFAULT_UA)
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        if (req.method == "POST" && req.body != null) {
            val charset = runCatching { Charset.forName(req.charsetName) }.getOrDefault(Charsets.UTF_8)
            builder.post(req.body.toByteArray(charset).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }
        return builder.build()
    }

    private fun recordFor(code: Int, ms: Long, url: String): BookProbeRecord {
        val status = when {
            code !in 200..399 -> "FAIL"
            ms > SLOW_MS -> "SLOW"
            else -> "OK"
        }
        return BookProbeRecord(status, if (status == "FAIL") 0L else ms, System.currentTimeMillis(), url)
    }

    /** 单次请求只取状态码：IO 异常抛给调用方处理，取消原样抛。 */
    private suspend fun probeCode(request: Request): Int {
        val call = CmsApi.defaultClient().newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { cont.resume(it.code) }
                }
            })
        }
    }
}
