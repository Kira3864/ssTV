package com.songshu.sstv.cache

import android.content.Context
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** 取消令牌。Downloader 每读一块、每下一个分段都看一眼它。 */
class CancelFlag {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.HttpURLConnection>()
    @Volatile
    var cancelled = false
        private set

    @Volatile var keepPartial = false
        private set
    fun pause() { keepPartial = true; cancel() }

    fun cancel() {
        cancelled = true
        active.forEach { runCatching { it.disconnect() } }
    }
    internal fun watch(connection: java.net.HttpURLConnection) { active.add(connection); if (cancelled) runCatching { connection.disconnect() } }
    internal fun unwatch(connection: java.net.HttpURLConnection) { active.remove(connection) }
}

enum class DlState { QUEUED, RUN, PAUSED, OK, FAIL, CANCELED }

/** 每集独立保存；批量选择只共享启动队列。 */
class DlTask internal constructor(
    val id: Long, val title: String, val pic: String, val epCount: Int,
    val eps: List<Pair<String, String>>, val api: String = "", val sourceName: String = "",
    val vid: String = "", val series: Boolean = false
) {
    val mediaKey get() = api.trimEnd('/') + "\u0001" + vid.ifBlank { title }
    @Volatile var epName = eps.firstOrNull()?.first.orEmpty()
    @Volatile var epIndex = 0
    @Volatile var curPercent = 0
    @Volatile var doneBytes = 0L
    @Volatile var totalBytes = 0L
    @Volatile var speed = 0L
    @Volatile var okCount = 0
    @Volatile var state = DlState.QUEUED
    @Volatile var note = ""
    @Volatile var finishAt = 0L
    @Volatile var flag = CancelFlag()
    internal var batch: List<DlTask> = emptyList()
    internal val worker = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var removed = false
    val percent get() = curPercent.coerceIn(0,100)
    val isRunning get() = state == DlState.RUN || state == DlState.QUEUED
    val stage get() = ""
    fun cancel() = DlHub.pause(this)
}

object DlHub {
    private val seq = AtomicLong(System.currentTimeMillis())
    private val tasks = Collections.synchronizedList(ArrayList<DlTask>())
    private var context: Context? = null
    @Volatile var onTaskCreated: (() -> Unit)? = null

