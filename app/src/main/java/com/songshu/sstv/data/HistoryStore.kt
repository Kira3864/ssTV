package com.songshu.sstv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条播放记录 */
data class HisItem(
    val key: String,          // 唯一键，用播放地址
    val title: String,        // 影片名
    val ep: String,           // 这一集的名字
    val pic: String,
    val url: String,
    val pos: Long,            // 看到哪儿（毫秒）
    val dur: Long,            // 总时长（毫秒，0 表示还不知道）
    val time: Long,           // 最后一次更新的时间
    val eps: List<String>,    // 整条剧集表（"集名\u0001地址"），一键续播用
    val api: String = "",
    val srcName: String = "",
    val vid: String = ""
) {
    /** 看完的比例 0..1 */
    val ratio: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
}

/** 播放历史（SharedPreferences 里的一个 JSON 数组，最多留 [MAX] 条） */
object HistoryStore {

    private const val PREF = "sstv"
    private const val K = "history"
    private const val MAX = 200

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(c: Context): MutableList<HisItem> {
        val raw = sp(c).getString(K, null) ?: return ArrayList()
        val out = ArrayList<HisItem>()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val eps = ArrayList<String>()
                val ea = o.optJSONArray("eps")
                if (ea != null) for (j in 0 until ea.length()) eps.add(ea.getString(j))
                out.add(
                    HisItem(
                        key = o.optString("key"),
                        title = o.optString("title"),
                        ep = o.optString("ep"),
                        pic = o.optString("pic"),
                        url = o.optString("url"),
                        pos = o.optLong("pos"),
                        dur = o.optLong("dur"),
                        time = o.optLong("time"),
                        eps = eps,
                        api = o.optString("api"),
                        srcName = o.optString("srcName"),
                        vid = o.optString("vid")
                    )
                )
            }
        }
        return out
    }

    private fun save(c: Context, l: List<HisItem>) {
        val a = JSONArray()
        l.forEach { h ->
            a.put(
                JSONObject()
                    .put("key", h.key).put("title", h.title).put("ep", h.ep)
                    .put("pic", h.pic).put("url", h.url)
                    .put("pos", h.pos).put("dur", h.dur).put("time", h.time)
                    .put("eps", JSONArray(h.eps))
                    .put("api", h.api).put("srcName", h.srcName).put("vid", h.vid)
            )
        }
        sp(c).edit().putString(K, a.toString()).apply()
    }

    /** 记一笔（同一条地址只留最新的一次），返回落库后的记录 */
    fun put(c: Context, item: HisItem): HisItem {
        val l = list(c)
        l.removeAll { it.key == item.key && it.api == item.api }
        l.add(0, item)
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(c, l)
        return item
    }

    /** 按播放地址找（播放页判断这一集看到哪儿了） */
    fun byUrl(c: Context, url: String, api: String? = null): HisItem? =
        list(c).firstOrNull { (it.url == url || it.key == url) && (api == null || it.api == api || it.api.isBlank()) }

    /** 按影片名找最近一条（详情页「继续播放」用） */
    fun lastOfTitle(c: Context, title: String, api: String? = null): HisItem? {
        val t = title.trim()
        if (t.isEmpty()) return null
        return list(c).firstOrNull { it.title.trim() == t && (api == null || it.api == api || it.api.isBlank()) }
    }

    fun remove(c: Context, key: String, api: String? = null) {
        val l = list(c)
        l.removeAll { it.key == key && (api == null || it.api == api) }
        save(c, l)
    }

    @Synchronized fun removeMedia(c: Context, media: MediaRef) {
        val urls = media.eps.map { it.substringAfter('\u0001') }.toSet()
        save(c, list(c).filterNot { h ->
            if (h.api.isNotBlank()) h.api.trimEnd('/') == media.api.trimEnd('/') &&
                (if (h.vid.isNotBlank() && media.id.isNotBlank()) h.vid == media.id else h.title == media.title)
            else h.url in urls
        })
    }
    fun clear(c: Context) {
        sp(c).edit().remove(K).apply()
    }

    fun count(c: Context): Int = list(c).size
}

/** 「3 小时前」这种口语化时间 */
fun fmtAgo(t: Long): String {
    val d = System.currentTimeMillis() - t
    if (d < 60_000) return "刚刚"
    if (d < 3_600_000) return "${d / 60_000} 分钟前"
    if (d < 86_400_000) return "${d / 3_600_000} 小时前"
    if (d < 172_800_000) return "昨天"
    if (d < 2_592_000_000L) return "${d / 86_400_000} 天前"
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        .format(java.util.Date(t))
}
