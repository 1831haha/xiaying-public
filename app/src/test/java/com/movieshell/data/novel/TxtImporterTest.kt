package com.movieshell.data.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.4.34 D-P2-6：TXT 分章判定收紧 + 截断提示。 */
class TxtImporterTest {

    private fun importText(text: String): TxtImporter.Imported? {
        val f = File.createTempFile("txt-import-test", ".txt")
        try {
            f.writeText(text, Charsets.UTF_8)
            return TxtImporter.import(f)
        } finally {
            f.delete()
        }
    }

    @Test fun `date line 2023 year is not a chapter`() {
        val imported = importText(
            "第一章 开篇\n正文第一行\n2023年发生了很多事\n正文第二行\n第二章 继续\n正文第三行\n",
        )!!
        assertEquals(listOf("第一章 开篇", "第二章 继续"), imported.chapters.map { it.title })
    }

    @Test fun `date line with space 2023 year month is not a chapter`() {
        val imported = importText(
            "第一章 开篇\n正文第一行\n2023 年 5 月发生的事\n正文第二行\n第二章 继续\n正文第三行\n",
        )!!
        assertEquals(listOf("第一章 开篇", "第二章 继续"), imported.chapters.map { it.title })
    }

    @Test fun `numbered chapter still splits`() {
        val imported = importText(
            "1、开篇\n正文\n2、继续\n正文\n",
        )!!
        assertEquals(listOf("1、开篇", "2、继续"), imported.chapters.map { it.title })
    }

    @Test fun `bare number line is not a chapter`() {
        val imported = importText("第一章 开篇\n1 \n正文\n第二章 继续\n正文\n")!!
        assertEquals(listOf("第一章 开篇", "第二章 继续"), imported.chapters.map { it.title })
    }

    @Test fun `overlong title like line warns instead of splitting`() {
        val longTitle = "第一章 " + "很长很长的标题".repeat(10)
        val imported = importText("$longTitle\n正文第一行\n第二章 短标题\n正文第二行\n")!!
        assertEquals(listOf("第二章 短标题"), imported.chapters.map { it.title })
        assertTrue(imported.warnings.any { it.contains("疑似章节标题过长") })
    }

    @Test fun `overlong intro warns`() {
        val intro = "简介".repeat(1500)
        val imported = importText("$intro\n第一章 开篇\n正文\n")!!
        assertEquals(1, imported.chapters.size)
        assertTrue(imported.warnings.any { it.contains("简介超长") })
    }
}
