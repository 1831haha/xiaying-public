package com.movieshell.data.novel

import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.27 解缠④：搜书管线合流逻辑（JSON 流 + PY 一次性）。 */
class NovelSearchPipelineTest {

    private fun src(host: String) = BookSource(name = host, url = "https://$host")
    private fun book(id: String) = NovelSearchResult(remoteId = id, title = id)

    @Test fun mergesJsonAndPyAndDropsEmpty() = runBlocking {
        val json = flow {
            emit(NovelSourceEngine.SourceSearchResult(src("a.com"), listOf(book("1"))))
            emit(NovelSourceEngine.SourceSearchResult(src("b.com"), emptyList(), filtered = 3))
            emit(NovelSourceEngine.SourceSearchResult(src("c.com"), emptyList(), filtered = 0))
        }
        val py = listOf(
            NovelSearchPipeline.Hit("py:x", "X（本地PY）", listOf(book("p")), isPy = true),
            NovelSearchPipeline.Hit("py:y", "Y（本地PY）", emptyList(), isPy = true),
        )
        val hits = NovelSearchPipeline.merge(json) { py }.toList()
        assertEquals(setOf("a.com", "b.com", "py:x"), hits.map { it.key }.toSet())
        assertEquals(3, hits.first { it.key == "b.com" }.filtered)
        assertTrue(hits.first { it.key == "b.com" }.books.isEmpty())
        assertTrue(hits.first { it.key == "py:x" }.isPy)
        assertEquals("a.com", hits.first { it.key == "a.com" }.name)
    }

    @Test fun pyArrivesWhileJsonStillRunning() = runBlocking {
        // PY 与 JSON 并发：JSON 卡住时 PY 组照样先出（改版前 VM 的 pyJob 与 flowJob 并发同效）
        val gate = CompletableDeferred<Unit>()
        val json = flow {
            gate.await()
            emit(NovelSourceEngine.SourceSearchResult(src("a.com"), listOf(book("1"))))
        }
        val order = mutableListOf<String>()
        NovelSearchPipeline.merge(json) {
            listOf(NovelSearchPipeline.Hit("py:x", "X", listOf(book("p")), isPy = true))
        }.collect { h ->
            order += h.key
            if (h.isPy) gate.complete(Unit)
        }
        assertEquals(listOf("py:x", "a.com"), order)
    }

    @Test fun pyFailurePropagates() = runBlocking {
        val json = flow<NovelSourceEngine.SourceSearchResult> { }
        try {
            NovelSearchPipeline.merge(json) { throw IllegalStateException("py boom") }.toList()
            throw AssertionError("should throw")
        } catch (e: IllegalStateException) {
            assertEquals("py boom", e.message)
        }
    }
}
