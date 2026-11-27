package com.movieshell.player

/**
 * v1.4.33：自动跨源续播会话（内存态，不落盘）。
 *
 * 背景：v1.1.31 的 tryAutoSwitchSource 只在“当前源全部线路耗尽”时搜一次
 * 同名剧、只跳分最高的那个源，且搜的目标直接拿 PlayerScreen 的 title——
 * 详情页内嵌播放器传的 title 是“剧名 第X集”，精确片名永远匹配不上，
 * 自动换源等于没接线，播失败就停在报错上。
 *
 * 本会话补三件事（只管跨源这一段，同源内换线路/换内核一字不动）：
 * 1. 搜目标用干净剧名（PlayerScreen 优先传 infoName，没有时剥 title 的集数后缀）；
 * 2. 同剧同集连续失败时排除已试过的源（含当前源），按健康分往下顺延；
 * 3. 上限 MAX_HOPS 跳，跳满落回原报错，不死循环；播起来（首帧出画面）即清会话。
 */
object CrossSourceResume {
    /** 同剧同集最多换几个源（含第一次换源）；跳满后落回原报错。 */
    const val MAX_HOPS = 3

    /** 会话有效期：播起来后清掉；5 分钟内同一集又失败算同一轮。 */
    const val SESSION_TTL_MS = 5 * 60 * 1000L

    private val episodeSuffix = Regex("""[\s　]*第\s*\d+\s*[集话話期]$""")
    private val epSuffix = Regex("""[\s　]*[Ee][Pp]?\d+$""")

    /**
     * 剥掉标题尾巴的集数后缀（“斗破苍穹 第5集”→“斗破苍穹”）。
     * 只认“第N集/话/期”与“EP12/E12”两种写法，剧名本身带数字的不动
     * （如“庆余年2”既无“第”也无 EP，不会被剥）。
     */
    fun stripEpisodeSuffix(title: String): String {
        val t = title.trim()
        val noEp = episodeSuffix.replace(t, "")
        if (noEp != t) return noEp.trim()
        return epSuffix.replace(t, "").trim()
    }

    @Volatile private var showName = ""
    @Volatile private var episodeIndex = -1
    @Volatile private var startedAtMs = 0L
    @Volatile private var hops = 0
    private val triedKeys = mutableSetOf<String>()

    /**
     * 开新会话或续用同一剧同一集的会话（过期或换剧换集即重开）。
     * 当前源 key 一并记进已试集合，本轮搜源不再回跳它。
     * 跳满 MAX_HOPS 返回 false，调用方落回原报错。
     */
    @Synchronized
    fun begin(showName: String, episodeIndex: Int, currentKeys: Set<String>): Boolean {
        val now = System.currentTimeMillis()
        val same = this.showName == showName && this.episodeIndex == episodeIndex &&
            now - startedAtMs < SESSION_TTL_MS
        if (same && hops >= MAX_HOPS) return false
        if (!same) {
            this.showName = showName
            this.episodeIndex = episodeIndex
            startedAtMs = now
            hops = 0
            triedKeys.clear()
        }
        triedKeys.addAll(currentKeys)
        return true
    }

    /** 本轮已排除的源 key（当前源＋已跳过的源），搜源时跳过这些。 */
    @Synchronized
    fun excludedKeys(): Set<String> = triedKeys.toSet()

    /** 记一次换源：目标源 key 进已试集合，跳数加一。 */
    @Synchronized
    fun recordHop(siteKey: String) {
        if (siteKey.isNotBlank()) triedKeys.add(siteKey)
        hops++
    }

    /** 播起来了：会话结束，下次失败重新计。 */
    @Synchronized
    fun clear() {
        showName = ""
        episodeIndex = -1
        startedAtMs = 0L
        hops = 0
        triedKeys.clear()
    }

    /** 播起来了且是本会话的剧集才清，别处误调不影响。 */
    @Synchronized
    fun clearIfActiveFor(showName: String, episodeIndex: Int) {
        if (this.showName == showName && this.episodeIndex == episodeIndex) clear()
    }
}
