package com.laoyang.jingying.cache

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
    val pic: String = ""
)

/** 已缓存列表的索引（存 SharedPreferences） */
object DlStore {

    private const val PREF = "jingying"
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
                        pic = o.optString("pic")
                    )
                )
            }
        }
        return out
    }

    private fun save(c: Context, l: List<DlItem>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject()
                    .put("url", it.url).put("title", it.title).put("sub", it.sub)
                    .put("path", it.path).put("size", it.size).put("time", it.time)
                    .put("pic", it.pic)
            )
        }
        sp(c).edit().putString(K, a.toString()).apply()
    }

    fun add(c: Context, item: DlItem) {
        val l = list(c)
        l.removeAll { it.url == item.url }
        l.add(0, item)
        save(c, l)
    }

    fun remove(c: Context, url: String) {
        val l = list(c)
        l.removeAll { it.url == url }
        save(c, l)
    }

    fun totalSize(c: Context): Long = list(c).sumOf { it.size }
}
