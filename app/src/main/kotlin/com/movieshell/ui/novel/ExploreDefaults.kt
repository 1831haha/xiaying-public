package com.movieshell.ui.novel

import com.movieshell.domain.model.BookSource

/** v1.4.27 P0.8：发现页默认模式（纯函数，可单测）。 */
object ExploreDefaults {
    /** true=默认原网页直渲；有发现列表规则（ruleExploreList）的源默认列表。源未载入时按原网页（改版前默认）。 */
    fun defaultWebMode(source: BookSource?): Boolean = source?.ruleExploreList.isNullOrBlank()
}
