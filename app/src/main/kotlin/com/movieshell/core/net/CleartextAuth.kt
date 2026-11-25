package com.movieshell.core.net

import android.content.Context
import android.content.SharedPreferences
import com.movieshell.core.log.LogBus
import com.movieshell.core.security.SecurePrefs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * v1.4.23 轨道 4：明文 HTTP 授权存储（SharedPreferences "cleartext_auth"）。
 *
 * 与 CleartextGate 的分工：Gate 是纯判定（可单测），这里是 Android 存储＋快照。
 * 拦截器只读 authorizedSnapshot()（@Volatile），不碰 prefs、不碰磁盘。
 *
 * 授权集 = 用户显式授权 ＋ 祖父迁移（首次启动一次性从存量订阅文件里提 http host）。
 * 内置预授权域（CleartextGate.PREAUTHORIZED_HOSTS）与回环/私有 IP 不进授权集：
 * authorize() 与祖父迁移按同一口径（isBuiltinAllowed）过滤，init() 读盘时也
 * 过滤已入库的这三类；判定层直接放行，所以 authorizedHosts() 只含“用户授权的”
 *（设置页用；撤销即真正失效）。
 */
object CleartextAuth {

    private const val TAG = "CleartextAuth"
    private const val PREFS_NAME = "cleartext_auth"
    private const val KEY_AUTHORIZED = "authorized_hosts"
    private const val KEY_BLOCKED = "blocked_hosts"
    private const val KEY_MIGRATED = "cleartext_migrated_v1"

    /** 被拦记录写盘节流：同一 host 60 秒内只写一次盘（内存快照每次都更新）。 */
    private const val BLOCKED_PERSIST_INTERVAL_MS = 60_000L

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 拦截器读的快照：用户授权 ＋ 祖父迁移，不含内置预授权域。 */
    @Volatile
    private var snapshot: Set<String> = emptySet()

    @Volatile
    private var blockedSnapshot: Map<String, Long> = emptyMap()

