package com.movieshell.core.util

import java.util.Base64

/**
 * base64 信封解包（1.4.24，wmz）。
 *
 * 有些订阅把整段 JSON 用 base64 包了一层再下发，形如 `base64**<base64 JSON>`，
 * 其中 `base64` 标记本身也是 base64 编码（`YmFzZTY0`），整段前面还可能带换行；
 * 明文写 `base64**` 的也认。命中信封返回解出的 JSON；不命中、解码失败、
 * 解出物不是 JSON，一律原样返回，正常订阅不受影响。
 * 只认 JSON 体（解出物须以 `{` 开头）；信封里包 TXT 多仓清单的不解。
 *
 * 用 java.util.Base64（minSdk 26 可用，JVM 单测也不撞 Stub）。
 * 纯函数，可单测。
 */
object Base64Envelope {

    private val BASE64_ALPHABET = Regex("^[A-Za-z0-9+/=\\s]+$")

    fun unwrap(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF")
        val sep = text.indexOf("**")
        if (sep <= 0) return raw
        val head = text.substring(0, sep).trim()
        val body = text.substring(sep + 2).trim()
        if (head.isEmpty() || body.isEmpty()) return raw
        if (!BASE64_ALPHABET.matches(head) || !BASE64_ALPHABET.matches(body)) return raw
        val marker = runCatching {
            String(Base64.getMimeDecoder().decode(head), Charsets.UTF_8).trim()
        }.getOrNull()
        if (marker != "base64" && head != "base64") return raw
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(body), Charsets.UTF_8)
        }.getOrNull() ?: return raw
        val clean = decoded.trim().removePrefix("\uFEFF")
        return if (clean.startsWith("{")) clean else raw
    }
}
