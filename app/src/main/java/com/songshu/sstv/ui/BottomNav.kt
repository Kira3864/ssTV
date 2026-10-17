package com.songshu.sstv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.songshu.sstv.*
import com.songshu.sstv.data.HistoryStore

class BottomNav @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.themeColor(R.attr.appSurface))
        val activity = context as? Activity
        val destinations = listOf(MainActivity::class.java, SearchActivity::class.java,
            PlayerActivity::class.java, MyActivity::class.java)
        val labels = listOf("首页", "找片", "播放", "我的")
        val icons = listOf(R.drawable.ic_nav_home, R.drawable.ic_nav_search, R.drawable.ic_nav_play, R.drawable.ic_nav_person)
        destinations.forEachIndexed { i, target ->
            val active = activity?.javaClass == target
            val color = context.themeColor(if (active) R.attr.appAccent else R.attr.appTextMuted)
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isSelected = active
                contentDescription = labels[i]
                addView(ImageView(context).apply {
                    setImageResource(icons[i]); setColorFilter(color)
                }, LayoutParams(dp(22), dp(22)))
                addView(TextView(context).apply {
                    text = labels[i]; textSize = 11f; gravity = Gravity.CENTER
                    includeFontPadding = false; setTextColor(color)
                }, LayoutParams(-2, -2).apply { topMargin = dp(3) })
                setOnClickListener { if (!active && activity != null) open(activity, target) }
                Motion.interactive(this)
            }
            addView(item, LayoutParams(0, -1, 1f))
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun open(activity: Activity, target: Class<out Activity>) {
            val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (target == PlayerActivity::class.java) {
                intent.putExtra("resumeExisting", true).putExtra("startPaused", true)
                HistoryStore.list(activity).firstOrNull()?.takeIf { PlaybackPreferences.restoreLast(activity) && !PlaybackPreferences.cleared(activity) }?.let { h ->
                    val eps = ArrayList(h.eps.ifEmpty { listOf(h.ep + "\u0001" + h.url) })
                    intent.putExtra("title", h.title).putExtra("pic", h.pic)
                        .putExtra("api", h.api).putExtra("srcName", h.srcName).putExtra("vid", h.vid)
                        .putStringArrayListExtra("eps", eps)
                        .putExtra("index", eps.indexOfFirst { it.substringAfter('\u0001') == h.url }.coerceAtLeast(0))
                        .putExtra("seek", h.pos)
                }
            }
            activity.startActivity(intent)
        }
    }
}
