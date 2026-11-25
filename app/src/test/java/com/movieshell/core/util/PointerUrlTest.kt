package com.movieshell.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 指针卡片网址提取的纯函数测试。 */
class PointerUrlTest {

    @Test
    fun `url at end of title is extracted`() {
        // 弹幕卡片实证：标题就是地址。
        assertEquals(
            "https://dm.66608880.xyz/87654321",
            PointerUrl.extractFirstUrl("弹幕:https://dm.66608880.xyz/87654321")
        )
    }

    @Test
    fun `url in play address stops at dollar data segment`() {
        // 多仓卡片实证：播放地址是“标题:url$data:标题:url”，只取第一条、到 $ 截断。
        assertEquals(
            "https://movie.66608880.xyz/tvbox/duocang.json",
            PointerUrl.extractFirstUrl(
                "多仓地址：config.json改duocang.json",
                "多仓地址:https://movie.66608880.xyz/tvbox/duocang.json\$data:多仓地址:https://movie.66608880.xyz/tvbox/duocang.json"
            )
        )
    }

    @Test
    fun `title without url falls through to episode url`() {
        assertEquals(
            "https://example.com/list.json",
            PointerUrl.extractFirstUrl("多仓地址", "详情:https://example.com/list.json")
        )
    }

    @Test
    fun `no url returns null`() {
        assertNull(PointerUrl.extractFirstUrl("第1集", "普通标题"))
        assertNull(PointerUrl.extractFirstUrl(null, "", "   "))
        assertNull(PointerUrl.extractFirstUrl())
    }

    @Test
    fun `http scheme works and first url wins`() {
        assertEquals(
            "http://a.example.com/x.json",
            PointerUrl.extractFirstUrl("看这里 http://a.example.com/x.json 还有 https://b.example.com/y.json")
        )
    }

    @Test
    fun `trailing chinese punctuation and paren are trimmed`() {
        assertEquals(
            "https://example.com/a.json",
            PointerUrl.extractFirstUrl("地址：https://example.com/a.json，请点开")
        )
        assertEquals(
            "https://example.com/a.json",
            PointerUrl.extractFirstUrl("(https://example.com/a.json)")
        )
    }

    @Test
    fun `chinese path segment is kept`() {
        assertEquals(
            "https://盒子迷.top/禁止贩卖",
            PointerUrl.extractFirstUrl("地址：https://盒子迷.top/禁止贩卖")
        )
    }

    @Test
    fun `query port and percent encoding are kept`() {
        assertEquals(
            "https://example.com:8080/a.json?x=1&y=2",
            PointerUrl.extractFirstUrl("https://example.com:8080/a.json?x=1&y=2")
        )
        assertEquals(
            "https://example.com/%E4%B8%AD%E6%96%87.json",
            PointerUrl.extractFirstUrl("https://example.com/%E4%B8%AD%E6%96%87.json")
        )
    }

    @Test
    fun `scheme is case insensitive and kept as is`() {
        assertEquals(
            "HTTPS://example.com/a.json",
            PointerUrl.extractFirstUrl("地址：HTTPS://example.com/a.json")
        )
        assertEquals(
            "Http://example.com/a.json",
            PointerUrl.extractFirstUrl("地址：Http://example.com/a.json")
        )
    }

    @Test
    fun `ascii left paren truncates url`() {
        // 网址后跟“(说明)”是标题里的中文注解，不是路径的一部分。
        assertEquals(
            "https://example.com/a.json",
            PointerUrl.extractFirstUrl("见 https://example.com/a.json(说明)")
        )
    }

    @Test
    fun `paren in path truncates at paren by design`() {
        // 取舍：指针卡片的配置/弹幕地址里不会出现括号；`(` 一律当分隔符，
        // 换来“a.json(说明)”这类真实标题能正确截断。
        assertEquals(
            "https://example.com/path_",
            PointerUrl.extractFirstUrl("https://example.com/path_(film)")
        )
    }
}
