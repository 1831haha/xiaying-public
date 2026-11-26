package com.movieshell.data.novel

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentFail
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.source.NovelEngine
import kotlinx.coroutines.CancellationException

/**
 * v1.4.27 解缠③：正文净化单入口。
 *
 * 之前全局净化只在 ReaderScreen.fetchChapter 里做（引擎 content() 只做源内替换），
 * 将来整本缓存、追更预取等任何新的“拉正文写盘”路径都得各抄一遍，漏抄就写进未净化正文
 * 或把净化后的空白写盘。现在所有“拉正文→写盘”的路径都走 [fetchAndStore]。
 *
 * 处理顺序（固定，改顺序前先看这里）：
 * 1. 引擎 content()：源内规则提取 + 源内替换（JSON 源 ruleContentReplace，JS 源同链路；PY 无）；
 * 2. 全局净化：NovelPurifyEngine.purify（净化规则管理页的规则，按 bookUrl/sourceKey 过滤 scope，
 *    含换行归一与段首缩进）；
 * 3. 空白保护：净化后为空不写盘、不覆盖旧正文，原文随结果带回（只供“查看原文”临时显示，绝不写回缓存）。
 *
 * 为什么不直接塞进 NovelEngine.content()：NovelEngine 在 domain 层，不能依赖 data 层的
 * PurifyRuleStore/Context；而且“查看原文”需要拿到净化前的原文，content() 的契约只回一份文本。
 * 引擎 content() 本身保留为“源内处理后的原文”，语义不变。
 */
object NovelContentService {

    private const val TAG = "NovelContent"

    /** 拉正文 + 净化 + 写盘的结果。 */
    sealed interface Outcome {
        /** 净化后正文（已写盘）。 */
        data class Ok(val text: String) : Outcome

        /** 正文抓到了但净化后为空：未写盘；[raw] 是净化前原文，只供临时查看。 */
        data class PurifyEmpty(val raw: String) : Outcome

        /** 抓取失败（分类沿用 ContentFail）。 */
        data class Failed(val reason: ContentFail) : Outcome
    }

    /** 纯函数版净化判定（单测用；fetchAndStore 内部同一逻辑）。 */
    fun purifyOrEmpty(
        raw: String,
        rules: List<PurifyRule>,
        bookUrl: String,
        sourceKey: String,
    ): Outcome {
        if (raw.isBlank()) return Outcome.Failed(ContentFail.UNKNOWN)
        val clean = NovelPurifyEngine.purify(raw, rules, bookUrl = bookUrl, sourceKey = sourceKey)
        return if (clean.isBlank()) Outcome.PurifyEmpty(raw) else Outcome.Ok(clean)
    }

    /**
     * 拉一章正文、全局净化、非空才写盘。
     * - 引擎抛的非取消异常按 UNKNOWN（与改版前 ReaderScreen 口径一致）；取消原样抛；
     * - 引擎回空串按 UNKNOWN（改版前同）；
     * - [novelId] 即书 id（NovelStore 的书键，也是净化规则 scope 里的 bookUrl）。
     */
    suspend fun fetchAndStore(
        context: Context,
        engine: NovelEngine,
        novelId: String,
        sourceKey: String,
        chapter: ChapterMeta,
        allowGate: Boolean,
    ): Outcome {
        val outcome = fetchPurified(context, engine, novelId, sourceKey, chapter, allowGate)
        when (outcome) {
            is Outcome.Ok -> NovelStore.updateChapterContent(context, novelId, chapter.index, outcome.text)
            is Outcome.PurifyEmpty ->
                // v0.251.0：净化后空白不写盘、不覆盖旧正文——空白写盘是重复拉取循环的根因。
                LogBus.d(TAG, "净化后空白不写盘 chapter=${chapter.index} rawLen=${outcome.raw.length}")
            is Outcome.Failed -> Unit
        }
        return outcome
    }

    /**
     * v1.4.27：拉一章并净化、**不写盘**（整本缓存批量写盘用；阅读器仍走 [fetchAndStore]）。
     * 口径与 [fetchAndStore] 前半段逐字相同（原样从其中拆出）。
     */
    suspend fun fetchPurified(
        context: Context,
        engine: NovelEngine,
        novelId: String,
        sourceKey: String,
        chapter: ChapterMeta,
        allowGate: Boolean,
    ): Outcome {
        val result = runCatching { engine.content(chapter, allowGate) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { ContentResult.Failed(ContentFail.UNKNOWN) }
        val raw = when (result) {
            is ContentResult.Ok -> result.text
            is ContentResult.Failed -> return Outcome.Failed(result.reason)
        }
        if (raw.isBlank()) return Outcome.Failed(ContentFail.UNKNOWN)
        val rules = PurifyRuleStore.list(context)
        return purifyOrEmpty(raw, rules, bookUrl = novelId, sourceKey = sourceKey)
    }
}
