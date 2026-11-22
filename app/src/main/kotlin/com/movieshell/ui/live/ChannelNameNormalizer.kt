package com.movieshell.ui.live

import com.movieshell.data.store.LiveChannel

/**
 * v1.4.16：频道名归一化（纯函数）——同一频道在不同订阅里写法不同
 * （央视频 “CCTV-1HD”、河南电信 “CCTV1”），按归一化 key 聚合才合得上。
 * 宁可漏合、不许误合：只剥白名单质量后缀，4K/8K、+、地域词、语义词一律保留。
 */
private val QUALITY_SUFFIXES = listOf("超高清", "高清", "超清", "标清", "FHD", "UHD", "HD")

/** 全角转半角（全角空格转半角空格，全角 ASCII 减 0xFEE0）。 */
private fun String.toHalfWidth(): String = buildString(length) {
    for (c in this@toHalfWidth) {
        when {
            c == '\u3000' -> append(' ')
            c in '\uFF01'..'\uFF5E' -> append((c.code - 0xFEE0).toChar())
            else -> append(c)
        }
    }
}

/** 去全部空白与 -_·. 分隔符（不去 +、&、/ 等有意义区别符）。 */
private fun String.stripBlanksAndSeparators(): String = buildString(length) {
    for (c in this@stripBlanksAndSeparators) {
        if (!c.isWhitespace() && c != '-' && c != '_' && c != '·' && c != '.') append(c)
    }
}

/**
 * 频道归一化 key：转大写、全角转半角、去全部空白与 -_·. 分隔符、
 * 只剥尾部白名单质量后缀（循环剥）、数字前导零归一。
 * 对照：CCTV-1HD 与 CCTV1 都归一成 CCTV1；CCTV4欧洲、CCTV4美洲与 CCTV4
 * 各自独立；CCTV5+ 与 CCTV5 独立；CCTV-4K 归一成 CCTV4K，不与 CCTV4 合。
 */
internal fun normalizeChannelKey(name: String): String {
    var key = name.toHalfWidth().stripBlanksAndSeparators().uppercase()
    // 只剥尾部白名单质量后缀，循环剥（叠写如 CCTV1HD高清）；剥完不许空。
    var stripped: Boolean
    do {
        stripped = false
        for (suffix in QUALITY_SUFFIXES) {
            if (key.length > suffix.length && key.endsWith(suffix)) {
                key = key.dropLast(suffix.length)
                stripped = true
                break
            }
        }
    } while (stripped)
    // 数字前导零归一：CCTV01 → CCTV1；CCTV10 仍是 CCTV10。
    return Regex("\\d+").replace(key) { it.value.trimStart('0').ifEmpty { "0" } }
}

/**
 * 归一化 key 下中列显示的名字：短名优先（无后缀无分隔符的变体，
 * 如 CCTV1 优于 CCTV-1HD），同级取首次出现；没候选时回 key 本身。
 */
internal fun displayNameForKey(key: String, channels: List<LiveChannel>): String {
    var best: LiveChannel? = null
    var bestScore = Int.MAX_VALUE
    for (ch in channels) {
        if (normalizeChannelKey(ch.name) != key) continue
        val score = ch.name.toHalfWidth().stripBlanksAndSeparators().length
        if (score < bestScore) {
            best = ch
            bestScore = score
        }
    }
    return best?.name ?: key
}
