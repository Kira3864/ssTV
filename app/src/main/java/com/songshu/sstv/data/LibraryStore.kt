package com.songshu.sstv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FavoriteGroup(val id: String, val name: String, val note: String = "")
data class Favorite(val media: MediaRef, val group: String, val time: Long)
data class LikeMark(val id: String, val media: MediaRef, val position: Long, val created: Long)

/** Group changes and entries are written together; identities include the provider. */
object LibraryStore {
    private fun prefs(c: Context) = c.getSharedPreferences("library", Context.MODE_PRIVATE)
    private fun read(c: Context, key: String): List<JSONObject> = runCatching {
        val a = JSONArray(prefs(c).getString(key, "[]")); (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    private fun save(c: Context, key: String, values: List<JSONObject>) { prefs(c).edit().putString(key, JSONArray(values).toString()).apply() }
    @Synchronized fun groups(c: Context): List<FavoriteGroup> {
        val list = read(c, "groups").map { FavoriteGroup(it.optString("id"), it.optString("name"), it.optString("note")) }
        if (list.isNotEmpty()) return list
        return listOf(FavoriteGroup("default", "收藏1")).also { saveGroups(c, it) }
    }
    private fun saveGroups(c: Context, groups: List<FavoriteGroup>) = save(c, "groups", groups.map { JSONObject().put("id", it.id).put("name", it.name).put("note", it.note) })
    @Synchronized fun createGroup(c: Context, name: String, note: String): FavoriteGroup {
        val list = groups(c); var number = 1
        while (list.any { it.name == "收藏$number" }) number++
        val group = FavoriteGroup(UUID.randomUUID().toString(), name.trim().ifBlank { "收藏$number" }, note.trim())
        saveGroups(c, list + group); return group
    }
    @Synchronized fun editGroup(c: Context, id: String, name: String, note: String) {
        saveGroups(c, groups(c).map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, note = note.trim()) else it })
    }
    @Synchronized fun deleteGroup(c: Context, id: String) {
        if (id == "default") return
        val remaining = groups(c).filterNot { it.id == id }
        val entries = favorites(c).map { if (it.group == id) it.copy(group = "default") else it }
        prefs(c).edit().putString("groups", JSONArray(remaining.map { JSONObject().put("id", it.id).put("name", it.name).put("note", it.note) }).toString())
            .putString("favorites", JSONArray(entries.map { favoriteJson(it) }).toString()).apply()
    }
    fun favorites(c: Context) = read(c, "favorites").map { Favorite(MediaRef.read(it.getJSONObject("media")), it.optString("group", "default"), it.optLong("time")) }
    private fun favoriteJson(f: Favorite) = JSONObject().put("media", f.media.json()).put("group", f.group).put("time", f.time)
    @Synchronized fun addFavorite(c: Context, media: MediaRef, group: String): Boolean {
        val entries = favorites(c)
        if (entries.any { it.media.key == media.key }) return false
        val valid = groups(c).firstOrNull { it.id == group }?.id ?: "default"
        save(c, "favorites", (listOf(Favorite(media, valid, System.currentTimeMillis())) + entries).map { favoriteJson(it) }); return true
    }
    @Synchronized fun removeFavorite(c: Context, key: String) = save(c, "favorites", favorites(c).filterNot { it.media.key == key }.map { favoriteJson(it) })
    @Synchronized fun moveFavorite(c: Context, key: String, group: String) {
        if (groups(c).none { it.id == group }) return
        save(c, "favorites", favorites(c).map { favoriteJson(if (it.media.key == key) it.copy(group = group) else it) })
    }
    fun likes(c: Context) = read(c, "likes").map { LikeMark(it.optString("id"), MediaRef.read(it.getJSONObject("media")), it.optLong("position"), it.optLong("created")) }
    private fun likeJson(mark: LikeMark) = JSONObject().put("id", mark.id).put("media", mark.media.json()).put("position", mark.position).put("created", mark.created)
    @Synchronized fun like(c: Context, media: MediaRef, position: Long): LikeMark {
        val mark = LikeMark(UUID.randomUUID().toString(), media, position.coerceAtLeast(0), System.currentTimeMillis())
        save(c, "likes", (listOf(mark) + likes(c)).map { likeJson(it) }); return mark
    }
    @Synchronized fun removeLike(c: Context, id: String) = save(c, "likes", likes(c).filterNot { it.id == id }.map { likeJson(it) })
}
