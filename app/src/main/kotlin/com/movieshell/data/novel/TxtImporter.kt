package com.movieshell.data.novel

import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.Novel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * v1.4.34 B-P1-4：流式限量读——分块读、超上限返回 null，绝不一次性无上限
 * 读入内存；File.length() 快筛不可信（文件可增长/谎报），流内计数为准。
 * 调用方把 null 当导入失败处理。EpubImporter 同包复用。
 */
internal fun readBytesCapped(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * v0.35.0 TXT 小说导入。
 *
 * - 章节切分：按行逐条试内置标题模式表（第X章/节/卷、Chapter N、卷X、
 *   楔子/序章/番外等、数字序号“1、xxx”），命中且行短（≤40 字）才算标题；
 * - 第一章之前的文字算简介（desc）；
 * - 一个都匹配不到：整本算一章，标题=书名；
 * - 书名=文件名去扩展名；id=随机 UUID。
 *
 * 编码：先试 UTF-8，失败换 GBK（中文 TXT 常见）。
 */
object TxtImporter {

    // v1.1.3：单条正则换成标题模式表——原先只认“第X章”，
    // Chapter N、卷、楔子/番外、数字序号的 TXT 全被算成一章。
    // 数字序号要求数字后跟顿号/点/空格，避免正文“2026年”这类行误切。
    // v1.4.34 D-P2-6：数字序号再加两条收紧——①后头必须跟非空内容
    // （“1 ”这种裸序号不算标题）；②日期行先判出局（见 isChapterTitle
    // 的 DATE_LIKE 守门：“2023 年 5 月”这种行数字后跟空格也会撞
    // 上数字序号模式，必须先拦）。
    private val chapterPatterns = listOf(
        Regex("^第[一二三四五六七八九十百千万零〇两0-9]+[章节回集卷]"),
        Regex("^Chapter\\s*[0-9]+", RegexOption.IGNORE_CASE),
        Regex("^卷[一二三四五六七八九十百千万0-9]+"),
        Regex("^(楔子|序章|序言|前言|引子|番外|后记|尾声|终章)"),
        Regex("^[0-9]+([、．]|\\.\\s|\\s)\\S"),
    )

    // v1.4.34 B-P1-4：TXT 上限 64MB——超大文件直接拒，不再 readBytes 整本进内存
    // （数百 MB 文本在低端机直接 OOM）；64MB 已远超常规小说体积。
    private const val MAX_TXT_BYTES = 64L * 1024 * 1024

    /** v1.4.34 D-P2-6：日期行（2023年 / 2023 年 5 月 / 12月3日）永远不是章标题。 */
    private val dateLikePattern = Regex("^[0-9]{1,4}\\s*[年月日]")

    private fun isChapterTitle(line: String): Boolean {
        val t = line.trimStart()
        if (dateLikePattern.containsMatchIn(t)) return false
        return chapterPatterns.any { it.containsMatchIn(t) }
    }

    data class Imported(
        val novel: Novel,
        val chapters: List<Chapter>,
        /** v1.4.34 D-P2-6：导入时丢信息的提示（简介超长截断 / 疑似标题行过长未切分），
         * 调用方展示给用户；无截断时为空。 */
        val warnings: List<String> = emptyList(),
    )

    fun import(file: File): Imported? {
        val text = readTextSmart(file) ?: return null
        if (text.isBlank()) return null
        val title = file.nameWithoutExtension.ifBlank { "未知书名" }
        val novelId = UUID.randomUUID().toString().replace("-", "")

        val chapters = mutableListOf<Chapter>()
        var descBuilder = StringBuilder()
        var curTitle: String? = null
        var curBody = StringBuilder()
        // v1.4.34 D-P2-6：截断计数——简介超 2000 字、疑似标题行超 40 字时
        // 导入结果里带提示，不再静默丢信息。
        var descTruncated = false
        var longTitleLines = 0

        fun flush() {
            if (curTitle != null) {
                chapters += Chapter(
                    novelId = novelId,
                    index = chapters.size,
                    title = curTitle!!.trim(),
                    content = curBody.toString().trim(),
                )
            }
            curBody = StringBuilder()
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            // v0.215.0：首章与后续章同口径——标题行都要求短（≤40 字），
            // 否则正文里一句“第一章……”也会把首章切错位置。
            // v1.4.34 D-P2-6：超长疑似标题行只计数、不切分，提示里汇总一次。
            val looksTitle = isChapterTitle(line)
            val titleLen = line.trim().length
            if (looksTitle && titleLen > 40) longTitleLines++
            if (curTitle == null && looksTitle && titleLen <= 40) {
                // 第一章标题出现：之前攒的是简介
                curTitle = line.trim()
            } else if (curTitle != null && looksTitle && titleLen <= 40) {
                // 新章节（标题行一般很短，避免正文里"第一章"误切）
                flush()
                curTitle = line.trim()
            } else {
                if (curTitle == null) {
                    // v1.4.34 D-P2-6：简介上限 2000 字按实长截断——原来只看
                    // 累计长度，单行超长简介（3000 字一行）会整行塞进来、永不截断。
                    if (descBuilder.length < 2000) {
                        val room = 2000 - descBuilder.length
                        if (line.length <= room) descBuilder.appendLine(line)
                        else {
                            descBuilder.appendLine(line.take(room))
                            descTruncated = true
                        }
                    } else descTruncated = true
                } else {
                    curBody.appendLine(line)
                }
            }
        }
        flush()

        // 一章没切出来：整本一章
        val finalChapters = if (chapters.isEmpty()) {
            listOf(Chapter(novelId = novelId, index = 0, title = title, content = text.trim()))
        } else chapters

        val desc = descBuilder.toString().trim().take(500).ifBlank { null }
        val novel = Novel(
            id = novelId,
            sourceKey = "local",
            title = title,
            author = null,
            cover = null,
            desc = desc,
            totalChapters = finalChapters.size,
            addedAt = System.currentTimeMillis(),
        )
        val warnings = buildList {
            if (descTruncated) add("简介超长，已截断")
            if (longTitleLines > 0) add("有 $longTitleLines 行疑似章节标题过长（超 40 字），未切分章节")
        }
        return Imported(novel, finalChapters, warnings)
    }

    private fun readTextSmart(file: File): String? {
        // v1.4.34 B-P1-4：先按长度快筛、再流式限量读（双保险）。
        if (file.length() > MAX_TXT_BYTES) return null
        val bytes = runCatching {
            file.inputStream().buffered().use { readBytesCapped(it, MAX_TXT_BYTES) }
        }.getOrNull() ?: return null
        // v0.215.0：旧写法 file.readText(UTF_8) 遇到非法字节从不抛异常，
        // 只会塞替换字符硬读，GBK 回退是死代码——GBK 中文 TXT 全读成乱码。
        // 改严格解码：UTF-8 非法才落 GBK。
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8 = runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        if (!utf8.isNullOrBlank()) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
