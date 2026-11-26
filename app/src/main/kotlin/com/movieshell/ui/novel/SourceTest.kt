package com.movieshell.ui.novel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.movieshell.domain.model.SourceHealth
import com.movieshell.domain.model.TestSearchResult
import com.movieshell.domain.model.toSourceHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.4.27 搜索测试：结果弹窗的结构化内容（设计稿 §3 / 定稿 §七 TestResultDisplay）。
 * 标题统一「搜索测试」（PY 与 JSON 共用）；第一行关键词，第二行按档位着色的结果。
 */
data class TestResultDisplay(
    val sourceTitle: String,
    val keyword: String,
    val health: SourceHealth,
    val line: String,
) {
    companion object {
        /** 失败原因最多 200 字（E8：不许把整段 HTML/堆栈贴进弹窗）。 */
        const val MAX_REASON = 200

        /** JSON 源：Step 1 的 TestSearchResult → 显示。 */
        fun ofJson(title: String, keyword: String, r: TestSearchResult, elapsedMs: Long): TestResultDisplay {
            val h = r.toSourceHealth(elapsedMs)
            val line = when (r) {
                is TestSearchResult.Ok -> "通过：搜到 ${r.count} 本书（${elapsedMs}ms）"
                TestSearchResult.Zero -> "未验证：请求成功但没有搜到书，可换个关键词再试"
                TestSearchResult.Failed -> "失败：请求或解析出错（详情见悬浮日志）"
                TestSearchResult.Timeout -> "超时：30 秒内没有返回"
                TestSearchResult.NotConfigured -> "未配置搜索：缺搜索地址或搜索列表规则"
            }
            return TestResultDisplay(title, keyword, h, line)
        }

        /** PY 源：PyBookApi.testSearch 的 Result<Int> → 显示（0 条改「未验证」，与 JSON 一致——E6）。 */
        fun ofPy(title: String, keyword: String, r: Result<Int>?, elapsedMs: Long): TestResultDisplay {
            if (r == null) return TestResultDisplay(title, keyword, SourceHealth.Fail, "失败：找不到源文件")
            return r.fold(
                onSuccess = { n ->
                    if (n > 0) TestResultDisplay(title, keyword, SourceHealth.reachable(elapsedMs), "通过：搜到 $n 本书（${elapsedMs}ms）")
                    else TestResultDisplay(title, keyword, SourceHealth.Unverified, "未验证：请求成功但没有搜到书")
                },
                onFailure = { e ->
                    TestResultDisplay(title, keyword, SourceHealth.Fail, "失败：" + (e.message ?: "未知错误").take(MAX_REASON))
                },
            )
        }
    }
}

/**
 * v1.4.27 搜索测试排队器（定稿 §六 C 组 / 设计稿 §4 状态机）。
 * - 一次只测一个：共用一把 [Mutex]，按提交顺序串行（C1/C2）；排队等待不计入 30 秒超时；
 * - 同一行（selectKey）已在排队/执行中再点 ▶ 忽略，不重复入队（C3）；
 * - [phases]：行内状态——QUEUED 显示「等待」，RUNNING 显示转圈（E3/E4）；复位一律 finally（C8）；
 * - [cancel]：删源时取消该行的任务（C4/C5）；被取消的任务不写结果；
 * - 结果只在锁内写 [result]（串行，不需要代次号，C7）；run 返回 null=不出结果（源已删等）。
 * 只是界面状态，不碰探测圆点 / BookProbeStore / 搜索状态（E10–E12）。
 */
class SourceTestRunner(private val scope: CoroutineScope) {

    enum class Phase { QUEUED, RUNNING }

    var phases by mutableStateOf<Map<String, Phase>>(emptyMap())
        private set

    var result by mutableStateOf<TestResultDisplay?>(null)

    private val mutex = Mutex()
    private val jobs = HashMap<String, Job>()

    fun phaseOf(key: String): Phase? = phases[key]

    /** 提交测试；该行已在排队/执行中返回 false（不入队）。 */
    fun submit(key: String, run: suspend () -> TestResultDisplay?): Boolean {
        if (key in phases) return false
        phases = phases + (key to Phase.QUEUED)
        val job = scope.launch {
            try {
                mutex.withLock {
                    phases = phases + (key to Phase.RUNNING)
                    val r = run()
                    if (r != null) result = r
                }
            } finally {
                phases = phases - key
                jobs.remove(key)
            }
        }
        if (job.isActive) jobs[key] = job
        return true
    }

    /** 取消该行的排队/执行中任务（删源时调）。 */
    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }
}
