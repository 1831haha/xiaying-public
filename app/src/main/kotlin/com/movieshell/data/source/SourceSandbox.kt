package com.movieshell.data.source

/**
 * v1.4.22 轨道 8：源执行沙箱参数——白名单式网络域名范围。
 *
 * 口径（先说清楚，不夸大）：
 * - 这是“网络域名白名单”，不是完整沙箱：能力开关（读文件、执行命令、
 *   动态加载）不在此列——Rhino/Chaquopy/PHP 三个运行时都没有按源
 *   拦截这类能力的口子，要真拦得重写运行时，本轨不做。
 * - 强制执行目前只在 drpy JS 这一路：JS 联网全走宿主 RPC
 *   [V8DrpyEngine.doRequest]，那是唯一的 choke 点。PY/PHP/CMS/
 *   spider 没有等价 choke 点，策略只记录、不强制（见 SourceRiskGate）。
 * - 白名单从规则文本本身派生：规则的 host 字段 + 文本里出现的
 *   http(s) 字面域名。生产路径上站点 api 不传（见 SourceRiskGate.resolve，
 *   sub 站 api 本就是本地文件路径，派生不出域名）。规则要联网的地址基本都以
 *   字面量写在规则里，所以派生能覆盖正常用法；规则改了内容，
 *   同意记录随 SHA-256 失效，下次执行重新确认。
 * - 匹配是“本域 + 子域”：白名单 example.com 允许 a.example.com，
 *   不允许 badexample.com（后缀匹配带点号分隔，不做裸 endsWith）。
 */
data class SourceSandbox(
    /** 允许联网的域名集合（小写、无端口、无 scheme）。 */
    val allowedHosts: Set<String>,
    /** true = 只放行白名单；false = 不限制（只记录策略）。 */
    val enforced: Boolean = true,
) {

    /** url 或裸域名是否在白名单内。非法/空 host 一律不放行。 */
    fun allows(urlOrHost: String): Boolean {
        if (!enforced) return true
        val host = hostOf(urlOrHost) ?: return false
        return allowedHosts.any { allowed ->
            host == allowed || host.endsWith(".${allowed}")
        }
    }

    companion object {
        /** 白名单上限：防规则文本里塞几千个域名把同意记录撑爆。 */
        const val MAX_HOSTS = 64

        private val URL_HOST_RE = Regex("""https?://([A-Za-z0-9._\-]+)""")
        private val RULE_HOST_RE = Regex("""host\s*[:=]\s*['"]([^'"]+)['"]""")

        /**
         * 从 url / 裸域名里取 host：小写、去端口、去首尾空白与末尾点。
         * 不是 url 也不是合法域名时返回 null（调用方按“不放行”处理）。
         */
        fun hostOf(urlOrHost: String): String? {
            val raw = urlOrHost.trim().trimEnd('.')
            if (raw.isBlank()) return null
            val noScheme = raw.substringAfter("://", raw)
            val hostPort = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            if (hostPort.isBlank()) return null
            // IPv6 字面量 [::1]:port——本 App 的源域名基本不用，先按不支持处理。
            if (hostPort.startsWith("[")) return null
            val host = hostPort.substringBefore(':').trim().lowercase()
            if (host.isBlank() || host.contains(' ') || host.contains('_')) return null
            return host
        }

        /** 从规则文本（+ 可选的站点 api）派生白名单。生产路径上 apiUrl 传 ""。 */
        fun derive(ruleText: String, apiUrl: String): SourceSandbox {
            val hosts = LinkedHashSet<String>()
            // 规则 host 字段可能是完整 URL，也可能是裸域名。
            RULE_HOST_RE.findAll(ruleText).forEach { m ->
                hostOf(m.groupValues[1])?.let { hosts += it }
            }
            URL_HOST_RE.findAll(ruleText).forEach { m ->
                hostOf(m.groupValues[1])?.let { hosts += it }
            }
            hostOf(apiUrl)?.let { hosts += it }
            return SourceSandbox(hosts.take(MAX_HOSTS).toSet())
        }

        /** 同意记录里的域名串：排序后逗号拼接，稳定可比。 */
        fun encodeHosts(hosts: Set<String>): String = hosts.sorted().joinToString(",")

        fun decodeHosts(encoded: String): Set<String> =
            encoded.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }
}