    /**
     * App 启动时调一次（XiaYingApp.onCreate，HttpClient.initDoh 之后、
     * Coil.setImageLoader 之前）。读快照（顺手过滤旧 snapshot 里混进的回环/
     * 私有 IP/预授权域）；首次跑一次性祖父迁移。
     * 迁移失败只记日志，不阻塞启动；迁移未全部成功时不置完成标志，下次启动重试。
     */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        var cleaned = false
        synchronized(lock) {
            val loaded = p.getStringSet(KEY_AUTHORIZED, null)
                ?.map { CleartextGate.normalizeHost(it) }
                ?.filter { it.isNotEmpty() }
                ?.toSet().orEmpty()
            // P1-4：旧 snapshot 里可能混进回环/私有 IP/预授权域（1.4.23 初版
            // 迁移不过滤），读盘即清掉，否则设置页“撤销”是无效操作。
            val filtered = loaded.filterNot { isBuiltinAllowed(it) }.toSet()
            cleaned = filtered.size != loaded.size
            snapshot = filtered
            blockedSnapshot = parseBlocked(p.getStringSet(KEY_BLOCKED, null).orEmpty())
        }
        if (cleaned) {
            synchronized(lock) { persistAuthorizedLocked() }
        }
        if (!p.getBoolean(KEY_MIGRATED, false)) {
            runGrandfatherMigration(context, p)
        }
    }

    fun authorizedSnapshot(): Set<String> = snapshot

    /** 与 Gate.decide 同口径：https 放行；http 按快照＋预授权＋回环/私有 IP 判定。 */
    fun isAllowed(scheme: String, host: String): Boolean =
        CleartextGate.decide(scheme, host, snapshot)

    /** URL 级判定：解析失败 → true（fail-open，让调用方自己处理坏 URL）。 */
    fun isAllowedUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return true
        return isAllowed(httpUrl.scheme, httpUrl.host)
    }

    /**
     * 该 host 的 http 是否有效放行（含内置预授权与回环/私有 IP）。
     * 注意：revoke() 只删用户授权，内置预授权域 revoke 不掉，isAuthorized 仍为 true。
     */
    fun isAuthorized(host: String): Boolean =
        CleartextGate.decide("http", host, snapshot)

    /**
     * 授权某 host：归一化后持久化＋刷新快照。已靠内置规则放行的
     * （预授权/回环/私有 IP）不进授权集——authorizedHosts() 只给设置页看用户授权。
     */
    fun authorize(host: String) {
        val h = CleartextGate.normalizeHost(host)
        if (h.isEmpty()) return
        if (isBuiltinAllowed(h)) return
        synchronized(lock) {
            if (snapshot.contains(h)) return
            snapshot = snapshot + h
            persistAuthorizedLocked()
        }
    }

    /**
     * 与 authorize() 同口径的内置放行判定（P1-4）：回环/私有 IP 或内置预授权域
     * → 不进授权集。祖父迁移与 init() 读盘过滤都复用它，不许各写一份。
     */
    private fun isBuiltinAllowed(host: String): Boolean {
        if (CleartextGate.isLoopbackOrPrivateIp(host)) return true
        return CleartextGate.decide("http", host, emptySet())
    }

    fun revoke(host: String) {
        val h = CleartextGate.normalizeHost(host)
        if (h.isEmpty()) return
        synchronized(lock) {
            if (!snapshot.contains(h)) return
            snapshot = snapshot - h
            persistAuthorizedLocked()
        }
    }

    /** 授权集内容（用户授权＋祖父迁移；不含内置预授权域、回环、私有 IP）。设置页用。 */
    fun authorizedHosts(): Set<String> = snapshot

    fun blockedHosts(): Map<String, Long> = blockedSnapshot

    /** 拦截器被拦时记一笔：host → 最近被拦 epoch ms。未 init 时直接丢弃。 */
    fun recordBlocked(host: String) {
        if (prefs == null) return
        val h = CleartextGate.normalizeHost(host)
        if (h.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val old = blockedSnapshot[h]
            blockedSnapshot = blockedSnapshot + (h to now)
            if (old == null || now - old > BLOCKED_PERSIST_INTERVAL_MS) {
                persistBlockedLocked()
            }
        }
    }

    fun clearBlocked(host: String) {
        val h = CleartextGate.normalizeHost(host)
        if (h.isEmpty()) return
        synchronized(lock) {
            if (!blockedSnapshot.containsKey(h)) return
            blockedSnapshot = blockedSnapshot - h
            persistBlockedLocked()
        }
    }

    // ---------------- 祖父迁移 ----------------

    /**
     * 首次启动一次性迁移（标志位 cleartext_migrated_v1 guard）：
     * 按 JSON 结构解析各存量来源，只提真正参与网络请求的配置字段里的 http host
     *（P1-6：不扫整段文本，描述/备注里的 http 地址不进授权集；解析失败的来源
     * 不做正则兜底，按失败计）；提到的 host 再按 authorize() 同口径过滤回环/
     * 私有 IP/预授权域（P1-4）；上限 200 个，先出现的赢。
     *
     * 来源（每处独立记成功/失败）：
     * - filesDir/sub_sites.json：站点 api / origApi / playUrl、订阅 subUrl /
     *   baseUrl、订阅顶层 parses[].url、spiderJar；
     * - filesDir/api_sources.json：接口 url、parentUrl；
     * - filesDir/live_channels_cache.json：频道 url / logo / epgUrl /
     *   playlistEpgUrl；
     * - live_prefs：live_sources 的 url / epg、live_global_epg（裸 URL 字符串）；
     * - movieshell_prefs/custom_parses_json：自定义解析线路 url（P1-2）；
     * - emby_store/servers_json：服务器 baseUrl（加密串只走 SecurePrefs，P1-2）；
     * - cloud_sync_prefs/webdav_url：WebDAV 地址（裸 URL 字符串，P1-2）。
     *
     * P1-7：全部必要来源都读完才置 KEY_MIGRATED；任一来源失败（读失败或 JSON
     * 解析失败）不置，下次启动重试。文件/键不存在算成功（无内容可迁）。
     * 失败只记日志，不阻塞启动。
     */
    private fun runGrandfatherMigration(context: Context, p: SharedPreferences) {
        val hosts = linkedSetOf<String>()
        var allOk = true

        fun addHosts(newHosts: Iterable<String>) {
            for (h in newHosts) hosts.add(h)
        }

        fun addFile(name: String, fields: Set<String>) {
            val f = File(context.filesDir, name)
            if (!f.exists()) return
            val text = runCatching { f.readText() }
                .onFailure { LogBus.w(TAG, "祖父迁移读 $name 失败：${it.message}") }
                .getOrNull()
            if (text == null) {
                allOk = false
                return
            }
            val parsed = CleartextGate.extractHttpHostsFromJson(text, fields)
            if (parsed == null) {
                LogBus.w(TAG, "祖父迁移解析 $name 失败，下次启动重试")
                allOk = false
                return
            }
            addHosts(parsed.filterNot { isBuiltinAllowed(it) })
        }

        fun addPrefsJson(prefsName: String, key: String, fields: Set<String>) {
            val raw: String?
            try {
                raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .getString(key, null)
            } catch (e: Exception) {
                LogBus.w(TAG, "祖父迁移读 $prefsName/$key 失败：${e.message}")
                allOk = false
                return
            }
            if (raw.isNullOrBlank()) return
            val parsed = CleartextGate.extractHttpHostsFromJson(raw, fields)
            if (parsed == null) {
                LogBus.w(TAG, "祖父迁移解析 $prefsName/$key 失败，下次启动重试")
                allOk = false
                return
            }
            addHosts(parsed.filterNot { isBuiltinAllowed(it) })
        }

        fun addPrefsUrl(prefsName: String, key: String) {
            val raw: String?
            try {
                raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .getString(key, null)
            } catch (e: Exception) {
                LogBus.w(TAG, "祖父迁移读 $prefsName/$key 失败：${e.message}")
                allOk = false
                return
            }
            if (raw.isNullOrBlank()) return
            val host = CleartextGate.extractHttpHost(raw)
            if (host != null && !isBuiltinAllowed(host)) addHosts(listOf(host))
        }

        fun addEmbyServers() {
            val raw: String
            try {
                // servers_json 含各台 token，加密串只走 SecurePrefs，不直接读明文 prefs。
                raw = SecurePrefs.getString(context, "emby_store", "servers_json")
            } catch (e: Exception) {
                LogBus.w(TAG, "祖父迁移读 emby_store/servers_json 失败：${e.message}")
                allOk = false
                return
            }
            if (raw.isBlank()) return
            val parsed = CleartextGate.extractHttpHostsFromJson(raw, setOf("baseUrl"))
            if (parsed == null) {
                LogBus.w(TAG, "祖父迁移解析 emby_store/servers_json 失败，下次启动重试")
                allOk = false
                return
            }
            addHosts(parsed.filterNot { isBuiltinAllowed(it) })
        }

        addFile("sub_sites.json", setOf("subUrl", "baseUrl", "api", "origApi", "playUrl", "url", "spiderJar", "ext", "origExt"))
        addFile("api_sources.json", setOf("url", "parentUrl"))
        addFile("live_channels_cache.json", setOf("url", "logo", "epgUrl", "playlistEpgUrl"))
        addPrefsJson("live_prefs", "live_sources", setOf("url", "epg"))
        addPrefsUrl("live_prefs", "live_global_epg")
        addPrefsJson("movieshell_prefs", "custom_parses_json", setOf("url"))
        addEmbyServers()
        addPrefsUrl("cloud_sync_prefs", "webdav_url")

        val capped = CleartextGate.capHosts(hosts)
        if (capped.isNotEmpty()) {
            synchronized(lock) {
                snapshot = snapshot + capped
                persistAuthorizedLocked()
            }
            LogBus.w(TAG, "祖父迁移：授权 ${capped.size} 个存量 http host")
        }
        if (allOk) {
            runCatching { p.edit().putBoolean(KEY_MIGRATED, true).apply() }
                .onFailure { LogBus.w(TAG, "祖父迁移标志位写入失败：${it.message}") }
        } else {
            LogBus.w(TAG, "祖父迁移部分失败，未置完成标志，下次启动重试")
        }
    }

    // ---------------- 持久化（调用方须持 lock） ----------------

    private fun persistAuthorizedLocked() {
        runCatching {
            prefs?.edit()?.putStringSet(KEY_AUTHORIZED, snapshot.toSet())?.apply()
        }.onFailure { LogBus.w(TAG, "授权集写盘失败：${it.message}") }
    }

    private fun persistBlockedLocked() {
        runCatching {
            val set = blockedSnapshot.entries
                .map { "${it.key}|${it.value}" }.toSet()
            prefs?.edit()?.putStringSet(KEY_BLOCKED, set)?.apply()
        }.onFailure { LogBus.w(TAG, "被拦记录写盘失败：${it.message}") }
    }

    private fun parseBlocked(raw: Set<String>): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        for (entry in raw) {
            val idx = entry.lastIndexOf('|')
            if (idx <= 0) continue
            val host = CleartextGate.normalizeHost(entry.substring(0, idx))
            val ts = entry.substring(idx + 1).toLongOrNull() ?: continue
            if (host.isNotEmpty()) out[host] = ts
        }
        return out
    }
}
