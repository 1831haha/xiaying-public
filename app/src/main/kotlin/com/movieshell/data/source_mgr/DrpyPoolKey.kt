package com.movieshell.data.source_mgr

import com.movieshell.core.util.SafeFileName

/**
 * v1.4.22 GPT P1-1：drpy 引擎池 key 必须绑定规则内容 SHA-256 + 白名单稳定指纹。
 *
 * 根因：风险确认记录是 consent:siteKey:sha256（规则内容 SHA），规则改了
 * SHA 变、下次执行重新确认；但池 key 以前只含源 key + 白名单
 * Set.hashCode()——规则改了、白名单没变，旧池化引擎（已加载旧规则）
 * 会被取出复用，用户确认的却是新规则，确认与执行不是同一份规则。
 *
 * 池 key = 源 key + 规则内容 SHA-256 + 白名单稳定指纹：
 * - 规则 SHA 就是确认记录用的那份内容 SHA（见 SourceRiskGate），
 *   不要用文件修改时间；扫不到（超限/读不到）时调用方从可信副本
 *   再算一份，算不出才记 "unscanned"。
 * - 白名单指纹不用 Set.hashCode()（JVM 哈希、无碰撞保证），也不用
 *   分隔符拼接（域名本身可含分隔符，拼出来有歧义）：逐域名 SHA-256
 *   后排序拼接再 SHA-256。
 *
 * 纯函数，供单测核对；旧实例按 SubDrpyPool 现有在途检查与 LRU 淘汰回收。
 */
internal fun drpyPoolKey(siteKey: String, ruleSha256: String?, allowedHosts: Set<String>?): String {
    val rulePart = ruleSha256?.takeIf { it.isNotBlank() } ?: "unscanned"
    val hostsPart = allowedHosts?.let { whitelistFingerprint(it) } ?: "none"
    return "$siteKey#rule$rulePart#sb$hostsPart"
}

/** 白名单稳定指纹：逐域名 SHA-256，排序后拼接再 SHA-256——集合内容相同则指纹相同，顺序无关，不同集合不撞。 */
internal fun whitelistFingerprint(allowedHosts: Set<String>): String {
    val perHost = allowedHosts.map { SafeFileName.sha256Hex(it) }.sorted()
    return SafeFileName.sha256Hex(perHost.joinToString("\n"))
}
