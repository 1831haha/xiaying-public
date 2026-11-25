package com.movieshell.data.source_mgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DrpyPoolKeyTest {

    private val siteKey = "sub:demo"
    private val hosts = setOf("example.com", "api.other.com")

    @Test
    fun sameShaSameHosts_reusesPoolKey() {
        assertEquals(
            drpyPoolKey(siteKey, "aaa", hosts),
            drpyPoolKey(siteKey, "aaa", hosts),
        )
    }

    @Test
    fun differentRuleSha_sameHosts_differentPoolKey() {
        // P1-1 回归：规则改了、白名单没变，旧引擎绝不能复用。
        assertNotEquals(
            drpyPoolKey(siteKey, "aaa", hosts),
            drpyPoolKey(siteKey, "bbb", hosts),
        )
    }

    @Test
    fun sameSha_differentHosts_differentPoolKey() {
        assertNotEquals(
            drpyPoolKey(siteKey, "aaa", hosts),
            drpyPoolKey(siteKey, "aaa", setOf("example.com")),
        )
    }

    @Test
    fun hostOrder_doesNotChangePoolKey() {
        assertEquals(
            drpyPoolKey(siteKey, "aaa", setOf("a.com", "b.com")),
            drpyPoolKey(siteKey, "aaa", setOf("b.com", "a.com")),
        )
    }

    @Test
    fun nullSha_isUnscannedAndDeterministic() {
        val key = drpyPoolKey(siteKey, null, hosts)
        assertEquals(key, drpyPoolKey(siteKey, null, hosts))
        assertNotEquals(key, drpyPoolKey(siteKey, "aaa", hosts))
    }

    @Test
    fun blankSha_isUnscanned() {
        assertEquals(
            drpyPoolKey(siteKey, null, hosts),
            drpyPoolKey(siteKey, "   ", hosts),
        )
    }

    @Test
    fun nullHosts_isDeterministic() {
        val key = drpyPoolKey(siteKey, "aaa", null)
        assertEquals(key, drpyPoolKey(siteKey, "aaa", null))
        assertNotEquals(key, drpyPoolKey(siteKey, "aaa", hosts))
    }

    @Test
    fun fingerprint_orderIndependentAndNoSeparatorCollision() {
        assertEquals(
            whitelistFingerprint(setOf("a.com", "b.com")),
            whitelistFingerprint(setOf("b.com", "a.com")),
        )
        // 逐域名哈希再拼：{"ab"} 与 {"a", "b"} 指纹不同，分隔符拼接会撞。
        assertNotEquals(
            whitelistFingerprint(setOf("ab")),
            whitelistFingerprint(setOf("a", "b")),
        )
    }
}
