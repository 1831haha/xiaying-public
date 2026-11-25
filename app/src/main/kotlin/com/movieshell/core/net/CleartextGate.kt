package com.movieshell.core.net

import com.movieshell.core.log.LogBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import java.io.IOException
import java.util.Locale

/**
 * v1.4.23 轨道 4：明文 HTTP 应用层授权网关——纯判定部分。
 *
 * 背景（见 ~/workspace/review-1.4.23/track4-inventory.md §4 选项 1）：
 * NSC 是静态 XML，表达不了“用户任意填域名”；本版不启用 NSC，平台保持放行，
 * 由应用在自己的 OkHttp choke 点上执法。本文件是纯 Kotlin，不 import 任何
 * android.*，只依赖 okhttp3（Interceptor）与 kotlinx.serialization.json
 *（祖父迁移的结构化提取），判定逻辑可进纯函数单测。
 *
 * 判定口径（与 CleartextAuth.isAllowed 共用）：
 * - https 与其它 scheme 一律放行；
 * - http 只有预授权域（含子域）/ 已授权域（含子域）/ 回环 / 私有 IP 才放行。
 * 私有 IP 含 IPv6：fc00::/7（ULA）、fe80::/10（Link-Local）。
 *
 * 祖父迁移的结构化提取（P1-6）：只取白名单配置字段里的 http host，描述/
 * 备注等文本里的 URL 不进授权集；JSON 解析失败返回 null、调用方按失败计，
 * 绝不做正则兜底。
 */
object CleartextGate {

    private const val TAG = "CleartextGate"

    /** 内置预授权域：实测不支持 https、必须走 http 的写死接口。 */
    val PREAUTHORIZED_HOSTS: Set<String> = setOf("sspa8.top", "mobilecdn.kugou.com")

    /** 祖父迁移与授权集的上限（防订阅文件里几千个 host 把 prefs 撑爆）。 */
    const val MAX_HOSTS = 200

