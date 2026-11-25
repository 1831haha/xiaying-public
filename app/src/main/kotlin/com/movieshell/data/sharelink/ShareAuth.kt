package com.movieshell.data.sharelink

import android.content.Context
import android.webkit.CookieManager
import com.movieshell.core.log.LogBus
import com.movieshell.core.security.SecurePrefs
import com.movieshell.core.security.netdiskCookieKey
import com.movieshell.core.security.netdiskTokenKey
import java.io.File

/**
 * v1.3.5：分享链接播放的登录态存储（照搬 v1.1.31 砍盘前 PanCookieFile /
 * PanTokenFile 的落盘位置与口径，手机里 1.1.30 留下的登录文件能直接续用）。
 *
 * v1.4.21：Cookie/token 改存加密库（SecurePrefs，按盘分 key），老文件
 * 只作一次性迁移源——首次读到时写进加密库、校验通过后删文件；1.1.30
 * 留下的登录文件同样能续用（读时迁移）。expired 标记仍是普通空文件。
 * 只存 Cookie/token，不存账号密码；值绝不写进日志。
 */
object ShareAuth {

    private const val TAG = "ShareAuth"

    /** MainActivity 启动时调一次；晚于它调用的读写会返回空而不崩。 */
    fun init(context: Context) {
        CookieStore.init(context)
        TokenStore.init(context)
    }

    fun hasLogin(platform: SharePlatform): Boolean = when (platform) {
        SharePlatform.QUARK, SharePlatform.UC, SharePlatform.BAIDU ->
            CookieStore.hasCookie(platform.dirName)
        SharePlatform.ALIYUN, SharePlatform.GUANGYA, SharePlatform.XUNLEI ->
            TokenStore.hasToken(platform.dirName)
    }

    fun isExpired(platform: SharePlatform): Boolean = when (platform) {
        SharePlatform.QUARK, SharePlatform.UC, SharePlatform.BAIDU ->
            CookieStore.isExpired(platform.dirName)
        else -> false
    }

    fun getCookie(platform: SharePlatform): String = when (platform) {
        SharePlatform.QUARK, SharePlatform.UC, SharePlatform.BAIDU ->
            CookieStore.getCookie(platform.dirName)
        else -> ""
    }

    fun saveCookie(platform: SharePlatform, cookie: String): Boolean = when (platform) {
        SharePlatform.QUARK, SharePlatform.UC, SharePlatform.BAIDU ->
            CookieStore.saveCookie(platform.dirName, cookie)
        else -> false
    }

    fun getTokenJson(platform: SharePlatform): String = when (platform) {
        SharePlatform.ALIYUN, SharePlatform.GUANGYA, SharePlatform.XUNLEI ->
            TokenStore.getTokenJson(platform.dirName)
        else -> ""
    }

    fun saveTokenJson(platform: SharePlatform, json: String): Boolean = when (platform) {
        SharePlatform.ALIYUN, SharePlatform.GUANGYA, SharePlatform.XUNLEI ->
            TokenStore.saveTokenJson(platform.dirName, json)
        else -> false
    }

    /** 播前检测到登录态失效（31001）时打标记；登录页据此提示重登。 */
    fun markExpired(platform: SharePlatform) {
        if (platform == SharePlatform.QUARK || platform == SharePlatform.UC ||
            platform == SharePlatform.BAIDU
        ) {
            CookieStore.markExpired(platform.dirName)
        }
    }

    /** 退出登录：删本地文件；Cookie 盘顺带清 WebView 里该域的同名 Cookie。 */
    fun clear(platform: SharePlatform): Boolean {
        return when (platform) {
            SharePlatform.QUARK -> {
                CookieStore.clear(platform.dirName)
                clearWebCookies(
                    "https://pan.quark.cn", ".quark.cn",
                    listOf("__puus", "__pus", "__kps", "__uid"),
                )
                true
            }
            SharePlatform.UC -> {
                CookieStore.clear(platform.dirName)
                clearWebCookies(
                    "https://drive.uc.cn", ".uc.cn",
                    listOf("__puus", "__pus", "__kps", "__uid"),
                )
                true
            }
            SharePlatform.BAIDU -> {
                CookieStore.clear(platform.dirName)
                clearWebCookies(
                    "https://pan.baidu.com", ".baidu.com",
                    listOf("BDUSS", "STOKEN"),
                )
                true
            }
            else -> TokenStore.clear(platform.dirName)
        }
    }

    private fun clearWebCookies(url: String, domain: String, names: List<String>) {
        runCatching {
            val cm = CookieManager.getInstance()
            for (name in names) {
                cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$domain")
            }
            cm.flush()
        }
    }
}

/** Cookie 落盘小件（夸克/UC/百度三家共用）。 */
internal object CookieStore {

