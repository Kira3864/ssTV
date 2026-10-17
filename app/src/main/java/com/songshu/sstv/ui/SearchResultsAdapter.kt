package com.songshu.sstv.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.R
import com.songshu.sstv.ThemeStore
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.Vod
import com.songshu.sstv.data.VideoOrder
import com.songshu.sstv.themeColor

data class SearchGroup(val source: Source, val latencyMs: Long, val items: List<Vod>, val heading: String = "")

/** Source boundaries are kept even when two providers return the same title. */
class SearchResultsAdapter(private val open: (Vod) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    data class Row(val key: String, val group: SearchGroup? = null, val vod: Vod? = null)
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    })
    var pictureHeight = 300
        set(value) { if (field != value) { field = value; notifyItemRangeChanged(0, itemCount, "size") } }
    var listMode = true
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var query = ""
    var descending = false
    var ranked = false

    companion object {
        fun rows(groups: List<SearchGroup>): List<Row> = groups
            .filter { it.items.isNotEmpty() }
            .sortedWith(compareBy<SearchGroup> { it.latencyMs }.thenBy { it.source.api })
            .flatMap { group ->
                listOf(Row("source:${group.source.api}", group = group.copy(items = emptyList()))) +
                    group.items.distinctBy { it.id }.map { Row("vod:${group.source.api}:${it.id}", vod = it) }
            }
    }
    fun submit(groups: List<SearchGroup>) = differ.submitList(rows(groups.map { if (ranked) it else it.copy(items = VideoOrder.sort(it.items, query, descending)) }))
    fun isHeader(position: Int) = differ.currentList.getOrNull(position)?.group != null
    override fun getItemCount() = differ.currentList.size
    override fun getItemViewType(position: Int) = if (isHeader(position)) 0 else if (listMode) 2 else 1
    class Header(val label: TextView) : RecyclerView.ViewHolder(label)
    class Poster(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pic)
        val name: TextView = view.findViewById(R.id.name)
        val remark: TextView = view.findViewById(R.id.remark)
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        if (type == 0) return Header(TextView(parent.context).apply {
            val d = resources.displayMetrics.density
            gravity = Gravity.CENTER_VERTICAL
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.themeColor(R.attr.appText))
            setPadding((16*d).toInt(), (16*d).toInt(), (16*d).toInt(), (10*d).toInt())
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        return Poster(LayoutInflater.from(parent.context).inflate(if (type == 2) R.layout.item_vod_list else R.layout.item_vod, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = differ.currentList[position]
        if (holder is Header) {
            holder.label.text = row.group!!.heading.ifBlank { "${row.group.source.name}  ·  ${row.group.latencyMs} ms" }
        } else if (holder is Poster) {
            val vod = row.vod!!
            if (!listMode) holder.image.layoutParams = holder.image.layoutParams.apply { height = pictureHeight }
            Img.round(holder.image, ThemeStore.current(holder.image.context).radius * holder.image.resources.displayMetrics.density)
            Img.load(holder.image, vod.pic)
            holder.name.text = if (ranked) "${position}. ${vod.name}" else vod.name
            holder.remark.text = listOf(if (listMode) vod.episodeStatus else vod.compactStatus, vod.meta).filter { it.isNotBlank() }.distinct().joinToString(if (listMode) "\n" else " · ")
            holder.itemView.setOnClickListener { open(vod) }
            Motion.interactive(holder.itemView)
        }
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is Poster) Img.load(holder.image, null)
        super.onViewRecycled(holder)
    }
}
