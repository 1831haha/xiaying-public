package com.movieshell.domain.model

/**
 * v1.4.27 解缠②：探/测统一的显示模型。
 *
 * 被动探测（BookProbe，进书源页自动探有发现分类的源，结果落 BookProbeStore）与
 * 主动搜索测试（NovelSourceEngine.testSearch，行内 ▶）触发时机与用途不同，不合并；
 * 只合并“结果长什么样”：两边都映射到本模型，共用同一套色调与文案口径。
 *
 * 与总定稿的五档（Ok/Slow/Fail/Timeout/Unconfigured）相比多两档，原因如实写在这里：
 * - [Unverified]：搜索测试 0 条（TestSearchResult.Zero）——请求与解析都成功，只是没出书，
 *   映射成 Ok 或 Fail 都是假话（搜索测试 UI 设计稿五档里就有“未验证”）；
 * - [Untested]：没有探测记录（列表里现有的灰点“未测”）。
 */
sealed interface SourceHealth {
    /** 通：耗时 [ms]（未超过 [SLOW_MS]）。 */
    data class Ok(val ms: Long) : SourceHealth

    /** 慢：通了但耗时超过 [SLOW_MS]。 */
    data class Slow(val ms: Long) : SourceHealth

    /** 不通 / 执行失败。 */
    data object Fail : SourceHealth

    /** 超时（墙钟到点被取消）。 */
    data object Timeout : SourceHealth

    /** 未配置（搜索测试：searchUrl 或 ruleSearchList 任一为空）。 */
    data object Unconfigured : SourceHealth

    /** 未验证（搜索测试正常完成但 0 条）。 */
    data object Unverified : SourceHealth

    /** 未测（没有任何记录）。 */
    data object Untested : SourceHealth

    companion object {
        /** 慢阈值（毫秒）：原 BookProbe.SLOW_MS，探测与测试共用。 */
        const val SLOW_MS = 3000L

        /** 按耗时分通/慢。 */
        fun reachable(ms: Long): SourceHealth = if (ms > SLOW_MS) Slow(ms) else Ok(ms)
    }
}

/** 色调（UI 层按色调取色，探测圆点与测试结果同一套颜色）。 */
enum class HealthTone { GOOD, WARN, BAD, NEUTRAL }

val SourceHealth.tone: HealthTone
    get() = when (this) {
        is SourceHealth.Ok -> HealthTone.GOOD
        is SourceHealth.Slow, SourceHealth.Unverified -> HealthTone.WARN
        SourceHealth.Fail, SourceHealth.Timeout -> HealthTone.BAD
        SourceHealth.Unconfigured, SourceHealth.Untested -> HealthTone.NEUTRAL
    }

/** 行内短文案（书源行状态文字；通/慢/不通/未测 四档与 1.1.3 起的现有文案逐字一致）。 */
val SourceHealth.shortLabel: String
    get() = when (this) {
        is SourceHealth.Ok -> "通 ${ms}ms"
        is SourceHealth.Slow -> "慢 ${ms}ms"
        SourceHealth.Fail -> "不通"
        SourceHealth.Timeout -> "超时"
        SourceHealth.Unconfigured -> "未配置搜索"
        SourceHealth.Unverified -> "未验证"
        SourceHealth.Untested -> "未测"
    }

/**
 * 搜索测试结果 → 显示模型。[elapsedMs] 是本次测试墙钟耗时（UI 计时传入）；
 * Ok 的条数不进本模型，结果弹窗直接读 TestSearchResult.Ok.count。
 */
fun TestSearchResult.toSourceHealth(elapsedMs: Long): SourceHealth = when (this) {
    is TestSearchResult.Ok -> SourceHealth.reachable(elapsedMs)
    TestSearchResult.Zero -> SourceHealth.Unverified
    TestSearchResult.Failed -> SourceHealth.Fail
    TestSearchResult.Timeout -> SourceHealth.Timeout
    TestSearchResult.NotConfigured -> SourceHealth.Unconfigured
}
