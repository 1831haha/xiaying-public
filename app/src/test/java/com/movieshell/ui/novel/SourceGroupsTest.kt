package com.movieshell.ui.novel

import com.movieshell.domain.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 P0.2 书源分组口径 + P0.8 发现默认模式。 */
class SourceGroupsTest {

    private fun s(name: String, group: String) = BookSource(name = name, url = "https://$name.com", group = group)

    @Test fun primaryGroupTakesFirstToken() {
        assertEquals("精品", SourceGroups.primaryGroup(s("a", " 精品 ,优质")))
        assertEquals("优质", SourceGroups.primaryGroup(s("a", "，优质；x")))
        assertEquals(SourceGroups.UNGROUPED, SourceGroups.primaryGroup(s("a", "  ")))
    }

    @Test fun groupKeepsOrderAndUngroupedLast() {
        val g = SourceGroups.group(listOf(s("a", ""), s("b", "乙"), s("c", "甲"), s("d", "乙,甲")))
        assertEquals(listOf("乙", "甲", SourceGroups.UNGROUPED), g.map { it.first })
        assertEquals(listOf("b", "d"), g[0].second.map { it.name })
        // 每个源只出现一次（LazyColumn key 不重复）
        assertEquals(4, g.sumOf { it.second.size })
    }

    @Test fun exploreDefaultMode() {
        assertTrue(ExploreDefaults.defaultWebMode(null))
        assertTrue(ExploreDefaults.defaultWebMode(BookSource(name = "x")))
        assertFalse(ExploreDefaults.defaultWebMode(BookSource(name = "x", ruleExploreList = ".list li")))
    }
}
