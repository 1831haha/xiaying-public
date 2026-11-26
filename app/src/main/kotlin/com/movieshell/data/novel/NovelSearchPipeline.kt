package com.movieshell.data.novel

import android.content.Context
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * v1.4.27 解缠④：搜书管线合一。
 *
 * 改版前找书页（NovelSearchViewModel）和换源弹窗（ChangeSourceDialog）各自拼一遍
 * “JSON 源 searchAllFlow 流式 + PY 源 searchAllPy 一次性 → 按源分组”，PY 组名、key 前缀、
 * 空组过滤各写一份。现在两处都收 [groups] 这一条流：
 * - JSON 源每出一组就发一条 [Hit]（带精准过滤掉的条数；books 为空但 filtered>0 的也发，
 *   调用方据此累计“已过滤 N 条”，追加列表时只认 books 非空的）；
 * - PY 源整批跑完后逐组发出（只发非空组，[Hit.isPy]=true；PY 不走精准过滤——与改版前一致）；
 * - JSON 与 PY 并发跑，流在两边都结束后完成；取消向下传给两边。
 *
 * 失败口径不在管线里：clearSearchFailures / failedSearchLabel 仍由找书页 VM 自己管
 *（换源弹窗本来就不读失败汇总，行为不变）。管线只负责“搜”和“分组”，
 * 排序（换源的当前源置顶）、节流刷新（找书页 400ms 批量刷）留给调用方。
 */
object NovelSearchPipeline {

    /** 一个书源的一组结果。key 即 NovelStore.sourceKey 口径（PY 带 [PyBookSourceManager.SOURCE_KEY_PREFIX]）。 */
    data class Hit(
        val key: String,
        val name: String,
        val books: List<NovelSearchResult>,
        val filtered: Int = 0,
        val isPy: Boolean = false,
    )

    /** PY 规则的组 key / 组名（原 VM 与换源弹窗各写一份，收到这里）。 */
    fun pyKey(rule: PyBookSourceManager.PyBookRule): String = PyBookSourceManager.SOURCE_KEY_PREFIX + rule.name
    fun pyName(rule: PyBookSourceManager.PyBookRule): String = "${rule.title}（本地PY）"

    /** 只搜 PY 规则、一次性返回非空组（找书页“锁定单 PY 源”用）。 */
    suspend fun searchPyGroups(
        context: Context,
        rules: List<PyBookSourceManager.PyBookRule>,
        keyword: String,
    ): List<Hit> {
        if (rules.isEmpty()) return emptyList()
        return NovelSourceEngine.searchAllPy(context, rules, keyword)
            .filter { it.second.isNotEmpty() }
            .map { (r, books) -> Hit(pyKey(r), pyName(r), books, isPy = true) }
    }

    /** 全部启用源（JSON + PY）聚合搜书，按源分组流式发出。 */
    fun groups(
        context: Context,
        sources: List<BookSource>,
        pyRules: List<PyBookSourceManager.PyBookRule>,
        keyword: String,
        precision: Boolean = false,
    ): Flow<Hit> = merge(
        json = NovelSourceEngine.searchAllFlow(sources, keyword, precision),
        py = { searchPyGroups(context, pyRules, keyword) },
    )

    /** 合流逻辑（与网络无关，单测直接喂假流）。 */
    internal fun merge(
        json: Flow<NovelSourceEngine.SourceSearchResult>,
        py: suspend () -> List<Hit>,
    ): Flow<Hit> = channelFlow {
        launch {
            py().forEach { if (it.books.isNotEmpty()) send(it) }
        }
        json.collect { res ->
            if (res.books.isNotEmpty() || res.filtered > 0) {
                send(Hit(res.source.key, res.source.name, res.books, res.filtered))
            }
        }
    }
}
