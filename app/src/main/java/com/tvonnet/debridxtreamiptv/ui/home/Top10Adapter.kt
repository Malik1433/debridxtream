package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.tvonnet.debridxtreamiptv.R
import android.widget.Toast
import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.tvonnet.debridxtreamiptv.data.model.FeaturedItem
import com.tvonnet.debridxtreamiptv.util.FocusEffects

class Top10Adapter(
    private var items: List<FeaturedItem>,
    private val onItemFocused: (Int, FeaturedItem) -> Unit,
    private val onItemClick: (FeaturedItem) -> Unit
) : RecyclerView.Adapter<Top10Adapter.Top10ViewHolder>() {

    fun updateItems(newItems: List<FeaturedItem>) {
        val oldSize = items.size
        val newSize = newItems.size
        items = newItems
        
        if (oldSize == newSize) {
            notifyItemRangeChanged(0, newSize)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Top10ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_10_card, parent, false)
        return Top10ViewHolder(view)
    }

    override fun onBindViewHolder(holder: Top10ViewHolder, position: Int) {
        holder.bind(items[position], position, onItemFocused, onItemClick)
    }

    override fun getItemCount() = items.size

    class Top10ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.iv_poster)
        private val tvRank: TextView = itemView.findViewById(R.id.tv_rank_number)

        fun bind(item: FeaturedItem, position: Int, onFocus: (Int, FeaturedItem) -> Unit, onClick: (FeaturedItem) -> Unit) {
            tvRank.text = (position + 1).toString()

            val cornerRadius = (12 * itemView.context.resources.displayMetrics.density).toInt()
            
            Glide.with(itemView.context)
                .load(item.posterUrl)
                .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(cornerRadius)))
                .placeholder(R.drawable.bg_card_focus_cyan) 
                .error(R.drawable.bg_card_focus_cyan)
                .into(ivPoster)

            itemView.setOnClickListener { onClick(item) }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                FocusEffects.applyCinematicFocus(view, hasFocus, scale = 1.15f)
                FocusEffects.apply3DTilt(view, hasFocus)
                
                if (hasFocus) {
                    view.z = 20f // Topmost layering for no-clipping scale-up
                    onFocus(position, item)
                } else {
                    view.z = 0f
                }
            }
        }
    }
}
