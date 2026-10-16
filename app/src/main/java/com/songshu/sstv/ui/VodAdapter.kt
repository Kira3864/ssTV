package com.songshu.sstv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.R
import com.songshu.sstv.data.Vod

/** 影片网格适配器 */
class VodAdapter(
    private var picHeight: Int,
    private val onClick: (Vod) -> Unit
) : RecyclerView.Adapter<VodAdapter.VH>() {

    private val items = ArrayList<Vod>()

    fun submit(list: List<Vod>, append: Boolean) {
        if (!append) items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
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
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        val lp = holder.pic.layoutParams
        if (lp is LinearLayout.LayoutParams && lp.height != picHeight) {
            lp.height = picHeight
            holder.pic.layoutParams = lp
        }
        Img.round(holder.pic, holder.pic.resources.displayMetrics.density * 6f)
        Img.load(holder.pic, item.pic)

        holder.name.text = item.name
        holder.remark.text = buildString {
            append(item.remark.ifBlank { item.typeName })
            if (item.sourceName.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(item.sourceName)
            }
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
