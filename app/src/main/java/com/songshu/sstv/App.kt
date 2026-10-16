package com.songshu.sstv

import android.app.Application
import com.songshu.sstv.ui.DlOverlay

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 缓存进度只在**软件外面**那一颗蜘蛛上显示（桌面悬浮窗）。
        // v1.15 里 App 内页面底部那条进度条已经取消 —— 同一份进度挂两遍没意义，
        // 而且切页面时它会挡着列表。
        DlOverlay.install(this)
    }
}
