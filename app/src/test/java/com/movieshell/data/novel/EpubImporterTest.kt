package com.movieshell.data.novel

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.4.28 epub 导入：夹具用 ZipOutputStream 在测试内现造，不放 asset 文件。 */
class EpubImporterTest {

    private lateinit var dir: File
    private lateinit var covers: File

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("epub-test-").toFile()
        covers = File(dir, "covers")
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun container(opfPath: String = "OEBPS/content.opf") = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun opf(
        title: String? = "测试书名",
        manifest: String = DEFAULT_MANIFEST,
        spine: String = DEFAULT_SPINE,
        coverMeta: Boolean = true,
    ): String {
        val titleEl = if (title == null) "" else "<dc:title>$title</dc:title>"
        val coverEl = if (coverMeta) """<meta name="cover" content="cover-img"/>""" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    $titleEl
    <dc:creator>测试作者</dc:creator>
    <dc:description>简介文字</dc:description>
    $coverEl
  </metadata>
  <manifest>
$manifest
  </manifest>
  <spine>
$spine
  </spine>
</package>"""
    }

    private fun chapter(title: String?, vararg paras: String): String {
        val titleEl = if (title == null) "" else "<title>$title</title>"
        val body = paras.joinToString("\n") { "<p>$it</p>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head>$titleEl</head><body>$body</body></html>"""
    }

