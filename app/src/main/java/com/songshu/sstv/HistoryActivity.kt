package com.songshu.sstv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.HisItem
import com.songshu.sstv.data.HistoryStore
import com.songshu.sstv.data.fmtAgo
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.Img

/** 播放历史：点一条就直接接着上次的地方看 */
class HistoryActivity : ThemedActivity() {

    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var hint: TextView

    private val ad = HisAdapter(
        onPlay = { resume(it) },
        onDel = { del(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        list = findViewById(R.id.list)
        summary = findViewById(R.id.summary)
        hint = findViewById(R.id.hint)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ad

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (HistoryStore.count(this) == 0) {
                Dlg.tip(this, "清空历史", "现在还没有记录。")
                return@setOnClickListener
            }
            Dlg.confirm(this, "清空播放历史", "所有播放记录都清掉。缓存好的影片和片源设置不受影响。") {
                HistoryStore.clear(this)
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = HistoryStore.list(this)
        ad.submit(items)
        summary.text = "共 ${items.size} 条 · 点一条就接着上次看"
        if (items.isEmpty()) {
            hint.text = "还没有播放记录。\n看过的片子会自动记下来，下次点开接着看。"
            hint.visibility = View.VISIBLE
        } else {
            hint.visibility = View.GONE
        }
    }

    /** 直接进播放页，并带上上次的进度 */
    private fun resume(h: HisItem) {
        val eps = if (h.eps.isNotEmpty()) ArrayList(h.eps)
        else arrayListOf(h.ep + "\u0001" + h.url)
        var idx = eps.indexOfFirst { it.substringAfter("\u0001") == h.url }
        if (idx < 0) idx = eps.indexOfFirst { it.substringBefore("\u0001") == h.ep }
        if (idx < 0) idx = 0
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("title", h.title)
                .putStringArrayListExtra("eps", eps)
                .putExtra("index", idx)
                .putExtra("pic", h.pic)
                .putExtra("api", h.api)
                .putExtra("srcName", h.srcName)
                .putExtra("vid", h.vid)
                .putExtra("seek", if (h.pos > 20_000) h.pos else 0L)
        )
    }

    private fun del(h: HisItem) {
        Dlg.confirm(this, "删掉这条记录", "删掉「${h.title} ${h.ep}」的播放记录？下载好的文件不会被删。") {
            HistoryStore.remove(this, h.key, h.api)
            refresh()
        }
    }
}

class HisAdapter(
    private val onPlay: (HisItem) -> Unit,
    private val onDel: (HisItem) -> Unit
) : RecyclerView.Adapter<HisAdapter.VH>() {

    private val data = ArrayList<HisItem>()

    fun submit(l: List<HisItem>) {
        if (data == l) return
        data.clear()
        data.addAll(l)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val pic: ImageView = v.findViewById(R.id.pic)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val seen: TextView = v.findViewById(R.id.seen)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val btnDel: TextView = v.findViewById(R.id.btnDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val h = data[position]
        Img.round(holder.pic, holder.pic.resources.displayMetrics.density * 6f)
        Img.load(holder.pic, h.pic)
        holder.name.text = h.title.ifBlank { "未命名" }
        holder.sub.text = "${h.ep} · ${fmtAgo(h.time)}"
        holder.seen.text = if (h.dur > 0)
            "看到 ${fmtDur(h.pos)} / ${fmtDur(h.dur)}（${(h.ratio * 100).toInt()}%）"
        else "看到 ${fmtDur(h.pos)}"
        holder.bar.setProgress((h.ratio * 100).toInt(), false)
        holder.itemView.setOnClickListener { onPlay(h) }
        holder.btnDel.setOnClickListener { onDel(h) }
    }
}
