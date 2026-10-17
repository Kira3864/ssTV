package com.songshu.sstv.cache

/** 前台播放时减少离线下载并发，避免争抢带宽。 */
object TransferBudget {
    @Volatile var playbackActive = false
    val workers get() = if(playbackActive) 2 else 8
    private val active = java.util.concurrent.atomic.AtomicInteger()

    internal fun transfer(flag: CancelFlag?, block: () -> Boolean): Boolean {
        while(flag?.cancelled != true && !Thread.currentThread().isInterrupted) {
            val count=active.get()
            if(count<workers && active.compareAndSet(count,count+1)) {
                try { return block() } finally { active.decrementAndGet() }
            }
            try { Thread.sleep(40) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return false
    }
}
