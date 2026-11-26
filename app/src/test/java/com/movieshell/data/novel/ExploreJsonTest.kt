package com.movieshell.data.novel

import com.movieshell.core.js.LegadoJsEngine
import com.movieshell.domain.model.BookSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v1.4.26：发现页 JSON 分支（分类 URL 是 JSON 接口的源，如番茄小说）。
 * 夹具取自 2026-11-26 沙箱实抓的分类接口响应（/tmp/fq-booklist.json），
 * 截前 3 本字段齐全的书存测试资源 fq-booklist-3.json。
 */
class ExploreJsonTest {

    private lateinit var server: MockWebServer
    private lateinit var fixture: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fixture = javaClass.getResourceAsStream("/fq-booklist-3.json")!!
            .bufferedReader().readText()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    /** 番茄小说同款规则：列表 data.data，字段裸键，bookUrl 是 {{$.book_id}} 模板。 */
    private fun fqSource() = BookSource(
        name = "番茄小说",
        url = "https://api5-normal-sinfonlineb.fqnovel.com",
        exploreUrl = """[{"title":"都市","url":"${base()}/cat?offset={{(page-1)*100}}&limit=100&gender=1&category_id=1"}]""",
        ruleExploreList = "data.data",
        ruleExploreName = "book_name",
        ruleExploreAuthor = "author",
        ruleExploreBookUrl = "${base()}/detail?book_id={{$.book_id}}",
        ruleExploreCover = "thumb_url##(\\d+)-tt##6-novel",
    )

    private fun json(body: String) = MockResponse().setBody(body)
        .setHeader("Content-Type", "application/json; charset=utf-8")

