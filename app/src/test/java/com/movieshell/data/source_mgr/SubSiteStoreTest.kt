package com.movieshell.data.source_mgr

import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 订阅 UA 嗅探重试纯函数测试（v1.4.18）。 */
class SubSiteStoreTest {

    @Test
    fun `looksLikeHtml_doctype_isHtml`() {
        assertTrue(SubSiteStore.looksLikeHtml("<!DOCTYPE html><html lang=\"zh-CN\">"))
    }

    @Test
    fun `looksLikeHtml_plainHtmlTag_isHtml`() {
        assertTrue(SubSiteStore.looksLikeHtml("<html><head></head></html>"))
    }

    @Test
    fun `looksLikeHtml_leadingWhitespace_isHtml`() {
        assertTrue(SubSiteStore.looksLikeHtml("  \n<!doctype html>"))
    }

    @Test
    fun `looksLikeHtml_bomPrefix_isHtml`() {
        assertTrue(SubSiteStore.looksLikeHtml("\uFEFF<!DOCTYPE html>"))
    }

    @Test
    fun `looksLikeHtml_bomPlusWhitespace_isHtml`() {
        assertTrue(SubSiteStore.looksLikeHtml("\uFEFF  \n<!DOCTYPE html>"))
        assertTrue(SubSiteStore.looksLikeHtml("\uFEFF\uFEFF<!DOCTYPE html>"))
    }

    @Test
    fun `looksLikeHtml_realLandingPageHead_isHtml`() {
        assertTrue(
            SubSiteStore.looksLikeHtml(
                "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">",
            ),
        )
    }

    @Test
    fun `looksLikeHtml_jsonConfig_isNotHtml`() {
        assertFalse(SubSiteStore.looksLikeHtml("{\"sites\": [{\"key\": \"a\"}]}"))
    }

    @Test
    fun `looksLikeHtml_jsonWithLeadingWhitespace_isNotHtml`() {
        assertFalse(SubSiteStore.looksLikeHtml("  \n {\"sites\": []}"))
    }

    @Test
    fun `looksLikeHtml_base64Wrapped_isNotHtml`() {
        assertFalse(SubSiteStore.looksLikeHtml("YmFzZTY0**eyJzaXRlcyI6IFt"))
    }

    @Test
    fun `looksLikeHtml_txtWarehouse_isNotHtml`() {
        assertFalse(SubSiteStore.looksLikeHtml("https://example.com/sub.json"))
        assertFalse(SubSiteStore.looksLikeHtml("央视,https://example.com/sub.json"))
    }

    @Test
    fun `looksLikeHtml_empty_isNotHtml`() {
        assertFalse(SubSiteStore.looksLikeHtml(""))
        assertFalse(SubSiteStore.looksLikeHtml("   "))
    }

    @Test
    fun `retryUa_isOkhttpStyle`() {
        assertTrue(SubSiteStore.SUB_RETRY_UA.startsWith("okhttp/"))
        // 锁派生关系：改动常量写法（而非升级依赖）时这里会先失败
        assertEquals("okhttp/${OkHttp.VERSION}", SubSiteStore.SUB_RETRY_UA)
    }
}
