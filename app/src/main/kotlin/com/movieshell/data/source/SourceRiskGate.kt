package com.movieshell.data.source

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.ExecutableTrust
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.4.22 轨道 8：高风险源首次执行前的二次确认（执行时关卡）。
 *
 * 背景：SourceRiskScanner 此前只在“导入”时扫（SourcesScreen /
 * BookSourceScreen / MusicSourceScreen），订阅展开的源（sub: 站，
 * 规则文件直接落盘、从不经过导入扫描）从来没被扫过——用户平时
 * 真正执行的恰恰是这些订阅源。本关卡补的就是执行时这一道：
 * 订阅 drpy / PY 源在引擎构造前先读可信副本扫一遍，高危且未
 * 确认的，引擎层直接拦住不执行（返回空结果 + 失败原因），首页
 * 再把确认弹窗递到用户面前。
 *
 * 同意记录的语义（写清楚）：
 * - 按“源 key + 规则内容 SHA-256”记：同一份规则内容确认一次
 *   就够了；规则文件一改（SHA 变了）下次执行重新弹确认——源
 *   作者悄悄换规则不能沿用旧同意。
 * - 记在普通 SharedPreferences 里（不是凭据，不进加密存储），
 *   只存“已确认 + 白名单域名”，不存规则内容、不存任何 key。
 * - 取消 = 不执行，且不记任何东西；下次切回该源还会再弹。
 * - 聚合搜索里被拦的源只计入“失败源”汇总，不弹弹窗——后台
 *   批量执行没有地方弹确认；要放行它，先切到该源在首页确认一次。
 *
 * 不夸大：扫描是正则启发式（见 SourceRiskScanner），可被绕过，
 * 不是安全边界；本关卡拦的是“扫出来高危的已知写法”，不是“一切
 * 恶意源”。文案与注释里不写“已隔离/已沙箱化”这类话。
 *
 * 未覆盖（本轨做不了，见报告）：本地激活规则（DrpySourceManager /
 * PySourceManager / PhpCliEngine 各自建引擎，不走 SubSiteEngines）、
 * PHP 本地规则、CMS/Type4（服务端跑代码，本机不执行规则）、
 * Java spider jar（scanJar 至今零生产调用，接线另立项）、
 * SourceProbe / FollowCheckWorker 的后台引擎直构。
 */
object SourceRiskGate {

    private const val TAG = "RiskGate"
    private const val PREFS = "movieshell_risk_gate"

    /**
     * 引擎层决策：blocked=true 时调用方必须返回空引擎、不执行。
     * ruleSha256 是本次扫的那份可信副本的内容 SHA（与同意记录
     * consent:siteKey:sha256 用的同一份）；扫不到时为 null。
     * v1.4.22 GPT P1-1：池 key 必须绑这份 SHA，不能只凭源 key
     * 复用引擎，否则规则改了、白名单没变会跑旧规则。
     */
    data class Decision(val blocked: Boolean, val sandbox: SourceSandbox?, val ruleSha256: String? = null)

    /** 弹窗待确认项：首页确认后调 [confirm] 再重载。 */
    data class Pending(
        val siteKey: String,
        val siteName: String,
        val sha256: String,
        val report: SourceRiskScanner.Report,
        val sandbox: SourceSandbox,
    )

    private data class Cached(val sha: String, val report: SourceRiskScanner.Report, val sandbox: SourceSandbox)

    /** 内存 verdict 缓存：key=源 key，值里带 SHA，内容变了自动重扫。 */
    private val verdictCache = ConcurrentHashMap<String, Cached>()

    /** 同意记录 key：纯函数，供单测核对格式。 */
    fun consentKey(siteKey: String, sha256: String): String = "consent:${siteKey}:${sha256}"

    /** 该份规则内容是否已确认过。 */
    fun isConsented(context: Context, siteKey: String, sha256: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(consentKey(siteKey, sha256))

    /** 已确认时取回当时存的白名单；未确认返回 null。 */
    fun sandboxFor(context: Context, siteKey: String, sha256: String): SourceSandbox? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(consentKey(siteKey, sha256), null) ?: return null
        return SourceSandbox(SourceSandbox.decodeHosts(encoded))
    }

