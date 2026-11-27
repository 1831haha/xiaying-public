package com.movieshell.data.novel

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * v1.4.34 B-P1-4：TXT/EPUB 导入限量读——超大文件不再整本进内存。
 */
class NovelImportLimitsTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("import-limits-").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun readBytesCapped_underCapReadsFully() {
        val bytes = readBytesCapped(ByteArrayInputStream("hello".toByteArray()), 1024)
        assertEquals("hello", bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun readBytesCapped_overCapReturnsNull() {
        val big = ByteArray(2048) { 65 }
        assertNull(readBytesCapped(ByteArrayInputStream(big), 1024))
    }

    @Test
    fun readBytesCapped_exactCapReadsFully() {
        val bytes = ByteArray(1024) { 65 }
        assertNotNull(readBytesCapped(ByteArrayInputStream(bytes), 1024))
    }

    @Test
    fun txtImporter_rejectsOversizedFile() {
        // 稀疏文件：length 超 64MB 上限，但不实际占盘；导入应直接拒，不读它。
        val f = File(dir, "huge.txt")
        RandomAccessFile(f, "rw").use { it.setLength(65L * 1024 * 1024) }
        assertNull(TxtImporter.import(f))
    }

    @Test
    fun txtImporter_smallFileStillImports() {
        val f = File(dir, "small.txt")
        f.writeText("第1章 开始\n正文第一行。\n第2章 继续\n正文第二行。\n", Charsets.UTF_8)
        val imported = TxtImporter.import(f)
        assertNotNull(imported)
        assertEquals(2, imported!!.chapters.size)
    }
}
