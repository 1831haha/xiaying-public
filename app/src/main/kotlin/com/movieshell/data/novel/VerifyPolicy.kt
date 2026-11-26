package com.movieshell.data.novel

/**
 * v1.4.27 解缠⑨：验证通道收敛——特征词表、超时、判过口径收到这一处（纯逻辑，可单测）。
 *
 * 两条通道都留，分工：
 * - **后台静默**（[Channel.BACKGROUND]，VerifyGate.awaitVerifyBackground）：不挂界面的 WebView 自己跑
 *   JS 挑战，按 [backgroundPassed] 自动判过；不打扰用户，过不了（真人验证码/超时）返回 null。
 * - **前台弹窗**（[Channel.FOREGROUND]，VerifyGate.awaitVerify + VerifyGateDialog）：用户手动过验证、
 *   自己点「验证完成」，**不自动判过**（Cloudflare 形态多变，误判比让用户点一下更糟，v0.201.0 定）。
 *
 * 调用点与走哪条（每处代码旁也写明了为什么）：
 * | 调用点 | 通道 | 为什么 |
 * |---|---|---|
 * | NovelSourceEngine.gatedFetch（搜书/目录/详情等用户主动发起、首抓空） | 先后台、后前台 | 多数挑战后台自己过；过不了才打扰用户 |
 * | NovelSourceEngine 正文 200 但 [looksLikeChallenge] | 先后台、后前台 | 同上；只有命中特征词才进验证，普通提空不反复开验证 |
 * | 后台任务（allowGate=false：聚合搜书、追更、补目录、测试） | 不验证 | 后台不能弹窗；2000+ 源时每个失败源弹一次不可接受 |
 * | LegadoJsEngine.startBrowserAwait（书源 JS 主动要求开浏览器） | 只走前台 | 书源作者明确要用户看页面（登录/验证码），后台判过没有意义 |
 *
 * 影视侧 SniffEngine 的人机验证判断属嗅探链路，不在小说验证通道里，本段不动。
 */
object VerifyPolicy {

    enum class Channel { BACKGROUND, FOREGROUND }

    /** 挑战页特征词（原 VerifyGate.challengeMarkers，逐字不变）。 */
    val CHALLENGE_MARKERS: List<String> = listOf(
        "Just a moment",
        "cf-challenge",
        "Attention Required",
        "Checking if the site connection is secure",
    )

    /** 挑战通过后落地的 Cookie 名（带等号，按子串判）。 */
    const val CLEARANCE_COOKIE = "cf_clearance="

    /** 后台静默验证超时（原 awaitVerifyBackground 默认值）。 */
    const val BACKGROUND_TIMEOUT_MS = 30_000L

    /** 前台弹窗等待超时（原 awaitVerify 默认值；JS startBrowserAwait 注释里的“最长 180 秒”即此值）。 */
    const val FOREGROUND_TIMEOUT_MS = 180_000L

    /** 后台轮询页面的间隔。 */
    const val BACKGROUND_POLL_MS = 1_500L

    /** 后台判过：无挑战特征时正文至少这么长才算真页面。 */
    const val MIN_PASS_HTML_LEN = 500

    /** 疑似验证页（正文 200 提空时用；与后台判过同一组特征词）。 */
    fun looksLikeChallenge(html: String): Boolean = CHALLENGE_MARKERS.any { html.contains(it) }

    /** 后台静默验证判过：拿到 cf_clearance，或页面已无挑战特征且正文够长。 */
    fun backgroundPassed(html: String, cookieHeader: String): Boolean =
        cookieHeader.contains(CLEARANCE_COOKIE) ||
            (!looksLikeChallenge(html) && html.length > MIN_PASS_HTML_LEN)

    /**
     * 把若干个 CookieManager.getCookie() 原串（可空）合成一个 Cookie 头：按 ; 拆、去空白、去重、保序。
     * 原 VerifyGate.collectCookies 与 VerifyGateDialog 各写一份，口径相同，收到这里。
     */
    fun mergeCookieHeaders(raws: List<String?>): String =
        raws.flatMap { it.orEmpty().split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
}
