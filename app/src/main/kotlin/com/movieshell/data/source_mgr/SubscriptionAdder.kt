package com.movieshell.data.source_mgr

import android.content.Context
import com.movieshell.data.store.ApiSourceStore
import com.movieshell.data.store.SubRefreshStore
import com.movieshell.data.sync.WarehouseSync
import com.movieshell.ui.components.AppToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 加订阅共用入口：订阅页「添加订阅」与详情页指针卡片「添加到订阅」同一条链路。
 *
 * 流程与旧 SourcesScreen.addSubscription + parseSubscription 一致：
 * 校验 http(s) → 按域名起名 → 写 ApiSourceStore → SubSiteStore.refresh →
 * 多仓时 WarehouseSync 同步子仓并静默解析；成功/失败 Toast 沿用订阅页现有文案。
 * 弹幕地址这类非订阅网址会在 refresh 里走现有校验失败，不写特判。
 */
object SubscriptionAdder {

    /** 正在后台刷新的订阅地址（防重复点按导致同一地址并发刷新；与站源页 subParsing 同语义）。 */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /**
     * 加订阅并刷新。地址非法（非 http(s) 开头）返回 null，调用方自行提示；
     * 成功返回新订阅条目，刷新在进程级 scope 后台跑。
     */
    fun addAndRefresh(
        context: Context,
        url: String,
        name: String? = null,
        note: String = "",
        group: String = "",
        onCache: ((String, SubCache) -> Unit)? = null,
        onParsing: ((String, Boolean) -> Unit)? = null,
        onChildrenSynced: ((List<String>) -> Unit)? = null,
    ): ApiSource? {
        val trimmed = url.trim()
        if (!(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))) return null
        // v0.42.0：名称选填，不填则按域名自动生成（与旧 addSubscription 同口径）。
        val entryName = name?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                trimmed.substringAfter("://").substringBefore("/").substringBefore(":")
            }.getOrDefault("自定义订阅").ifBlank { "自定义订阅" }
        val source = ApiSource(entryName, trimmed, note = note.trim(), group = group.trim())
        val all = ApiSourceStore.load(context).toMutableList()
        all.add(0, source)
        ApiSourceStore.save(context, all)
        refresh(context, subUrl = trimmed, parent = source, onCache = onCache, onParsing = onParsing, onChildrenSynced = onChildrenSynced)
        return source
    }

    /**
     * 刷新订阅（站源页 parseSubscription 的同一套逻辑抽出来）：
     * SubSiteStore.refresh → 多仓走 WarehouseSync 同步子仓并静默解析 → Toast 沿用旧文案。
     * parent 为 null 时不做多仓同步（与旧逻辑 parentEntry 为空时一致）。
     */
    fun refresh(
        context: Context,
        subUrl: String,
        parent: ApiSource? = null,
        quiet: Boolean = false,
        onCache: ((String, SubCache) -> Unit)? = null,
        onParsing: ((String, Boolean) -> Unit)? = null,
        onChildrenSynced: ((List<String>) -> Unit)? = null,
    ) {
        if (!refreshing.add(subUrl)) return
        SubSiteStore.appScope.launch {
            try {
                onParsing?.invoke(subUrl, true)
                SubSiteStore.refresh(context, subUrl)
                    .onSuccess { cache ->
                        // v0.46.0：手动刷新也记状态，“已失效”badge 及时更新。
                        SubRefreshStore.recordResult(context, subUrl, true)
                        onCache?.invoke(subUrl, cache)
                        if (cache.warehouses.isNotEmpty()) {
                            // v0.122.0：多仓展开为独立子仓订阅——同步增删后对齐 UI 列表，
                            // 新增子仓静默自动解析展开（子仓本身又是多仓时 WarehouseSync
                            // 按 parentUrl 拦截，只展开一层）。
                            val added = if (parent != null) {
                                val added = WarehouseSync.syncChildren(context, parent, cache.warehouses)
                                onChildrenSynced?.invoke(added)
                                added
                            } else {
                                emptyList()
                            }
                            added.forEach { childUrl ->
                                ApiSourceStore.load(context).firstOrNull { it.url == childUrl }?.let { child ->
                                    refresh(context, child.url, parent = child, quiet = true, onCache = onCache, onParsing = onParsing, onChildrenSynced = onChildrenSynced)
                                }
                            }
                            if (!quiet) {
                                AppToast.show(context, "多仓解析完成：${cache.warehouses.size} 个子仓", long = true)
                            }
                            return@onSuccess
                        }
                        if (!quiet) {
                            val ok = cache.sites.count { it.kind != SubSiteStore.KIND_UNSUPPORTED }
                            val bad = cache.sites.size - ok
                            AppToast.show(
                                context,
                                "解析完成：${cache.sites.size} 个站点，$ok 个可用" +
                                    if (bad > 0) "，$bad 个不支持" else "",
                                long = true,
                            )
                        }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        // v0.46.0：手动刷新失败也记，“已失效”badge 及时更新。
                        SubRefreshStore.recordResult(context, subUrl, false)
                        if (!quiet) {
                            AppToast.show(context, "解析失败：${e.message}", long = true)
                        }
                    }
            } finally {
                refreshing.remove(subUrl)
                onParsing?.invoke(subUrl, false)
            }
        }
    }
}
