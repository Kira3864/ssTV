package com.songshu.sstv.ui

import android.content.Context
import android.view.View
import android.widget.PopupMenu

class DisplayPrefs(private val c: Context, private val key: String, private val defaultGrid: Boolean = true) {
    private val sp get() = c.getSharedPreferences("display", Context.MODE_PRIVATE)
    var grid: Boolean
        get() = sp.getBoolean("${key}_grid", defaultGrid)
        set(value) { sp.edit().putBoolean("${key}_grid", value).apply() }
    var columns: Int
        get() = sp.getInt("${key}_columns", 3).coerceIn(2, 8)
        set(value) { sp.edit().putInt("${key}_columns", value.coerceIn(2,8)).apply() }
    fun menu(anchor: View, changed: () -> Unit) {
        PopupMenu(c, anchor).apply {
            menu.add(0, 1, 0, "列表").isChecked = !grid
            menu.add(0, 2, 0, "网格").isChecked = grid
            menu.setGroupCheckable(0, true, true)
            val counts = menu.addSubMenu("网格列数")
            for (n in 2..8) counts.add(1, 10 + n, n, "$n 列").apply { isCheckable = true; isChecked = columns == n }
            setOnMenuItemClickListener {
                when (it.itemId) { 1 -> grid = false; 2 -> grid = true; in 12..18 -> { columns = it.itemId - 10; grid = true }; else -> return@setOnMenuItemClickListener false }
                changed(); true
            }
            show()
        }
    }
}
