package com.songshu.sstv.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.R
import com.songshu.sstv.ThemeStore
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.themeColor

enum class SourceAction { SELECT, EDIT, TEST, DELETE, ADD, IMPORT, TEST_ALL, RESTORE }

class SourceManagerDialog(private val activity: Activity, private val action: (SourceAction, Source?) -> Unit) : Dialog(activity) {
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private val rows = SourceRows()
    private val count = TextView(context)
    private val empty = TextView(context)
    private val search = EditText(context)
    private val list = RecyclerView(context)

    init {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)); setBackgroundResource(R.drawable.bg_card)
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("片源管理", 19f, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        count.apply { textSize = 13f; setTextColor(context.themeColor(R.attr.appTextMuted)) }
        head.addView(count)
        head.addView(label("×", 25f).apply {
            gravity = Gravity.CENTER; contentDescription = "关闭片源管理"; setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        box.addView(head)
        search.apply {
            id = R.id.sourceSearch; hint = "搜索片源"; textSize = 14f; setSingleLine()
            setPadding(dp(12), 0, dp(12), 0); setTextColor(context.themeColor(R.attr.appText)); setHintTextColor(context.themeColor(R.attr.appTextMuted))
            setBackgroundResource(R.drawable.bg_chip)
        }
        box.addView(search, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })
        val tools = listOf("添加" to SourceAction.ADD, "导入" to SourceAction.IMPORT, "检测全部" to SourceAction.TEST_ALL, "恢复预置" to SourceAction.RESTORE)
        tools.chunked(2).forEach { pair ->
            val row = LinearLayout(context)
            pair.forEachIndexed { index, (text, command) ->
                row.addView(label(text, 13f, true).apply {
                    gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_btn_ghost)
                    setOnClickListener { action(command, null) }; Motion.interactive(this)
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index == 0) rightMargin = dp(8) })
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val body = FrameLayout(context)
        list.apply { id = R.id.sourceList; layoutManager = LinearLayoutManager(context); adapter = rows; itemAnimator = null }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.apply { text = "没有匹配的片源"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(context.themeColor(R.attr.appTextMuted)) }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        box.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(box)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        search.addTextChangedListener { refresh() }
        refresh()
    }

    override fun show() { super.show(); resize() }
    fun resize() {
        val metrics = context.resources.displayMetrics
        window?.setLayout((metrics.widthPixels - dp(24)).coerceAtMost(dp(560)), (metrics.heightPixels * .84f).toInt().coerceAtMost(dp(680)))
    }
    fun refresh() {
        val query = search.text.toString().trim()
        rows.sources = SourceStore.sources(context).filter { it.name.contains(query, true) }
        rows.current = SourceStore.current(context).api
        rows.notifyDataSetChanged()
        count.text = "${rows.sources.size} 个"
        empty.visibility = if (rows.sources.isEmpty()) View.VISIBLE else View.GONE
    }
    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; gravity = Gravity.CENTER_VERTICAL; setTextColor(context.themeColor(R.attr.appText))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private inner class Holder(val row: LinearLayout, val name: TextView, val selected: TextView, val more: TextView) : RecyclerView.ViewHolder(row)
    private inner class SourceRows : RecyclerView.Adapter<Holder>() {
        var sources = emptyList<Source>()
        var current = ""
        override fun getItemCount() = sources.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0); minimumHeight = dp(58) }
            val name = label("", 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val selected = label("当前", 12f).apply { setTextColor(context.themeColor(R.attr.appAccent)); setPadding(dp(8), 0, 0, 0) }
            val more = label("⋮", 24f).apply { gravity = Gravity.CENTER }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(selected)
            row.addView(more, LinearLayout.LayoutParams(dp(48), dp(56)))
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            Motion.interactive(row); Motion.interactive(more)
            return Holder(row, name, selected, more)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val source = sources[position]
            holder.name.text = source.name
            holder.selected.visibility = if (source.api == current) View.VISIBLE else View.GONE
            holder.row.isSelected = source.api == current
            holder.row.contentDescription = source.name + if (source.api == current) "，当前片源" else "，设为当前"
            holder.row.setOnClickListener { action(SourceAction.SELECT, source) }
            holder.more.contentDescription = "管理${source.name}"
            holder.more.setOnClickListener {
                PopupMenu(context, holder.more).apply {
                    listOf("设为当前" to SourceAction.SELECT, "编辑" to SourceAction.EDIT, "检测" to SourceAction.TEST, "删除" to SourceAction.DELETE).forEach { (text, command) ->
                        menu.add(0, command.ordinal, 0, text)
                    }
                    setOnMenuItemClickListener { action(SourceAction.entries[it.itemId], source); true }
                    show()
                }
            }
        }
    }
}

object ThemePicker {
    fun show(activity: Activity, onSelect: (ThemeStore.Style) -> Unit): Dialog {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val dialog = Dialog(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(14)); setBackgroundResource(R.drawable.bg_card)
        }
        box.addView(TextView(activity).apply {
            text = "主题风格"; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(activity.themeColor(R.attr.appText)); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(48)))
        ThemeStore.styles.forEach { style ->
            val selected = ThemeStore.current(activity).id == style.id
            val card = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0); minimumHeight = dp(64)
                background = GradientDrawable().apply {
                    setColor(style.background); cornerRadius = dp(style.radius.toInt()).toFloat()
                    setStroke(dp(if (selected) 2 else 1), if (selected) style.accent else style.surface)
                }
                isSelected = selected; contentDescription = style.label + if (selected) "，已选中" else "，应用主题"
                setOnClickListener { dialog.dismiss(); onSelect(style) }
            }
            card.addView(TextView(activity).apply {
                text = style.label; textSize = 15f; setTextColor(style.ink); maxLines = 1
                typeface = Typeface.create(style.font, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            listOf(style.accent, style.surface, style.ink).forEach { color ->
                card.addView(View(activity).apply { background = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL } }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6) })
            }
            card.addView(TextView(activity).apply { text = if (selected) "✓" else ""; textSize = 18f; gravity = Gravity.END; setTextColor(style.accent) }, LinearLayout.LayoutParams(dp(28), -2))
            Motion.interactive(card)
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        dialog.setContentView(ScrollView(activity).apply { addView(box); setBackgroundColor(Color.TRANSPARENT) })
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.CENTER) }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
