package com.laoyang.jingying.cache

import android.content.Context
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** 取消令牌。Downloader 每读一块、每下一个分段都看一眼它。 */
class CancelFlag {
    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }
}

enum class DlState { RUN, OK, FAIL, CANCELED }

/**
 * 一个缓存任务。
 *
 * 单集就一条；「一次挑好几集」也用一条覆盖 —— 进度按
 * 「已完成集数 + 当前集进度」折算，列表里只占一行也不会看乱。
 *
 * 字段都是 [Volatile]：下载线程写，UI 线程读，不走锁。
 */
class DlTask internal constructor(
    val id: Long,
    val title: String,
    val pic: String,
    val epCount: Int,
    val eps: List<Pair<String, String>>
) {

    @Volatile var epName: String = eps.firstOrNull()?.first.orEmpty()
    @Volatile var epIndex: Int = 0
    @Volatile var curPercent: Int = 0
    @Volatile var doneBytes: Long = 0
    @Volatile var totalBytes: Long = 0
    @Volatile var speed: Long = 0
    @Volatile var okCount: Int = 0
    @Volatile var state: DlState = DlState.RUN
    @Volatile var note: String = ""
    @Volatile var finishAt: Long = 0

    val flag = CancelFlag()

    /** 总进度 0-100 */
    val percent: Int
        get() {
            val p = if (epCount <= 1) curPercent
            else ((epIndex * 100L + curPercent) / epCount).toInt()
            return p.coerceIn(0, 100)
        }

    val isRunning: Boolean get() = state == DlState.RUN

    /** 多集时显示「第 3/12 集」 */
    val stage: String
        get() = if (epCount <= 1) "" else "第 ${(epIndex + 1).coerceAtMost(epCount)}/$epCount 集"

    fun cancel() = flag.cancel()

    /** 下载线程收尾：定状态、写一句人话 */
    fun finish() {
        state = when {
            flag.cancelled -> DlState.CANCELED
            okCount > 0 -> DlState.OK
            else -> DlState.FAIL
        }
        if (state == DlState.OK) curPercent = 100
        speed = 0
        finishAt = System.currentTimeMillis()
        note = when (state) {
            DlState.OK -> if (epCount > 1) "已缓存 ${okCount}/${epCount} 集" else "缓存完成"
            DlState.CANCELED -> if (okCount > 0) "已取消（成功 $okCount 集）" else "已取消"
            else -> "没下下来，换个源或稍后再试"
        }
    }
}

/**
 * 全局缓存任务表。App 活着就在，所以从播放页发起、切到缓存页也能看见；
 * 反过来 App 退出，任务也就没了（没做完的 .part 下次会重下）。
 */
object DlHub {

    private val seq = AtomicLong(1)
    private val tasks = Collections.synchronizedList(ArrayList<DlTask>())

    /** 最多留几条已结束的记录给用户看结果 */
    private const val KEEP_DONE = 5

    /**
     * 一有新任务就回调一次。
     *
     * App 里拿它把「桌面悬浮窗」叫起来 —— 不然 DlHub 这个纯数据表
     * 就得自己去碰 Service 和权限，脏。
     */
    @Volatile
    var onTaskCreated: (() -> Unit)? = null

    fun create(title: String, pic: String, eps: List<Pair<String, String>>): DlTask {
        val t = DlTask(seq.getAndIncrement(), title, pic, eps.size.coerceAtLeast(1), eps)
        synchronized(tasks) {
            tasks.add(0, t)
            val done = tasks.filter { it.state != DlState.RUN }
            if (done.size > KEEP_DONE) tasks.removeAll(done.take(done.size - KEEP_DONE))
        }
        // 通知的失败不能连累下载本身，包一层
        runCatching { onTaskCreated?.invoke() }
        return t
    }

    fun list(): List<DlTask> = synchronized(tasks) { ArrayList(tasks) }

    fun runningCount(): Int = list().count { it.isRunning }

    /** 这一集的地址此刻有没有任务在跑 —— 选缓存集数时用来标「缓存中」并防重复下 */
    fun isDownloading(url: String): Boolean =
        list().any { it.isRunning && it.eps.any { p -> p.second == url } }

    fun remove(t: DlTask) {
        tasks.remove(t)
    }

    /** 成功的任务：记录已经进了「我的缓存」列表，这里就不必再占一行 */
    fun clearDone() {
        tasks.removeAll(list().filter { it.state == DlState.OK })
    }
}

/** 把一批集跑完，进度实时写回任务 */
object DlRunner {

    /** 返回成功集数 */
    fun run(ctx: Context, task: DlTask): Int {
        var ok = 0
        for (i in task.eps.indices) {
            if (task.flag.cancelled) break
            val ep = task.eps[i]
            task.epIndex = i
            task.epName = ep.first
            task.curPercent = 0
            task.doneBytes = 0
            task.totalBytes = 0
            task.speed = 0
            val f = Downloader.download(
                ctx, task.title, ep.first, ep.second, task.pic, task.flag
            ) { r ->
                task.curPercent = r.percent
                task.doneBytes = r.bytes
                task.totalBytes = r.total
                task.speed = r.speed
            }
            if (f != null) {
                ok++
                task.okCount = ok
            }
        }
        task.finish()
        return ok
    }
}
