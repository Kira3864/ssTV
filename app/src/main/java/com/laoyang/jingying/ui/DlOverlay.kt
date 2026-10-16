package com.laoyang.jingying.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.laoyang.jingying.R
import com.laoyang.jingying.cache.DlHub
import java.lang.ref.WeakReference

/**
 * 桌面悬浮窗的「调度员」。
 *
 * 只干三件事：
 * 1. 盯着有没有人发起新缓存（挂 [DlHub.onTaskCreated]），有就把 [DlOverlayService] 叫起来；
 * 2. 叫之前先看系统给没给「显示在其他应用上层」的权限 —— 没给就引导一次；
 * 3. 记住**当前在哪个页面**（[Dlg] 弹框得要个 Activity）。
 *
 * 【为什么现在每次没权限都要问】
 * v1.15 之前，App 里面还挂着一条进度条，所以「不开悬浮窗」只是少了个后台可见的进度，
 * 不影响用 —— 那就可以只问一次。
 * v1.16 把里面那条**取消了**，悬浮窗成了唯一能「边刷手机边看进度」的地方，
 * 于是改成：只要没权限、又新起了任务，就再问一次（弹框带「取消」，不会强逼）。
 */
object DlOverlay {

    private var app: Application? = null
    private var cur: WeakReference<Activity>? = null

    fun install(a: Application) {
        app = a
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                cur = WeakReference(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (cur?.get() === activity) cur = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        DlHub.onTaskCreated = { onTask() }
    }

    /** 系统允许我们「显示在其他应用上层」吗 */
    fun canDraw(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** 跳到系统的授权页 */
    fun openPerm(a: Activity) {
        val withPkg = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + a.packageName)
        )
        runCatching { a.startActivity(withPkg) }.onFailure {
            // 少数 ROM 不认带 package 的写法，退回打开总开关列表
            runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun onTask() {
        val c = app ?: return
        if (canDraw(c)) {
            DlOverlayService.start(c)
            return
        }

        // 没权限 —— 起服务也白起（系统会拦），直接引导。
        // 不再有「问过一次就永远闭嘴」的开关：里面那条进度条取消之后，
        // 这里不问，用户就真的看不到进度了。
        val a = cur?.get() ?: return
        a.runOnUiThread {
            if (a.isFinishing || a.isDestroyed) return@runOnUiThread
            Dlg.confirm(
                a,
                a.getString(R.string.overlay_ask_title),
                a.getString(R.string.overlay_ask_body)
            ) { openPerm(a) }
        }
    }
}
