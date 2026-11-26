package com.movieshell.ui.novel

import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.SourceHealth
import com.movieshell.domain.model.TestSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTestTest {

    private fun disp(t: String) = TestResultDisplay(t, "k", SourceHealth.Ok(1), "通过")

    @Test
    fun runner_serializes_dedups_and_resets() = runBlocking<Unit> {
        coroutineScope {
            val r = SourceTestRunner(this)
            val gateA = CompletableDeferred<Unit>()
            var bRan = false
            assertTrue(r.submit("a") { gateA.await(); disp("A") })
            assertTrue(r.submit("b") { bRan = true; disp("B") })
            repeat(3) { yield() }
            assertEquals(SourceTestRunner.Phase.RUNNING, r.phaseOf("a"))
            assertEquals(SourceTestRunner.Phase.QUEUED, r.phaseOf("b"))
            assertFalse("同一行重复点击不入队", r.submit("a") { disp("A2") })
            assertFalse(bRan)
            gateA.complete(Unit)
        }
        // coroutineScope 返回即全部完成
    }

    @Test
    fun runner_cancel_skips_result_and_clears_phase() = runBlocking<Unit> {
        var runner: SourceTestRunner? = null
        coroutineScope {
            val r = SourceTestRunner(this).also { runner = it }
            val gateA = CompletableDeferred<Unit>()
            r.submit("a") { gateA.await(); disp("A") }
            r.submit("b") { disp("B") }
            repeat(3) { yield() }
            r.cancel("b")          // 排队中的被删
            gateA.complete(Unit)
        }
        val r = runner!!
        assertEquals("A", r.result?.sourceTitle)
        assertTrue(r.phases.isEmpty())
    }

    @Test
    fun runner_running_cancel_and_null_result() = runBlocking<Unit> {
        var runner: SourceTestRunner? = null
        coroutineScope {
            val r = SourceTestRunner(this).also { runner = it }
            r.submit("a") { CompletableDeferred<Unit>().await(); disp("A") }
            r.submit("b") { null } // 源已删：不出结果
            repeat(3) { yield() }
            r.cancel("a")          // 执行中被删：finally 复位，锁释放给 b
        }
        val r = runner!!
        assertNull(r.result)
        assertTrue(r.phases.isEmpty())
        // 复位后可再次提交
    }

    @Test
    fun ofJson_lines_and_tones() {
        assertEquals(SourceHealth.Unverified, TestResultDisplay.ofJson("s", "k", TestSearchResult.Zero, 10).health)
        assertTrue(TestResultDisplay.ofJson("s", "k", TestSearchResult.Zero, 10).line.startsWith("未验证"))
        val ok = TestResultDisplay.ofJson("s", "k", TestSearchResult.Ok(3), 10)
        assertTrue(ok.line.contains("3 本"))
        assertTrue(ok.health is SourceHealth.Ok)
        assertEquals(SourceHealth.Timeout, TestResultDisplay.ofJson("s", "k", TestSearchResult.Timeout, 30000).health)
        assertEquals(SourceHealth.Unconfigured, TestResultDisplay.ofJson("s", "k", TestSearchResult.NotConfigured, 0).health)
        assertEquals(SourceHealth.Fail, TestResultDisplay.ofJson("s", "k", TestSearchResult.Failed, 5).health)
    }

    @Test
    fun ofPy_zero_unverified_missing_and_truncation() {
        assertEquals(SourceHealth.Unverified, TestResultDisplay.ofPy("p", "斗罗", Result.success(0), 5).health)
        assertTrue(TestResultDisplay.ofPy("p", "斗罗", Result.success(2), 5).health is SourceHealth.Ok)
        assertEquals("失败：找不到源文件", TestResultDisplay.ofPy("p", "斗罗", null, 5).line)
        val long = "x".repeat(1000)
        val f = TestResultDisplay.ofPy("p", "斗罗", Result.failure(RuntimeException(long)), 5)
        assertEquals("失败：".length + TestResultDisplay.MAX_REASON, f.line.length)
        assertEquals(SourceHealth.Fail, f.health)
    }

    @Test
    fun resolveKeyword_fallbacks() {
        val withKw = BookSource(name = "s", checkKeyWord = " 凡人 ")
        val noKw = BookSource(name = "s")
        assertEquals("剑来", SourceRowActions.resolveKeyword("  剑来 ", withKw))
        assertEquals("凡人", SourceRowActions.resolveKeyword("   ", withKw))
        assertEquals(BookSource.DEFAULT_CHECK_KEYWORD, SourceRowActions.resolveKeyword("", noKw))
        assertEquals("斗罗", BookSource.DEFAULT_CHECK_KEYWORD)
    }
}