    /** 用户在弹窗点“确认执行”：记下这份规则内容的同意 + 白名单。 */
    fun confirm(context: Context, pending: Pending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(consentKey(pending.siteKey, pending.sha256), SourceSandbox.encodeHosts(pending.sandbox.allowedHosts))
            .apply()
        LogBus.i(TAG, "高危源已确认执行 site=${pending.siteKey} 白名单 ${pending.sandbox.allowedHosts.size} 个域名")
    }

    /**
     * 引擎层决策：调用方已拿到可信副本文件（SubSiteEngines 的
     * ExecutableTrust 流程），这里只扫、不再碰公开文件。
     * 扫不出（文件超限/读不到）时按“未发现高危”放行并记日志——
     * 扫不到不等于安全，但本关卡不做“扫不到就全拦”（会误伤正常源）。
     */
    fun decisionFor(context: Context, siteKey: String, trustedFile: File): Decision {
        val resolved = resolve(siteKey, trustedFile) ?: return Decision(blocked = false, sandbox = null)
        if (!resolved.report.hasHighRisk) return Decision(blocked = false, sandbox = null, ruleSha256 = resolved.sha)
        if (isConsented(context, siteKey, resolved.sha)) {
            val sb = sandboxFor(context, siteKey, resolved.sha)
            // v1.4.22 审稿 S1：派生白名单为空时不限流——否则已确认的源会被
            // 永久全网拦截，重新确认也拿不到新白名单（派生结果不变），无路可走。
            if (sb != null && sb.allowedHosts.isEmpty()) {
                LogBus.i(TAG, "白名单为空，不限制联网 site=$siteKey")
                return Decision(blocked = false, sandbox = null, ruleSha256 = resolved.sha)
            }
            return Decision(blocked = false, sandbox = sb, ruleSha256 = resolved.sha)
        }
        return Decision(blocked = true, sandbox = null, ruleSha256 = resolved.sha)
    }

    /**
     * 首页弹窗用：给一个订阅站算待确认项；不是代码执行路（CMS/Type4/
     * spider）、文件丢失、信任校验失败、无高危、已确认——一律返回 null。
     * [isCodeSite] 由调用方按路由判定（sub 站的 drpy/PY、local:drpy:/py:），
     * 本函数不认 Site 类型，避免再拖一个 domain 依赖。
     */
    fun pendingFor(context: Context, siteKey: String, siteName: String, api: String, isCodeSite: Boolean): Pending? {
        if (!isCodeSite) return null
        val f = File(api)
        if (!f.isFile) return null
        val trusted = ExecutableTrust.trustedFile(
            context.filesDir, f, "site:${siteKey}", allowRetrust = false,
        ) ?: return null
        val resolved = resolve(siteKey, trusted) ?: return null
        if (!resolved.report.hasHighRisk) return null
        if (isConsented(context, siteKey, resolved.sha)) return null
        return Pending(siteKey, siteName, resolved.sha, resolved.report, resolved.sandbox)
    }

    /** 扫可信副本并缓存 verdict；超限/读不到返回 null（调用方按放行处理）。 */
    private fun resolve(siteKey: String, trustedFile: File): Cached? {
        val sha = ExecutableTrust.sha256(trustedFile) ?: return null
        verdictCache[siteKey]?.let { if (it.sha == sha) return it }
        val bytes = try {
            FileInputStream(trustedFile).use { ins -> SourceRiskScanner.readCappedBytes(ins) }
        } catch (e: Exception) {
            LogBus.w(TAG, "高危扫描读规则失败 site=$siteKey")
            return null
        }
        if (bytes == null) {
            LogBus.w(TAG, "规则超 ${SourceRiskScanner.MAX_SCAN_BYTES} 字节没扫 site=$siteKey")
            return null
        }
        val text = bytes.toString(Charsets.UTF_8)
        val report = SourceRiskScanner.scanText(text)
        val sandbox = SourceSandbox.derive(text, "")
        val cached = Cached(sha, report, sandbox)
        verdictCache[siteKey] = cached
        return cached
    }
}
