package com.songshu.sstv.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条已缓存记录 */
data class DlItem(
    val url: String,
    val title: String,
    val sub: String,
    val path: String,
    val size: Long,
    val time: Long,
    val pic: String = "",
    val api: String = "", val sourceName: String = "", val vid: String = "", val series: Boolean = false
) {
    val mediaKey get() = api.trimEnd('/') + "\u0001" + vid.ifBlank { title }
    val sourceLabel get() = sourceName.ifBlank { if (api.isNotBlank()) runCatching { java.net.URL(api).host }.getOrDefault(api) else "来源未记录" }
}

/** 已缓存列表的索引（存 SharedPreferences） */
object DlStore {

    private const val PREF = "sstv"
    private const val K = "downloads"

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(c: Context): MutableList<DlItem> {
        val raw = sp(c).getString(K, null) ?: return ArrayList()
        val out = ArrayList<DlItem>()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    DlItem(
                        url = o.optString("url"),
                        title = o.optString("title"),
                        sub = o.optString("sub"),
                        path = o.optString("path"),
                        size = o.optLong("size"),
                        time = o.optLong("time"),
                        pic = o.optString("pic"), api = o.optString("api"), sourceName = o.optString("sourceName"), vid = o.optString("vid"), series = o.optBoolean("series")
                    )
                )
            }
        }
        val history = com.songshu.sstv.data.HistoryStore.list(c)
        return out.map { item ->
            if (item.api.isNotBlank()) item else {
                val h = history.firstOrNull { it.url == item.url || it.eps.any { ep -> ep.substringAfter('\u0001') == item.url } }
                if (h == null) item else item.copy(api = h.api, sourceName = h.srcName, vid = h.vid, series = h.eps.size > 1)
            }
        }.toMutableList()
    }

    private fun save(c: Context, l: List<DlItem>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject()
                    .put("url", it.url).put("title", it.title).put("sub", it.sub)
                    .put("path", it.path).put("size", it.size).put("time", it.time)
                    .put("pic", it.pic).put("api",it.api).put("sourceName",it.sourceName).put("vid",it.vid).put("series",it.series)
            )
        }
        sp(c).edit().putString(K, a.toString()).apply()
    }

    @Synchronized
    fun add(c: Context, item: DlItem) {
        val l = list(c)
        l.removeAll { it.url == item.url && it.api == item.api }
        l.add(0, item)
        save(c, l)
    }

    @Synchronized
    fun remove(c: Context, url: String, api: String? = null) {
        val l = list(c)
        l.removeAll { it.url == url && (api == null || it.api == api) }
        save(c, l)
    }

    fun totalSize(c: Context): Long = list(c).sumOf { it.size }
}
