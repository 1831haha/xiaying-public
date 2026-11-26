package com.movieshell.data.novel

import android.content.Context
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.source.NovelEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.4.27 解缠⑤：目录单服务。
 *
 * 改版前目录有三条路：NovelDirectoryFill 的补取、搜书详情页自己调 engine.chapters()、
 * 换源/追更各自调 engine.chapters()。详情页刚抓过的目录，入架时补取又整本重抓一次。
 * 现在：
 * - **唯一抓取函数** [fetchChapters]：全仓 engine.chapters() 只在这里调用；成功的目录写进
 *   短时缓存（按 sourceKey + remoteId，10 分钟，最多 [MAX_ENTRIES] 本）；
 * - **未入架的书**（详情页）读 [remote]：命中缓存直接给，不命中才抓；返回 [TocState]；
 * - **入架补取**：NovelDirectoryFill 仍是补取逻辑（应用级作用域、按书去重、写盘、终态通知），
 *   它的抓取改走 [fetchChapters]，首次尝试先用 [cachedChapters]（详情页刚抓过就不重抓）；
 * - **在架的书**：[shelfState] 读 NovelStore 的 dirState；[awaitReady] 等补取终态
 *   （原 NovelDirectoryFill.awaitReady 搬来，语义逐字不变）。
 *
 * 换源与追更检查调 [fetchChapters] 时一律现抓（不读缓存），只顺手把结果写进缓存。
 */
object NovelTocService {

    /** 目录状态。 */
    sealed interface TocState {
        /** 在架书补取中（dirState=LOADING）。 */
        data object Loading : TocState

        /** 目录就绪。 */
        data class Ready(val chapters: List<ChapterMeta>) : TocState

        /**
         * 目录可能不全（v1.4.27 P0.3）：章号中段跳了一大截（[TocGap.detect]，如全本小说网只直出头尾
         * 约 45 章）。[chapters] 是已拿到的章节，照常可读；[gap] 给界面提示“约缺 N 章”与“展开全部”。
         */
        data class Partial(val chapters: List<ChapterMeta>, val gap: TocGap.Gap) : TocState

        /** 抓取失败 / 目录为空 / 书不在架。 */
        data object Failed : TocState
    }

    /** 缓存时长（毫秒）。 */
    const val CACHE_TTL_MS = 10 * 60_000L

    /** 缓存容量（本）。 */
    const val MAX_ENTRIES = 32

