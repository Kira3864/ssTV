package com.songshu.sstv.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

object Dlg {

    /** 通用多行输入框 */
    fun input(
        ctx: Context,
        title: String,
        hints: List<String>,
        initial: List<String> = emptyList(),
        onOk: (List<String>) -> Unit
    ) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 30, 52, 0)
        }
        val edits = hints.mapIndexed { i, h ->
            EditText(ctx).apply {
                hint = h
                setText(initial.getOrElse(i) { "" })
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
                box.addView(
                    this,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) 0 else 26 }
                )
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onOk(edits.map { it.text.toString().trim() }) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun confirm(ctx: Context, title: String, msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    fun tip(ctx: Context, title: String, msg: String) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }
}
