package com.songshu.sstv

import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.*

/** Favorites are folders; likes form a series / episode / timestamp hierarchy. */
class LibraryActivity : ThemedActivity() {
    private val likes get() = intent.getBooleanExtra("likes", false)
    private val groupId get() = intent.getStringExtra("group")
    private val mediaKey get() = intent.getStringExtra("media")
    private val episodeKey get() = intent.getStringExtra("episode")
    private val display by lazy { DisplayPrefs(this, if (likes) "likes" else "favorites") }
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var adapter: Entries
    private data class Entry(val title: String, val sub: String, val covers: List<String>, val folder: Boolean = false,
        val group: FavoriteGroup? = null, val media: MediaRef? = null, val mark: LikeMark? = null, val episode: String? = null)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(themeColor(R.attr.appText)); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(themeColor(R.attr.appBg)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        val back = label("‹", 32f).apply { gravity = Gravity.CENTER; contentDescription = "返回"; setOnClickListener { finish() } }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(56)))
        title = label("", 20f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }; bar.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        val create = label("新建", 14f).apply { setPadding(dp(12), 0, dp(12), 0); setTextColor(themeColor(R.attr.appAccent)); setOnClickListener { LibraryActions.group(this@LibraryActivity) { render() } } }
        create.visibility = if (!likes && groupId == null) View.VISIBLE else View.GONE
        bar.addView(create, LinearLayout.LayoutParams(-2, dp(48)))
        val mode = label("视图 ▾", 14f).apply { setPadding(dp(12), 0, dp(12), 0); setTextColor(themeColor(R.attr.appAccent)); setOnClickListener { this@LibraryActivity.display.menu(it) { render() } } }
        bar.addView(mode, LinearLayout.LayoutParams(-2, dp(48))); root.addView(bar)
        val body = FrameLayout(this)
        list = RecyclerView(this).apply { clipToPadding = false; setPadding(dp(10), dp(6), dp(10), dp(16)); itemAnimator = null }
        adapter = Entries(); list.adapter = adapter
        body.addView(list, FrameLayout.LayoutParams(-1,-1))
        empty = label(if (likes) "暂无喜欢" else "暂无收藏", 16f).apply { gravity = Gravity.CENTER; setPadding(dp(24),dp(24),dp(24),dp(24)); setTextColor(themeColor(R.attr.appTextMuted)) }
        body.addView(empty, FrameLayout.LayoutParams(-1,-1)); root.addView(body, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }
    override fun onResume() { super.onResume(); render() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); render() }
    private fun render() {
        val entries: List<Entry>
        if (!likes) {
            val groups = LibraryStore.groups(this)
            val favorites = LibraryStore.favorites(this)
            val group = groups.firstOrNull { it.id == groupId }
            title.text = group?.name ?: "收藏夹"
            entries = if (groupId == null) groups.map { g ->
                val movies = favorites.filter { it.group == g.id }
                Entry(g.name, listOf(g.note, "${movies.size} 部").filter { it.isNotBlank() }.joinToString(" · "), movies.take(4).map { it.media.pic }, true, group = g)
            } else favorites.filter { it.group == groupId }.map { Entry(it.media.title, it.media.source, listOf(it.media.pic), media = it.media) }
        } else {
            val marks = LibraryStore.likes(this)
            entries = when {
                mediaKey == null -> {
                    title.text = "喜欢"
                    marks.groupBy { it.media.key }.values.map { group -> val media = group.first().media
                        Entry(media.title, "${media.source} · ${group.size} 个时间点", listOf(media.pic), media = media) }
                }
                episodeKey == null -> {
                    val selected = marks.filter { it.media.key == mediaKey }; title.text = selected.firstOrNull()?.media?.title ?: "剧集"
                    selected.groupBy { it.media.episode }.toList().sortedBy { VideoOrder.natural(it.first) }.map { (episode, points) ->
                        Entry(episode.ifBlank { "正片" }, "${points.size} 个时间点", listOf(points.first().media.pic), media = points.first().media, episode = episode) }
                }
                else -> {
                    title.text = episodeKey!!.ifBlank { "正片" }
                    marks.filter { it.media.key == mediaKey && it.media.episode == episodeKey }.sortedBy { it.position }.map {
                        Entry(time(it.position), java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it.created)), listOf(it.media.pic), media = it.media, mark = it) }
                }
            }
        }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.layoutManager = GridLayoutManager(this, if (display.grid) display.columns else 1)
        adapter.items = entries; adapter.notifyDataSetChanged()
    }
    private fun time(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s/3600, s/60%60, s%60) else "%d:%02d".format(s/60,s%60) }
    private fun open(entry: Entry) {
        if (!likes) {
            if (entry.group != null) startActivity(Intent(this, LibraryActivity::class.java).putExtra("group", entry.group.id))
            else entry.media?.open(this)
        } else if (entry.mark != null) startActivity(entry.mark.media.playIntent(this, entry.mark.position))
        else startActivity(Intent(this, LibraryActivity::class.java).putExtra("likes", true).putExtra("media", entry.media!!.key).apply {
            if (mediaKey != null) putExtra("episode", entry.episode)
        })
    }
    private fun options(entry: Entry) {
        if (entry.group != null) {
            val group = entry.group
            val options = if (group.id == "default") arrayOf("编辑分组") else arrayOf("编辑分组", "删除分组")
            AlertDialog.Builder(this).setTitle(group.name).setItems(options) { _, i ->
                if (i == 0) LibraryActions.group(this, group) { render() }
                else Dlg.confirm(this, "删除分组", "分组内的影片将移入「${LibraryStore.groups(this).first { it.id == "default" }.name}」。") { LibraryStore.deleteGroup(this, group.id); render() }
            }.show()
        } else if (!likes) LibraryActions.favorite(this, entry.media!!) { render() }
        else Dlg.confirm(this, "移除喜欢", if (entry.mark != null) "移除此时间点？" else "移除此${if (entry.episode != null) "集" else "影片"}的所有时间点？") {
            LibraryStore.likes(this).filter { mark -> if (entry.mark != null) mark.id == entry.mark.id else mark.media.key == entry.media!!.key && (entry.episode == null || mark.media.episode == entry.episode) }
                .forEach { LibraryStore.removeLike(this, it.id) }; render()
        }
    }
    private inner class Entries : RecyclerView.Adapter<Entries.Holder>() {
        var items = emptyList<Entry>()
        inner class Holder(val box: LinearLayout, val art: FrameLayout, val name: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(box)
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (display.grid) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val box = LinearLayout(this@LibraryActivity).apply { orientation = if (type == 0) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(dp(6),dp(6),dp(6),dp(12)); gravity = Gravity.CENTER_VERTICAL }
            val art = FrameLayout(this@LibraryActivity).apply { background = getDrawable(R.drawable.bg_card); clipToOutline = true }
            box.addView(art, LinearLayout.LayoutParams(if (type == 0) -1 else dp(76), if (type == 0) dp(140) else dp(100)))
            val info = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(if (type == 0) 0 else dp(14), dp(8), 0,0) }
            val name = label("", 15f,true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val sub = label("",12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(themeColor(R.attr.appTextMuted)) }
            info.addView(name); info.addView(sub, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
            box.addView(info, LinearLayout.LayoutParams(if(type == 0) -1 else 0,-2).apply { if (type != 0) weight = 1f })
            box.layoutParams = RecyclerView.LayoutParams(-1,-2)
            return Holder(box,art,name,sub)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.name.text = entry.title; holder.subtitle.text = entry.sub; holder.subtitle.visibility = if (entry.sub.isBlank()) View.GONE else View.VISIBLE
            val dense = display.grid && display.columns > 4
            holder.name.textSize = if (dense) 11f else 15f
            holder.subtitle.textSize = if (dense) 10f else 12f
            holder.subtitle.maxLines = if (dense) 1 else 2
            holder.box.setPadding(dp(if (dense) 2 else 6), dp(6), dp(if (dense) 2 else 6), dp(12))
            val width = ((resources.configuration.screenWidthDp - 20) / display.columns - if (dense) 4 else 12).coerceAtLeast(20)
            holder.art.layoutParams = holder.art.layoutParams.apply { height = dp(if (display.grid) if(entry.folder) width else (width*1.35f).toInt() else 100) }
            for (i in 0 until holder.art.childCount) (holder.art.getChildAt(i) as? ImageView)?.let { Img.load(it,null) }
            holder.art.removeAllViews()
            if (entry.folder) {
                val mosaic = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5),dp(5),dp(5),dp(5)) }
                repeat(2) { row -> val strip = LinearLayout(this@LibraryActivity)
                    repeat(2) { col -> val image = ImageView(this@LibraryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(themeColor(R.attr.appSurface)); contentDescription = "分组封面" }
                        strip.addView(image, LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }); Img.load(image, entry.covers.getOrNull(row*2+col)) }
                    mosaic.addView(strip, LinearLayout.LayoutParams(-1,0,1f)) }
                holder.art.addView(mosaic, FrameLayout.LayoutParams(-1,-1))
                if (entry.covers.isEmpty()) holder.art.addView(label("☆",28f).apply { gravity = Gravity.CENTER; setTextColor(themeColor(R.attr.appAccent)) }, FrameLayout.LayoutParams(-1,-1))
            } else {
                val image = ImageView(this@LibraryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                Img.round(image, ThemeStore.current(this@LibraryActivity).radius * resources.displayMetrics.density)
                holder.art.addView(image, FrameLayout.LayoutParams(-1,-1)); Img.load(image, entry.covers.firstOrNull())
            }
            holder.box.setOnClickListener { open(entry) }; holder.box.setOnLongClickListener { options(entry); true }; Motion.interactive(holder.box)
        }
    }
}
