package com.movieshell.ui.bookshelf

import com.movieshell.data.store.BookGroup
import com.movieshell.data.store.BookshelfGroupStore
import com.movieshell.data.store.NovelUpdateInfo
import com.movieshell.domain.model.Novel
import com.movieshell.domain.model.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4.27 解缠⑦：书架 VM 的纯计算（分组计数、页签/分组筛选、四种排序）与改版前 remember 块同口径。 */
class BookshelfViewModelTest {

    private val a = Novel(id = "a", title = "乙书", totalChapters = 10, lastReadChapter = 4, hasStartedReading = true)
    private val b = Novel(id = "b", title = "甲书", totalChapters = 10, lastReadChapter = 8, hasStartedReading = true)
    private val c = Novel(id = "c", title = "丙书", totalChapters = 10)
    private val books = listOf(a, b, c)
    private val progress = mapOf(
        "a" to ReadingProgress("a", updatedAt = 300),
        "b" to ReadingProgress("b", updatedAt = 100),
        "c" to ReadingProgress("c", updatedAt = 200),
    )
    private val updates = mapOf("c" to NovelUpdateInfo("c", 3, checkedAt = 999))
    private val groups = listOf(BookGroup("g1", "收藏", listOf("a", "c", "ghost")))

    private fun visible(group: String = BookshelfGroupStore.GROUP_ALL, sort: Int = BookshelfGroupStore.SORT_RECENT, tab: Int = 0, failed: Set<String> = emptySet()) =
        BookshelfViewModel.visibleBooks(books, group, sort, progress, updates, groups, tab, failed).map { it.id }

    @Test fun shelfGroupsAllFirstWithCounts() {
        val gs = BookshelfViewModel.shelfGroups(books, groups)
        assertEquals(listOf(ShelfGroup("all", "全部", 3), ShelfGroup("g1", "收藏", 2)), gs)
    }

    @Test fun sortRecentByProgressUpdatedAt() = assertEquals(listOf("a", "c", "b"), visible())

    @Test fun sortUpdatedThenRecent() =
        assertEquals(listOf("c", "a", "b"), visible(sort = BookshelfGroupStore.SORT_UPDATED))

    @Test fun sortTitle() = assertEquals(books.sortedBy { it.title }.map { it.id }, visible(sort = BookshelfGroupStore.SORT_TITLE))

    @Test fun sortProgress() = assertEquals(listOf("b", "a", "c"), visible(sort = BookshelfGroupStore.SORT_PROGRESS))

    @Test fun customGroupFilters() = assertEquals(listOf("a", "c"), visible(group = "g1"))

    @Test fun unknownGroupShowsNothing() = assertEquals(emptyList<String>(), visible(group = "gone"))

    @Test fun failedTabIgnoresGroup() =
        assertEquals(listOf("b"), visible(group = "g1", tab = 1, failed = setOf("b")))
}