    /** 归一化：小写、trim、去末尾点、去 :端口；"[::1]:8080" 形也处理。 */
    fun normalizeHost(host: String): String {
        var h = host.trim().lowercase(Locale.ROOT)
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end >= 0) return h.substring(1, end).trimEnd('.')
            return h
        }
        // 单个冒号是 host:port；多个冒号是不带括号的 IPv6 字面量，原样保留。
        if (h.count { it == ':' } == 1) h = h.substringBefore(':')
        return h.trimEnd('.')
    }

    /**
     * 回环 / 私有 IP：127.0.0.0/8、::1、localhost、10/8、172.16/12、192.168/16、
     * 169.254/16；IPv6：fc00::/7（ULA，首 hextet FC00–FDFF）、fe80::/10
     *（Link-Local，首 hextet FE80–FEBF）。IPv6 只看首个 hextet 定网段。
     */
    fun isLoopbackOrPrivateIp(host: String): Boolean {
        val h = normalizeHost(host)
        if (h == "localhost" || h == "::1") return true
        if (':' in h) return isIpv6UniqueLocalOrLinkLocal(h)
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        return when {
            nums[0] == 127 -> true
            nums[0] == 10 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 169 && nums[1] == 254 -> true
            else -> false
        }
    }

    /**
     * IPv6 私有段判定（P2）：fc00::/7 与 fe80::/10。首 hextet 为空（如 "::"
     * 开头的压缩地址）或非十六进制时返回 false；不做 DNS 查询。
     */
    private fun isIpv6UniqueLocalOrLinkLocal(h: String): Boolean {
        val first = h.substringBefore(':')
        if (first.isEmpty()) return false
        val hextet = first.toIntOrNull(16) ?: return false
        if (hextet !in 0..0xFFFF) return false
        return hextet in 0xFC00..0xFDFF || hextet in 0xFE80..0xFEBF
    }

    /**
     * 判定：https（与其它 scheme）→ true；http → 预授权 / 已授权 / 回环 /
     * 私有 IP 才 true。authorized 里逐项归一化后按“相等或子域”匹配。
     */
    fun decide(scheme: String, host: String, authorized: Set<String>): Boolean {
        if (!scheme.trim().equals("http", ignoreCase = true)) return true
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        if (isLoopbackOrPrivateIp(h)) return true
        if (matchesAny(h, PREAUTHORIZED_HOSTS)) return true
        if (matchesAny(h, authorized)) return true
        return false
    }

    private fun matchesAny(host: String, domains: Set<String>): Boolean {
        for (d in domains) {
            val n = normalizeHost(d)
            if (n.isEmpty()) continue
            if (host == n || host.endsWith(".$n")) return true
        }
        return false
    }

    /**
     * 单个 URL → http host（小写、去端口、去 userinfo）；非 http / 解析不出
     * host 一律 null。祖父迁移里每条配置地址先过它，再进授权集。
     */
    fun extractHttpHost(url: String): String? {
        val t = url.trim()
        if (!t.startsWith("http://", ignoreCase = true)) return null
        val authority = t.substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        val host = normalizeHost(authority.substringAfterLast('@'))
        return host.ifEmpty { null }
    }

    /**
     * 结构化提取（P1-6）：解析 JSON，只取 [fields] 白名单键的字符串值里的
     * http host。返回 null = JSON 解析失败（调用方按该来源失败计、下次重试；
     * 绝不做正则兜底）。白名单键在任意嵌套层级都认；description / note /
     * remark 等非白名单键的字符串值一律不扫——里面写着 http 地址也不进授权集。
     *
     * 递归边界（GPT 增量审稿补漏）：白名单键的值就是地址本身，不再钻进它的
     * 子树（ext 在个别来源里是 header/flags 对象，里面的 url 不是 ext 地址）；
     * description / note / remark / ext / origExt 这些自由文本或地址型字段，
     * 不在白名单里时整个子树直接跳过——防止备注对象里嵌的 url 借道进授权集。
     * 其余键（sites / caches / data / items 等合法容器）照常递归。
     */
    private val IGNORED_FIELDS = setOf("description", "note", "remark", "ext", "origExt")

    fun extractHttpHostsFromJson(text: String, fields: Set<String>): Set<String>? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val out = linkedSetOf<String>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> for ((key, value) in el) {
                    if (key in IGNORED_FIELDS && key !in fields) continue
                    if (key in fields) {
                        val s = (value as? JsonPrimitive)?.contentOrNull
                        if (s != null) extractHttpHost(s)?.let { out.add(it) }
                        continue
                    }
                    walk(value)
                }
                is JsonArray -> for (item in el) walk(item)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    /**
     * 授权集上限（默认 200）：按顺序取 host、去重，先出现的赢，超限丢弃。
     * 祖父迁移多来源汇总后统一过它。
     */
    fun capHosts(hosts: Iterable<String>, limit: Int = MAX_HOSTS): Set<String> {
        val out = linkedSetOf<String>()
        for (h in hosts) {
            if (out.size >= limit) return out
            out.add(h)
        }
        return out
    }

    /**
     * 网络拦截器：逐跳检查（OkHttp 的 network interceptor 每跳重定向都会再走一次，
     * 跳到新 host 同样被拦）。未授权 http 抛 CleartextBlockedException；判定逻辑
     * 本身异常时 fail-open＋记日志；被拦时记一笔 CleartextAuth.recordBlocked。
     * 注意：CleartextBlockedException 必须从 try 外抛，不许被下面的 runCatching 吞掉。
     */
    val networkInterceptor: Interceptor = Interceptor { chain ->
        val url = chain.request().url
        val allowed = try {
            CleartextAuth.isAllowed(url.scheme, url.host)
        } catch (e: Exception) {
            LogBus.w(TAG, "明文网关判定异常，已放行：${url.host} ${e.message}")
            true
        }
        if (!allowed) {
            runCatching { CleartextAuth.recordBlocked(url.host) }
            throw CleartextBlockedException(url.host)
        }
        chain.proceed(chain.request())
    }
}

/** 明文 HTTP 未授权：拦截器抛给调用方，调用方按“未授权”处理（不重试、不静默吞）。 */
class CleartextBlockedException(val host: String) : IOException("明文 HTTP 未授权：$host")
