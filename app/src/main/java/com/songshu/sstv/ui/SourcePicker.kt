package com.songshu.sstv.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.addTextChangedListener
import com.songshu.sstv.R
import com.songshu.sstv.SettingsActivity
import com.songshu.sstv.data.Source
import com.songshu.sstv.themeColor

object SourcePicker {
    fun show(context: Context, sources: List<Source>, currentApi: String, onSelect: (Source) -> Unit) {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val dialog = Dialog(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundResource(R.drawable.bg_card)
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = "切换片源"; gravity = Gravity.CENTER_VERTICAL; textSize = 19f; setTextColor(context.themeColor(R.attr.appText))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        head.addView(TextView(context).apply {
            text = "管理"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(context.themeColor(R.attr.appAccent)); setPadding(dp(12), 0, 0, 0)
            setOnClickListener {
                dialog.dismiss(); context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        box.addView(head)
        val search = EditText(context).apply {
            hint = "搜索片源"; textSize = 14f; setSingleLine(); setPadding(dp(12), 0, dp(12), 0)
            setTextColor(context.themeColor(R.attr.appText))
            setHintTextColor(context.themeColor(R.attr.appTextMuted))
            setBackgroundResource(R.drawable.bg_chip)
        }
        box.addView(search, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(rows); isFillViewport = false }
        box.addView(scroll, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * .48f).toInt().coerceAtMost(dp(320))))
        fun render(query: String) {
            rows.removeAllViews()
            sources.filter { it.name.contains(query, true) }.forEach { source ->
                rows.addView(TextView(context).apply {
                    text = source.name + if (source.api == currentApi) "  ✓" else ""
                    textSize = 14f; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    setTextColor(context.themeColor(if (source.api == currentApi) R.attr.appOnAccent else R.attr.appText))
                    if (source.api == currentApi) setBackgroundResource(R.drawable.bg_btn_primary)
                    setOnClickListener { dialog.dismiss(); onSelect(source) }
                    Motion.interactive(this)
                }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(4) })
            }
        }
        render("")
        search.addTextChangedListener { render(it.toString().trim()) }
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setGravity(Gravity.BOTTOM)
            setLayout(context.resources.displayMetrics.widthPixels - dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setLayout(context.resources.displayMetrics.widthPixels - dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
