package com.movieshell.data.novel

import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.Novel
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.jsoup.Jsoup
import org.w3c.dom.Element

/**
 * v1.4.28 epub 导入（P1），复用 [TxtImporter.Imported] 契约与本地书落盘链路。
 *
 * 解析口径（简报 §2 设计决定 3–5，按 TA 审方案结论 ②-R1/R2/R4/R5/R6 落地）：
 * - container.xml → 第一个 rootfile（media-type 为 application/oebps-package+xml 者优先）；
 * - OPF：dc:title / dc:creator / dc:description；manifest 建 id 表；spine 按 itemref 顺序，
 *   跳 linear="no"、跳 ncx（application/x-dtbncx+xml）、跳 properties 含 nav token 的项；
 * - 章节正文复用 [BookRuleEngine.extractHtmlText]（②-R2：不自己拼 p/div 的 text()，
 *   嵌套元素会正文翻倍；口径带“　　”段首缩进，与在线书源一致、与 TXT 无缩进口径不同）；
 * - 章标题：`<title>` → 第一个 h1/h2 → “第N章”（N 为 spine 序号，从 1 起；空章丢弃后不重排）；
 * - 封面：meta name="cover" → manifest 对应项，字节写到 coversDir/{novelId}.{ext}，
 *   Novel.cover 记 "file://" + 绝对路径；找不到/损坏/写盘失败时 cover = null，不阻塞导入；
 * - ②-R6：封面写盘后若解析失败（只剩本函数返回 null 的路），自删孤儿封面文件；
 * - ②-R4：manifest href 先原样匹配 zip 条目，失败再 URLDecoder 解码一次；OPF 相对路径
 *   （../、子目录）按 OPF 所在目录解析归一化；
 * - ②-R5：EPUB3 properties 是空格分隔的 token 列表，精确匹配 token "nav"，不用 contains。
 *
 * 与简报的两处偏离（TA 审方案 ②-R3 授权“没有 kxml2 则调整用例策略”，见 patch README）：
 * - XML 解析不用 XmlPullParser：纯 JVM harness 里没有任何 XmlPullParser 实现
 *   （harness classpath 无 kxml2；CmsApi 用的 Xml.newPullParser() 是 Android 框架调用，
 *   JVM 单测里是 stub 炸弹），改用 JDK/Android 双内置的 javax.xml.parsers.DocumentBuilder，
 *   不引新依赖，真机与 harness 同一条代码；
 * - zip 访问不用 ZipInputStream：spine 顺序与 zip 内顺序不一致，流式读要把全部条目缓存在内存
 *   里才能按 spine 取章；改用 ZipFile 按条目名随机取，只读用到的条目，同样不一次性读入整个 zip。
 * EPUB 规范即 UTF-8，章节 XHTML 不做 GBK 回退（与 TXT 不同）。
 */