    private const val TAG = "ShareAuth"
    private const val FILE_NAME = "cookie.txt"
    private const val EXPIRED_NAME = "expired"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(dirName: String): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, dirName).also { it.mkdirs() }
    }

    private fun cookieFile(dirName: String): File? = dir(dirName)?.let { File(it, FILE_NAME) }

    private fun expiredFile(dirName: String): File? = dir(dirName)?.let { File(it, EXPIRED_NAME) }

    /** 读老文件（v1.4.21 前的存法）：没有文件返回 null，供迁移时区分“没存过”。 */
    private fun readCookieFile(dirName: String): String? {
        val f = cookieFile(dirName) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrDefault(null)
    }

    fun getCookie(dirName: String): String {
        val ctx = appContext ?: return ""
        return SecurePrefs.getStringWithFileMigration(
            ctx,
            netdiskCookieKey(dirName),
            readLegacy = { readCookieFile(dirName) },
            deleteLegacy = { cookieFile(dirName)?.delete() },
        )
    }

    fun hasCookie(dirName: String): Boolean = getCookie(dirName).isNotBlank()

    fun saveCookie(dirName: String, cookie: String): Boolean {
        val v = cookie.trim()
        if (v.isBlank()) return false
        val ctx = appContext
        if (ctx != null && SecurePrefs.putStringDroppingLegacy(ctx, netdiskCookieKey(dirName), v) {
                cookieFile(dirName)?.delete()
            }
        ) {
            expiredFile(dirName)?.delete()
            LogBus.i(TAG, "${dirName} 登录态已保存")
            return true
        }
        // 加密库不可用：回落老文件（旧行为）。
        val f = cookieFile(dirName) ?: return false
        return runCatching {
            f.writeText(v)
            expiredFile(dirName)?.delete()
            LogBus.i(TAG, "${dirName} 登录态已保存")
            true
        }.onFailure { LogBus.w(TAG, "${dirName} 登录态保存失败：${it.message}") }.getOrDefault(false)
    }

    fun clear(dirName: String): Boolean {
        val ctx = appContext
        if (ctx != null) SecurePrefs.removeEncrypted(ctx, netdiskCookieKey(dirName))
        val f = cookieFile(dirName) ?: return false
        return runCatching {
            if (f.exists()) f.delete()
            expiredFile(dirName)?.delete()
            LogBus.i(TAG, "${dirName} 登录态已清除")
            true
        }.getOrDefault(false)
    }

    fun markExpired(dirName: String) {
        runCatching { expiredFile(dirName)?.writeText("1") }
    }

    fun isExpired(dirName: String): Boolean = expiredFile(dirName)?.isFile == true && hasCookie(dirName)
}

/** token 落盘小件（阿里/光鸭/迅雷三家共用）。 */
internal object TokenStore {

    private const val TAG = "ShareAuth"
    private const val FILE_NAME = "token.json"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(dirName: String): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, dirName).also { it.mkdirs() }
    }

    private fun tokenFile(dirName: String): File? = dir(dirName)?.let { File(it, FILE_NAME) }

    /** 读老文件（v1.4.21 前的存法）：没有文件返回 null，供迁移时区分“没存过”。 */
    private fun readTokenFile(dirName: String): String? {
        val f = tokenFile(dirName) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrDefault(null)
    }

    fun getTokenJson(dirName: String): String {
        val ctx = appContext ?: return ""
        return SecurePrefs.getStringWithFileMigration(
            ctx,
            netdiskTokenKey(dirName),
            readLegacy = { readTokenFile(dirName) },
            deleteLegacy = { tokenFile(dirName)?.delete() },
        )
    }

    fun hasToken(dirName: String): Boolean = getTokenJson(dirName).isNotBlank()

    fun saveTokenJson(dirName: String, json: String): Boolean {
        val v = json.trim()
        if (v.isBlank()) return false
        val ctx = appContext
        if (ctx != null && SecurePrefs.putStringDroppingLegacy(ctx, netdiskTokenKey(dirName), v) {
                tokenFile(dirName)?.delete()
            }
        ) {
            LogBus.i(TAG, "${dirName} 登录态已保存")
            return true
        }
        // 加密库不可用：回落老文件（旧行为）。
        val f = tokenFile(dirName) ?: return false
        return runCatching {
            f.writeText(v)
            LogBus.i(TAG, "${dirName} 登录态已保存")
            true
        }.onFailure { LogBus.w(TAG, "${dirName} 登录态保存失败：${it.message}") }.getOrDefault(false)
    }

    fun clear(dirName: String): Boolean {
        val ctx = appContext
        if (ctx != null) SecurePrefs.removeEncrypted(ctx, netdiskTokenKey(dirName))
        val f = tokenFile(dirName) ?: return false
        return runCatching {
            if (f.exists()) f.delete()
            LogBus.i(TAG, "${dirName} 登录态已清除")
            true
        }.getOrDefault(false)
    }
}
