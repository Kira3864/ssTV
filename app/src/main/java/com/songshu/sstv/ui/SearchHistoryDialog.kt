package com.songshu.sstv.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import com.songshu.sstv.R
import com.songshu.sstv.themeColor
import com.songshu.sstv.data.SearchHistoryStore

object SearchHistoryDialog {
    fun show(c: Context, selected: (String) -> Unit): Dialog {
        fun dp(n: Int) = (n * c.resources.displayMetrics.density).toInt()
        fun label(value: String) = TextView(c).apply { text=value; textSize=15f; gravity=Gravity.CENTER_VERTICAL; setTextColor(c.themeColor(R.attr.appText)) }
        val dialog = Dialog(c)
        val box = LinearLayout(c).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(10),dp(18),dp(14)); setBackgroundResource(R.drawable.bg_card) }
        val head = LinearLayout(c).apply { gravity=Gravity.CENTER_VERTICAL }
        head.addView(label("搜索历史").apply { textSize=19f; setTypeface(typeface,android.graphics.Typeface.BOLD) },LinearLayout.LayoutParams(0,dp(52),1f))
        val clear = label("清空").apply { setTextColor(c.themeColor(R.attr.appAccent)); gravity=Gravity.CENTER }
        head.addView(clear,LinearLayout.LayoutParams(dp(52),dp(48))); box.addView(head)
        val scroll = ScrollView(c)
        val rows = LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(rows); box.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        fun render() {
            rows.removeAllViews()
            val terms = SearchHistoryStore.list(c); clear.visibility = if (terms.isEmpty()) View.GONE else View.VISIBLE
            if (terms.isEmpty()) rows.addView(label("暂无搜索历史").apply { gravity=Gravity.CENTER; setTextColor(c.themeColor(R.attr.appTextMuted)) },LinearLayout.LayoutParams(-1,dp(100)))
            terms.forEach { term ->
                val row = LinearLayout(c).apply { gravity=Gravity.CENTER_VERTICAL }
                row.addView(label(term).apply {
                    maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
                    setOnClickListener { dialog.dismiss(); selected(term) }
                },LinearLayout.LayoutParams(0,dp(48),1f))
                row.addView(label("×").apply { textSize=22f; gravity=Gravity.CENTER; contentDescription="删除搜索 $term"; setTextColor(c.themeColor(R.attr.appTextMuted)); setOnClickListener { SearchHistoryStore.remove(c,term); render() } },LinearLayout.LayoutParams(dp(40),dp(48)))
                rows.addView(row)
            }
            scroll.layoutParams = scroll.layoutParams.apply { height = minOf(dp(if (terms.isEmpty()) 100 else terms.size*48), (c.resources.displayMetrics.heightPixels*.58f).toInt()) }
        }
        clear.setOnClickListener { SearchHistoryStore.clear(c); render() }
        render(); dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setGravity(Gravity.BOTTOM); dialog.show()
        dialog.window?.setLayout(c.resources.displayMetrics.widthPixels-dp(24),-2)
        return dialog
    }
}
