package com.songshu.sstv.data

import android.content.Context
import android.content.Intent
import com.songshu.sstv.DetailActivity
import com.songshu.sstv.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject

data class MediaRef(val id: String = "", val title: String = "", val pic: String = "", val api: String = "",
    val source: String = "", val eps: List<String> = emptyList(), val index: Int = 0, val line: String = "", val status: String = "") {
    val key get() = api.trimEnd('/') + "\u0001" + id.ifBlank { title.trim() }
    val episode get() = eps.getOrNull(index)?.substringBefore('\u0001').orEmpty()
    fun playIntent(c: Context, position: Long = 0) = Intent(c, PlayerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .putExtra("title", title).putExtra("pic", pic).putExtra("api", api).putExtra("srcName", source)
        .putExtra("vid", id).putExtra("index", index).putExtra("line", line)
        .putStringArrayListExtra("eps", ArrayList(eps)).putExtra("seek", position).putExtra("startPaused", false)
    fun open(c: Context) {
        c.startActivity(if (id.isNotBlank() && api.isNotBlank() && eps.none { it.substringAfter('\u0001').startsWith("file:") }) Intent(c, DetailActivity::class.java)
            .putExtra("id", id).putExtra("name", title).putExtra("pic", pic).putExtra("api", api).putExtra("srcName", source)
            else playIntent(c))
    }
    fun json() = JSONObject().put("id", id).put("title", title).put("pic", pic).put("api", api)
        .put("source", source).put("eps", JSONArray(eps)).put("index", index).put("line", line).put("status",status)
    companion object {
        fun from(v: Vod, lineIndex: Int = 0) = MediaRef(v.id, v.name, v.pic, v.sourceApi, v.sourceName,
            v.lines.getOrNull(lineIndex)?.episodes?.map { it.name + "\u0001" + it.url }.orEmpty(), 0, v.lines.getOrNull(lineIndex)?.name.orEmpty(), v.episodeStatus)
        fun read(o: JSONObject): MediaRef {
            val a = o.optJSONArray("eps") ?: JSONArray()
            return MediaRef(o.optString("id"), o.optString("title"), o.optString("pic"), o.optString("api"), o.optString("source"),
                (0 until a.length()).map { a.optString(it) }, o.optInt("index"), o.optString("line"), o.optString("status"))
        }
    }
}
