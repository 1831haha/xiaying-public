package com.movieshell.data.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NovelSourceTrashTest {

    private fun tmp(): File = Files.createTempDirectory("trash").toFile()

    @Test
    fun putJson_roundtrip_and_order() {
        val dir = tmp()
        val t = NovelSourceTrash(File(dir, "bin"))
        val a = t.putJson("A源", "a.com", """{"bookSourceName":"A源"}""", now = 1000)!!
        val b = t.putJson("B源", "b.com", "{}", now = 2000)!!
        assertEquals(listOf(b.id, a.id), t.list().map { it.id })
        assertEquals("""{"bookSourceName":"A源"}""", t.payloadOf(a)!!.readText())
        // 新实例（模拟重启）读回同样内容
        val t2 = NovelSourceTrash(File(dir, "bin"))
        val back = t2.list().first { it.id == a.id }
        assertEquals(NovelSourceTrash.KIND_JSON, back.kind)
        assertEquals("a.com", back.originKey)
        assertEquals("A源", back.title)
    }

    @Test
    fun putPy_copies_file_keeps_enabled_and_original_untouched() {
        val dir = tmp()
        val src = File(dir, "x.py").apply { writeText("def bookSearch(): pass") }
        val t = NovelSourceTrash(File(dir, "bin"))
        val e = t.putPy("x", "x.py", src, enabled = true)!!
        assertTrue("进回收站只拷副本，真删由调用方做", src.isFile)
        assertEquals("def bookSearch(): pass", t.payloadOf(e)!!.readText())
        assertTrue(t.list().single().enabled)
        assertNull(t.putPy("y", "y.py", File(dir, "missing.py"), enabled = false))
        assertEquals(1, t.count())
    }

    @Test
    fun drop_removes_entry_and_payload() {
        val t = NovelSourceTrash(File(tmp(), "bin"))
        val e = t.putJson("A", "a", "{}")!!
        val pf = t.payloadOf(e)!!
        assertTrue(t.drop(e.id))
        assertFalse(pf.exists())
        assertEquals(0, t.count())
        assertNull(t.payloadOf(e))
        assertTrue("不存在的 id 视为已删", t.drop("nope"))
    }

    @Test
    fun clear_counts_and_empties() {
        val t = NovelSourceTrash(File(tmp(), "bin"))
        assertEquals(0, t.clear())
        t.putJson("A", "a", "{}"); t.putJson("B", "b", "{}")
        assertEquals(2, t.clear())
        assertEquals(0, t.count())
    }

    @Test
    fun corrupt_index_reads_empty_and_unknown_kind_skipped() {
        val bin = File(tmp(), "bin").apply { mkdirs() }
        File(bin, "index.json").writeText("not json")
        assertEquals(0, NovelSourceTrash(bin).count())
        File(bin, "index.json").writeText(
            """[{"id":"1","kind":"weird","originKey":"k"},{"id":"2","kind":"json","originKey":"k","title":"T","deletedAt":5}]"""
        )
        val l = NovelSourceTrash(bin).list()
        assertEquals(listOf("2"), l.map { it.id })
        assertNotNull(l.single())
    }
}
