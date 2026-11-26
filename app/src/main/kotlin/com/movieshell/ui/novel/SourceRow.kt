package com.movieshell.ui.novel

import android.content.Context
import com.movieshell.core.util.SafeFileName
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSourceTrash
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookApi
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.domain.model.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.4.27 解缠⑧：书源行模型统一。
 *
 * 存储层仍是两套（JSON 书源表 / PY 规则文件，格式不同不硬合）；书源管理页的“行”收成这一个
 * 密封模型。批量选择 key、在架 sourceKey、搜索过滤、删除、测试都从行模型取，JSON 行与 PY 行
 * 不再各写一份。**界面不动**：LazyColumn 的 item key（py_xxx / json_xxx）、过滤口径、
 * 删除提示文案与改版前逐字一致。Step 3 的“搜索测试”状态按 [selectKey] 落。
 */
sealed interface SourceRow {
    /** 批量选择 / 列表 item key（改版前的 "py_${name}" / "json_${key}"）。 */
    val selectKey: String

    /** NovelStore 里书的 sourceKey（统计在架本数、换源用）。 */
    val shelfKey: String

    /** 行标题（测试结果前缀等用）。 */
    val title: String

    /** 顶部搜索框过滤：JSON 比 name/url，PY 比 name/title，忽略大小写；空查询全保留。 */
    fun matches(query: String): Boolean

    data class Json(val source: BookSource) : SourceRow {
        override val selectKey get() = JSON_PREFIX + source.key
        override val shelfKey get() = source.key
        override val title get() = source.name
        override fun matches(query: String) = query.isBlank() ||
            source.name.contains(query, ignoreCase = true) || source.url.contains(query, ignoreCase = true)
    }

    data class Py(val rule: PyBookSourceManager.PyBookRule) : SourceRow {
        override val selectKey get() = PY_PREFIX + rule.name
        override val shelfKey get() = PyBookSourceManager.SOURCE_KEY_PREFIX + rule.name
        override val title get() = rule.title
        override fun matches(query: String) = query.isBlank() ||
            rule.name.contains(query, ignoreCase = true) || rule.title.contains(query, ignoreCase = true)
    }

    companion object {
        const val PY_PREFIX = "py_"
        const val JSON_PREFIX = "json_"

        /** 列表顺序：PY 在前、JSON 在后（与改版前页面一致）。 */
        fun rows(pyRules: List<PyBookSourceManager.PyBookRule>, sources: List<BookSource>): List<SourceRow> =
            pyRules.map { Py(it) } + sources.map { Json(it) }

        /** 选择 key → 在架 sourceKey（批量删除确认里统计受影响本数用；未知前缀原样返回）。 */
        fun shelfKeyOf(selectKey: String): String = when {
            selectKey.startsWith(PY_PREFIX) -> PyBookSourceManager.SOURCE_KEY_PREFIX + selectKey.removePrefix(PY_PREFIX)
            selectKey.startsWith(JSON_PREFIX) -> selectKey.removePrefix(JSON_PREFIX)
            else -> selectKey
        }

        /** 批量勾选：勾上加、取消减。 */
        fun toggled(selected: Set<String>, selectKey: String, checked: Boolean): Set<String> =
            if (checked) selected + selectKey else selected - selectKey

        /**
         * v1.4.27 Step4 审稿 A4：批量“全选”= 已选 ∪ 可见行。原来直接替换成可见行，
         * 折叠组里已手动勾上的行会被丢掉（计数还会出现 5/3）。折叠组里没勾的行仍不会被勾上。
         */
        fun selectAllVisible(selected: Set<String>, visible: Collection<String>): Set<String> = selected + visible
    }
}

/** 行动作：删除、测试——JSON / PY 共用入口。 */
object SourceRowActions {

    /**
     * 删除一行，返回是否真删掉。v1.4.27 P0.7：改软删除——先进 [NovelSourceTrash]（写载荷＋索引都成功），
     * 再真删；回收站写不进去就不删（返回 false，提示“保存失败”同口径）；真删失败则撤回回收站条目。
     * JSON 删除无返回值，按“删完 key 还在即失败”判（v0.186.0 / v0.217.0 F45 同款）；PY 删除返回 false 即文件删除失败。
     */
    suspend fun delete(context: Context, row: SourceRow): Boolean = withContext(Dispatchers.IO) {
        when (row) {
            is SourceRow.Json -> softDeleteJson(context, row.source)
            is SourceRow.Py -> softDeletePy(context, row.rule.name, row.rule.title)
        }
    }

