package com.movieshell.data.novel

import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.NovelSearchResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * v1.4.28 A3：恢复/导入清缓存——invalidateCaches 只清指定 key 的源，
 * 其它源的目录/发现缓存原样保留；不存在的 key 是空操作。
 *
 * 注意：importSources / restoreBuiltin 本体依赖 Context 写盘，harness 里测不了，
 * 这里只覆盖 invalidateCaches 本体；两个调用点由审稿 grep 确认（写盘成功分支有调用）。
 */
class SourceCacheInvalidateTest {

    private val chapters = listOf(
        ChapterMeta(index = 0, title = "第一章", url = "/book/1/1"),
        ChapterMeta(index = 1, title = "第二章", url = "/book/1/2"),
    )
    private val books = listOf(NovelSearchResult(remoteId = "1", title = "甲"))
    private var calls = 0

    @Before fun setUp() {
        NovelTocService.clearAll()
        NovelExploreLoader.clearAll()
        calls = 0
    }

    @After fun tearDown() {
        NovelTocService.clearAll()
        NovelExploreLoader.clearAll()
    }

    private suspend fun loadExplore(sourceKey: String) =
        NovelExploreLoader.loadWith(NovelExploreLoader.keyOf(sourceKey, "/cat/1", 1), false) { calls++; books }

    @Test fun invalidateCachesClearsOnlyTargetSource() = runBlocking {
        NovelTocService.put("a.com", "/book/1", chapters)
        NovelTocService.put("b.com", "/book/1", chapters)
        loadExplore("a.com")
        loadExplore("b.com")
        assertEquals(2, calls)

        BookSourceManager.invalidateCaches("a.com")

        assertNull(NovelTocService.cachedChapters("a.com", "/book/1"))
        assertNotNull(NovelTocService.cachedChapters("b.com", "/book/1"))
        // a.com 的发现缓存已清：下次加载重新抓取；b.com 仍命中缓存。
        loadExplore("a.com")
        assertEquals(3, calls)
        loadExplore("b.com")
        assertEquals(3, calls)
    }

    @Test fun invalidateUnknownKeyIsNoop() = runBlocking {
        NovelTocService.put("a.com", "/book/1", chapters)
        loadExplore("a.com")
        BookSourceManager.invalidateCaches("不存在的key")
        assertNotNull(NovelTocService.cachedChapters("a.com", "/book/1"))
        loadExplore("a.com")
        assertEquals(1, calls)
    }
}
