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
 * 2. 同一链内连续失败时排除已试过的源（含当前源），按健康分往下顺延；
 * 3. 上限 MAX_HOPS 跳，跳满落回原报错，不死循环；播稳 20 秒才清会话。
 *
 * v1.4.33 TA 审码修（M1–M3/S1/S2/S6）：
 * - M2：同一轮链内不看片名/集数——跨站后详情 vodName 与集数分组都可能
 *   与原站逐字不同，反推必丢链；hops>0 时只认 TTL，片名只在 hops==0
 *   时用来建立会话（换集后中途切集不再另开会话，见单测）。
 * - M3：换源带播放位置——recordHop 记当前位置，consumePendingPos
 *   由新页面兜底消费一次。
 * - S6：recordHop 刷新 startedAtMs，慢链路不中途过期。
 */
object CrossSourceResume {
    /** 同一链（任意剧或集）最多换几个源（含第一次换源）；跳满后落回原报错。 */
    const val MAX_HOPS = 3

    /** 会话有效期：播稳后清掉；5 分钟内同一链又失败算同一轮。 */
    const val SESSION_TTL_MS = 5 * 60 * 1000L

    /**
     * 集数后缀候选表（按顺序试，第一条命中即停）。
     * 覆盖面（S1）：中文数字（第十二集）、全角数字（第５集）、
     * 无“第”的纯数字（斗破苍穹 05）、带尾巴（第5集 高清、更新至12集）、
     * 区间（第1-2集）。第N季千万别剥（那是季，不是集）。
     * 边界（S2）：大写 EP 可不带分隔符；单个 E/e 须有前置分隔符，
     * 防“...SE2”这类片名被误剥；纯数字须有分隔符且至多 3 位，
     * “庆余年2”不动、“剧名 2024”这种年份后缀也不动。
     */
    private val episodeSuffixPatterns = listOf(
        Regex("""[\s　]*更新至\s*(?:第\s*)?[0-9０-９一二三四五六七八九十百千零两]+\s*[集话話期].*$"""),
        Regex("""[\s　]*第\s*[0-9０-９一二三四五六七八九十百千零两]+(?:\s*[-－—~～至到]\s*[0-9０-９一二三四五六七八九十百千零两]+)?\s*[集话話期].*$"""),
        Regex("""[\s　]*EP[0-9０-９]+.*$"""),
        Regex("""(?<=[\s　_.\-])[Ee][Pp]?[0-9０-９]+.*$"""),
        Regex("""[\s　]+[0-9０-９]{1,3}$"""),
    )

    /**
     * 剥掉标题尾巴的集数后缀（“斗破苍穹 第5集”→“斗破苍穹”）。
     * 剧名本身带数字的不动（如“庆余年2”既无“第”也无 EP，不会被剥）。
     */
    fun stripEpisodeSuffix(title: String): String {
        val t = title.trim()
        for (pattern in episodeSuffixPatterns) {
            val stripped = pattern.replace(t, "").trim()
            if (stripped != t && stripped.isNotEmpty()) return stripped
        }
        return t
    }

    @Volatile private var showName = ""
    @Volatile private var episodeIndex = -1
    @Volatile private var startedAtMs = 0L
    @Volatile private var hops = 0
    private val triedKeys = mutableSetOf<String>()

    /** M3：换源时记下的播放位置（毫秒），新页面兜底消费一次，只消费一次。 */
    @Volatile private var pendingPosMs = 0L

    /**
     * 开新会话或续用同一轮链（过期即重开）。
     * 当前源 key 一并记进已试集合，本轮搜源不再回跳它。
     * 跳满 MAX_HOPS 返回 false，调用方落回原报错。
     * M2：同一轮链内不看片名/集数——hops>0 时只认 TTL；片名与集数
     * 只在 hops==0 时用来建立会话（跨站后详情 vodName 与集数分组都
     * 可能与原站不同，反推必丢链）。
     */
    @Synchronized
    fun begin(showName: String, episodeIndex: Int, currentKeys: Set<String>): Boolean {
        val now = System.currentTimeMillis()
        val same = (hops > 0 ||
            (this.showName == showName && this.episodeIndex == episodeIndex)) &&
            now - startedAtMs < SESSION_TTL_MS
        if (same && hops >= MAX_HOPS) return false
        if (!same) {
            this.showName = showName
            this.episodeIndex = episodeIndex
            startedAtMs = now
            hops = 0
            triedKeys.clear()
            pendingPosMs = 0L
        }
        triedKeys.addAll(currentKeys)
        return true
    }

    /** 本轮已排除的源 key（当前源＋已跳过的源），搜源时跳过这些。 */
    @Synchronized
    fun excludedKeys(): Set<String> = triedKeys.toSet()

    /** 当前会话已跳次数（0 = 刚开的新会话；PlayerScreen S3 用它判断是否重建 repo）。 */
    @Synchronized
    fun hopCount(): Int = hops

    /**
     * 记一次换源：目标源 key 进已试集合，跳数加一。
     * M3：posMs>0 时记下换源瞬间的播放位置，新页面兜底消费。
     * S6：刷新 startedAtMs，TTL 续期——慢链路（4×12s 搜索＋解析）
     * 中途过期会导致会话重开、跳数清零。
     */
    @Synchronized
    fun recordHop(siteKey: String, posMs: Long = 0L) {
        if (siteKey.isNotBlank()) triedKeys.add(siteKey)
        hops++
        if (posMs > 0) pendingPosMs = posMs
        startedAtMs = System.currentTimeMillis()
    }

    /**
     * M3：新页面兜底消费一次换源时记下的播放位置。
     * 剧集对不上返回 0 且不消费；对上则返回位置并清零，只消费一次。
     */
    @Synchronized
    fun consumePendingPos(showName: String, episodeIndex: Int): Long {
        if (this.showName != showName || this.episodeIndex != episodeIndex) return 0L
        val pos = pendingPosMs
        pendingPosMs = 0L
        return pos
    }

    /** 仅单测用：把会话开始时间拨回指定毫秒，模拟 TTL 过期。 */
    @Synchronized
    internal fun backdateStartedAtForTest(millisAgo: Long) {
        startedAtMs = System.currentTimeMillis() - millisAgo
    }

    /** 播稳了：会话结束，下次失败重新计。 */
    @Synchronized
    fun clear() {
        showName = ""
        episodeIndex = -1
        startedAtMs = 0L
        hops = 0
        triedKeys.clear()
        pendingPosMs = 0L
    }

    /** 播起来了且是本会话的剧集才清，别处误调不影响。 */
    @Synchronized
    fun clearIfActiveFor(showName: String, episodeIndex: Int) {
        if (this.showName == showName && this.episodeIndex == episodeIndex) clear()
    }

    /**
     * TA 复核 1a：播稳 20 秒后清会话——只认“本轮链还在”（hops>0），
     * 不比片名/集数。M2 之后跨站改名（斗破苍穹 2024、斗破苍穹·年番）
     * 或集数被限定到别的下标时，clearIfActiveFor 逐字比对不上，
     * 播稳也清不掉，5 分钟内任何失败都会沿用旧链。不要在 begin 里
     * 更新 showName，那会把 M3 consumePendingPos 的严格片名语义
     * 一起改掉。
     */
    @Synchronized
    fun clearIfChainActive() {
        if (hops > 0) clear()
    }
}
