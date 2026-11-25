package com.movieshell.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.movieshell.core.log.LogBus

/**
 * v1.4.21：凭据加密存储集中收口——所有凭据读写只走这里，不许再有
 * 第二处直接碰明文凭据。
 *
 * 加密库：EncryptedSharedPreferences（MasterKey AES256_GCM 存 Keystore，
 * key 用 AES256_SIV、value 用 AES256_GCM），文件名 secure_credentials，
 * key 名沿用各家原来的 key 名（webdav_pass / tmdb_api_key /
 * ai_key_<id> / token / servers_json），网盘 Cookie/token 用
 * netdisk_cookie_<dir> / netdisk_token_<dir>。
 *
 * 失败兜底：加密库建不出来（Keystore 异常等）时，读写回落到原来的
 * 明文位置、行为与旧版一致，绝不因加密失败丢数据或闪退；迁移也
 * 顺延到下次启动再试。值绝不写进日志。
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val ENCRYPTED_PREFS = "secure_credentials"

    private val lock = Any()

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptedUnavailable = false

    /** 本进程内已跑过迁移的明文 prefs 文件名：跑一次就够，重复调用直接返回。 */
    private val migratedFiles = mutableSetOf<String>()

    /** 加密库实例；建不出来返回 null（调用方回落明文，见类注释）。 */
    private fun encrypted(context: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (encryptedUnavailable) return null
        synchronized(lock) {
            encryptedPrefs?.let { return it }
            if (encryptedUnavailable) return null
            val appContext = context.applicationContext
            val created = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    ENCRYPTED_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                LogBus.w(TAG, "加密库创建失败，回落明文存储：${e.message}")
                encryptedUnavailable = true
                null
            }
            if (created != null) encryptedPrefs = created
            return created
        }
    }

    private fun plainPrefs(context: Context, plainPrefsName: String): SharedPreferences =
        context.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)

    /**
     * 读一个凭据：加密库里有就读加密库，没有就读明文旧值（迁移前 /
     * 加密库不可用时的回落）。调用前一般先调 [migrateKeys]。
     */
    fun getString(context: Context, plainPrefsName: String, key: String, default: String = ""): String {
        val enc = encrypted(context)
        if (enc != null && enc.contains(key)) return enc.getString(key, default).orEmpty()
        return plainPrefs(context, plainPrefsName).getString(key, default).orEmpty()
    }

    /** 这个 key 是否存过（加密库或明文任一处有即算存过）。 */
    fun contains(context: Context, plainPrefsName: String, key: String): Boolean {
        val enc = encrypted(context)
        if (enc != null && enc.contains(key)) return true
        return plainPrefs(context, plainPrefsName).contains(key)
    }

    /**
     * 写一个凭据：加密库可用就写加密库（同步提交，写成功才删明文旧值；
     * 提交失败则回发明文旧位置，值不丢）；加密库不可用就写明文旧位置。
     */
    fun putString(context: Context, plainPrefsName: String, key: String, value: String) {
        val enc = encrypted(context)
        if (enc != null) {
            val committed = enc.edit().putString(key, value).commit()
            if (committed) {
                plainPrefs(context, plainPrefsName).edit().remove(key).apply()
            } else {
                LogBus.w(TAG, "加密写入失败，回落明文：$plainPrefsName/$key")
                plainPrefs(context, plainPrefsName).edit().putString(key, value).apply()
            }
        } else {
            plainPrefs(context, plainPrefsName).edit().putString(key, value).apply()
        }
    }

    /** 删一个凭据：加密库与明文两处都删（退出登录 / 清空用）。 */
    fun removeString(context: Context, plainPrefsName: String, key: String) {
        encrypted(context)?.edit()?.remove(key)?.apply()
        plainPrefs(context, plainPrefsName).edit().remove(key).apply()
    }

    /** 只删加密库里的一个 key（网盘退出登录用，明文文件由调用方自己删）。 */
    fun removeEncrypted(context: Context, key: String) {
        encrypted(context)?.edit()?.remove(key)?.apply()
    }

    /**
     * 一次性迁移：把 [plainPrefsName] 里 [keys] 的存量明文值迁进加密库。
     * 幂等（跑第二次时明文已空，全是 SKIP）、中断不丢数据（加密副本
     * 写成功并读回校验通过后才删明文；校验失败则明文保留，下次再迁）。
     * 加密库不可用时直接返回，明文原地不动。
     */
    fun migrateKeys(context: Context, plainPrefsName: String, keys: List<String>) {
        synchronized(lock) {
            if (migratedFiles.contains(plainPrefsName)) return
        }
        val enc = encrypted(context) ?: return
        val plain = plainPrefs(context, plainPrefsName)
        var failed = false
        for (key in keys) {
            val plainValue = if (plain.contains(key)) plain.getString(key, null) else null
            when (decideMigration(plainValue, enc.getString(key, null))) {
                MigrationDecision.SKIP -> Unit
                MigrationDecision.DELETE_PLAIN_ONLY -> {
                    plain.edit().remove(key).apply()
                }
                MigrationDecision.COPY_THEN_DELETE -> {
                    val value = plainValue.orEmpty()
                    enc.edit().putString(key, value).commit()
                    if (enc.getString(key, null) == value) {
                        plain.edit().remove(key).apply()
                    } else {
                        // 校验失败：删掉没写对的加密副本，明文保留，下次再迁。
                        enc.edit().remove(key).apply()
                        LogBus.w(TAG, "迁移校验失败，明文已保留，下次再迁：$plainPrefsName/$key")
                        failed = true
                    }
                }
            }
        }
        if (!failed) {
            synchronized(lock) {
                migratedFiles.add(plainPrefsName)
            }
        }
    }

    /**
     * 读一个原来存文件的凭据（网盘 Cookie/token）：加密库优先；没有则
     * 迁老文件——写加密副本、读回校验通过后才删文件，中断不丢数据。
     * 加密库不可用时直接读老文件。
     */
    fun getStringWithFileMigration(
        context: Context,
        key: String,
        readLegacy: () -> String?,
        deleteLegacy: () -> Unit,
    ): String {
        val enc = encrypted(context) ?: return readLegacy().orEmpty()
        if (enc.contains(key)) return enc.getString(key, "").orEmpty()
        val legacy = readLegacy()
        when (decideMigration(legacy, null)) {
            MigrationDecision.SKIP -> return ""
            MigrationDecision.DELETE_PLAIN_ONLY -> {
                deleteLegacy()
                return ""
            }
            MigrationDecision.COPY_THEN_DELETE -> {
                val value = legacy.orEmpty()
                enc.edit().putString(key, value).commit()
                if (enc.getString(key, null) == value) {
                    deleteLegacy()
                } else {
                    // 校验失败：删掉没写对的加密副本，老文件保留，下次再迁。
                    enc.edit().remove(key).apply()
                }
                return value
            }
        }
    }

    /**
     * 写一个原来存文件的凭据：加密库可用就写加密库（读回校验通过后
     * 才删老文件），返回 true；加密库不可用或校验失败返回 false，
     * 调用方回落写老文件（旧行为），值不丢。
     */
    fun putStringDroppingLegacy(
        context: Context,
        key: String,
        value: String,
        deleteLegacy: () -> Unit,
    ): Boolean {
        val enc = encrypted(context) ?: return false
        enc.edit().putString(key, value).commit()
        if (enc.getString(key, null) == value) {
            deleteLegacy()
            return true
        }
        enc.edit().remove(key).apply()
        return false
    }
}
