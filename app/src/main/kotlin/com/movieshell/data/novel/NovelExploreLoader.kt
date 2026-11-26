package com.movieshell.data.novel

import android.content.Context
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.4.27 解缠⑥：发现加载器合一。
 *
 * 改版前单源发现页（ExploreScreen）与聚合发现页（ExploreAggregateScreen）各写一份
 * “找引擎 → explore → runCatching 吞异常”，切回抓过的分类、单源页与聚合页之间来回，
 * 同一分类同一页每次都整页重抓。现在两页都走 [load]：
 * - 按（书源 key，分类地址，页码）缓存 [CACHE_TTL_MS]，最多 [MAX_ENTRIES] 条，超了挤掉最旧的；
 * - **空结果不缓存**（空多半是撞验证/网络抖，下次要能重抓）；
 * - [force]=true 跳过缓存现抓（“刷新”“重试”按钮）；
 * - 异常回空列表（与改版前 getOrDefault(emptyList()) 同口径），**取消原样抛**——
 *   改版前 runCatching 连取消一起吞了，页面离开后旧请求还会往状态里写。
 *
 * P0.8 的“默认列表模式”属于 Step 3，本段不动 webMode 默认值。
 */
object NovelExploreLoader {

    const val CACHE_TTL_MS = 5 * 60_000L
    const val MAX_ENTRIES = 64

    private data class Entry(val time: Long, val books: List<NovelSearchResult>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 单测可替换的时钟。 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    internal fun keyOf(sourceKey: String, categoryUrl: String, page: Int) =
        "$sourceKey\u0001$categoryUrl\u0001$page"

    /** 抓发现页一页书目（带缓存）。 */
    suspend fun load(
        context: Context,
        source: BookSource,
        categoryUrl: String,
        page: Int,
        force: Boolean = false,
    ): List<NovelSearchResult> = loadWith(keyOf(source.key, categoryUrl, page), force) {
        NovelSourceEngine.novelEngineFor(context, source).explore(categoryUrl, page, allowGate = true)
    }

    /** 某源整体作废。v1.4.27 Step4 审稿 A3：书源编辑保存、删除（含批量删）后由 [BookSourceManager.invalidateCaches] 调用。 */
    fun invalidateSource(sourceKey: String) {
        val prefix = "$sourceKey\u0001"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    internal fun clearAll() = cache.clear()

    /** 缓存/抓取逻辑（与网络无关，单测直接喂假抓取函数）。 */
    internal suspend fun loadWith(
        key: String,
        force: Boolean,
        fetch: suspend () -> List<NovelSearchResult>,
    ): List<NovelSearchResult> {
        if (!force) {
            val e = cache[key]
            if (e != null) {
                if (clock() - e.time <= CACHE_TTL_MS) return e.books
                cache.remove(key)
            }
        }
        val list = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) {
            if (cache.size >= MAX_ENTRIES && !cache.containsKey(key)) {
                cache.entries.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
            }
            cache[key] = Entry(clock(), list)
        } else {
            cache.remove(key)
        }
        return list
    }
}
