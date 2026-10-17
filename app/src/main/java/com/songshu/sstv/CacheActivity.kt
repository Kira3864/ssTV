package com.songshu.sstv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.cache.*
import com.songshu.sstv.ui.Dlg
import com.songshu.sstv.ui.Img
import java.io.File

class CacheActivity : ThemedActivity() {
    private lateinit var list: RecyclerView
    private val folderKey get() = intent.getStringExtra("folder")
    private val ad = DlAdapter({ play(it) }, { del(it) }, { task ->
        if (task.isRunning) DlHub.pause(task) else DlHub.resume(this,task)
        refresh()
    })
    private val ticker=object: Runnable {
        override fun run() { refresh(); list.postDelayed(this,700) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cache)
        list=findViewById(R.id.list); list.layoutManager=LinearLayoutManager(this); list.adapter=ad
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.dlBox).visibility=View.GONE
    }
    override fun onResume() { super.onResume(); refresh(); list.postDelayed(ticker,700) }
    override fun onPause() { super.onPause(); list.removeCallbacks(ticker) }
    private fun refresh() {
        val all=DlStore.list(this).filter { File(it.path).isFile }
        val pending=DlHub.list().filter { t -> all.none { it.api==t.api && it.url==t.eps.first().second } }
        val items=if(folderKey==null) all else all.filter { it.mediaKey==folderKey }
        val tasks=if(folderKey==null) pending else pending.filter { it.mediaKey==folderKey }
        val rows=CacheGroups.rows(items,folderKey==null,tasks)
        ad.submit(rows)
        findViewById<TextView>(R.id.cacheTitle).text=if(folderKey==null) getString(R.string.cache_title)
            else items.firstOrNull()?.title ?: tasks.firstOrNull()?.title ?: "剧集缓存"
        findViewById<TextView>(R.id.summary).apply {
            text=listOfNotNull(items.takeIf { it.isNotEmpty() }?.let { "已缓存 ${it.size} 集 · ${fmtSize(it.sumOf { v -> v.size })}" },
                tasks.takeIf { it.isNotEmpty() }?.let { "下载任务 ${it.size}" }).joinToString(" · ")
            visibility=if(text.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.hint).visibility=if(rows.isEmpty()) View.VISIBLE else View.GONE
    }
    private fun play(entry: CacheEntry) {
        if(entry.folder) { startActivity(Intent(this,CacheActivity::class.java).putExtra("folder",entry.mediaKey)); return }
        val item=entry.items.firstOrNull() ?: return
        val group=DlStore.list(this).filter { it.mediaKey==item.mediaKey && File(it.path).isFile }
            .sortedBy { com.songshu.sstv.data.VideoOrder.natural(it.sub) }
        val eps=group.map { it.sub+"\u0001"+Uri.fromFile(File(it.path)) }
        startActivity(com.songshu.sstv.data.MediaRef(item.vid,item.title,item.pic,item.api,item.sourceLabel,eps,
            group.indexOfFirst { it.path==item.path }.coerceAtLeast(0),"离线缓存").playIntent(this))
    }
    private fun del(entry: CacheEntry) {
        Dlg.confirm(this,"删除缓存",if(entry.folder) "删除「${entry.title}」的全部缓存及下载任务？" else "删除此集缓存及下载进度？") {
            entry.tasks.forEach { DlHub.remove(it) }
            entry.items.forEach { item ->
                DlHub.list().filter { it.api==item.api && it.eps.first().second==item.url }.forEach { DlHub.remove(it) }
                DlStore.remove(this,item.url,item.api)
                if(DlStore.list(this).none { it.path==item.path }) File(item.path).delete()
            }
            refresh()
        }
    }
}

