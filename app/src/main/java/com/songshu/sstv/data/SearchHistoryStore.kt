package com.songshu.sstv.data

import android.content.Context
import org.json.JSONArray

object SearchHistoryStore {
    private fun prefs(c: Context) = c.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    fun list(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("terms", "[]"))
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }.distinct().take(15)
    }.getOrDefault(emptyList())
    private fun save(c: Context, terms: List<String>) { prefs(c).edit().putString("terms",JSONArray(terms).toString()).apply() }
    @Synchronized fun add(c: Context, value: String) {
        val term = value.trim()
        if (term.isNotEmpty()) save(c, (listOf(term) + list(c).filterNot { it.equals(term, true) }).take(15))
    }
    @Synchronized fun remove(c: Context, term: String) = save(c,list(c).filterNot { it == term })
    fun clear(c: Context) { prefs(c).edit().clear().apply() }
}