    @Test fun exploreJsonParsesThreeBooks() = runBlocking {
        server.enqueue(json(fixture))
        val src = fqSource()
        val categoryUrl = NovelSourceEngine.parseExploreCategories(src.exploreUrl).first().url
        val books = NovelSourceEngine.explore(src, categoryUrl, 1)
        assertEquals(3, books.size)
        val first = books[0]
        assertEquals("我不是戏神", first.title)
        assertEquals("三九音域", first.author)
        assertEquals("${base()}/detail?book_id=7276384138653862966", first.remoteId)
        assertTrue(first.cover!!.startsWith("https://p9-novel-sign.byteimg.com/novel-pic/"))
        assertEquals("开局地摊卖大力", books[1].title)
        assertEquals("弈青锋", books[1].author)
        assertEquals("天神殿", books[2].title)
        assertEquals("燕北", books[2].author)
        // 第 1 页 offset 求值成 0
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.contains("offset=0"))
    }

    @Test fun exploreCategoryTemplatePage2Offset100() = runBlocking {
        server.enqueue(json(fixture))
        val src = fqSource()
        val categoryUrl = NovelSourceEngine.parseExploreCategories(src.exploreUrl).first().url
        val books = NovelSourceEngine.explore(src, categoryUrl, 2)
        assertEquals(3, books.size)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(req.path!!.contains("offset=100"))
    }

    @Test fun evalCategoryUrlPageTemplates() {
        val src = BookSource(name = "t", url = "https://example.com")
        assertEquals(
            "https://example.com/cat?offset=0&limit=100",
            NovelSourceEngine.evalCategoryUrl(src, "https://example.com/cat?offset={{(page-1)*100}}&limit=100", 1),
        )
        assertEquals(
            "https://example.com/cat?offset=100&limit=100",
            NovelSourceEngine.evalCategoryUrl(src, "https://example.com/cat?offset={{(page-1)*100}}&limit=100", 2),
        )
        assertEquals(
            "https://example.com/cat?page=2",
            NovelSourceEngine.evalCategoryUrl(src, "https://example.com/cat?page={{page}}", 2),
        )
    }

    // evalJsonField 裸键
    private fun firstItem(): Any? {
        val root = LegadoJsEngine.parseJson(fixture)!!
        return LegadoJsEngine.jsonPathQuery(root, "data.data[0]").first()
    }

    private fun ctx() = LegadoJsEngine.JsCtx(
        BookSource(name = "t", url = "https://example.com").toJsRuleSource(), "https://example.com/",
    )

    @Test fun evalJsonFieldBareKeys() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("我不是戏神", LegadoJsEngine.evalJsonField(item, "book_name", ctx(), state))
        assertEquals("三九音域", LegadoJsEngine.evalJsonField(item, "author", ctx(), state))
        assertTrue(LegadoJsEngine.evalJsonField(item, "thumb_url", ctx(), state).startsWith("https://p9-novel-sign.byteimg.com/"))
        // $. 规则不受影响
        assertEquals("我不是戏神", LegadoJsEngine.evalJsonField(item, "$.book_name", ctx(), state))
    }

    @Test fun evalJsonFieldBareKeyRejectsNonKeys() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("", LegadoJsEngine.evalJsonField(item, "a@href", ctx(), state))
        assertEquals("", LegadoJsEngine.evalJsonField(item, "div.book", ctx(), state))
        assertEquals("", LegadoJsEngine.evalJsonField(item, "book name", ctx(), state))
        assertEquals("", LegadoJsEngine.evalJsonField(item, "a/b", ctx(), state))
    }

    @Test fun evalJsonFieldDoubleBraceTemplateUrl() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals(
            "https://example.com/detail?book_id=7276384138653862966",
            LegadoJsEngine.evalJsonField(item, "https://example.com/detail?book_id={{$.book_id}}", ctx(), state),
        )
    }

    @Test fun evalJsonFieldCoverRegexStillApplies() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        // 夹具 thumb_url 里没有 "数字-tt"，正则段不命中即原样返回
        val raw = (item as JSONObject).getString("thumb_url")
        assertEquals(raw, LegadoJsEngine.evalJsonField(item, "thumb_url##(\\d+)-tt##6-novel", ctx(), state))
    }

    // v1.4.26 修（智能体 + GPT 推前审稿）：字面量模板只认单占位或 URL 形，
    // 内嵌字段取空按旧语义回 ""，CSS 形规则不进字面量分支。
    @Test fun evalJsonFieldMissingFieldTemplateReturnsEmpty() {
        val item = JSONObject("""{"book_name":"x"}""")
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("", LegadoJsEngine.evalJsonField(item, "https://example.com/detail?book_id={{$.book_id}}", ctx(), state))
    }

    @Test fun evalJsonFieldCssShapedTemplateReturnsEmpty() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("", LegadoJsEngine.evalJsonField(item, "div.{{$.book_name}}", ctx(), state))
    }

    @Test fun evalJsonFieldSinglePlaceholderStillWorks() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("我不是戏神", LegadoJsEngine.evalJsonField(item, "{{$.book_name}}", ctx(), state))
    }

    // GPT 建议的组合覆盖：裸键 + ||、双花括号 + ##。
    @Test fun evalJsonFieldBareKeyWithFallback() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("我不是戏神", LegadoJsEngine.evalJsonField(item, "missing_key || book_name", ctx(), state))
    }

    @Test fun evalJsonFieldDoubleBraceWithHash() {
        val item = firstItem()
        val state = LegadoJsEngine.JsState("example.com")
        assertEquals("我不是戏", LegadoJsEngine.evalJsonField(item, "{{$.book_name}}##神", ctx(), state))
    }

    // 字段缺失的条目在发现页被丢弃（bookUrl 模板取空 → remoteId 为空 → 不保留）。
    @Test fun exploreDropsBookWithMissingBookId() = runBlocking {
        val body = """{"data":{"data":[{"book_name":"有","author":"a","book_id":"1","thumb_url":"https://x/y.jpg"},{"book_name":"无","author":"b","thumb_url":"https://x/z.jpg"}]}}"""
        server.enqueue(json(body))
        val src = fqSource()
        val categoryUrl = NovelSourceEngine.parseExploreCategories(src.exploreUrl).first().url
        val books = NovelSourceEngine.explore(src, categoryUrl, 1)
        assertEquals(1, books.size)
        assertEquals("有", books[0].title)
    }

    // v1.4.26 修（智能体推前审稿）：WebView 默认视图的分类地址同样走模板求值。
    @Test fun explorePageUrlEvaluatesOffsetTemplate() {
        val src = BookSource(name = "t", url = "https://example.com")
        assertEquals(
            "https://example.com/cat?offset=0&limit=100",
            NovelSourceEngine.explorePageUrl(src, "https://example.com/cat?offset={{(page-1)*100}}&limit=100"),
        )
        assertEquals(
            "https://example.com/cat?page=1",
            NovelSourceEngine.explorePageUrl(src, "https://example.com/cat?page={{page}}"),
        )
    }
}
