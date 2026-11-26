package com.movieshell.ui.novel

import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.SourceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 解缠⑧：书源行模型——选择 key / 在架 key / 过滤口径与改版前逐字一致。 */
class SourceRowTest {

    private val json = BookSource(name = "全本小说", url = "https://www.quanben.io/")
    private val py = PyBookSourceManager.PyBookRule(name = "fanqie", title = "番茄", sizeKb = 3)

    @Test fun keysMatchLegacyStrings() {
        assertEquals("json_${json.key}", SourceRow.Json(json).selectKey)
        assertEquals("py_fanqie", SourceRow.Py(py).selectKey)
        assertEquals(json.key, SourceRow.Json(json).shelfKey)
        assertEquals(PyBookSourceManager.SOURCE_KEY_PREFIX + "fanqie", SourceRow.Py(py).shelfKey)
    }

    @Test fun shelfKeyOfSelectKeyRoundTrips() {
        assertEquals(SourceRow.Json(json).shelfKey, SourceRow.shelfKeyOf(SourceRow.Json(json).selectKey))
        assertEquals(SourceRow.Py(py).shelfKey, SourceRow.shelfKeyOf(SourceRow.Py(py).selectKey))
        assertEquals("other", SourceRow.shelfKeyOf("other"))
    }

    @Test fun filterUsesLegacyFields() {
        assertTrue(SourceRow.Json(json).matches(""))
        assertTrue(SourceRow.Json(json).matches("QUANBEN"))   // url，忽略大小写
        assertTrue(SourceRow.Json(json).matches("全本"))
        assertFalse(SourceRow.Json(json).matches("番茄"))
        assertTrue(SourceRow.Py(py).matches("FanQie"))        // name
        assertTrue(SourceRow.Py(py).matches("番茄"))           // title
        assertFalse(SourceRow.Py(py).matches("quanben"))
    }

    @Test fun rowsOrderPyFirst() {
        val rows = SourceRow.rows(listOf(py), listOf(json))
        assertEquals(listOf("py_fanqie", "json_${json.key}"), rows.map { it.selectKey })
    }

    @Test fun toggle() {
        val s1 = SourceRow.toggled(emptySet(), "py_a", true)
        assertEquals(setOf("py_a"), s1)
        assertEquals(emptySet<String>(), SourceRow.toggled(s1, "py_a", false))
    }

    // v1.4.27 Step4 审稿 A4：全选 = 已选 ∪ 可见，折叠组里已勾的行不丢、没勾的不被勾上。
    @Test fun selectAllVisibleIsUnion() {
        val selected = setOf("json:a", "json:hidden1")
        val visible = listOf("json:a", "json:b", "py:c")
        val r = SourceRow.selectAllVisible(selected, visible)
        assertEquals(setOf("json:a", "json:hidden1", "json:b", "py:c"), r)
        assertFalse("json:hidden2" in r)
        assertEquals(setOf("x"), SourceRow.selectAllVisible(emptySet(), listOf("x", "x")))
    }

    // v1.4.28 A4：反选只翻转可见行，折叠组里藏起来的行保持原样。
    @Test fun invertVisibleKeepsHiddenSelections() {
        val selected = setOf("json:hidden1", "json:hidden2", "json:a")
        val visible = listOf("json:a", "json:b")
        assertEquals(setOf("json:hidden1", "json:hidden2", "json:b"), SourceRow.invertVisible(selected, visible))
    }

    @Test fun invertVisibleFlipsVisible() {
        assertEquals(setOf("json:a", "json:b"), SourceRow.invertVisible(emptySet(), listOf("json:a", "json:b")))
        assertEquals(emptySet<String>(), SourceRow.invertVisible(setOf("json:a", "json:b"), listOf("json:a", "json:b")))
    }

    @Test fun invertVisibleEmptySelection() {
        assertEquals(setOf("x", "y"), SourceRow.invertVisible(emptySet(), listOf("x", "y")))
        assertEquals(setOf("json:a"), SourceRow.invertVisible(setOf("json:a"), emptyList()))
    }

    // v1.4.28 B1：PY 区段头只数过滤后列表里启用了的，不数未过滤全量。
    @Test fun enabledPyCountUsesFilteredList() {
        val rules = listOf(
            PyBookSourceManager.PyBookRule(name = "a", title = "A", sizeKb = 1),
            PyBookSourceManager.PyBookRule(name = "b", title = "B", sizeKb = 1),
            PyBookSourceManager.PyBookRule(name = "c", title = "C", sizeKb = 1),
        )
        val enabled = setOf("a", "b")
        // 过滤后只剩 b（启用）与 c（未启用），应数 1 而不是全量的 2。
        assertEquals(1, SourceRow.enabledPyCount(listOf(rules[1], rules[2]), enabled))
        assertEquals(2, SourceRow.enabledPyCount(rules, enabled))
        assertEquals(0, SourceRow.enabledPyCount(emptyList(), enabled))
    }

    // v1.4.29：统计区四格筛选口径——与 SourceStats.of 的计数口径一致。
    @Test fun statFilterAllPassesEverything() {
        assertTrue(StatFilter.ALL.matchesRow(false, false, SourceHealth.Fail))
        assertTrue(StatFilter.ALL.matchesRow(true, false, SourceHealth.Untested))
    }

    @Test fun statFilterEnabledFollowsSwitch() {
        assertTrue(StatFilter.ENABLED.matchesRow(false, true, SourceHealth.Fail))
        assertTrue(StatFilter.ENABLED.matchesRow(true, true, SourceHealth.Untested))
        assertFalse(StatFilter.ENABLED.matchesRow(false, false, SourceHealth.Ok(10)))
        assertFalse(StatFilter.ENABLED.matchesRow(true, false, SourceHealth.Untested))
    }

    @Test fun statFilterFailedAndUnverifiedOnlyJson() {
        assertTrue(StatFilter.FAILED.matchesRow(false, true, SourceHealth.Fail))
        assertTrue(StatFilter.FAILED.matchesRow(false, true, SourceHealth.Timeout))
        assertFalse(StatFilter.FAILED.matchesRow(false, true, SourceHealth.Ok(10)))
        assertFalse(StatFilter.FAILED.matchesRow(false, true, SourceHealth.Unverified))
        // PY 源无探测记录，永不进失效/未验证档。
        assertFalse(StatFilter.FAILED.matchesRow(true, true, SourceHealth.Untested))
        assertFalse(StatFilter.UNVERIFIED.matchesRow(true, true, SourceHealth.Untested))
        assertTrue(StatFilter.UNVERIFIED.matchesRow(false, true, SourceHealth.Untested))
        assertTrue(StatFilter.UNVERIFIED.matchesRow(false, true, SourceHealth.Unverified))
        assertFalse(StatFilter.UNVERIFIED.matchesRow(false, true, SourceHealth.Fail))
    }
}
