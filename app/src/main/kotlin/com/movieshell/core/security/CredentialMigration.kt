package com.movieshell.core.security

/**
 * v1.4.21：凭据迁移判定（纯函数，可单测，不依赖 Android）。
 *
 * 迁移只有三种结局，判定比较明文值与加密库值：
 * - 明文无值 → 不动（SKIP）；
 * - 加密库没这个 key → 先写加密副本、校验通过后再删明文（COPY_THEN_DELETE）；
 * - 两边都有值且相等 → 只删明文（DELETE_PLAIN_ONLY，中断续迁的清理）；
 * - 两边都有值且不等 → 明文覆盖加密库（后写优先，同样走 COPY_THEN_DELETE）。
 *
 * 冲突时以后写为准：明文值比加密库值新是可达的（降级回旧版重填、
 * 或 Keystore 暂时不可用时回落写明文），静默丢掉它会让用户回到旧凭据
 * 却无从得知。代价是放弃另一种极小概率情形的保护：进程恰好死在
 * putString 的 enc.commit() 与 plain.remove() 之间时，明文里残留的
 * 是旧值，下次迁移会把它写回加密库——那是两条相邻语句之间、没有 I/O
 * 的几条 CPU 指令窗口，远比“用户降级重填”难命中。
 */
enum class MigrationDecision {
    /** 明文无值（null / 空串 / 全空白）：无物可迁，本次不做任何事。 */
    SKIP,

    /** 明文有值、加密库里没有，或两边值不等（明文更新）：先写加密副本，校验成功后再删明文。 */
    COPY_THEN_DELETE,

    /** 两边值相等：只删明文，不动加密库（中断续迁的清理）。 */
    DELETE_PLAIN_ONLY,
}

/**
 * 单个 key 的迁移判定。
 *
 * @param plainValue 明文库里的值（null = 这个 key 从没存过）
 * @param encryptedValue 加密库里的值（null = 加密库里没有这个 key）
 */
fun decideMigration(plainValue: String?, encryptedValue: String?): MigrationDecision {
    if (plainValue.isNullOrBlank()) return MigrationDecision.SKIP
    if (encryptedValue == null) return MigrationDecision.COPY_THEN_DELETE
    if (plainValue == encryptedValue) return MigrationDecision.DELETE_PLAIN_ONLY
    return MigrationDecision.COPY_THEN_DELETE
}

/** 网盘 Cookie 在加密库里的 key：按盘目录名区分（quark / uc / baidu）。 */
fun netdiskCookieKey(dirName: String): String = "netdisk_cookie_${dirName}"

/** 网盘 token 在加密库里的 key：按盘目录名区分（aliyun / guangya / xunlei）。 */
fun netdiskTokenKey(dirName: String): String = "netdisk_token_${dirName}"
