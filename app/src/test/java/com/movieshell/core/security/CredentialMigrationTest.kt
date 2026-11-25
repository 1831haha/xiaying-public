package com.movieshell.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.4.21：凭据迁移判定的纯函数测试。
 * 只用假 key（如 "fake-test-key-123"），不碰任何真实凭据、不碰 Android。
 */
class CredentialMigrationTest {

    @Test
    fun `plain value missing needs no migration`() {
        assertEquals(MigrationDecision.SKIP, decideMigration(null, null))
        assertEquals(MigrationDecision.SKIP, decideMigration(null, "fake-test-key-123"))
    }

    @Test
    fun `plain value blank needs no migration`() {
        assertEquals(MigrationDecision.SKIP, decideMigration("", null))
        assertEquals(MigrationDecision.SKIP, decideMigration("   ", null))
        assertEquals(MigrationDecision.SKIP, decideMigration("", "fake-test-key-123"))
    }

    @Test
    fun `plain value present and encrypted missing copies then deletes`() {
        assertEquals(
            MigrationDecision.COPY_THEN_DELETE,
            decideMigration("fake-test-key-123", null),
        )
    }

    @Test
    fun `plain value present and encrypted present with same value only deletes plain copy`() {
        assertEquals(
            MigrationDecision.DELETE_PLAIN_ONLY,
            decideMigration("fake-test-key-123", "fake-test-key-123"),
        )
    }

    @Test
    fun `plain value present and encrypted present with different value overwrites encrypted`() {
        assertEquals(
            MigrationDecision.COPY_THEN_DELETE,
            decideMigration("fake-test-key-123", "fake-test-key-OLD"),
        )
    }

    @Test
    fun `netdisk cookie key is namespaced by dir`() {
        assertEquals("netdisk_cookie_quark", netdiskCookieKey("quark"))
        assertEquals("netdisk_cookie_baidu", netdiskCookieKey("baidu"))
    }

    @Test
    fun `netdisk token key is namespaced by dir`() {
        assertEquals("netdisk_token_aliyun", netdiskTokenKey("aliyun"))
        assertEquals("netdisk_token_xunlei", netdiskTokenKey("xunlei"))
    }
}