    private fun buildEpub(name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun minimalEntries(
        opfText: String = opf(),
        ch1: String = chapter("第一章 开始", "第一段正文。", "第二段正文。"),
        // 第二章故意用嵌套块：div 里套两个 p——②-R2 的回归点，旧的逐元素 text() 拼法会把这两段各出两次。
        ch2: String = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章 继续</title></head><body><div><p>嵌套段一。</p><p>嵌套段二。</p></div><p>尾段。</p></body></html>""",
    ): Map<String, ByteArray> = mapOf(
        "META-INF/container.xml" to container().toByteArray(Charsets.UTF_8),
        "OEBPS/content.opf" to opfText.toByteArray(Charsets.UTF_8),
        "OEBPS/ch1.xhtml" to ch1.toByteArray(Charsets.UTF_8),
        "OEBPS/ch2.xhtml" to ch2.toByteArray(Charsets.UTF_8),
        "OEBPS/cover.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x01),
    )

    @Test fun minimalEpubRoundtrip() {
        val imported = EpubImporter.import(buildEpub("test.epub", minimalEntries()), covers)
        assertNotNull(imported)
        imported!!
        assertEquals("测试书名", imported.novel.title)
        assertEquals("测试作者", imported.novel.author)
        assertEquals("简介文字", imported.novel.desc)
        assertEquals("local", imported.novel.sourceKey)
        assertEquals(2, imported.novel.totalChapters)
        assertEquals(2, imported.chapters.size)
        assertEquals("第一章 开始", imported.chapters[0].title)
        assertTrue(imported.chapters[0].content.contains("第一段正文。"))
        assertTrue(imported.chapters[0].content.contains("第二段正文。"))
        assertEquals("第二章 继续", imported.chapters[1].title)
        // ②-R2：嵌套块级元素不重复出正文。
        assertEquals(1, imported.chapters[1].content.split("嵌套段一。").size - 1)
        assertEquals(1, imported.chapters[1].content.split("嵌套段二。").size - 1)
        assertEquals(1, imported.chapters[1].content.split("尾段。").size - 1)
        // 封面落盘且 cover 为 file:// 路径。
        val cover = imported.novel.cover
        assertNotNull(cover)
        assertTrue(cover!!.startsWith("file://"))
        val coverFile = File(cover.removePrefix("file://"))
        assertTrue(coverFile.isFile)
        assertEquals(
            listOf(0xFF, 0xD8, 0xFF, 0x00, 0x01),
            coverFile.readBytes().map { it.toInt() and 0xFF },
        )
    }

    @Test fun spineOrderRespected() {
        val manifest = """    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>"""
        val spine = """    <itemref idref="ch3"/>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>"""
        val entries = minimalEntries(opfText = opf(manifest = manifest, spine = spine)) +
            ("OEBPS/ch3.xhtml" to chapter("第三章 末尾", "尾章正文。").toByteArray(Charsets.UTF_8))
        val imported = EpubImporter.import(buildEpub("order.epub", entries), covers)
        assertNotNull(imported)
        assertEquals(
            listOf("第三章 末尾", "第一章 开始", "第二章 继续"),
            imported!!.chapters.map { it.title },
        )
    }

    @Test fun navAndNcxSkipped() {
        val manifest = """    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml" properties="navigation"/>"""
        val spine = """    <itemref idref="ch1"/>
    <itemref idref="nav"/>
    <itemref idref="ncx"/>
    <itemref idref="ch2"/>"""
        val entries = minimalEntries(opfText = opf(manifest = manifest, spine = spine)) +
            mapOf(
                "OEBPS/nav.xhtml" to chapter("目录页", "目录。").toByteArray(Charsets.UTF_8),
                "OEBPS/toc.ncx" to "<ncx/>".toByteArray(Charsets.UTF_8),
            )
        val imported = EpubImporter.import(buildEpub("nav.epub", entries), covers)
        assertNotNull(imported)
        // properties="nav" 与 ncx 不计入章节；properties="navigation" 只是子串，不是 nav token，要计入。
        assertEquals(listOf("第一章 开始", "第二章 继续"), imported!!.chapters.map { it.title })
    }

    @Test fun missingContainerReturnsNull() {
        val entries = minimalEntries().filterKeys { it != "META-INF/container.xml" }
        assertNull(EpubImporter.import(buildEpub("nocontainer.epub", entries), covers))
    }

    @Test fun missingOpfReturnsNull() {
        val entries = minimalEntries().filterKeys { it != "OEBPS/content.opf" }
        assertNull(EpubImporter.import(buildEpub("noopf.epub", entries), covers))
    }

    @Test fun emptySpineReturnsNull() {
        val entries = minimalEntries(opfText = opf(spine = ""))
        assertNull(EpubImporter.import(buildEpub("emptyspine.epub", entries), covers))
    }

    @Test fun titleFallbackToFilename() {
        // 注意：这里故意用 ASCII 文件名——沙箱 JVM 的文件系统编码是 ASCII，
        // ZipFile(File) 碰到中文文件名会抛 InvalidPathException（纯 harness 环境问题，
        // 真机 Android 一律 UTF-8，不影响）；回落逻辑本身只看 nameWithoutExtension。
        val entries = minimalEntries(opfText = opf(title = null))
        val imported = EpubImporter.import(buildEpub("notitle.epub", entries), covers)
        assertNotNull(imported)
        assertEquals("notitle", imported!!.novel.title)
    }

    @Test fun coverExtractedToFile() {
        val imported = EpubImporter.import(buildEpub("cover.epub", minimalEntries()), covers)
        assertNotNull(imported)
        val cover = imported!!.novel.cover
        assertNotNull(cover)
        assertTrue(cover!!.startsWith("file://"))
        assertTrue(File(cover.removePrefix("file://")).isFile)
    }

    @Test fun blankChaptersDropped() {
        val entries = minimalEntries(
            ch1 = chapter("空白章"),
            ch2 = chapter(null, "有内容的章。"),
        )
        val imported = EpubImporter.import(buildEpub("blank.epub", entries), covers)
        assertNotNull(imported)
        // 空章丢弃；“第N章”回落用 spine 序号，不重排——剩下的这章仍是第 2 章。
        assertEquals(1, imported!!.chapters.size)
        assertEquals("第2章", imported.chapters[0].title)
        assertTrue(imported.chapters[0].content.contains("有内容的章。"))
    }

    @Test fun orphanCoverDeletedOnParseFailure() {
        // 封面在、有 spine，但全部章节空白 → 导入失败，且孤儿封面文件不能留下（②-R6）。
        val entries = minimalEntries(
            ch1 = chapter("空白一"),
            ch2 = chapter("空白二"),
        )
        assertNull(EpubImporter.import(buildEpub("orphan.epub", entries), covers))
        assertTrue((covers.listFiles() ?: emptyArray()).isEmpty())
    }

    @Test fun percentEncodedHrefAndParentDirResolved() {
        // ②-R4：href 含 %20（zip 真名是空格）、OPF 在子目录、章节用 ../ 回到上层目录。
        val manifest = """    <item id="ch1" href="my%20chap.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="../text/c2.xhtml" media-type="application/xhtml+xml"/>"""
        val entries = mapOf(
            "META-INF/container.xml" to container().toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to opf(manifest = manifest, coverMeta = false).toByteArray(Charsets.UTF_8),
            "OEBPS/my chap.xhtml" to chapter("空格章", "空格章正文。").toByteArray(Charsets.UTF_8),
            "text/c2.xhtml" to chapter("上层章", "上层章正文。").toByteArray(Charsets.UTF_8),
        )
        val imported = EpubImporter.import(buildEpub("encoded.epub", entries), covers)
        assertNotNull(imported)
        assertEquals(listOf("空格章", "上层章"), imported!!.chapters.map { it.title })
    }

    private companion object {
        const val DEFAULT_MANIFEST = """    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover-img" href="cover.jpg" media-type="image/jpeg"/>"""
        const val DEFAULT_SPINE = """    <itemref idref="ch1"/>
    <itemref idref="ch2"/>"""
    }
}
