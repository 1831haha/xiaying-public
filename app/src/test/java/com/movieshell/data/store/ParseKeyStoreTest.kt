package com.movieshell.data.store

import com.movieshell.domain.model.ParseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.4.22（轨道 5）：解析线路密钥纯函数测试。不碰 Android 存储；
 * 旧地址形状的用例 key 一律用 FAKEKEY123 之类的假值，真实旧 key
 * 不出现在代码与单测里。
 */
class ParseKeyStoreTest {

    private val template = "http://sspa8.top:8100/api/?key={key}&url="
    private val legacy = "http://sspa8.top:8100/api/?key=FAKEKEY123&url="

    @Test
    fun needsKey_placeholderOnly() {
        assertTrue(ParseKeyStore.needsKey(template))
        assertFalse(ParseKeyStore.needsKey("https://www.huaqi.live/?url="))
        assertFalse(ParseKeyStore.needsKey(""))
    }

    @Test
    fun substituteKey_encodesKey() {
        assertEquals(
            "http://sspa8.top:8100/api/?key=ab%2Bcd%2Fef&url=",
            ParseKeyStore.substituteKey(template, "ab+cd/ef"),
        )
    }

    @Test
    fun substituteKey_noPlaceholderReturnsTemplate() {
        val plain = "https://www.huaqi.live/?url="
        assertEquals(plain, ParseKeyStore.substituteKey(plain, "whatever"))
    }

    @Test
    fun keySlot_stableAndDistinct() {
        val a = ParseKeyStore.keySlot(template)
        assertEquals(a, ParseKeyStore.keySlot(template))
        assertEquals(a, ParseKeyStore.keySlot("  $template  "))
        assertTrue(a.startsWith("parse_key_"))
        assertEquals("parse_key_".length + 16, a.length)
        assertTrue(ParseKeyStore.keySlot("https://www.huaqi.live/?url=") != a)
    }

    @Test
    fun extractLegacyKey_fakeKey() {
        assertEquals("FAKEKEY123", ParseKeyStore.extractLegacyKey(legacy))
        assertEquals(
            "FAKEKEY123",
            ParseKeyStore.extractLegacyKey("http://sspa8.top:8100/api/?key=FAKEKEY123&url=&foo=1"),
        )
    }

    @Test
    fun extractLegacyKey_rejectsNonLegacy() {
        assertNull(ParseKeyStore.extractLegacyKey("https://www.huaqi.live/?url="))
        assertNull(ParseKeyStore.extractLegacyKey("http://sspa8.top:8100/api/?url="))
        assertNull(ParseKeyStore.extractLegacyKey("http://sspa8.top:8100/api/?key=&url="))
        assertNull(ParseKeyStore.extractLegacyKey("http://sspa8.top:8100/api/?key=A B&url="))
    }

    @Test
    fun toTemplate_rewritesLegacyShapeOnly() {
        assertEquals(template, ParseKeyStore.toTemplate(legacy))
        assertEquals(
            "http://sspa8.top:8100/api/?key={key}&url=&foo=1",
            ParseKeyStore.toTemplate("http://sspa8.top:8100/api/?key=FAKEKEY123&url=&foo=1"),
        )
        assertNull(ParseKeyStore.toTemplate("https://www.huaqi.live/?url="))
        assertNull(ParseKeyStore.toTemplate("http://sspa8.top:8100/api/?url="))
    }

    @Test
    fun migrateLegacyKeys_movesKeyAndRewritesUrl() {
        val plain = ParseConfig(name = "W3", url = "https://yparse.ik9.cc/index.php?url=", type = 0)
        val written = mutableMapOf<String, String>()
        val out = ParseStore.migrateLegacyKeys(
            listOf(ParseConfig(name = "J2", url = legacy, type = 1), plain),
            readKey = { "" },
            writeKey = { t, k -> written[t] = k },
        )
        assertEquals(template, out[0].url)
        assertEquals("J2", out[0].name)
        assertEquals("FAKEKEY123", written[template])
        assertEquals(plain, out[1])
    }

    @Test
    fun migrateLegacyKeys_doesNotOverwriteExistingKey() {
        val written = mutableMapOf<String, String>()
        val out = ParseStore.migrateLegacyKeys(
            listOf(ParseConfig(name = "J2", url = legacy, type = 1)),
            readKey = { "ALREADY-THERE" },
            writeKey = { t, k -> written[t] = k },
        )
        assertEquals(template, out[0].url)
        assertTrue(written.isEmpty())
    }

    @Test
    fun migrateLegacyKeys_emptyKeyStillRewritesUrlWithoutWriting() {
        val written = mutableMapOf<String, String>()
        val out = ParseStore.migrateLegacyKeys(
            listOf(ParseConfig(name = "J2", url = "http://sspa8.top:8100/api/?key=&url=", type = 1)),
            readKey = { "" },
            writeKey = { t, k -> written[t] = k },
        )
        assertEquals(template, out[0].url)
        assertTrue(written.isEmpty())
    }

    @Test
    fun migrateLegacyKeys_noLegacy_returnsSameList() {
        val list = listOf(ParseConfig(name = "W3", url = "https://yparse.ik9.cc/index.php?url=", type = 0))
        assertSame(
            list,
            ParseStore.migrateLegacyKeys(list, readKey = { "" }, writeKey = { _, _ -> fail("不应写入") }),
        )
    }
}
