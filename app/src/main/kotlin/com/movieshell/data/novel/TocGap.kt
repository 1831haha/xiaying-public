package com.movieshell.data.novel

/**
 * v1.4.27 P0.3：长书目录“缺中段”检测（纯函数，可单测）。
 *
 * 已知现象：部分站（如全本小说网）的目录页只直出头尾若干章（约 45–47 章），中段（上千章）要点
 * “展开全部”由网页脚本加载。改版前这种目录原样入架，用户只看到“共 47 章”，不知道缺章。
 *
 * 判定口径（宁可漏报不误报）：
 * - 从章名解析章号：「第N章/回/节/话/集」（阿拉伯或中文数字）或行首阿拉伯数字；
 * - 至少 [MIN_NUMBERED] 个章名能解析出章号，且占比不低于 [MIN_NUMBERED_RATIO]；
 * - 相邻两个有号章节之间向前跳号，缺的章数 ≥ [MIN_MISSING] 才算缺段（取跳得最大的一处）；
 * - 章号回退（分卷重新从第一章起）多于 [MAX_BACKWARD_RATIO] 的书不判（分卷书章号不连续是常态）。
 * 只报“可能不全”，不臆测缺的章名。
 */
object TocGap {

    const val MIN_NUMBERED = 10
    const val MIN_NUMBERED_RATIO = 0.6
    const val MIN_MISSING = 50
    const val MAX_BACKWARD_RATIO = 0.1

    /** 缺段：第 [afterIndex] 个条目（0 起，章号 [fromNo]）之后直接跳到章号 [toNo]。 */
    data class Gap(val afterIndex: Int, val fromNo: Int, val toNo: Int) {
        val missing: Int get() = toNo - fromNo - 1
    }

    private val CHAPTER_NO = Regex("第\\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千万]+)\\s*[章回节話话集]")
    private val LEADING_NO = Regex("^\\s*([0-9]+)(?:[\\s.、:：_\\-]|$)")

    /** 从章名解析章号；解析不出返回 null。 */
    fun chapterNumber(title: String): Int? {
        CHAPTER_NO.find(title)?.let { m ->
            val raw = m.groupValues[1]
            return if (raw[0].isDigit()) toAsciiDigits(raw).toIntOrNull() else chineseToInt(raw)
        }
        return LEADING_NO.find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun detect(titles: List<String>): Gap? {
        if (titles.size < MIN_NUMBERED) return null
        val numbered = titles.mapIndexedNotNull { i, t -> chapterNumber(t)?.let { i to it } }
        if (numbered.size < MIN_NUMBERED || numbered.size < titles.size * MIN_NUMBERED_RATIO) return null
        var backward = 0
        var best: Gap? = null
        for (k in 1 until numbered.size) {
            val (pi, pn) = numbered[k - 1]
            val (_, cn) = numbered[k]
            if (cn < pn) { backward++; continue }
            val g = Gap(pi, pn, cn)
            if (g.missing >= MIN_MISSING && (best == null || g.missing > best.missing)) best = g
        }
        if (backward > (numbered.size - 1) * MAX_BACKWARD_RATIO) return null
        return best
    }

    private fun toAsciiDigits(s: String) = buildString {
        s.forEach { c -> append(if (c in '０'..'９') ('0' + (c - '０')) else c) }
    }

    /** 中文数字（≤ 九千九百九十九万…，章号够用）；解析不出返回 null。 */
    internal fun chineseToInt(s: String): Int? {
        val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val unit = mapOf('十' to 10, '百' to 100, '千' to 1000)
        // 纯数字串（如“一二三”）按位读
        if (s.all { it in digit }) return s.fold(0) { acc, c -> acc * 10 + digit.getValue(c) }
        var total = 0
        var section = 0
        var num = 0
        for (c in s) {
            when {
                c in digit -> num = digit.getValue(c)
                c in unit -> {
                    section += (if (num == 0) 1 else num) * unit.getValue(c)
                    num = 0
                }
                c == '万' -> {
                    total += (section + num) * 10000
                    section = 0; num = 0
                }
                else -> return null
            }
        }
        return total + section + num
    }
}
