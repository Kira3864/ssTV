package com.songshu.sstv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.cache.DlHub
import com.songshu.sstv.cache.DlItem
import com.songshu.sstv.cache.DlState
import com.songshu.sstv.cache.DlStore
import com.songshu.sstv.cache.DlTask
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.Img
import java.io.File

class CacheActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var dlBox: View
    private lateinit var dlTitle: TextView
    private lateinit var dlList: LinearLayout

    private val ad = DlAdapter(
        onPlay = { play(it) },
        onDel = { del(it) }
    )

    /** 正在缓存的每一行，按任务 id 复用，免得每刷新一次就重建整块（会闪） */
    private val dlViews = HashMap<Long, View>()
    private var tick = 0

    /** 700ms 刷一次进度；已缓存列表不必这么勤，每 3 拍刷一次就够 */
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            refreshDl()
            if (tick % 3 == 0) refreshDone()
            list.postDelayed(this, 700L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cache)

        list = findViewById(R.id.list)
        summary = findViewById(R.id.summary)
        hint = findViewById(R.id.hint)
        dlBox = findViewById(R.id.dlBox)
        dlTitle = findViewById(R.id.dlTitle)
        dlList = findViewById(R.id.dlList)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ad

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        tick = 0
        refresh()
        list.removeCallbacks(ticker)
        list.postDelayed(ticker, 400L)
    }

    override fun onPause() {
        super.onPause()
        list.removeCallbacks(ticker)
    }

    // ───────────────────────── 正在缓存 ─────────────────────────

    private fun refresh() {
        refreshDl()
        refreshDone()
    }

    private fun refreshDl() {
        // 成功的任务记录已经进了下面的列表，这里留 2 秒让人看见「完成」就撤掉
        val now = System.currentTimeMillis()
        DlHub.list()
            .filter { it.state == DlState.OK && now - it.finishAt > 2000 }
            .forEach { DlHub.remove(it) }

        val tasks = DlHub.list()
        if (tasks.isEmpty()) {
            dlList.removeAllViews()
            dlViews.clear()
            dlBox.visibility = View.GONE
            return
        }
        dlBox.visibility = View.VISIBLE
        val running = tasks.count { it.isRunning }
        dlTitle.text = if (running > 0) "正在缓存（$running 个）" else "缓存记录"

        // 已经不在表里的行，拆掉
        val ids = tasks.map { it.id }.toSet()
        dlViews.keys.filter { it !in ids }.forEach { id ->
            dlViews[id]?.let { dlList.removeView(it) }
            dlViews.remove(id)
        }
        // 按任务表的顺序摆好
        tasks.forEachIndexed { i, t ->
            val v = dlViews.getOrPut(t.id) {
                val nv = layoutInflater.inflate(R.layout.item_dl_task, dlList, false)
                bindClicks(nv, t)
                nv
            }
            if (dlList.indexOfChild(v) != i) {
                dlList.removeView(v)
                dlList.addView(v, i)
            }
            bindTask(v, t)
        }
    }

    private fun bindClicks(v: View, t: DlTask) {
        v.findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            if (t.isRunning) {
                Dlg.confirm(this, "取消缓存", "停下「${t.epName}」的缓存？已经下到一半的部分会清掉，不会留下坏文件。") {
                    t.cancel()
                }
            } else {
                DlHub.remove(t)
                refreshDl()
            }
        }
    }

    private fun bindTask(v: View, t: DlTask) {
        val pic = v.findViewById<ImageView>(R.id.pic)
        val name = v.findViewById<TextView>(R.id.name)
        val state = v.findViewById<TextView>(R.id.state)
        val percent = v.findViewById<TextView>(R.id.percent)
        val bar = v.findViewById<ProgressBar>(R.id.bar)
        val detail = v.findViewById<TextView>(R.id.detail)
        val btn = v.findViewById<TextView>(R.id.btnCancel)

        Img.round(pic, 4f * resources.displayMetrics.density)
        Img.load(pic, t.pic)
        name.text = t.title.ifBlank { "未命名" }
        if (bar.progress != t.percent) bar.setProgress(t.percent, true)
        percent.text = "${t.percent}%"

        when (t.state) {
            DlState.RUN -> {
                state.text = if (t.stage.isNotEmpty()) "${t.stage} · ${t.epName}" else t.epName
                state.setTextColor(0xFFE62429.toInt())
                percent.setTextColor(0xFFF2F4F7.toInt())
                detail.text = speedLine(t)
                btn.text = "取消"
                btn.setTextColor(0xFF98A2B3.toInt())
            }
            DlState.OK -> {
                state.text = t.note
                state.setTextColor(0xFF3FB950.toInt())
                percent.setTextColor(0xFF3FB950.toInt())
                detail.text = if (t.epCount > 1) "挑的这几集都进下面的列表了" else "已经躺在下面的列表里了"
                btn.text = "关闭"
                btn.setTextColor(0xFF98A2B3.toInt())
            }
            DlState.CANCELED -> {
                state.text = t.note
                state.setTextColor(0xFF98A2B3.toInt())
                percent.text = "—"
                percent.setTextColor(0xFF98A2B3.toInt())
                detail.text = "没下完的部分已经清掉了"
                btn.text = "关闭"
                btn.setTextColor(0xFF98A2B3.toInt())
            }
            DlState.FAIL -> {
                state.text = t.note
                state.setTextColor(0xFFE62429.toInt())
                percent.text = "—"
                percent.setTextColor(0xFFE62429.toInt())
                detail.text = "地址可能过期了，回影片页再点一次缓存试试"
                btn.text = "关闭"
                btn.setTextColor(0xFF98A2B3.toInt())
            }
        }
    }

    private fun speedLine(t: DlTask): String = buildString {
        append("已下 ${fmtSize(t.doneBytes)}")
        if (t.totalBytes > 0) append(" / ${fmtSize(t.totalBytes)}")
        if (t.speed > 0) append(" · ${fmtSize(t.speed)}/s")
        if (t.epCount > 1) append(" · 已完成 ${t.okCount}/${t.epCount} 集")
    }

    // ───────────────────────── 已缓存列表 ─────────────────────────

    private fun refreshDone() {
        val items = DlStore.list(this).filter { File(it.path).exists() }
        ad.submit(items)
        summary.text = "共 ${items.size} 个 · 占用 ${fmtSize(items.sumOf { it.size })}"
        if (items.isEmpty() && DlHub.runningCount() == 0) {
            hint.text = "还没有缓存。\n影片详情页点「缓存选集」、播放页顶栏点「缓存」，" +
                    "挑好要下哪几集（能多选）就开始了。"
            hint.visibility = View.VISIBLE
        } else {
            hint.visibility = View.GONE
        }
    }

    private fun play(item: DlItem) {
        val uri = Uri.fromFile(File(item.path)).toString()
        val arr = arrayListOf(item.sub + "\u0001" + uri)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("title", item.title)
                .putStringArrayListExtra("eps", arr)
                .putExtra("index", 0)
                .putExtra("pic", item.pic)
        )
    }

    private fun del(item: DlItem) {
        Dlg.confirm(this, "删除缓存", "删掉「${item.title} ${item.sub}」？删了就没了，要重新下载。") {
            runCatching { File(item.path).delete() }
            DlStore.remove(this, item.url)
            refresh()
        }
    }
}

class DlAdapter(
    private val onPlay: (DlItem) -> Unit,
    private val onDel: (DlItem) -> Unit
) : RecyclerView.Adapter<DlAdapter.VH>() {

    private val data = ArrayList<DlItem>()

    fun submit(l: List<DlItem>) {
        data.clear()
        data.addAll(l)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val pic: ImageView = v.findViewById(R.id.pic)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val btnDel: TextView = v.findViewById(R.id.btnDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cached, parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        Img.round(holder.pic, holder.pic.resources.displayMetrics.density * 6f)
        Img.load(holder.pic, item.pic)
        holder.name.text = item.title
        holder.sub.text = "${item.sub}  ·  ${fmtSize(item.size)}  ·  ${fmtTime(item.time)}"
        holder.itemView.setOnClickListener { onPlay(item) }
        holder.btnDel.setOnClickListener { onDel(item) }
    }
}
