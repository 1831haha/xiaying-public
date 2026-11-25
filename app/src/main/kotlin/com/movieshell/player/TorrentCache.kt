package com.movieshell.player

import com.movieshell.core.log.LogBus
import java.net.HttpURLConnection
import java.net.URL

/** v0.294.30：按 infohash 从公共种子缓存取 .torrent（任一命中即用，失败返回 null）。 */
object TorrentCache {
    fun urls(hashHex: String): List<String> {
        val up = hashHex.uppercase()
        return listOf(
            "https://itorrents.org/torrent/$up.torrent",
            "https://itorrents.net/torrent/$up.torrent",
            "https://btcache.me/torrent/$up",
        )
    }

    fun fetch(hashHex: String): ByteArray? {
        for (u in urls(hashHex)) {
            val b = runCatching {
                // v1.4.23 S3：不自动跟随重定向——手动跟随并校验 scheme，https 起始
                // 跳到 http 即拒绝（防降级明文）；相对 Location 用当前 URL 解析。
                var current = URL(u)
                var body: ByteArray? = null
                var redirects = 0
                while (body == null && redirects <= 5) {
                    val c = current.openConnection() as HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 5000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    try {
                        val code = c.responseCode
                        when {
                            code == 200 -> body = c.inputStream.use { it.readBytes() }
                            (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && redirects < 5 -> {
                                val loc = c.getHeaderField("Location") ?: return@runCatching null
                                val next = URL(current, loc)
                                if (current.protocol == "https" && next.protocol == "http") return@runCatching null
                                current = next
                                redirects++
                            }
                            else -> return@runCatching null
                        }
                    } finally { c.disconnect() }
                }
                body
            }.getOrNull()
            if (MagnetStreamLogic.looksLikeTorrent(b)) {
                // 校验 infohash 一致，防缓存给错种子
                val ok = runCatching {
                    org.libtorrent4j.TorrentInfo(b).infoHash().toHex().equals(hashHex, true)
                }.getOrDefault(false)
                if (ok) { LogBus.i("XiaYingMagnet", "种子缓存命中：${URL(u).host}"); return b }
            }
        }
        return null
    }
}
