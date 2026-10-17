package com.songshu.sstv

import android.content.Context

object PlaybackPreferences {
    fun restoreLast(context: Context) = context.getSharedPreferences("sstv_player", 0)
        .getBoolean("restore_last", false)

    fun setRestoreLast(context: Context, enabled: Boolean) {
        context.getSharedPreferences("sstv_player", 0).edit().putBoolean("restore_last", enabled).apply()
    }
}
