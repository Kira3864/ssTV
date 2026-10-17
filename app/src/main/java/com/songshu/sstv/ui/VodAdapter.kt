package com.songshu.sstv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import com.songshu.sstv.R
import com.songshu.sstv.ThemeStore
import com.songshu.sstv.data.Vod

/** 影片网格适配器 */
class VodAdapter(
    private var picHeight: Int,
    private val onClick: (Vod) -> Unit
) : RecyclerView.Adapter<VodAdapter.VH>() {

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Vod>() {
        override fun areItemsTheSame(old: Vod, new: Vod) =
            old.id == new.id && old.sourceApi == new.sourceApi
        override fun areContentsTheSame(old: Vod, new: Vod) = old == new
    })
    private val items get() = differ.currentList
    private var submitted = emptyList<Vod>()
    val dataCount get() = submitted.size
    var listMode = false
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    fun submit(list: List<Vod>, append: Boolean) {
        submitted = ((if (append) submitted else emptyList()) + list)
            .distinctBy { it.sourceApi to it.id }
        differ.submitList(submitted)
    }

    fun clear() {
        submitted = emptyList()
        differ.submitList(submitted)
    }

    fun setPicHeight(h: Int) {
        if (h > 0 && h != picHeight) {
            picHeight = h
            notifyDataSetChanged()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val pic: ImageView = v.findViewById(R.id.pic)
        val name: TextView = v.findViewById(R.id.name)
        val remark: TextView = v.findViewById(R.id.remark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.item_vod_list else R.layout.item_vod, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size
    override fun getItemViewType(position: Int) = if (listMode) 1 else 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        val lp = holder.pic.layoutParams
        if (!listMode && lp is LinearLayout.LayoutParams && lp.height != picHeight) {
            lp.height = picHeight
            holder.pic.layoutParams = lp
        }
        Img.round(holder.pic, holder.pic.resources.displayMetrics.density * ThemeStore.current(holder.pic.context).radius)
        Img.load(holder.pic, item.pic)

        holder.name.text = item.name
        holder.remark.text = buildString {
            append(item.remark.ifBlank { item.typeName })
            if (listMode && item.meta.isNotBlank()) append("\n${item.meta}")
            if (item.sourceName.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(item.sourceName)
            }
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.contentDescription = "${item.name}，${item.remark.ifBlank { item.typeName }}"
        Motion.interactive(holder.itemView)
    }

    override fun onViewRecycled(holder: VH) {
        Img.load(holder.pic, null)
        super.onViewRecycled(holder)
    }
}
