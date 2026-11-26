package com.movieshell.data.novel

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.BookDetailInfo
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.ContentFail
import com.movieshell.domain.model.ContentResult
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.domain.model.ParagraphComment
import com.movieshell.domain.source.NovelEngine
import kotlinx.coroutines.CancellationException

/**
 * v1.1.6 分层第 2 批：PY 书源适配器——把绑规则的 PyBookRule 接到 NovelEngine。
 * PY 规则没有发现页与详情封面链：explore 回空列表，detailCover 回 null，
 * 与改版前调用方行为一致；bookDetail 走规则的 bookDetail 取名/作者/简介/封面。
 *
 * v1.4.27 解缠①（三引擎路由收敛）：PY 链路的四个函数体（searchPy / chaptersPy /
 * contentPy / bookDetailPy）连同 pyBookApi、pyRulePath 从 NovelSourceEngine 整体
 * 搬到本类伴生对象，函数签名与函数体逐字不动（只把失败汇总改走
 * NovelSourceEngine.recordSearchFailure——无收集器时即原 failedSearchNames.add）；
 * NovelSourceEngine 只留 JSON 路径与多源聚合（searchAllPy 改调这里的 searchPy）。
 */
class PyNovelEngine(
    private val context: Context,
    private val rule: PyBookSourceManager.PyBookRule,
) : NovelEngine {

    private val sourceKey: String get() = PyBookSourceManager.SOURCE_KEY_PREFIX + rule.name

    /** PY 规则无分页（bookSearch 只有一页）：page>1 回空列表（v1.4.27 解缠④）。 */
    override suspend fun search(keyword: String, allowGate: Boolean, page: Int): List<NovelSearchResult> =
        if (page > 1) emptyList() else searchPy(context, rule, keyword)

    override suspend fun chapters(book: NovelSearchResult): List<ChapterMeta> =
        chaptersPy(context, book)

    override suspend fun content(chapter: ChapterMeta, allowGate: Boolean): ContentResult {
        val text = contentPy(context, chapter, sourceKey)
        // PY 侧失败不分类（规则文件缺失/规则报错/回空都混在一起），按调用方
        // 原行为（ReaderScreen）定 UNKNOWN，不谎称 PARSE_EMPTY。
        return if (text.isBlank()) ContentResult.Failed(ContentFail.UNKNOWN)
        else ContentResult.Ok(text)
    }

    override suspend fun explore(categoryUrl: String, page: Int, allowGate: Boolean): List<NovelSearchResult> =
        emptyList()

    override suspend fun bookDetail(book: NovelSearchResult): BookDetailInfo =
        bookDetailPy(context, book)

    override suspend fun detailCover(book: NovelSearchResult): String? = null

    /** v1.1.10：PY 书源没有 ruleParagraphComments，结构化段评恒为空。 */
    override suspend fun paragraphComments(
        novelId: String,
        chapter: ChapterMeta,
        allowGate: Boolean,
    ): List<ParagraphComment> = emptyList()

    // ---------------- v0.64.0 本地 PY 书源链路（v1.4.27 自 NovelSourceEngine 搬入） ----------------
    // PY 书源的 NovelSearchResult.sourceKey 为 "pybook:<文件名>"，
    // 凭此外缀找到规则文件，走 PyBookApi 调 bookSearch/bookDetail/bookChapter。
    companion object {
        private const val TAG = "NovelSourceEngine" // 沿用原日志标签，悬浮日志过滤口径不变

        private val pyBookApi = PyBookApi()

        /** 拉 PY 书源的详情（名/作者/简介/封面），供 PyNovelEngine.bookDetail。 */
        suspend fun bookDetailPy(context: Context, book: NovelSearchResult): BookDetailInfo {
            val path = pyRulePath(context, book) ?: return BookDetailInfo()
            return runCatching { pyBookApi.detail(path, book.remoteId) }
                .onFailure {
                    if (it is CancellationException) throw it
                    LogBus.w(TAG, "PY 书源详情失败 ${book.sourceKey}: ${it.message}")
                }
                .getOrNull()
                ?.let {
                    BookDetailInfo(
                        name = it.name,
                        author = it.author.orEmpty(),
                        intro = it.intro,
                        cover = it.cover.orEmpty(),
                    )
                }
                ?: BookDetailInfo()
        }

        private fun pyRulePath(context: Context, book: NovelSearchResult): String? {
            val name = PyBookSourceManager.fileNameOf(book.sourceKey) ?: return null
            return PyBookSourceManager.rulePath(context, name)
        }

        /** 在单个 PY 书源里搜书。 */
        suspend fun searchPy(
            context: Context,
            rule: PyBookSourceManager.PyBookRule,
            keyword: String,
        ): List<NovelSearchResult> {
            val path = PyBookSourceManager.rulePath(context, rule.name) ?: return emptyList()
            return runCatching { pyBookApi.search(path, keyword) }
                .onFailure {
                    // v0.247.0（取消语义）：同 searchAll，取消原样抛出。
                    if (it is CancellationException) throw it
                    // v1.1.12：记一笔失败，搜书页据此区分“真没书”和“搜失败了”。
                    NovelSourceEngine.recordSearchFailure(rule.name)
                    LogBus.w(TAG, "PY 书源搜索失败 ${rule.name}: ${it.message}")
                }
                .getOrDefault(emptyList())
                .map { it.copy(sourceKey = PyBookSourceManager.SOURCE_KEY_PREFIX + rule.name) }
        }

        /** 拉 PY 书源的目录。 */
        suspend fun chaptersPy(context: Context, book: NovelSearchResult): List<ChapterMeta> {
            val path = pyRulePath(context, book) ?: return emptyList()
            return runCatching { pyBookApi.detail(path, book.remoteId)?.chapters.orEmpty() }
                .onFailure {
                    // v0.247.0（取消语义）：同 searchAll，取消原样抛出。
                    if (it is CancellationException) throw it
                    LogBus.w(TAG, "PY 书源目录失败 ${book.sourceKey}: ${it.message}")
                }
                .getOrDefault(emptyList())
                // v0.194.0：导航章兜底过滤（见 NovelStore.isNavChapterTitle）。
                .filterNot { NovelStore.isNavChapterTitle(it.title) }
                .mapIndexed { i, m -> m.copy(index = i) }
        }

        /** 拉 PY 书源的单章正文。 */
        suspend fun contentPy(context: Context, chapter: ChapterMeta, sourceKey: String): String {
            val name = PyBookSourceManager.fileNameOf(sourceKey) ?: return ""
            val path = PyBookSourceManager.rulePath(context, name) ?: return ""
            if (chapter.url.isBlank()) return ""
            return runCatching { pyBookApi.chapter(path, chapter.url) }
                .onFailure {
                    // v0.247.0（取消语义）：同 searchAll，取消原样抛出。
                    if (it is CancellationException) throw it
                    LogBus.w(TAG, "PY 书源正文失败 $sourceKey: ${it.message}")
                }
                .getOrDefault("")
        }
    }
}
