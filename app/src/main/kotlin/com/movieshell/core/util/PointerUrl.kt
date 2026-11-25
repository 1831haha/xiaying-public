package com.movieshell.core.util

/**
 * 指针卡片：从标题 / 播放地址里提取第一条 http(s) 网址。
 *
 * 订阅的 PY 规则会返回标题或播放地址里带网址的“指针卡片”（如多仓清单、弹幕服务
 * 地址），点开只会尝试播放然后报“未返回播放地址”；详情页用它把网址抠出来，
 * 给「复制链接」「添加到订阅」用。
 *
 * 通用规则：只认 http(s) scheme，不认站名、域名、卡片名；网址须在文本末尾或
 * 后跟空白、引号、括号、标点或 $ | 等分隔符（播放地址里的 $data: 后缀到此截断）。
 * 局限：网址后直接粘中文字符（如 …json请看）不截断——实际指针卡片的网址后
 * 都跟分隔符或在末尾，中文路径段（如 /禁止贩卖）本身是合法网址内容，不做切分。
 */
object PointerUrl {
    // 网址主体：空白、引号、尖括号、方括号、括号、$、|、反斜杠、中文标点一律截断
    // （$ 是播放地址的段分隔符；中文标点永不是网址字符，中文路径段不受影响。
    // scheme 大小写不敏感；`(` 后是中文说明而不是合法路径——指针卡片的配置/
    // 弹幕地址里不会出现括号，见单测。）
    private val URL_PATTERN = Regex(
        """https?://[^\s"'<>\[\]()$|\\，。；：、！？（）【】《》　]+""",
        RegexOption.IGNORE_CASE
    )
    // 尾部赘余标点：只去末尾，不动网址中间的字符。
    private val TRAILING_JUNK = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}',
        '，', '。', '；', '：', '、', '！', '？', '）', '】', '》',
    )

    /** 按顺序从多段文本里找第一条网址；都没有返回 null。 */
    fun extractFirstUrl(vararg texts: String?): String? {
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val match = URL_PATTERN.find(text) ?: continue
            val url = match.value.trimEnd(*TRAILING_JUNK)
            if (url.isNotBlank()) return url
        }
        return null
    }
}
