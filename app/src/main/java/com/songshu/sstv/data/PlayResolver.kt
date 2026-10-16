package com.songshu.sstv.data

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放地址解析器。
 *
 * 很多资源站返回的「剧集地址」其实是一个**播放页**（形如 https://xxx/play/abcd），
 * 直接丢给播放器只会拿到一段 HTML。真正的 m3u8 有两条常见规律：
 *   ① 播放页地址 + /index.m3u8      （红牛 / 虎牙 / 金鹰 / 豪华 / 极速 / 新浪 / 光速 这类）
 *   ② 页面 HTML 里内嵌 m3u8         （绝对地址，或像 /20250923/xx/index.m3u8?sign=xx 这种相对路径）
 * 按 ① → ② 顺序试；都失败就原样返回，让播放器自己再试一次。
 *
 * 纯离线判断 + 一次网络探测，结果缓存在内存里，同一集不会重复解析。
 */
object PlayResolver {

    private val memo = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * 缓存有效期 5 分钟。
     * 不少站的 m3u8 带动态 sign（如 /20251221/xx/index.m3u8?sign=xxx），放久了会失效，
     * 所以不能一直用旧结果。
     */
    private const val TTL = 5 * 60 * 1000L

    private val VIDEO_EXT = listOf(
        ".mp4", ".mkv", ".flv", ".avi", ".mov",
        ".m4v", ".ts", ".webm", ".rmvb", ".wmv", ".mp3", ".m4a"
    )

    private val ABS_M3U8 = Regex("""https?://[^\s"'<>\\]{4,400}?\.m3u8[^\s"'<>\\]*""")
    private val REL_M3U8 = Regex("""["'](/[^"'\s<>]{2,400}?\.m3u8(?:\?[^"'\s<>]*)?)["']""")

    /** 这个地址是不是「播放页」（需要解析） */
    fun isPage(url: String): Boolean {
        if (url.isBlank() || url.startsWith("file:")) return false
        val lower = url.lowercase()
        if (lower.contains(".m3u8")) return false
        val path = lower.substringBefore('?').substringBefore('#')
        return VIDEO_EXT.none { path.endsWith(it) }
    }

    /** 解析成可直接播放的地址；解析不出来返回原地址 */
    fun resolve(url: String): String {
        if (!isPage(url)) return url
        val now = System.currentTimeMillis()
        memo[url]?.let { (v, t) -> if (now - t < TTL) return v }
        val got = tryHard(url)
        memo[url] = got to now
        return got
    }

    /** 清掉缓存（换源后重新解析用） */
    fun clear() = memo.clear()

    private fun tryHard(url: String): String {
        val u = url.trimEnd('/')
        // ① 播放页 + /index.m3u8
        val guess = "$u/index.m3u8"
        if (isRealM3u8(guess)) return guess
        // ② 抓页面找 m3u8
        return pageM3u8(url) ?: url
    }

    /** 探测一个地址是不是真的 m3u8（要求返回 200 且内容以 #EXTM3U 开头） */
    private fun isRealM3u8(u: String): Boolean = runCatching {
        val c = open(u)
        try {
            if (c.responseCode >= 400) return false
            val buf = ByteArray(1024)
            val n = c.inputStream.read(buf)
            if (n <= 0) return false
            String(buf, 0, n, Charsets.UTF_8).contains("#EXTM3U")
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    private fun pageM3u8(page: String): String? = runCatching {
        val c = open(page)
        val html = try {
            if (c.responseCode >= 400) return null
            val ct = c.contentType ?: ""
            // 只解析网页/文本，别把视频流当页面读进来
            if (ct.contains("video") || ct.contains("octet-stream")) return null
            c.inputStream.use { ins ->
                val buf = ByteArray(256 * 1024)
                var off = 0
                while (off < buf.size) {
                    val n = ins.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
        // 绝对地址优先
        ABS_M3U8.find(html)?.value?.let { if (it.length > 14) return it }
        // 相对地址拼上域名
        REL_M3U8.find(html)?.groupValues?.getOrNull(1)?.let { rel ->
            return absolutize(page, rel)
        }
        null
    }.getOrNull()

    private fun absolutize(base: String, rel: String): String = runCatching {
        URL(URL(base), rel).toString()
    }.getOrDefault(rel)

    private fun open(url: String): HttpURLConnection {
        val safe = VodApi.encodeUrl(url)
        val u = URL(safe)
        val conn = u.openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", VodApi.UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Referer", "${u.protocol}://${u.host}/")
        return conn
    }
}