    private suspend fun softDeleteJson(context: Context, source: BookSource): Boolean {
        val trash = NovelSourceTrash.of(context)
        val entry = trash.putJson(source.name, source.key, source.toJson().toString()) ?: return false
        BookSourceManager.remove(context, source.key)
        val ok = BookSourceManager.sources.value.none { it.key == source.key }
        if (!ok) trash.drop(entry.id)
        return ok
    }

    private suspend fun softDeletePy(context: Context, name: String, title: String): Boolean {
        val file = SafeFileName.childInside(PyBookSourceManager.dir(context), name)
        // 文件本就不在：没有可留的东西，按原逻辑（remove 视为已删除）走
        if (file == null || !file.isFile) return PyBookSourceManager.remove(context, name)
        val trash = NovelSourceTrash.of(context)
        val entry = trash.putPy(title, name, file, PyBookSourceManager.isEnabled(name)) ?: return false
        val ok = PyBookSourceManager.remove(context, name)
        if (!ok) trash.drop(entry.id)
        // v1.4.27 Step4 审稿 A3：PY 源删除同样作废它的目录/发现缓存（JSON 源在 BookSourceManager.remove 里清）。
        else BookSourceManager.invalidateCaches(PyBookSourceManager.SOURCE_KEY_PREFIX + name)
        return ok
    }

    /** 按选择 key 批量删除（同样进回收站），返回实删个数（未知前缀 / 已不存在的 JSON key 跳过、不计数）。 */
    suspend fun deleteBySelectKeys(context: Context, keys: Collection<String>): Int = withContext(Dispatchers.IO) {
        var ok = 0
        keys.forEach { k ->
            when {
                k.startsWith(SourceRow.PY_PREFIX) -> {
                    val name = k.removePrefix(SourceRow.PY_PREFIX)
                    if (softDeletePy(context, name, name.removeSuffix(".py"))) ok++
                }
                k.startsWith(SourceRow.JSON_PREFIX) -> {
                    val key = k.removePrefix(SourceRow.JSON_PREFIX)
                    val src = BookSourceManager.sources.value.find { it.key == key }
                    if (src != null && softDeleteJson(context, src)) ok++
                }
            }
        }
        ok
    }

    /** 回收站恢复结果。 */
    enum class RestoreResult {
        OK,
        /** 同 key 的 JSON 源 / 同名 PY 文件已存在——不覆盖，条目留在回收站。 */
        CONFLICT,
        /** 载荷丢了或解析不了（条目可彻底删除）。 */
        BROKEN,
        /** 写盘 / 无存储授权等失败，可重试。 */
        FAILED,
    }

    /** 从回收站恢复一条：先加回源，成功后才删回收站条目（宁可留条目也不丢源）。 */
    suspend fun restore(context: Context, entry: NovelSourceTrash.Entry): RestoreResult = withContext(Dispatchers.IO) {
        val trash = NovelSourceTrash.of(context)
        val payload = trash.payloadOf(entry) ?: return@withContext RestoreResult.BROKEN
        val r = when (entry.kind) {
            NovelSourceTrash.KIND_JSON -> {
                val s = runCatching { BookSource.fromJson(payload.readText()) }.getOrNull()
                when {
                    s == null -> RestoreResult.BROKEN
                    BookSourceManager.sources.value.any { it.key == s.key } -> RestoreResult.CONFLICT
                    BookSourceManager.importSources(context, listOf(s)) == 1 -> RestoreResult.OK
                    else -> RestoreResult.FAILED
                }
            }
            NovelSourceTrash.KIND_PY -> restorePy(context, entry, payload)
            else -> RestoreResult.BROKEN
        }
        if (r == RestoreResult.OK) trash.drop(entry.id)
        r
    }

    private suspend fun restorePy(context: Context, entry: NovelSourceTrash.Entry, payload: java.io.File): RestoreResult {
        if (!StoragePaths.hasAccess(context)) return RestoreResult.FAILED
        val dst = SafeFileName.childInside(PyBookSourceManager.dir(context), entry.originKey) ?: return RestoreResult.BROKEN
        if (dst.exists()) return RestoreResult.CONFLICT
        val staging = java.io.File(dst.parentFile, "${dst.name}.${java.util.UUID.randomUUID()}.tmp")
        val ok = runCatching {
            payload.copyTo(staging, overwrite = false)
            staging.length() == payload.length() && !dst.exists() && staging.renameTo(dst)
        }.getOrDefault(false)
        if (!ok) {
            staging.delete()
            return RestoreResult.FAILED
        }
        PyBookSourceManager.load(context)
        if (entry.enabled) PyBookSourceManager.setEnabled(context, entry.originKey, true)
        return RestoreResult.OK
    }