class DlAdapter(
    private val onPlay: (CacheEntry)->Unit,
    private val onDel: (CacheEntry)->Unit,
    private val onToggle: (DlTask)->Unit = {}
): RecyclerView.Adapter<DlAdapter.VH>() {
    private var data: List<CacheEntry> = emptyList()
    fun submit(rows: List<CacheEntry>) {
        if(data==rows) { data.indices.filter { data[it].tasks.isNotEmpty() }.forEach { notifyItemChanged(it,"progress") }; return }
        data=rows; notifyDataSetChanged()
    }
    class VH(v: View): RecyclerView.ViewHolder(v)
    override fun getItemCount()=data.size
    override fun getItemViewType(position: Int)=if(!data[position].folder && data[position].tasks.isNotEmpty()) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)=VH(LayoutInflater.from(parent.context).inflate(
        if(viewType==1) R.layout.item_dl_task else R.layout.item_cached,parent,false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val e=data[position]; val v=holder.itemView
        val pic=v.findViewById<ImageView>(R.id.pic)
        Img.round(pic,6*pic.resources.displayMetrics.density); Img.load(pic,e.pic)
        v.findViewById<TextView>(R.id.name).text=if(e.folder) "${e.title}  ›" else if(e.tasks.isNotEmpty()) (if(e.tasks.first().series) e.tasks.first().epName else e.title) else e.items.first().sub
        if(getItemViewType(position)==0) {
            v.findViewById<TextView>(R.id.name).text=if(e.folder) "${e.title}  ›" else if(e.items.first().series) e.items.first().sub else e.title
            v.findViewById<TextView>(R.id.sub).text=listOfNotNull(
                if(e.folder) "${e.items.size} 已缓存 · ${e.tasks.size} 待完成" else null,
                e.sourceLabel,fmtSize(e.items.sumOf { it.size }+e.tasks.sumOf { it.doneBytes })).joinToString(" · ")
            v.findViewById<View>(R.id.btnDel).setOnClickListener { onDel(e) }
            v.setOnClickListener { onPlay(e) }
        } else {
            val t=e.tasks.first()
            v.findViewById<TextView>(R.id.state).text=when(t.state) {
                DlState.QUEUED -> "等待下载"
                DlState.RUN -> "正在下载"
                DlState.PAUSED -> "已暂停"
                DlState.FAIL -> "下载中断"
                DlState.OK -> "缓存完成"
                DlState.CANCELED -> "已取消"
            }+" · "+e.sourceLabel
            v.findViewById<TextView>(R.id.percent).text="${t.percent}%"
            v.findViewById<ProgressBar>(R.id.bar).progress=t.percent
            v.findViewById<TextView>(R.id.detail).text=fmtSize(t.doneBytes)+(if(t.totalBytes>0) " / ${fmtSize(t.totalBytes)}" else "")+
                (if(t.speed>0) " · ${fmtSize(t.speed)}/s" else "")
            v.findViewById<TextView>(R.id.btnCancel).apply {
                text=if(t.isRunning) "暂停" else "继续"
                isEnabled=!t.worker.get() || t.isRunning
                setOnClickListener { onToggle(t) }
            }
            v.findViewById<View>(R.id.btnDel).setOnClickListener { onDel(e) }
        }
    }
}

data class CacheEntry(val items: List<DlItem>, val folder: Boolean, val tasks: List<DlTask> = emptyList()) {
    val mediaKey get()=items.firstOrNull()?.mediaKey ?: tasks.first().mediaKey
    val title get()=items.firstOrNull()?.title ?: tasks.first().title
    val pic get()=items.firstOrNull()?.pic ?: tasks.first().pic
    val sourceLabel get()=items.firstOrNull()?.sourceLabel ?: tasks.first().sourceName.ifBlank { tasks.first().api.takeIf { it.isNotBlank() }?.let { runCatching { java.net.URL(it).host }.getOrDefault(it) } ?: "来源未记录" }
}
object CacheGroups {
    fun rows(items: List<DlItem>, folders: Boolean, tasks: List<DlTask> = emptyList()): List<CacheEntry> {
        val leaves=items.map { CacheEntry(listOf(it),false) }+tasks.map { CacheEntry(emptyList(),false,listOf(it)) }
        if(!folders) return leaves.sortedBy { com.songshu.sstv.data.VideoOrder.natural(it.items.firstOrNull()?.sub ?: it.tasks.first().epName) }
        return leaves.groupBy { it.mediaKey }.values.flatMap { group ->
            if(group.size>1 || group.any { it.items.any { v -> v.series } || it.tasks.any { t -> t.series } })
                listOf(CacheEntry(group.flatMap { it.items },true,group.flatMap { it.tasks })) else group
        }
    }
}
