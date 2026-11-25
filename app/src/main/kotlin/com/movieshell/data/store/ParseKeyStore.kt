package com.movieshell.data.store

import android.content.Context
import com.movieshell.core.security.SecurePrefs
import java.net.URLEncoder

/**
 * v1.4.22（轨道 5）：解析线路密钥。
 *
 * 背景：8 条 WorkBuddy 点名的预装解析线路里，只有 J2（sspa8.top:8100）真正带
 * 硬编码 key（地址里 `?key=…&url=` 那串固定值，本文本及代码其它位置均不
 * 再出现）；
 * 其余 7 条（huaqi.live / playm3u8.cn / yparse.ik9.cc / pangujiexi.com /
 * pouyun.com / 8090g.cn / ckplayer.vip）本来就是无 key 的嗅探线，代码里
 * 没有 key 可去。本文件的机制是通用的，不按站打补丁：
 *
 * - 任何解析线路的地址里都可以写 `{key}` 占位符；请求时由本文件把用户
 *   填的 key（URL 编码后）代入；
 * - key 只存本机加密存储（[SecurePrefs]，1.4.21 凭据加密存储的收口），
 *   不进明文 prefs、不进日志、不进单测、不随订阅分享；
 * - 没填 key 的 `{key}` 线路不参与解析（不发请求、不计失败），设置页
 *   逐行提示“未填 key”并可点“填 key”补填；
 * - 旧版种子地址里的 key：[toTemplate]/[extractLegacyKey] 只按结构识别
 *   sspa8 旧地址形状（前缀 + `&url=`），把 key 迁进加密存储、地址改写
 *   成模板——代码里不出现旧 key 的值，存量用户的 J2 也不会因此断掉。
 */
object ParseKeyStore {

    /** 地址模板里的密钥占位符。 */
    const val KEY_PLACEHOLDER = "{key}"

    private const val PREFS = "movieshell_prefs"
    private const val SLOT_PREFIX = "parse_key_"

    /** 旧版 J2 种子地址的形状（只认结构，不含 key 值本身）。 */
    private const val LEGACY_PREFIX = "http://sspa8.top:8100/api/?key="
    private const val LEGACY_MARKER = "&url="

    /** 地址里是否含 `{key}` 占位符（需要用户填 key 才可用）。 */
    fun needsKey(url: String): Boolean = url.contains(KEY_PLACEHOLDER)

    /**
     * 纯函数：把 key 代入模板（URL 编码后替换全部占位符）；无占位符时原样返回。
     * key 为空时不代入——调用方应先判空（空 key 的线路直接跳过，不发请求）。
     */
    fun substituteKey(template: String, key: String): String {
        if (!needsKey(template)) return template
        return template.replace(KEY_PLACEHOLDER, URLEncoder.encode(key, Charsets.UTF_8.name()))
    }

    /**
     * 纯函数：模板对应的加密存储槽位名（模板 SHA-256 前 16 位 hex，稳定、
     * 与 key 值无关；地址改了就换槽位，旧 key 不会串到新地址上）。
     */
    fun keySlot(template: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(template.trim().toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return SLOT_PREFIX + hex.take(16)
    }

    /**
     * 纯函数：从旧版 J2 种子地址里取出 key（结构不匹配 / key 为空或含 `&` /
     * 空白时返回 null）。只认旧种子地址形状，不碰用户自己加的其它含 key
     * 参数的地址。
     */
    fun extractLegacyKey(url: String): String? {
        val u = url.trim()
        if (!u.startsWith(LEGACY_PREFIX)) return null
        val rest = u.substring(LEGACY_PREFIX.length)
        val end = rest.indexOf(LEGACY_MARKER)
        if (end < 0) return null
        val key = rest.substring(0, end)
        if (key.isBlank() || key.any { it == '&' || it.isWhitespace() }) return null
        return key
    }

    /** 纯函数：旧版 J2 种子地址改写成 `{key}` 模板；不是该形状时返回 null。 */
    fun toTemplate(url: String): String? {
        val u = url.trim()
        if (!u.startsWith(LEGACY_PREFIX)) return null
        val rest = u.substring(LEGACY_PREFIX.length)
        val end = rest.indexOf(LEGACY_MARKER)
        if (end < 0) return null
        return LEGACY_PREFIX + KEY_PLACEHOLDER + rest.substring(end)
    }

    /** 读某模板的 key（未填返回 ""；值绝不写日志）。 */
    fun getKey(context: Context, template: String): String =
        SecurePrefs.getString(context, PREFS, keySlot(template))

    /** 该模板是否已填 key。 */
    fun hasKey(context: Context, template: String): Boolean =
        getKey(context, template).isNotBlank()

    /**
     * 写某模板的 key；空值 = 清除。加密库不可用时按 SecurePrefs 的既有
     * 回落口径走明文旧位置（与 1.4.21 其它凭据一致），值不丢。
     */
    fun setKey(context: Context, template: String, key: String) {
        val slot = keySlot(template)
        if (key.isBlank()) SecurePrefs.removeString(context, PREFS, slot)
        else SecurePrefs.putString(context, PREFS, slot, key.trim())
    }
}
