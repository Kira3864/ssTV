package com.songshu.sstv

import java.util.concurrent.Executors

/** 全局后台线程池。网络与下载走 io，封面解码走 img。 */
object Bg {
    val io = Executors.newFixedThreadPool(4)
    val play = Executors.newFixedThreadPool(2)
    val ranking = Executors.newFixedThreadPool(2)
    val img = Executors.newFixedThreadPool(4)
    val dl = Executors.newFixedThreadPool(3)
}
