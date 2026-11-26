package com.movieshell.data.novel

import com.movieshell.domain.model.ContentFail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 解缠③：净化单入口的判定（纯函数部分；写盘路径依赖 Context，走真机回归）。 */
class NovelContentServiceTest {

    private fun rule(regex: String, replacement: String = "", scope: String = "all", target: String = "") =
        PurifyRule(name = "t", regex = regex, replacement = replacement, scope = scope, target = target)

    @Test fun purifiedTextIsReturned() {
        val r = NovelContentService.purifyOrEmpty("第一段\n广告：快来\n第二段", listOf(rule("广告：.*")), "book", "src")
        assertTrue(r is NovelContentService.Outcome.Ok)
        assertEquals("　　第一段\n\n　　第二段", (r as NovelContentService.Outcome.Ok).text)
    }

    @Test fun purifyEmptyKeepsRawAndIsNotOk() {
        val raw = "全是广告\n还是广告"
        val r = NovelContentService.purifyOrEmpty(raw, listOf(rule(".*广告.*")), "book", "src")
        assertEquals(NovelContentService.Outcome.PurifyEmpty(raw), r)
    }

    @Test fun blankRawIsUnknownFailure() {
        assertEquals(
            NovelContentService.Outcome.Failed(ContentFail.UNKNOWN),
            NovelContentService.purifyOrEmpty("  \n ", emptyList(), "book", "src"),
        )
    }

    @Test fun sameAsDirectPurifyEngineCall() {
        // 单入口不改变净化结果：与改版前 ReaderScreen 直接调 NovelPurifyEngine.purify 逐字一致
        val raw = "a\\r\\nb\r\nc"
        val rules = NovelPurifyEngine.defaultRules()
        val expected = NovelPurifyEngine.purify(raw, rules, bookUrl = "book", sourceKey = "src")
        val r = NovelContentService.purifyOrEmpty(raw, rules, "book", "src")
        assertEquals(NovelContentService.Outcome.Ok(expected), r)
    }
}
