package com.movieshell.data.novel

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.95.0 净化替换规则（对标阅读 3.0 净化引擎）。
 *
 * scope 取值：book=单本书 / source=单个书源 / all=全局。
 * target 为 scope!=all 时的目标（bookUrl 或书源 key）。
 */
@Serializable
data class PurifyRule(
    val name: String = "",
    val group: String = "",
    val enable: Boolean = true,
    val scope: String = "all",
    val target: String = "",
    val regex: String = "",
    val replacement: String = "",
    /** v0.102.0：出厂内置规则标记。老数据无此字段时按 false 解码（有默认值）。 */
    val builtin: Boolean = false,
)

/**
 * v0.95.0 独立净化引擎：正文拉取后统一过一遍替换规则，再进缓存/展示。
 * 纯函数，可单元测试；失败的单条规则跳过，不影响其他规则。
 */
object NovelPurifyEngine {

    /** v0.102.0 P2-2：正则预编译缓存。Kotlin Regex 底层 Pattern 线程安全，可跨规则/跨调用共享。 */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun regexOf(pattern: String): Regex =
        regexCache.getOrPut(pattern) { Regex(pattern) }

    /** 正文净化主入口。bookUrl/sourceKey 用于 scope 过滤（可空=不过滤）。 */
    fun purify(
        text: String,
        rules: List<PurifyRule>,
        bookUrl: String = "",
        sourceKey: String = "",
    ): String {
        // v1.2.28：换行符归一（对标阅读 ContentProcessor 的内置步骤，不是用户规则）：
        // 正文里字面量 \r（反斜杠+r）与真实 \r 一律归一成段落分隔。
        // 真机制（黑岩 #破冰字节级实证）：站方 JSON 是标准单层转义，\r 是合法
        // 转义不是双重转义；根因修在 evalJsonRule 的 JSON 路径求值，这里是兜底：
        // 任何残留的字面量/真实换行符都不许进屏幕。
        // v1.4.34 D-P2-7：归一目标从单换行改成双换行——\r / 字面量 \r\n
        // 在原文里就是段落分隔；单换行只留给原文真正的行内换行（诗歌/
        // 对白），收尾的 collapseBlankLines 不再动它。
        // 字面量 \r\n 先换，避免拆成两个换行。
        var out = text
            .replace("\\r\\n", "\n\n")
            .replace("\\r", "\n\n")
            .replace("\\n", "\n\n")
            .replace("\r\n", "\n\n")
            .replace("\r", "\n\n")
        for (r in rules) {
            if (!r.enable || r.regex.isBlank()) continue
            if (!scopeMatch(r, bookUrl, sourceKey)) continue
            // 正则写错时 regexOf 抛异常，被 runCatching 吞掉且不进缓存，不影响其他规则
            out = runCatching { out.replace(regexOf(r.regex), r.replacement) }
                .getOrDefault(out)
        }
        // 收尾：只合并“多余”空行（连续 2 个以上空行 → 1 个），其余版式原样保留。
        // v1.4.34 D-P2-7：原来这里把每行都 trim 后滤掉空行、再以 \n\n + 首行缩进
        // 重排全篇——诗歌/对白的行内单换行全被改写成段落间隔，内置规则
        // “合并多余空行”（\n{3,} → \n\n）也因收尾重排而实际失效。
        // 新口径：先按空行切“块”（段落/诗节），块内单换行原样保留，
        // 块间只留一个空行；缩进只加在每块首行。
        // 注意：\r 归一那步已把 \r / 字面量 \r\n 等换成段落分隔（见上文），
        // 所以这里见到的单 \n 只来自原文真正的行内换行。
        return collapseBlankLines(out)
    }

    /**
     * v1.4.34 D-P2-7（纯函数）：空行折叠 + 块首缩进。
     * - 连续 2 个以上空行压成 1 个（即 3 连以上换行 → 双换行）；
     * - 块内各行只 trim、不重排，单换行保留（诗歌/对白版式）；
     * - 每块首行加全角缩进，后续行不加；
     * - 首尾空行丢掉。
     */
    internal fun collapseBlankLines(text: String): String {
        val lines = text.split("\n")
        val blocks = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (cur.isNotEmpty()) {
                    blocks.add(cur)
                    cur = ArrayList()
                }
            } else {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) blocks.add(cur)
        return blocks.joinToString("\n\n") { block ->
            block.mapIndexed { i, line -> if (i == 0) "　　$line" else line }
                .joinToString("\n")
        }
    }

    private fun scopeMatch(r: PurifyRule, bookUrl: String, sourceKey: String): Boolean =
        when (r.scope) {
            "book" -> r.target.isNotBlank() && r.target == bookUrl
            "source" -> r.target.isNotBlank() && r.target == sourceKey
            // v0.217.0（F47）：fail-closed——原来 else 全放行，scope 写错
            // 一个字母（或外来规则带了未知 scope）就从单书／单源变成全局，
            // 悄悄改掉所有书的正文；只有明确的 all 才全局，未知 scope 不生效。
            "all" -> true
            else -> false
        }

    /** 内置保守默认规则：只处理明显安全的版式问题，广告类默认关闭交由用户按需开启。 */
    fun defaultRules(): List<PurifyRule> = listOf(
        PurifyRule(
            name = "合并多余空行",
            group = "版式",
            regex = "\\n{3,}",
            replacement = "\n\n",
        ),
        PurifyRule(
            name = "去除重复章节标题行",
            group = "版式",
            // extractContent 以 \n\n 分段，重复标题行之间是双换行
            regex = "(?m)^(.{1,40})\n\n\\1$",
            replacement = "$1",
        ),
        PurifyRule(
            name = "去除站长推广语",
            group = "广告",
            regex = "(?m)^.{0,60}(最新章节|最快更新|无弹窗|手机用户请|请记住本站)[^\\n]*$",
            replacement = "",
        ),
    )

    /**
     * v0.102.0 P2-3：内置净化规则库（默认全部开启，builtin=true）。
     * 由 PurifyRuleStore 首次运行时补种，不覆盖用户自定义规则。
     * 正则都锚定行首关键词/行尾，宁可漏杀、不误杀正文。
     */
    fun builtinRules(): List<PurifyRule> = listOf(
        PurifyRule(
            name = "去除断章提示",
            group = "内置",
            builtin = true,
            regex = "(?m)^.{0,80}((本章未完|未完待续).{0,20}(下一页|继续阅读)|点击下一页继续阅读)[^\\n]*$",
            replacement = "",
        ),
        PurifyRule(
            name = "去除求票文案",
            group = "内置",
            builtin = true,
            regex = "(?m)^.{0,80}(喜欢本书(请|的话|就)|求收藏|求推荐|求月票|求订阅|投推荐票|请收藏本书)[^\\n]*$",
            replacement = "",
        ),
        PurifyRule(
            name = "去除推荐阅读",
            group = "内置",
            builtin = true,
            regex = "(?m)^.{0,40}(推荐阅读|相关推荐|猜你喜欢|延伸阅读)[：:][^\\n]*$",
            replacement = "",
        ),
        PurifyRule(
            name = "剥离残留 HTML 标签",
            group = "内置",
            builtin = true,
            // 标签名必须以字母 / ! 开头，避免误杀 "3 < 5" 这类数学比较
            regex = "</?[a-zA-Z!][^<>\\n]{0,119}>",
            replacement = "",
        ),
    )
}
