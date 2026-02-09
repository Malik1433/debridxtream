package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.NewAddedItem

/**
 * Adapter for displaying recently added content on home screen
 * Supports both VOD (Movies) and Series content
 */
class NewAddedAdapter(
    private var items: List<NewAddedItem>,
    private val onItemClick: (NewAddedItem) -> Unit
) : RecyclerView.Adapter<NewAddedAdapter.NewAddedViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NewAddedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_new_added_card, parent, false)
        return NewAddedViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: NewAddedViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<NewAddedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class NewAddedViewHolder(
        itemView: View,
        private val onItemClick: (NewAddedItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivPoster: ImageView = itemView.findViewById(R.id.iv_poster)
        private val tvNewBadge: TextView = itemView.findViewById(R.id.tv_new_badge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvMetadata: TextView = itemView.findViewById(R.id.tv_metadata)

        fun bind(item: NewAddedItem) {
            // Set title
            tvTitle.text = item.title

            // Set metadata (year and quality)
            val metadata = buildString {
                item.year?.let { append(it) }
                if (item.year != null && item.quality != null) {
                    append(" | ")
                }
                item.quality?.let { append(it) }
            }
            tvMetadata.text = metadata.ifEmpty { "New Content" }

            // Load poster image
            if (!item.posterUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(item.posterUrl)
                    .placeholder(R.drawable.tv_card_placeholder)
                    .error(R.drawable.tv_card_placeholder)
                    .centerCrop()
                    .into(ivPoster)
            } else {
                ivPoster.setImageResource(R.drawable.tv_card_placeholder)
            }

            // NEW badge is always visible for this adapter
            tvNewBadge.visibility = View.VISIBLE

            // Click listener
            itemView.setOnClickListener {
                onItemClick(item)
            }

            // Focus listener for TV remote - More prominent animation
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Scale up animation on focus (bigger for visibility)
                    itemView.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .setDuration(250)
                        .start()
                } else {
                    // Scale back to normal
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200)
                        .start()
                }
            }
        }
    }
}