    private data class Entry(val time: Long, val chapters: List<ChapterMeta>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 单测可替换的时钟。 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private fun keyOf(sourceKey: String, remoteId: String) = "$sourceKey\u0001$remoteId"

    /** 缓存里未过期的目录；没有回 null。 */
    fun cachedChapters(sourceKey: String, remoteId: String): List<ChapterMeta>? {
        if (remoteId.isBlank()) return null
        val k = keyOf(sourceKey, remoteId)
        val e = cache[k] ?: return null
        if (clock() - e.time > CACHE_TTL_MS) {
            cache.remove(k)
            return null
        }
        return e.chapters
    }

    /** 作废某本书的缓存（详情页“重试”时用）。 */
    fun invalidate(sourceKey: String, remoteId: String) {
        cache.remove(keyOf(sourceKey, remoteId))
    }

    /**
     * v1.4.27 Step4 审稿 A3：按书源整体作废目录缓存（key 前缀 = "sourceKey\u0001"）。
     * 书源编辑保存、删除（含批量删）后由 [BookSourceManager.invalidateCaches] 调用，
     * 免得 10 分钟内还拿旧规则抓到的目录。
     */
    fun invalidateSource(sourceKey: String) {
        val prefix = "$sourceKey\u0001"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    internal fun clearAll() = cache.clear()

    internal fun put(sourceKey: String, remoteId: String, chapters: List<ChapterMeta>) {
        if (remoteId.isBlank() || chapters.isEmpty()) return
        if (cache.size >= MAX_ENTRIES) {
            cache.entries.minByOrNull { it.value.time }?.let { cache.remove(it.key) }
        }
        cache[keyOf(sourceKey, remoteId)] = Entry(clock(), chapters)
    }

    /**
     * 唯一抓取函数：调引擎拉目录，非空则写缓存。异常与取消原样抛出——
     * 各调用方原有的 runCatching / 失败口径不变（服务只换了“谁去调 chapters()”）。
     */
    suspend fun fetchChapters(engine: NovelEngine, sourceKey: String, book: NovelSearchResult): List<ChapterMeta> {
        val metas = engine.chapters(book)
        put(sourceKey, book.remoteId, metas)
        return metas
    }

    /**
     * 未入架书的目录（搜书详情页用）：缓存命中直接给；否则找引擎现抓。
     * 引擎找不到、抓取抛异常（取消除外）、目录为空 → Failed（与改版前详情页“加载目录失败”同口径）。
     */
    suspend fun remote(
        context: Context,
        sourceKey: String,
        book: NovelSearchResult,
        force: Boolean = false,
    ): TocState {
        if (force) invalidate(sourceKey, book.remoteId)
        cachedChapters(sourceKey, book.remoteId)?.let { return classify(it) }
        val metas = runCatching {
            val engine = NovelSourceEngine.novelEngineForKey(context, sourceKey) ?: return TocState.Failed
            fetchChapters(engine, sourceKey, book)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return if (metas.isNullOrEmpty()) TocState.Failed else classify(metas)
    }

    /** 非空目录分 Ready / Partial（缺段检测见 [TocGap]）。 */
    fun classify(chapters: List<ChapterMeta>): TocState =
        TocGap.detect(chapters.map { it.title })?.let { TocState.Partial(chapters, it) } ?: TocState.Ready(chapters)

    /** 展开结果。 */
    sealed interface ExpandResult {
        /** 拿到了更多章节（可能仍是 Partial）。 */
        data class Improved(val state: TocState, val before: Int, val after: Int) : ExpandResult
        /** 跟了展开链接/分页也没多出章节（站点要网页脚本展开，或页上没有可跟的链接）。 */
        data object NoMore : ExpandResult
        /** 这个源不支持（PY 源 / 源已删 / JS 源）。 */
        data object Unsupported : ExpandResult
        data object Failed : ExpandResult
    }

    /**
     * 详情页“展开全部”（v1.4.27 P0.3 第二档）：只对非 JS 的 JSON 源做——跟页上“展开全部/全部章节”
     * 普通链接与 ruleTocNext 分页再抓一次，章数比现有多才写缓存（入架补取会直接用这份）。
     * 靠网页脚本点击展开的站拿不到，返回 [ExpandResult.NoMore]，界面如实说明。
     */
    suspend fun expandAll(context: Context, sourceKey: String, book: NovelSearchResult, currentCount: Int): ExpandResult {
        val source = BookSourceManager.sources.value.find { it.key == sourceKey } ?: return ExpandResult.Unsupported
        if (NovelSourceEngine.isJsBookSource(source)) return ExpandResult.Unsupported
        val metas = runCatching { NovelSourceEngine.chapters(source, book, expand = true) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { return ExpandResult.Failed }
        if (metas.size <= currentCount) return ExpandResult.NoMore
        put(sourceKey, book.remoteId, metas)
        return ExpandResult.Improved(classify(metas), currentCount, metas.size)
    }

    /** 在架书的目录状态（读 NovelStore；不抓网）。 */
    suspend fun shelfState(context: Context, novelId: String): TocState {
        val dirState = NovelStore.get(context, novelId)?.dirState
        val chapters = if (dirState == NovelStore.DIR_LOADING || dirState == NovelStore.DIR_FAILED || dirState == null) {
            emptyList()
        } else {
            NovelStore.chapters(context, novelId)
        }
        return shelfStateOf(dirState, chapters)
    }

    /**
     * 纯函数（可单测）：目录终态 + 落盘章节 → TocState。
     * v1.4.27 Step4 审稿 A2：终态非空原来直接给 Ready、不做缺段检测；改为走 [classify]——
     * 缺段从落盘章节标题现算，所以入架缓存快路径（NovelDirectoryFill 直接标 DIR_READY）
     * 也不用另存缺段信息，读的时候就能得到 Partial。
     */
    internal fun shelfStateOf(dirState: String?, chapters: List<Chapter>): TocState = when (dirState) {
        NovelStore.DIR_LOADING -> TocState.Loading
        NovelStore.DIR_FAILED, null -> TocState.Failed
        else ->
            if (chapters.isEmpty()) TocState.Failed
            else classify(chapters.map { ChapterMeta(it.index, it.title, it.url, commentUrl = it.commentUrl) })
    }

    /** 等目录就绪：READY 立刻 true；LOADING 轮询至终态；FAILED／超时／书不在架 false。
     * （v0.249.0 原 NovelDirectoryFill.awaitReady，v1.4.27 搬入，逐字不变。） */
    suspend fun awaitReady(context: Context, novelId: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            when (NovelStore.get(context, novelId)?.dirState) {
                NovelStore.DIR_READY -> return true
                NovelStore.DIR_FAILED -> return false
                null -> return false
                else -> {
                    if (System.currentTimeMillis() >= deadline) return false
                    delay(500)
                }
            }
        }
    }
}
