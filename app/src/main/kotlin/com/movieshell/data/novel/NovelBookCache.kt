package com.movieshell.data.novel

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.ChapterMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.4.27 P0.1：整本缓存（离线下载）。
 *
 * 改版前只有阅读器“向后预加载 10 章”。现在书籍详情里可一键缓存全本：
 * - 应用级作用域跑（离开书籍详情/书架继续下），按书 id 去重，同一本只跑一个任务；
 * - 只拉“有地址、还没正文”的章，已缓存的跳过（断点续下：取消/暂停后再点“继续缓存”补缺）；
 * - 逐章走 [NovelContentService.fetchPurified]（与阅读器同一净化口径），**allowGate=false**——
 *   后台任务不弹验证窗（见 VerifyPolicy 调用点表）；净化后为空的章不写盘（与阅读器同口径）；
 * - 每 [FLUSH_EVERY] 章批量写一次盘（[NovelStore.updateChapterContents]，带章节地址校验，
 *   中途换源不会把旧源正文写进新目录）；取消/暂停时攒着的也落盘，不白下；
 * - 连续失败 [MAX_CONSECUTIVE_FAIL] 章即停（多半撞了验证墙或断网，别把站点打爆），标“已暂停”；
 * - 抓取限流沿用引擎正文链自带的 withSearchThrottle（Semaphore(4)），本任务串行逐章，不额外加压。
 *
 * 已知限制：不是 WorkManager 前台任务，进程被系统杀掉时随之中止，再点“继续缓存”补缺即可。
 */
object NovelBookCache {

    private const val TAG = "NovelBookCache"
    const val FLUSH_EVERY = 20
    const val MAX_CONSECUTIVE_FAIL = 5

    /** 单本缓存进度。 */
    data class Progress(
        val total: Int,
        val done: Int,
        val failed: Int = 0,
        val running: Boolean = true,
        /** 停下的原因；正常完成且无失败时为 null。 */
        val stopReason: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    fun isRunning(novelId: String): Boolean = jobs[novelId]?.isActive == true

    /** 已缓存章数 / 总章数。没地址的章（本地 TXT 等）无从下载，算已缓存——与下载循环口径一致。 */
    suspend fun cachedCount(context: Context, novelId: String): Pair<Int, Int> {
        val chapters = NovelStore.chapters(context, novelId)
        return chapters.count { it.content.isNotBlank() || it.url.isBlank() } to chapters.size
    }

    /** 开始/继续缓存全本；已在跑则忽略。 */
    fun start(context: Context, novelId: String) {
        val app = context.applicationContext
        launchOnce(novelId) {
            try {
                runFor(app, novelId)
            } catch (e: CancellationException) {
                setProgress(novelId) { it?.copy(running = false, stopReason = "已取消") }
                throw e
            } catch (e: Exception) {
                LogBus.w(TAG, "整本缓存异常 $novelId: ${e.message}")
                setProgress(novelId) { it?.copy(running = false, stopReason = "缓存出错") }
            }
        }
    }

    /**
     * v1.4.27 Step4 审稿 A1：原子去重启动。原来“查 jobs[novelId]?.isActive → launch → jobs[novelId]=job”
     * 三步不是原子操作，两次几乎同时的 start() 会各起一个任务。改为在 synchronized(jobs) 里
     * check-and-put：任务以 LAZY 方式创建，先登记进 jobs 再 start()，登记前不会跑、不会先跑完；
     * 结束时只移除自己（remove(key, value)），不误删后起的新任务。
     * 返回 true=本次真的启动了；false=已有任务在跑，忽略。
     */
    internal fun launchOnce(novelId: String, block: suspend () -> Unit): Boolean {
        val job: Job
        synchronized(jobs) {
            if (jobs[novelId]?.isActive == true) return false
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    coroutineContext[Job]?.let { jobs.remove(novelId, it) }
                }
            }
            jobs[novelId] = job
        }
        job.start()
        return true
    }

    fun cancel(novelId: String) {
        jobs.remove(novelId)?.cancel()
    }

    private fun setProgress(novelId: String, f: (Progress?) -> Progress?) {
        _progress.update { m ->
            val n = f(m[novelId])
            if (n == null) m - novelId else m + (novelId to n)
        }
    }

    private suspend fun runFor(context: Context, novelId: String) {
        val novel = NovelStore.get(context, novelId) ?: return
        val chapters = NovelStore.chapters(context, novelId)
        val engine = NovelSourceEngine.novelEngineForKey(context, novel.sourceKey)
        if (engine == null) {
            val (done, total) = cachedCount(context, novelId)
            setProgress(novelId) { Progress(total, done, running = false, stopReason = "书源不可用") }
            return
        }
        val result = download(
            chapters = chapters,
            onProgress = { p -> setProgress(novelId) { p } },
            fetch = { c ->
                when (val r = NovelContentService.fetchPurified(
                    context, engine, novelId, novel.sourceKey, ChapterMeta(c.index, c.title, c.url), allowGate = false,
                )) {
                    is NovelContentService.Outcome.Ok -> r.text
                    else -> null
                }
            },
            flush = { batch -> NovelStore.updateChapterContents(context, novelId, batch) },
        )
        LogBus.d(TAG, "整本缓存结束 ${novel.title} done=${result.done}/${result.total} failed=${result.failed} reason=${result.stopReason}")
    }

    /**
     * 下载主循环（与 Android 无关，单测直接喂假 fetch/flush）。
     * [fetch] 返回净化后正文，null=本章失败；[flush] 批量写盘（下标 → 地址, 正文）。
     */
    internal suspend fun download(
        chapters: List<Chapter>,
        onProgress: (Progress) -> Unit,
        fetch: suspend (Chapter) -> String?,
        flush: suspend (Map<Int, Pair<String, String>>) -> Int,
    ): Progress {
        val total = chapters.size
        var done = chapters.count { it.content.isNotBlank() || it.url.isBlank() }
        val pending = chapters.filter { it.content.isBlank() && it.url.isNotBlank() }
        var failed = 0
        var consecutive = 0
        val batch = LinkedHashMap<Int, Pair<String, String>>()
        var p = Progress(total, done)
        onProgress(p)
        try {
            for (c in pending) {
                val text = fetch(c)
                if (text.isNullOrBlank()) {
                    failed++
                    consecutive++
                    if (consecutive >= MAX_CONSECUTIVE_FAIL) {
                        p = Progress(total, done, failed, running = false, stopReason = "连续 $consecutive 章失败，已暂停")
                        return p
                    }
                } else {
                    consecutive = 0
                    batch[c.index] = c.url to text
                    done++
                    if (batch.size >= FLUSH_EVERY) {
                        flush(LinkedHashMap(batch))
                        batch.clear()
                    }
                }
                p = Progress(total, done, failed)
                onProgress(p)
            }
            p = Progress(total, done, failed, running = false, stopReason = if (failed > 0) "$failed 章失败，可再点继续" else null)
            return p
        } finally {
            if (batch.isNotEmpty()) {
                withContext(NonCancellable) { flush(LinkedHashMap(batch)) }
            }
            onProgress(p)
        }
    }
}
