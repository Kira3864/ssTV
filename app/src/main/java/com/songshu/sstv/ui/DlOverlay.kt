package com.songshu.sstv.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.songshu.sstv.cache.DlHub

/** 已授权时显示下载悬浮窗；授权只由设置页手动发起。 */
object DlOverlay {
    fun install(app: Application) {
        DlHub.onTaskCreated = {
            if (canDraw(app)) DlOverlayService.start(app)
        }
    }

    fun canDraw(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    fun openPerm(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + activity.packageName))
        runCatching { activity.startActivity(intent) }.onFailure {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }
}
