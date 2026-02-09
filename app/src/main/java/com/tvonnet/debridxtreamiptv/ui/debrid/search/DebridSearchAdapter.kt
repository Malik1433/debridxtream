package com.tvonnet.debridxtreamiptv.ui.debrid.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem

class DebridSearchAdapter(
    private var items: List<DebridContentItem> = emptyList(),
    private val onItemClick: (DebridContentItem) -> Unit
) : RecyclerView.Adapter<DebridSearchViewHolder>() {

    fun updateItems(newItems: List<DebridContentItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebridSearchViewHolder {
        // Reuse the item_debrid_content layout which works well for grids too (poster card)
        // Or better, item_poster_cinematic if available and compatible.
        // Let's stick to item_debrid_content which we know works with DebridContentItem binding
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debrid_content, parent, false)
        return DebridSearchViewHolder(view)
    }

    override fun onBindViewHolder(holder: DebridSearchViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size
}

class DebridSearchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val ivPoster: ImageView = itemView.findViewById(R.id.iv_poster)
    private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
    private val tvYear: TextView = itemView.findViewById(R.id.tv_year)

    fun bind(item: DebridContentItem, onItemClick: (DebridContentItem) -> Unit) {
        tvTitle.text = item.title
        tvYear.text = item.year ?: ""
        tvYear.visibility = if (item.year.isNullOrBlank()) View.GONE else View.VISIBLE

        Glide.with(itemView.context)
            .load(item.posterUrl)
            .placeholder(R.drawable.placeholder_poster) // Assuming this drawable exists as used in DebridItemsAdapter
            .error(R.drawable.placeholder_poster)
            .into(ivPoster)

        itemView.setOnClickListener { onItemClick(item) }
        
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
    }
}
