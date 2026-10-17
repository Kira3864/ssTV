package com.songshu.sstv.data

import android.content.Context
import com.songshu.sstv.Bg

/** Home prefetch and the discovery page share one request per provider/filter. */
object RankingLoader {
    private data class Listener(val changed: (List<Vod>) -> Unit, val finished: (Result<List<Vod>>) -> Unit)
    private class Request {
        var latest = emptyList<Vod>()
        val listeners = mutableListOf<Listener>()
    }
    private val lock = Any()
    private val running = hashMapOf<String,Request>()
    fun load(context: Context, source: Source, filtered: Boolean, changed: (List<Vod>) -> Unit = {},
        finished: (Result<List<Vod>>) -> Unit = {}): () -> Unit {
        val app = context.applicationContext
        val cached = RankingCache.read(app,source,filtered)
        if (cached?.fresh == true) { changed(cached.items); finished(Result.success(cached.items)); return {} }
        val key = source.api.trimEnd('/') + "|" + filtered
        val listener = Listener(changed,finished)
        var created = false
        val request: Request
        val latest: List<Vod>
        synchronized(lock) {
            request = running.getOrPut(key) { created=true; Request() }
            request.listeners.add(listener); latest = request.latest
        }
        if (latest.isNotEmpty()) changed(latest)
        if (created) Bg.ranking.execute {
            val result = runCatching {
                val movies = linkedMapOf<String,Vod>()
                val seen = hashSetOf<String>()
                var page = 1
                do {
                    val response = VodApi.listByType(source.api,"",page,source.name)
                    var added = 0
                    response.list.forEach { movie -> if (seen.add(movie.id)) {
                        added++
                        if (!filtered || !SourceStore.isAdultCategory(movie.typeName)) movies[movie.id] = movie
                    } }
                    val partial = RankingCache.top(movies.values.toList())
                    if (partial.isNotEmpty()) {
                        val listeners = synchronized(lock) { request.latest=partial; request.listeners.toList() }
                        listeners.forEach { runCatching { it.changed(partial) } }
                    }
                    page++
                    if (added == 0 || page > response.pageCount) break
                } while (movies.size < 10)
                RankingCache.top(movies.values.toList()).also { RankingCache.put(app,source,filtered,it) }
            }
            val listeners = synchronized(lock) { running.remove(key); request.listeners.toList().also { request.listeners.clear() } }
            listeners.forEach { runCatching { it.finished(result) } }
        }
        return { synchronized(lock) { request.listeners.remove(listener) }; Unit }
    }
}