    @Synchronized fun init(ctx: Context) {
        context = ctx.applicationContext
        tasks.clear()
        val raw = ctx.getSharedPreferences("download_tasks",0).getString("tasks","[]")
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val t = DlTask(o.getLong("id"),o.getString("title"),o.optString("pic"),1,
                    listOf(o.getString("ep") to o.getString("url")),o.optString("api"),o.optString("source"),o.optString("vid"),o.optBoolean("series"))
                t.state = runCatching { DlState.valueOf(o.getString("state")) }.getOrDefault(DlState.PAUSED)
                if (t.isRunning) t.state = DlState.PAUSED
                t.curPercent=o.optInt("percent"); t.doneBytes=o.optLong("bytes"); t.totalBytes=o.optLong("total")
                tasks.add(t); seq.set(maxOf(seq.get(),t.id+1))
            }
        }
    }
    @Synchronized internal fun persist() {
        val c = context ?: return
        val a = org.json.JSONArray()
        list().forEach { t -> a.put(org.json.JSONObject().put("id",t.id).put("title",t.title).put("pic",t.pic)
            .put("ep",t.epName).put("url",t.eps.first().second).put("api",t.api).put("source",t.sourceName)
            .put("vid",t.vid).put("series",t.series).put("state",t.state.name).put("percent",t.percent)
            .put("bytes",t.doneBytes).put("total",t.totalBytes)) }
        c.getSharedPreferences("download_tasks",0).edit().putString("tasks",a.toString()).commit()
    }
    @Synchronized fun create(title: String, pic: String, eps: List<Pair<String,String>>, api: String = "", sourceName: String = "", vid: String = "", series: Boolean = eps.size>1): DlTask {
        require(eps.isNotEmpty())
        val batch = eps.distinctBy { it.second }.map { ep ->
            list().firstOrNull { it.api == api && it.eps.first().second == ep.second } ?: DlTask(seq.getAndIncrement(),title,pic,1,listOf(ep),api,sourceName,vid,series).also { tasks.add(it) }
        }
        batch.filter { !it.worker.get() && it.state in listOf(DlState.PAUSED,DlState.FAIL,DlState.CANCELED) }.forEach {
            it.flag=CancelFlag(); it.state=DlState.QUEUED
        }
        batch.first().batch = batch
        persist(); runCatching { onTaskCreated?.invoke() }
        return batch.first()
    }
    @Synchronized internal fun claim(t: DlTask): Boolean {
        if(t.removed || t.state!=DlState.QUEUED || !t.worker.compareAndSet(false,true)) return false
        t.state=DlState.RUN; persist(); return true
    }
    fun list(): List<DlTask> = synchronized(tasks) { ArrayList(tasks) }
    fun runningCount() = list().count { it.isRunning }
    fun isDownloading(url: String, api: String? = null) = list().any { it.isRunning && (api == null || it.api == api) && it.eps.any { p -> p.second == url } }
    @Synchronized fun pause(t: DlTask) {
        if (!t.isRunning) return
        t.state=DlState.PAUSED; t.flag.pause(); t.speed=0; persist()
    }
    @Synchronized fun resume(ctx: Context, t: DlTask) {
        if (t.removed || t.isRunning || t.state == DlState.OK || t.worker.get()) return
        t.flag=CancelFlag(); t.state=DlState.QUEUED; persist()
        val app=ctx.applicationContext
        com.songshu.sstv.Bg.dl.execute { DlRunner.run(app,t) }
        runCatching { onTaskCreated?.invoke() }
    }
    @Synchronized fun remove(t: DlTask) {
        t.removed=true; t.flag.cancel(); tasks.remove(t); persist()
        if (!t.worker.get()) context?.let { Downloader.discard(it,t.eps.first().second,t.api,t.vid,t.title,t.series) }
    }
    @Synchronized internal fun finished(ctx: Context, t: DlTask, file: java.io.File?) {
        if(t.removed) {
            Downloader.discard(ctx,t.eps.first().second,t.api,t.vid,t.title,t.series)
            if(file!=null) { DlStore.remove(ctx,t.eps.first().second,t.api); file.delete() }
        } else if(file!=null) {
            t.state=DlState.OK; t.curPercent=100; t.okCount=1
        } else if(t.state!=DlState.PAUSED) t.state=DlState.FAIL
        t.speed=0; t.finishAt=System.currentTimeMillis(); t.worker.set(false); persist()
    }
    fun clearDone() { list().filter { it.state == DlState.OK }.forEach { remove(it) } }
}

object DlRunner {
    // Serialize retries of the same episode, including deletion cleanup from an old worker.
    private val episodeLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    fun run(ctx: Context, task: DlTask): Int {
        val app=ctx.applicationContext
        var ok=0
        val batch=task.batch.ifEmpty { listOf(task) }; task.batch=emptyList()
        for(t in batch) {
            val key=t.api+"\u0001"+t.eps.first().second
            synchronized(episodeLocks.getOrPut(key) { Any() }) {
                if(!DlHub.claim(t)) return@synchronized
                var file: java.io.File?=null
                try {
                    var saved=0L
                    file=Downloader.download(app,t.title,t.epName,t.eps.first().second,t.pic,t.flag,t.api,t.sourceName,t.vid,t.series) { r ->
                        t.curPercent=r.percent; t.doneBytes=r.bytes; t.totalBytes=r.total; t.speed=r.speed
                        if(System.currentTimeMillis()-saved>1000) { DlHub.persist(); saved=System.currentTimeMillis() }
                    }
                } catch (_: Exception) { /* Incomplete data remains available for a retry. */ }
                finally { DlHub.finished(app,t,file) }
                if(t.state==DlState.OK && !t.removed) ok++
            }
        }
        return ok
    }
}
