package com.movieshell.ui.novel

import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.domain.model.BookSource
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
}
