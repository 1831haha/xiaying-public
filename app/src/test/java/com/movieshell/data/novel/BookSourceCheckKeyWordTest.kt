package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27：ruleSearch.checkKeyWord 读写（A1–A6）。 */
class BookSourceCheckKeyWordTest {

    private fun json(ruleSearchExtra: String = ""): String = """
        {"bookSourceName":"测","bookSourceUrl":"https://a.example",
         "searchUrl":"https://a.example/s?q={{key}}",
         "ruleSearch":{"bookList":"div.book","name":"a@text","bookUrl":"a@href"$ruleSearchExtra}}
    """.trimIndent()

    @Test fun a1_missingFieldReadsEmptyAndDefaults() {
        val s = BookSource.fromJson(json())!!
        assertEquals("", s.checkKeyWord)
        assertEquals("斗罗", s.effectiveCheckKeyWord)
        assertFalse(s.toJson().getJSONObject("ruleSearch").has("checkKeyWord"))
    }

    @Test fun a2_emptyAndNullFallBack() {
        for (extra in listOf(",\"checkKeyWord\":\"\"", ",\"checkKeyWord\":null")) {
            val s = BookSource.fromJson(json(extra))!!
            assertEquals("", s.checkKeyWord)
            assertEquals("斗罗", s.effectiveCheckKeyWord)
            assertFalse(s.toJson().getJSONObject("ruleSearch").has("checkKeyWord"))
        }
    }

    @Test fun a3_blankFallsBackAndIsNotWritten() {
        val s = BookSource.fromJson(json(",\"checkKeyWord\":\"   \""))!!
        assertEquals("", s.checkKeyWord)
        assertEquals("斗罗", s.effectiveCheckKeyWord)
        assertFalse(s.toJson().getJSONObject("ruleSearch").has("checkKeyWord"))
    }

    @Test fun a4_customKeywordUsed() {
        val s = BookSource.fromJson(json(",\"checkKeyWord\":\"凡人修仙传\""))!!
        assertEquals("凡人修仙传", s.checkKeyWord)
        assertEquals("凡人修仙传", s.effectiveCheckKeyWord)
    }

    @Test fun a5_editAndSaveUpdatesJson() {
        val s = BookSource.fromJson(json(",\"checkKeyWord\":\"凡人\""))!!.copy(checkKeyWord = "遮天")
        assertEquals("遮天", s.toJson().getJSONObject("ruleSearch").getString("checkKeyWord"))
        val cleared = s.copy(checkKeyWord = "")
        assertFalse(cleared.toJson().getJSONObject("ruleSearch").has("checkKeyWord"))
    }

    @Test fun a6_importExportRoundTrip() {
        val s = BookSource.fromJson(json(",\"checkKeyWord\":\"诡秘之主\""))!!
        val again = BookSource.fromJson(s.toJson().toString())!!
        assertEquals(s, again)
        assertTrue(JSONObject(s.toJson().toString()).getJSONObject("ruleSearch").has("checkKeyWord"))
        val list = BookSource.listFromJson("[" + s.toJson() + "]")
        assertEquals("诡秘之主", list.single().checkKeyWord)
    }
}
