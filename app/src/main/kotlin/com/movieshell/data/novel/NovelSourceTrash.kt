package com.movieshell.data.novel

import android.content.Context
import com.movieshell.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * v1.4.27 P0.7：小说书源回收站（软删除＋恢复）。
 *
 * 改版前书源删除是硬删除（BookSourceManager.remove 直接从表里去掉、PyBookSourceManager.remove
 * 直接删 .py 文件），删错无后悔药。影视侧的 [com.movieshell.data.store.TrashStore] 按影视规则目录
 * 恢复（py 恢复到 py/ 根目录，而小说 PY 在 py/book/），种类集合也不同，硬塞进去会恢复错位置，
 * 所以小说侧单独一个回收站，放 App 私有目录（filesDir/novel_source_trash/），不依赖存储授权。
 *
 * 存储：index.json（条目元数据，原子写）＋ 每条一个载荷文件 <id>.json / <id>.py。
 * - JSON 源：载荷是 BookSource.toJson() 全量（启用状态、规则、checkKeyWord 都在里面，恢复原样）；
 * - PY 源：载荷是 .py 文件原文副本；[Entry.enabled] 记删除前的启用状态，恢复时还原。
 *
 * 顺序保证（与 TrashStore 同口径，宁可留副本也不丢源）：先写载荷、再写索引，都成功后调用方才真删；
 * 真删失败调用方调 [drop] 回滚。恢复：调用方先把源加回，成功后再 [drop]；目标已存在（同 key /
 * 同文件名）报冲突、不覆盖。不做自动过期（不偷偷删用户东西），提供单条彻底删除与清空。
 *
 * 纯 java.io 实现（不碰 Context），可在 JVM 单测里直接测；[of] 只负责定位目录。
 */
class NovelSourceTrash(private val dir: File) {

    data class Entry(
        val id: String,
        /** [KIND_JSON] / [KIND_PY] */
        val kind: String,
        /** 展示名（JSON 源名 / PY 标题） */
        val title: String,
        /** 原 key：JSON 为 BookSource.key；PY 为原文件名（含 .py） */
        val originKey: String,
        val deletedAt: Long,
        /** PY：删除前是否启用（JSON 的启用状态在载荷里） */
        val enabled: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("kind", kind).put("title", title)
            .put("originKey", originKey).put("deletedAt", deletedAt).put("enabled", enabled)

        companion object {
            fun fromJson(o: JSONObject): Entry? = runCatching {
                Entry(
                    id = o.getString("id"),
                    kind = o.getString("kind"),
                    title = o.optString("title"),
                    originKey = o.getString("originKey"),
                    deletedAt = o.optLong("deletedAt"),
                    enabled = o.optBoolean("enabled", false),
                ).takeIf { it.kind == KIND_JSON || it.kind == KIND_PY }
            }.getOrNull()
        }
    }

    private val lock = Any()
    private val indexFile get() = File(dir, INDEX)

    /** 全部条目，新删的在前。索引坏了按空处理，并保全原文件（AtomicFile.preserveCorrupt）。 */
    fun list(): List<Entry> = synchronized(lock) { readIndex() }.sortedByDescending { it.deletedAt }

    fun count(): Int = list().size

    /** 载荷文件（恢复用）；没有返回 null。 */
    fun payloadOf(e: Entry): File? = payloadFile(e).takeIf { it.isFile }

    /** JSON 源进回收站：写载荷 + 索引，成功返回条目，失败返回 null（调用方不得继续删）。 */
    fun putJson(title: String, key: String, sourceJson: String, now: Long = System.currentTimeMillis()): Entry? =
        put(Entry(newId(), KIND_JSON, title, key, now)) { f -> AtomicFile.writeText(f, sourceJson) }

    /** PY 源进回收站：拷一份文件副本 + 写索引。 */
    fun putPy(title: String, fileName: String, file: File, enabled: Boolean, now: Long = System.currentTimeMillis()): Entry? {
        if (!file.isFile) return null
        return put(Entry(newId(), KIND_PY, title, fileName, now, enabled)) { f ->
            runCatching { file.copyTo(f, overwrite = false); f.length() == file.length() }.getOrDefault(false)
        }
    }

    /** 去掉一条（恢复成功后 / 彻底删除 / 真删失败回滚），连同载荷。返回索引是否写成。 */
    fun drop(id: String): Boolean = synchronized(lock) {
        val all = readIndex()
        val e = all.find { it.id == id } ?: return true
        if (!writeIndex(all.filterNot { it.id == id })) return false
        payloadFile(e).delete()
        true
    }

    /** 清空：返回删掉的条数。 */
    fun clear(): Int = synchronized(lock) {
        val all = readIndex()
        if (all.isEmpty()) return 0
        if (!writeIndex(emptyList())) return 0
        all.forEach { payloadFile(it).delete() }
        all.size
    }

    // ---------------- 内部 ----------------

    private fun put(e: Entry, writePayload: (File) -> Boolean): Entry? = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val pf = payloadFile(e)
        if (!writePayload(pf)) { pf.delete(); return null }
        if (!writeIndex(readIndex() + e)) { pf.delete(); return null }
        e
    }

    private fun payloadFile(e: Entry) = File(dir, e.id + if (e.kind == KIND_PY) ".py" else ".json")

    private fun newId() = UUID.randomUUID().toString()

    private fun readIndex(): List<Entry> {
        val f = indexFile
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { Entry.fromJson(arr.getJSONObject(it)) }
        }.getOrElse {
            runCatching { AtomicFile.preserveCorrupt(f) }
            emptyList()
        }
    }

    private fun writeIndex(list: List<Entry>): Boolean {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return AtomicFile.writeText(indexFile, arr.toString())
    }

    companion object {
        const val KIND_JSON = "json"
        const val KIND_PY = "py"
        private const val INDEX = "index.json"
        private const val DIR = "novel_source_trash"

        @Volatile private var instance: NovelSourceTrash? = null

        /** 进程内单例（同一把锁），目录 filesDir/novel_source_trash。 */
        fun of(context: Context): NovelSourceTrash =
            instance ?: synchronized(this) {
                instance ?: NovelSourceTrash(File(context.applicationContext.filesDir, DIR)).also { instance = it }
            }
    }
}