    /** 恢复结果的提示文案（界面直接用）。 */
    fun restoreMessage(title: String, r: RestoreResult): String = when (r) {
        RestoreResult.OK -> "已恢复「$title」"
        RestoreResult.CONFLICT -> "恢复失败：已有同名/同地址的书源，请先删除或改名后再恢复"
        RestoreResult.BROKEN -> "恢复失败：回收站里的副本已损坏或丢失，只能彻底删除"
        RestoreResult.FAILED -> "恢复失败：保存失败或没有存储权限，请重试"
    }

    /**
     * PY 行测试（v0.67.0 连通性测试原逻辑，v1.4.27 改结构化结果：0 条改「未验证」，与 JSON 一致）。
     * PY 不弹关键词输入框（定稿“不做 PY 输入框”），关键词用 PyBookApi.testSearch 默认的「斗罗」。
     */
    suspend fun testPy(context: Context, row: SourceRow.Py): TestResultDisplay {
        val keyword = BookSource.DEFAULT_CHECK_KEYWORD
        val path = PyBookSourceManager.rulePath(context, row.rule.name)
        val t0 = System.currentTimeMillis()
        val r = path?.let { PyBookApi().testSearch(it, keyword) }
        return TestResultDisplay.ofPy(row.title, keyword, r, System.currentTimeMillis() - t0)
    }

    /**
     * JSON 行搜索测试（定稿 §七“执行流程 6 步”，在 SourceTestRunner 的锁内调用）：
     * 1. 锁后按 key 从管理器重读当前源（不用列表里的旧对象）；源已删 → null（不发请求、不出结果）；
     * 2. 用重读到的配置快照测（测试中用户改源不影响本次请求）；
     * 3. 未配置搜索由 testSearch 先判，不发请求；
     * 4. 关键词：输入框 trim 后空白回落源 checkKeyWord / 「斗罗」（[resolveKeyword]）；
     * 5. NovelSourceEngine.testSearch（allowGate=false，30 秒墙钟，取消原样抛）；
     * 6. 写结果前再查一次源还在不在，删了 → null（与删源时 cancel Job 双保险）。
     */
    suspend fun testJson(sourceKey: String, inputKeyword: String): TestResultDisplay? {
        val snapshot = BookSourceManager.sources.value.find { it.key == sourceKey } ?: return null
        val keyword = resolveKeyword(inputKeyword, snapshot)
        val t0 = System.currentTimeMillis()
        val r = NovelSourceEngine.testSearch(snapshot, keyword)
        val elapsed = System.currentTimeMillis() - t0
        if (BookSourceManager.sources.value.none { it.key == sourceKey }) return null
        return TestResultDisplay.ofJson(snapshot.name, keyword, r, elapsed)
    }

    /** 输入框关键词：trim；空白回落源自带 checkKeyWord，再空回落「斗罗」（A2/A3/A4）。 */
    fun resolveKeyword(input: String, source: BookSource): String =
        input.trim().ifBlank { source.effectiveCheckKeyWord }
}

/** v1.4.27 P0.2：书源分组展示的分组口径（纯函数，可单测）。 */
object SourceGroups {
    const val UNGROUPED = "未分组"

    /** JSON 源超过这个数时，首次进书源管理默认全部折叠。 */
    const val AUTO_COLLAPSE_OVER = 100

    private val SPLIT = Regex("[,，;；]")

    /**
     * 一个源归哪个组：group 字段按 , ， ; ； 拆开取第一个非空名（阅读书源 group 可写多个，
     * 列表只放在第一个组里，避免同一源出现两次导致 LazyColumn key 重复）；空即 [UNGROUPED]。
     */
    fun primaryGroup(source: BookSource): String =
        source.group.split(SPLIT).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: UNGROUPED

    /** 按首次出现顺序分组（保持用户导入顺序），“未分组”恒在最后。 */
    fun group(sources: List<BookSource>): List<Pair<String, List<BookSource>>> {
        val m = LinkedHashMap<String, MutableList<BookSource>>()
        sources.forEach { m.getOrPut(primaryGroup(it)) { mutableListOf() }.add(it) }
        val ungrouped = m.remove(UNGROUPED)
        return m.map { it.key to it.value.toList() } + listOfNotNull(ungrouped?.let { UNGROUPED to it.toList() })
    }
}
