package com.songshu.sstv.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** 在线播放的边看边缓存。播过的片段留在本地，断网可接着放。 */
object CacheMgr {

    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2GB 上限，LRU 自动淘汰

    @Volatile
    private var inst: SimpleCache? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "online_cache")

    @Synchronized
    fun get(ctx: Context): SimpleCache {
        inst?.let { return it }
        val c = SimpleCache(
            dir(ctx),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(ctx)
        )
        inst = c
        return c
    }

    @Synchronized
    fun size(ctx: Context): Long = runCatching { get(ctx).cacheSpace }.getOrDefault(0L)

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { inst?.release() }
        inst = null
        runCatching {
            SimpleCache.delete(dir(ctx), StandaloneDatabaseProvider(ctx))
        }
    }
}
