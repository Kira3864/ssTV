package com.songshu.sstv

import android.content.Context

object PlaybackPreferences {
    fun cleared(context: Context) = context.getSharedPreferences("sstv_player",0).getBoolean("cleared", false)
    fun setCleared(context: Context, value: Boolean) { context.getSharedPreferences("sstv_player",0).edit().putBoolean("cleared",value).apply() }
    fun restoreLast(context: Context) = context.getSharedPreferences("sstv_player", 0)
        .getBoolean("restore_last", true)

    fun setRestoreLast(context: Context, enabled: Boolean) {
        context.getSharedPreferences("sstv_player", 0).edit().putBoolean("restore_last", enabled).apply()
    }
}
