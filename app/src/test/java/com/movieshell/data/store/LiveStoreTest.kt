package com.movieshell.data.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.9：已隐藏直播流（live_hidden_streams）纯函数单测，不碰 SharedPreferences。
 * 隐藏粒度按流 URL：同一条流挂多个台名下时删一次全干净；刷新聚合与缓存
 * 加载都过滤，隐藏后刷新不复活。
 */
class LiveStoreTest {

    private val dead = "http://45.192.97.170:8880/play/1695.m3u8"
    private val alive = "http://8.137.121.66:20241/608807420"

    @Test
    fun `parse empty or bad json yields empty set`() {
        assertTrue(LiveStore.parseHiddenStreamsJson(null).isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("").isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("not json").isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("{}").isEmpty())
        assertTrue(LiveStore.parseHiddenStreamsJson("[]").isEmpty())
    }

    @Test
    fun `round trip keeps urls and drops blanks and duplicates`() {
        val parsed = LiveStore.parseHiddenStreamsJson(
            LiveStore.serializeHiddenStreamsJson(listOf(dead, alive, dead)),
        )
        assertEquals(linkedSetOf(dead, alive), parsed)
    }

    @Test
    fun `filter drops hidden urls only`() {
        val channels = listOf(
            LiveChannel(id = "m3u:a|$dead", name = "CCTV1", url = dead),
            LiveChannel(id = "m3u:a|$alive", name = "CCTV1", url = alive),
            LiveChannel(id = "m3u:b|$dead", name = "CCTV2", url = dead),
        )
        val kept = LiveStore.filterHiddenChannels(channels, setOf(dead))
        assertEquals(listOf(channels[1]), kept)
    }

    @Test
    fun `filter with empty hidden set returns same list`() {
        val channels = listOf(LiveChannel(id = "m3u:a|$alive", name = "CCTV1", url = alive))
        assertEquals(channels, LiveStore.filterHiddenChannels(channels, emptySet()))
    }

    @Test
    fun `hostOfUrl extracts host with ip port and domain`() {
        assertEquals("45.192.97.170", LiveStore.hostOfUrl(dead))
        assertEquals("8.137.121.66", LiveStore.hostOfUrl(alive))
        assertEquals(
            "mediaserver.abnvideos.com",
            LiveStore.hostOfUrl("https://mediaserver.abnvideos.com/streams/abnchina.m3u8"),
        )
    }

    @Test
    fun `hostOfUrl returns empty for bad or schemeless input`() {
        assertEquals("", LiveStore.hostOfUrl(""))
        assertEquals("", LiveStore.hostOfUrl("not a url"))
        assertEquals("", LiveStore.hostOfUrl("just-a-path/1695.m3u8"))
    }

    // v1.4.12 第 2 步：本源 EPG 四级解析（频道属性＞订阅＞列表＞全局前三级）。

    private val subA = LiveSource(
        name = "央视",
        url = "https://example.com/a.m3u",
        epg = "https://example.com/sub.xml",
    )
    private val subB = LiveSource(name = "卫视", url = "https://example.com/b.m3u")

    private fun epgChannel(
        epg: String = "",
        playlist: String = "",
        sourceKey: String = "m3u:央视",
    ) = LiveChannel(
        id = "x",
        name = "CCTV-1",
        url = "http://x/1",
        epgUrl = epg,
        playlistEpgUrl = playlist,
        sourceKey = sourceKey,
    )

    @Test
    fun `resolve channel epg attr wins over subscription and playlist`() {
        val c = epgChannel(
            epg = "https://example.com/ch.xml",
            playlist = "https://example.com/list.xml",
        )
        assertEquals("https://example.com/ch.xml", LiveStore.resolveSourceEpgUrl(c, listOf(subA), true))
    }

    @Test
    fun `resolve subscription wins over playlist when channel attr blank`() {
        val c = epgChannel(playlist = "https://example.com/list.xml")
        assertEquals("https://example.com/sub.xml", LiveStore.resolveSourceEpgUrl(c, listOf(subA), true))
    }

    @Test
    fun `resolve playlist used when attr and subscription blank`() {
        val c = epgChannel(playlist = "https://example.com/list.xml")
        assertEquals("https://example.com/list.xml", LiveStore.resolveSourceEpgUrl(c, listOf(subB), true))
    }

    @Test
    fun `resolve blank when nothing configured`() {
        // 审稿 M1：原来配 listOf(subA) 是错的——epgChannel() 默认 sourceKey
        // "m3u:央视" 与 subA（名“央视”、epg 非空）恰好匹配，解析会返回
        // 订阅地址而非 ""；用 subB（无 epg、名也对不上）才真是“全空”。
        assertEquals("", LiveStore.resolveSourceEpgUrl(epgChannel(), listOf(subB), true))
    }

    @Test
    fun `resolve empty when follow off even with channel epg`() {
        val c = epgChannel(
            epg = "https://example.com/ch.xml",
            playlist = "https://example.com/list.xml",
        )
        assertEquals("", LiveStore.resolveSourceEpgUrl(c, listOf(subA), false))
    }

    @Test
    fun `resolve plugin and manual channels skip subscription level`() {
        val plugin = epgChannel(
            playlist = "https://example.com/list.xml",
            sourceKey = "plugin:foo.json",
        )
        assertEquals("https://example.com/list.xml", LiveStore.resolveSourceEpgUrl(plugin, listOf(subA), true))
        val manual = epgChannel(sourceKey = "manual")
        assertEquals("", LiveStore.resolveSourceEpgUrl(manual, listOf(subA), true))
    }

