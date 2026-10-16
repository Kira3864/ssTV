package com.songshu.sstv.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.songshu.sstv.R

/**
 * 「选要缓存哪几集」面板。
 *
 * 详情页和播放页共用一套：一整季铺成网格，点一下勾一集、想多选就接着点，
 * 长按能一次圈一段（比如第 20 到 40 集）。顶部随时显示「已选 N/总 集」，
 * 底部按钮上的数字跟着变，**点之前就知道自己要下多少集**。
 *
 * 每一集的本地状态直接标在颜色上：
 *   · 红底白字 = 选中的
 *   · 灰底     = 没选的
 *   · 绿字     = 本地已经有了（默认不勾，免得白下一遍）
 *   · 橙字     = 正在缓存中（默认不勾）
 */
object CachePicker {

    /** 一集的本地状态 */
    enum class Mark { NONE, DONE, DOING }

    /**
     * @param eps      整季的「集名 to 地址」
     * @param selected 每一集初始勾没勾（长度必须跟 eps 一样）
     * @param marks    每一集的本地状态，用来上色
     * @param onStart  点「缓存 N 集」之后回调，只给选中的那些
     */
    fun show(
        ctx: Context,
        title: String,
        eps: List<Pair<String, String>>,
        selected: BooleanArray,
        marks: Array<Mark>,
        onStart: (List<Pair<String, String>>) -> Unit
    ) {
        if (eps.isEmpty()) return
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()

        val sel = selected.copyOf()
        val cells = ArrayList<TextView>()     // 下标 = 集号
        var anchor = -1                       // 长按圈区间时的起点：最近一次点过的那一集
        var countTv: TextView? = null
        var dlg: AlertDialog? = null

        // ── 先定行为，再搭界面：下面建控件时的监听才能引用到这几个函数 ──

        /** 重画：颜色 + 计数 + 按钮上的数字 */
        fun paint() {
            var n = 0
            for (i in cells.indices) {
                if (sel[i]) n++
                val on = sel[i]
                cells[i].background = ContextCompat.getDrawable(
                    ctx, if (on) R.drawable.bg_btn_primary else R.drawable.bg_episode
                )
                cells[i].setTextColor(
                    when {
                        on -> 0xFFFFFFFF.toInt()
                        marks.getOrNull(i) == Mark.DONE -> 0xFF3FB950.toInt()
                        marks.getOrNull(i) == Mark.DOING -> 0xFFFFB020.toInt()
                        else -> 0xFFC7CDD6.toInt()
                    }
                )
            }
            countTv?.text = "已选 $n/${eps.size} 集"
            dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.text =
                if (n > 0) "缓存 $n 集" else "缓存"
        }

        /** 点一下：切换这一集 */
        fun toggle(i: Int) {
            sel[i] = !sel[i]
            paint()
        }

        /** 长按：从上次点过的那一集圈到这一集；这一圈要是全勾着，那就整圈取消 */
        fun pickRange(i: Int) {
            if (anchor < 0) {
                toggle(i)
                anchor = i
                return
            }
            val a = minOf(anchor, i)
            val b = maxOf(anchor, i)
            val allOn = (a..b).all { sel[it] }
            for (k in a..b) sel[k] = !allOn
            paint()
        }

        // ── 顶部：计数 + 全选 / 清空 ───────────────────────────────
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20f), px(2f), px(20f), 0)
        }
        val cTv = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFFF2F4F7.toInt())
        }
        countTv = cTv
        head.addView(cTv, LinearLayout.LayoutParams(0, -2, 1f))

        head.addView(TextView(ctx).apply {
            text = "全选"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(px(12f), px(7f), px(12f), px(7f))
            setTextColor(0xFFE62429.toInt())
            setOnClickListener { for (i in sel.indices) sel[i] = true; paint() }
        })
        head.addView(TextView(ctx).apply {
            text = "清空"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(px(12f), px(7f), px(12f), px(7f))
            setTextColor(0xFF98A2B3.toInt())
            setOnClickListener { for (i in sel.indices) sel[i] = false; paint() }
        })

        val tipTv = TextView(ctx).apply {
            text = if (eps.size > 1)
                "点一下勾一集 · 长按可以圈一段 · 绿字是已经下过的"
            else
                "点一下勾上"
            textSize = 11.5f
            setTextColor(0xFF98A2B3.toInt())
            setPadding(px(20f), px(6f), px(20f), px(4f))
        }

        // ── 网格 ──────────────────────────────────────────────────
        val dm = ctx.resources.displayMetrics
        val per = if (dm.widthPixels > dm.heightPixels) 6 else 5
        val gap = px(6f)
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        for (r in 0 until (eps.size + per - 1) / per) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = gap }
            for (c in 0 until per) {
                val i = r * per + c
                if (i >= eps.size) {
                    // 最后一行不满，补等宽空位把前面的格子顶住，不然它们会被拉宽
                    row.addView(
                        View(ctx),
                        LinearLayout.LayoutParams(0, 1, 1f).apply { if (c > 0) leftMargin = gap }
                    )
                    continue
                }
                val tv = TextView(ctx).apply {
                    text = eps[i].first.ifBlank { "第${i + 1}集" }
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(px(4f), px(11f), px(4f), px(11f))
                    setOnClickListener {
                        toggle(i)
                        anchor = i
                    }
                    setOnLongClickListener {
                        pickRange(i)
                        true
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = gap })
                cells.add(tv)          // 按集号顺序加 → 下标就是集号
            }
            grid.addView(row)
        }

        val scroll = ScrollView(ctx).apply {
            setPadding(px(14f), px(2f), px(14f), px(10f))
            addView(grid, LinearLayout.LayoutParams(-1, -2))
        }
        // 行数少就别撑一大片空白；行数多就给到半屏高，里面滚
        val cellH = px(40f)
        val want = (eps.size + per - 1) / per * cellH + px(18f)
        scroll.layoutParams = LinearLayout.LayoutParams(
            -1, want.coerceIn(cellH + px(18f), (dm.heightPixels * 0.5f).toInt())
        )

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(head, LinearLayout.LayoutParams(-1, -2))
            addView(tipTv, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, -2))
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("缓存", null)
            .setNegativeButton("取消", null)
            .create()
        dlg = dialog

        dialog.setOnShowListener {
            paint()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val picked = ArrayList<Pair<String, String>>()
                for (i in sel.indices) if (sel[i]) picked.add(eps[i])
                if (picked.isEmpty()) {
                    // 一集都没勾就别关窗，让人接着选 —— 直接关掉会像"点了没反应"
                    Toast.makeText(ctx, "一集都没选", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    onStart(picked)
                }
            }
            // 打开时滚到第一个勾上的那一集，摆在中间 ——
            // 播放页默认勾的是"正在看的这一集"，可能是第 37 集，不滚过去就看不见
            val first = sel.indexOfFirst { it }
            if (first >= 0) {
                scroll.post {
                    val row = cells.getOrNull(first)?.parent as? View ?: return@post
                    if (scroll.height <= 0) return@post
                    val target = row.top - (scroll.height - row.height) / 2
                    scroll.scrollTo(0, target.coerceAtLeast(0))
                }
            }
        }

        dialog.show()
    }
}
