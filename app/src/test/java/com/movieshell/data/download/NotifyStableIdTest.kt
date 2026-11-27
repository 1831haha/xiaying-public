package com.movieshell.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v1.4.34 C 组 P2-01：通知 ID 不再用 String.hashCode()——同一 key 在任何进程
 * 永远拿同一 ID（同片更新覆盖旧通知），不同 key 拿不同 ID，空串也有自己的 ID。
 * v1.4.34 修复轮：确定性 SHA-256 ID，跨进程稳定（见 stableNotificationId）。
 */
class NotifyStableIdTest {

    @Test
    fun `same key returns same id`() {
        val a = Notify.stableNotificationId("follow:test-same-key-1")
        val b = Notify.stableNotificationId("follow:test-same-key-1")
        assertEquals(a, b)
    }

    @Test
    fun `different keys return different ids`() {
        val a = Notify.stableNotificationId("follow:test-diff-a-1")
        val b = Notify.stableNotificationId("follow:test-diff-b-1")
        assertNotEquals(a, b)
    }

    @Test
    fun `empty vodId does not collide with another empty key`() {
        // 旧实现：vodId 空串时 hashCode 全是 0，所有空 vodId 通知互相覆盖。
        val emptyFollow = Notify.stableNotificationId("follow:")
        val emptyDownload = Notify.stableNotificationId("download:")
        val otherFollow = Notify.stableNotificationId("follow:test-empty-other-1")
        assertNotEquals(emptyFollow, emptyDownload)
        assertNotEquals(emptyFollow, otherFollow)
        assertEquals(emptyFollow, Notify.stableNotificationId("follow:"))
    }

    @Test
    fun `follow and download namespaces do not share ids`() {
        val a = Notify.stableNotificationId("follow:test-ns-1")
        val b = Notify.stableNotificationId("download:test-ns-1")
        assertNotEquals(a, b)
    }
}
