package com.movieshell.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v1.4.20：jar 地址 http 改 https 重试的纯函数测试。 */
class JarSpiderStoreTest {

    @Test
    fun `http jar url upgrades to https keeping host path query`() {
        assertEquals(
            "https://movie.66608880.xyz/tvbox/pkg/%E4%B8%89%E5%90%88%E4%B8%80%E5%AE%8C%E7%BE%8E%E7%89%88/jar/spider.jar",
            JarSpiderStore.toHttpsUrl("http://movie.66608880.xyz/tvbox/pkg/%E4%B8%89%E5%90%88%E4%B8%80%E5%AE%8C%E7%BE%8E%E7%89%88/jar/spider.jar")
        )
        assertEquals(
            "https://cdn.example.com/a.jar?x=1&y=2",
            JarSpiderStore.toHttpsUrl("http://cdn.example.com/a.jar?x=1&y=2")
        )
        // md5 段先由 urlPart 拆掉再升级，不进下载地址。
        assertEquals(
            "https://cdn.example.com/a.jar",
            JarSpiderStore.toHttpsUrl(
                JarSpiderStore.urlPart("http://cdn.example.com/a.jar;md5;0123456789abcdef0123456789abcdef")
            )
        )
    }

    @Test
    fun `explicit port 80 is dropped on upgrade other ports kept`() {
        assertEquals(
            "https://cdn.example.com/a.jar",
            JarSpiderStore.toHttpsUrl("http://cdn.example.com:80/a.jar")
        )
        assertEquals(
            "https://cdn.example.com:8080/a.jar",
            JarSpiderStore.toHttpsUrl("http://cdn.example.com:8080/a.jar")
        )
    }

    @Test
    fun `non http url does not upgrade`() {
        assertNull(JarSpiderStore.toHttpsUrl("https://cdn.example.com/a.jar"))
        assertNull(JarSpiderStore.toHttpsUrl("not a url"))
        assertNull(JarSpiderStore.toHttpsUrl(""))
    }

    @Test
    fun `effective download url never returns the http url`() {
        val http = "http://movie.66608880.xyz/tvbox/pkg/x/jar/spider.jar"
        assertEquals(
            "https://movie.66608880.xyz/tvbox/pkg/x/jar/spider.jar",
            JarSpiderStore.effectiveJarDownloadUrl(http)
        )
        // 升级失败（解析不出）时返回 null：调用方拒绝下载，绝不回落 http。
        assertNull(JarSpiderStore.effectiveJarDownloadUrl("http://"))
        // https 地址原样返回。
        assertEquals(
            "https://cdn.example.com/a.jar",
            JarSpiderStore.effectiveJarDownloadUrl("https://cdn.example.com/a.jar")
        )
    }

    @Test
    fun `zip magic accepts real jar header rejects html landing page`() {
        assertTrue(JarSpiderStore.isJarMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertFalse(JarSpiderStore.isJarMagic("<!DO".toByteArray(Charsets.UTF_8)))
        assertFalse(JarSpiderStore.isJarMagic("{\"sp".toByteArray(Charsets.UTF_8)))
        assertFalse(JarSpiderStore.isJarMagic(byteArrayOf(0x50, 0x4B)))
        assertFalse(JarSpiderStore.isJarMagic(byteArrayOf()))
        // 空 zip 尾记录开头不是合法 jar 入口。
        assertFalse(JarSpiderStore.isJarMagic(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun `file magic check reads first four bytes of file`() {
        val dir = Files.createTempDirectory("jar-magic-test").toFile()
        try {
            val jar = File(dir, "a.jar").apply {
                writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
            }
            assertTrue(JarSpiderStore.fileHasJarMagic(jar))
            val html = File(dir, "b.html").apply { writeText("<!DOCTYPE html>", Charsets.UTF_8) }
            assertFalse(JarSpiderStore.fileHasJarMagic(html))
            val tiny = File(dir, "c.bin").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
            assertFalse(JarSpiderStore.fileHasJarMagic(tiny))
            assertFalse(JarSpiderStore.fileHasJarMagic(File(dir, "missing.jar")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