object EpubImporter {

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)

    /**
     * @param coversDir 封面落盘目录（UI 传 File(context.filesDir, "epub_covers")）；null 表示不存封面。
     * @return 解析失败（找不到 container/OPF、spine 为空、全章空白等）返回 null。
     */
    fun import(file: File, coversDir: File?): TxtImporter.Imported? {
        var writtenCover: File? = null
        try {
            val novelId = UUID.randomUUID().toString().replace("-", "")
            ZipFile(file).use { zip ->
                val container = readEntry(zip, "META-INF/container.xml") ?: return null
                val containerDoc = parseXml(container) ?: return null
                val rootfile = containerDoc.documentElement.descendants("rootfile")
                    .firstOrNull { it.getAttribute("media-type") == "application/oebps-package+xml" }
                    ?: containerDoc.documentElement.descendants("rootfile").firstOrNull()
                    ?: return null
                val opfPath = rootfile.getAttribute("full-path").ifBlank { return null }
                val opfBytes = readEntry(zip, opfPath) ?: return null
                val opfDoc = parseXml(opfBytes) ?: return null
                val opfDir = opfPath.substringBeforeLast('/', "")

                val root = opfDoc.documentElement
                val metadata = root.children("metadata").firstOrNull()
                val manifestEl = root.children("manifest").firstOrNull() ?: return null
                val spineEl = root.children("spine").firstOrNull() ?: return null

                val title = metadata?.childText("title")
                    ?: file.nameWithoutExtension.ifBlank { "未知书名" }
                val author = metadata?.childText("creator")
                val desc = metadata?.childText("description")?.take(500)

                val manifest = manifestEl.children("item").mapNotNull { item ->
                    val id = item.getAttribute("id")
                    if (id.isBlank()) null else id to ManifestItem(
                        href = item.getAttribute("href"),
                        mediaType = item.getAttribute("media-type"),
                        properties = item.getAttribute("properties"),
                    )
                }.toMap()
                if (manifest.isEmpty()) return null

                val coverId = metadata?.children("meta")
                    ?.firstOrNull { it.getAttribute("name") == "cover" }
                    ?.getAttribute("content")?.ifBlank { null }

                val chapters = mutableListOf<Chapter>()
                val spineItems = spineEl.children("itemref")
                spineItems.forEachIndexed { spineIndex, ref ->
                    if (ref.getAttribute("linear") == "no") return@forEachIndexed
                    val item = manifest[ref.getAttribute("idref")] ?: return@forEachIndexed
                    if (item.mediaType == "application/x-dtbncx+xml") return@forEachIndexed
                    if (item.properties.split(' ', '\t').any { it == "nav" }) return@forEachIndexed
                    val path = resolveZipPath(opfDir, item.href)
                    val xhtmlBytes = readEntry(zip, path) ?: return@forEachIndexed
                    val xhtml = runCatching { String(xhtmlBytes, Charsets.UTF_8) }.getOrNull()
                        ?: return@forEachIndexed
                    val chapterTitle = chapterTitle(xhtml, spineIndex + 1)
                    val text = BookRuleEngine.extractHtmlText(xhtml, path)
                    if (text.isBlank()) return@forEachIndexed
                    chapters += Chapter(
                        novelId = novelId,
                        index = chapters.size,
                        title = chapterTitle,
                        content = text,
                    )
                }
                if (chapters.isEmpty()) return null

                var cover: String? = null
                val coverItem = coverId?.let { manifest[it] }
                if (coverItem != null && coversDir != null) {
                    val coverBytes = readEntry(zip, resolveZipPath(opfDir, coverItem.href))
                    if (coverBytes != null) {
                        val ext = coverItem.href.substringAfterLast('/')
                            .substringAfterLast('.', "").lowercase()
                            .takeIf { it == "jpg" || it == "png" || it == "webp" } ?: "jpg"
                        cover = runCatching {
                            coversDir.mkdirs()
                            val out = File(coversDir, "$novelId.$ext")
                            out.writeBytes(coverBytes)
                            writtenCover = out
                            "file://" + out.absolutePath
                        }.getOrNull()
                    }
                }

                val novel = Novel(
                    id = novelId,
                    sourceKey = "local",
                    title = title,
                    author = author,
                    cover = cover,
                    desc = desc,
                    totalChapters = chapters.size,
                    addedAt = System.currentTimeMillis(),
                )
                return TxtImporter.Imported(novel, chapters)
            }
        } catch (_: Exception) {
            writtenCover?.delete()
            return null
        }
    }

    /** 章标题：`<title>` → 第一个 h1/h2 → “第N章”（N 为 spine 序号，从 1 起）。 */
    private fun chapterTitle(xhtml: String, spineNumber: Int): String {
        val doc = runCatching { Jsoup.parse(xhtml) }.getOrNull() ?: return "第${spineNumber}章"
        val title = doc.selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isNotBlank()) return title
        val heading = doc.selectFirst("h1, h2")?.text()?.trim().orEmpty()
        if (heading.isNotBlank()) return heading
        return "第${spineNumber}章"
    }

    /** 先原样匹配 zip 条目，失败再 URLDecoder 解码一次（href 里的 %20 与 zip 真名可能不一致）。 */
    private fun readEntry(zip: ZipFile, path: String): ByteArray? {
        val entry = zip.getEntry(path)
            ?: runCatching { zip.getEntry(URLDecoder.decode(path, "UTF-8")) }.getOrNull()
            ?: return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    /** OPF 相对路径按 OPF 所在目录解析归一化（处理 ../ 与子目录；前导 / 视为 zip 根）。 */
    private fun resolveZipPath(baseDir: String, href: String): String {
        val raw = if (baseDir.isEmpty()) href else "$baseDir/$href"
        val parts = ArrayDeque<String>()
        raw.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** XML 解析：禁用外部实体与外部 DTD 加载（防 XXE），但不禁 DOCTYPE——老 EPUB2 的 OPF 常带 DOCTYPE。 */
    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // 不设 isXIncludeAware：Android 真机上该方法抛 UnsupportedOperationException（TA 审码 2026-11-27）；默认即 false，不设等价。
                isExpandEntityReferences = false
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    private fun Element.children(local: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element && n.tagName.substringAfter(':') == local) out += n
        }
        return out
    }

    private fun Element.descendants(local: String): List<Element> {
        val out = mutableListOf<Element>()
        val all = getElementsByTagName("*")
        for (i in 0 until all.length) {
            val n = all.item(i)
            if (n is Element && n.tagName.substringAfter(':') == local) out += n
        }
        return out
    }

    private fun Element.childText(local: String): String? =
        children(local).firstOrNull()?.textContent?.trim()?.ifBlank { null }
}
