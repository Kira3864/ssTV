package com.songshu.sstv

import android.content.Context
import android.util.TypedValue

/** Stable IDs keep the saved choice independent of the order of theme cards. */
object ThemeStore {
    data class Style(val id: String, val label: String, val description: String,
                     val resource: Int, val background: Int, val surface: Int,
                     val accent: Int, val ink: Int, val radius: Float, val font: String)

    val styles = listOf(
        Style("cinema", "曜石影院", "深黑 · 朱红 · 沉浸观影", R.style.AppTheme,
            0xFF101014.toInt(), 0xFF202027.toInt(), 0xFFCE334A.toInt(), 0xFFF7F3F2.toInt(), 12f, "sans-serif"),
        Style("aurora", "极光漫游", "靛蓝 · 薄荷 · 柔和光晕", R.style.Theme_Aurora,
            0xFF10182C.toInt(), 0xFF1C2D46.toInt(), 0xFF83EFCD.toInt(), 0xFFEAF5FF.toInt(), 24f, "sans-serif"),
        Style("paper", "晴日手帖", "米白 · 陶土 · 纸感衬线", R.style.Theme_Paper,
            0xFFF7F2E8.toInt(), 0xFFFFFCF5.toInt(), 0xFF974B32.toInt(), 0xFF302A23.toInt(), 6f, "serif"),
        Style("terminal", "像素终端", "墨绿 · 荧光 · 等宽直角", R.style.Theme_Terminal,
            0xFF09130F.toInt(), 0xFF12251B.toInt(), 0xFFA9EE80.toInt(), 0xFFE0F2DB.toInt(), 2f, "monospace")
    )

    fun current(c: Context): Style {
        val id = c.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "cinema")
        return styles.firstOrNull { it.id == id } ?: styles.first()
    }

    fun select(c: Context, style: Style) {
        c.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", style.id).apply()
    }

    fun reducedMotion(c: Context) =
        c.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("reduced_motion", false)

    fun setReducedMotion(c: Context, value: Boolean) {
        c.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("reduced_motion", value).apply()
    }
}

fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) getColor(value.resourceId) else value.data
}
