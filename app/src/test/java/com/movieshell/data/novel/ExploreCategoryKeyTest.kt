package com.movieshell.data.novel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.4.29：聚合发现闪退回归——同一源的发现地址里同一分类标题可出现两次
 * （不同地址）。分类必须按地址区分，标题作 LazyColumn key 会直接闪退
 * （IllegalArgumentException: Key "西方奇幻" was already used）。
 * 解析层保留两条（都是合法分类），UI 层 key 必须用下标＋地址复合——
 * 同一地址也可出现两次（解析层不去重），只按地址仍会撞 key。
 */
class ExploreCategoryKeyTest {

    @Test fun duplicateTitleDifferentUrlBothKept() {
        val cats = NovelSourceEngine.parseExploreCategories(
            """[{"title":"西方奇幻","url":"https://example.com/a"},{"title":"西方奇幻","url":"https://example.com/b"},{"title":"东方玄幻","url":"https://example.com/c"}]""",
        )
        assertEquals(3, cats.size)
        assertEquals(listOf("西方奇幻", "西方奇幻", "东方玄幻"), cats.map { it.title })
        // 地址互不相同——同标题不同地址分态。
        assertEquals(3, cats.map { it.url }.toSet().size)
    }

    @Test fun duplicateUrlBothKept() {
        // TA 审码必修：数组形里同一地址也可出现两次（解析层不去重），
        // UI 层只按地址作 key 仍会撞，必须用下标＋地址复合。
        val cats = NovelSourceEngine.parseExploreCategories(
            """[{"title":"西方奇幻","url":"https://example.com/a"},{"title":"东方玄幻","url":"https://example.com/a"}]""",
        )
        assertEquals(2, cats.size)
        assertEquals(1, cats.map { it.url }.toSet().size)
    }

    @Test fun objectFormTitlesAreKeysSoNoDuplicates() {
        val cats = NovelSourceEngine.parseExploreCategories(
            """{"西方奇幻":"https://example.com/a","东方玄幻":"https://example.com/c"}""",
        )
        assertEquals(2, cats.size)
        assertEquals(setOf("https://example.com/a", "https://example.com/c"), cats.map { it.url }.toSet())
    }
}