    @Test
    fun `parseM3u splits channel epg attr and playlist x-tvg-url`() {
        val text = """
            #EXTM3U x-tvg-url="https://example.com/list.xml"
            #EXTINF:-1 tvg-logo="x" epg="https://example.com/ch.xml",CCTV-1
            http://x/1.m3u8
            #EXTINF:-1,CCTV-2
            http://x/2.m3u8
        """.trimIndent()
        val list = LiveStore.parseM3u(text, "m3u:央视")
        assertEquals(2, list.size)
        assertEquals("https://example.com/ch.xml", list[0].epgUrl)
        assertEquals("https://example.com/list.xml", list[0].playlistEpgUrl)
        assertEquals("", list[1].epgUrl)
        assertEquals("https://example.com/list.xml", list[1].playlistEpgUrl)
    }

    @Test
    fun `parseM3u reads x-tvg-url from EXTM3U header even when not first line`() {
        // v1.4.34 B-P1-5：头行之前有注释行时，仍从 #EXTM3U 行取 x-tvg-url，不取第一行。
        val text = """
            #EXTGRP:央视
            #EXTM3U x-tvg-url="https://example.com/list.xml"
            #EXTINF:-1,CCTV-1
            http://x/1.m3u8
        """.trimIndent()
        val list = LiveStore.parseM3u(text, "m3u:央视")
        assertEquals(1, list.size)
        assertEquals("https://example.com/list.xml", list[0].playlistEpgUrl)
    }

    @Test
    fun `parseM3u without playlist header leaves playlist blank`() {
        val text = """
            #EXTINF:-1,CCTV-1
            http://x/1.m3u8
        """.trimIndent()
        val list = LiveStore.parseM3u(text, "m3u:央视")
        assertEquals(1, list.size)
        assertEquals("", list[0].epgUrl)
        assertEquals("", list[0].playlistEpgUrl)
    }

    @Test
    fun `old LiveSource json without epg field decodes as blank`() {
        val list = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(LiveSource.serializer()),
            """[{"name":"央视","type":"M3U_URL","url":"https://example.com/a.m3u"}]""",
        )
        assertEquals(1, list.size)
        assertEquals("", list[0].epg)
    }

    @Test
    fun `old LiveChannel json without playlistEpgUrl decodes as blank`() {
        val list = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(LiveChannel.serializer()),
            """[{"id":"m3u:a|http://x/1","name":"CCTV-1","url":"http://x/1","epgUrl":"https://example.com/ch.xml"}]""",
        )
        assertEquals(1, list.size)
        assertEquals("https://example.com/ch.xml", list[0].epgUrl)
        assertEquals("", list[0].playlistEpgUrl)
    }

    // v1.4.13 第 3 步：套用候选——其它源下发的模板。

    @Test
    fun `collect templates from global subscription and channel`() {
        val tplGlobal = "https://g.example.com/epg?ch={name}"
        val tplSub = "https://t.example.com/{name}/{date}.xml"
        val tplCh = "https://c.example.com/{name}.xml"
        val sources = listOf(
            LiveSource(name = "央视", url = "https://example.com/a.m3u", epg = tplSub),
            LiveSource(name = "卫视", url = "https://example.com/b.m3u", epg = "https://example.com/plain.xml"),
        )
        val got = LiveStore.collectEpgTemplates(
            sources,
            listOf(epgChannel(epg = tplCh)),
            tplGlobal,
            "",
        )
        assertEquals(
            listOf(
                LiveStore.EpgTemplate(tplGlobal, "全局节目单"),
                LiveStore.EpgTemplate(tplSub, "订阅「央视」"),
                LiveStore.EpgTemplate(tplCh, "频道「CCTV-1」"),
            ),
            got,
        )
    }

    @Test
    fun `collect templates dedups keeps first label and caps at three`() {
        val tpl2 = "https://t2.example.com/{name}.xml"
        val tpl3 = "https://t3.example.com/{name}.xml"
        val tpl4 = "https://t4.example.com/{name}.xml"
        val tpl5 = "https://t5.example.com/{name}.xml"
        val sources = listOf(
            LiveSource(name = "央视", url = "https://example.com/a.m3u", epg = tpl2),
            LiveSource(name = "卫视", url = "https://example.com/b.m3u", epg = tpl3),
        )
        val channels = listOf(
            epgChannel(epg = tpl3, playlist = tpl4),
            epgChannel(epg = tpl5, sourceKey = "m3u:卫视"),
        )
        val got = LiveStore.collectEpgTemplates(sources, channels, "https://example.com/plain.xml", "")
        // tpl3 在订阅与频道各出现一次，只留第一次的标签（订阅级）；tpl5 被 3 条上限截掉。
        assertEquals(
            listOf(
                LiveStore.EpgTemplate(tpl2, "订阅「央视」"),
                LiveStore.EpgTemplate(tpl3, "订阅「卫视」"),
                LiveStore.EpgTemplate(tpl4, "列表「央视」"),
            ),
            got,
        )
    }

    @Test
    fun `collect templates empty when none or all excluded`() {
        assertTrue(LiveStore.collectEpgTemplates(listOf(subA), listOf(epgChannel()), "", "").isEmpty())
        val tpl = "https://t.example.com/{name}.xml"
        val sources = listOf(LiveSource(name = "央视", url = "https://example.com/a.m3u", epg = tpl))
        assertTrue(LiveStore.collectEpgTemplates(sources, emptyList(), "", tpl).isEmpty())
    }
}
