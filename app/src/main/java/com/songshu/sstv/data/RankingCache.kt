package com.songshu.sstv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small presentation snapshots survive process death without keeping episode URLs in memory. */
object RankingCache {
    data class Snapshot(val items: List<Vod>, val time: Long, val complete: Boolean = true) {
        val fresh get() = complete && System.currentTimeMillis() - time in 0 until 10 * 60_000L
    }
    private fun prefs(c: Context) = c.getSharedPreferences("rankings",Context.MODE_PRIVATE)
    private fun key(api: String, filtered: Boolean) = api.trimEnd('/') + "|" + filtered
    fun read(c: Context, source: Source, filtered: Boolean): Snapshot? = runCatching {
        val root = JSONObject(prefs(c).getString(key(source.api,filtered), null) ?: return null)
        val list = root.getJSONArray("items")
        Snapshot((0 until list.length()).map { val o = list.getJSONObject(it)
            Vod(id=o.optString("id"),name=o.optString("name"),pic=o.optString("pic"),remark=o.optString("remark"),
                year=o.optString("year"),area=o.optString("area"),typeName=o.optString("type"),sourceApi=source.api,
                sourceName=source.name,updated=o.optString("updated"),heat=o.optLong("heat"),totalEpisodes=o.optInt("episodes"),completed=o.optBoolean("completed"))
        },root.optLong("time"),root.optBoolean("complete",true)).takeIf { it.items.isNotEmpty() }
    }.getOrNull()
    fun put(c: Context, source: Source, filtered: Boolean, items: List<Vod>, complete: Boolean = true) {
        val sorted = top(items.filterNot { filtered && SourceStore.isAdultCategory(it.typeName) })
        if (sorted.isEmpty()) { prefs(c).edit().remove(key(source.api,filtered)).apply(); return }
        val a = JSONArray(sorted.map { v -> JSONObject().put("id",v.id).put("name",v.name).put("pic",v.pic).put("remark",v.episodeStatus)
            .put("year",v.year).put("area",v.area).put("type",v.typeName).put("updated",v.updated).put("heat",v.heat)
            .put("episodes",v.knownEpisodeCount).put("completed",v.isCompleted) })
        prefs(c).edit().putString(key(source.api,filtered),JSONObject().put("items",a).put("time",System.currentTimeMillis()).put("complete",complete).toString()).apply()
    }
    fun top(items: List<Vod>) = items.distinctBy { it.id }.sortedWith(compareByDescending<Vod> { it.heat }.thenByDescending { it.updated }).take(10)
}
