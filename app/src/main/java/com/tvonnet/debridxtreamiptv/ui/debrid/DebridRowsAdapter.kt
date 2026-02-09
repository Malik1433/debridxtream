package com.tvonnet.debridxtreamiptv.ui.debrid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tvonnet.debridxtreamiptv.R

/**
 * Adapter for vertical list of horizontal content rows
 * Similar to Netflix/Stremio layout
 */
class DebridRowsAdapter(
    private val onItemClick: (DebridContentItem) -> Unit,
    private val onRowLoadMore: (String) -> Unit
) : ListAdapter<DebridRow, DebridRowViewHolder>(DebridRowDiffCallback()) {
    
    fun updateRows(newRows: List<DebridRow>) {
        submitList(newRows)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebridRowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debrid_row, parent, false)
        return DebridRowViewHolder(view, onItemClick)
    }
    
    override fun onBindViewHolder(holder: DebridRowViewHolder, position: Int) {
        holder.bind(getItem(position), onRowLoadMore)
    }
}

class DebridRowDiffCallback : DiffUtil.ItemCallback<DebridRow>() {
    override fun areItemsTheSame(oldItem: DebridRow, newItem: DebridRow): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: DebridRow, newItem: DebridRow): Boolean {
        // We need to check if the content of the row has changed, including the items list
        return oldItem == newItem
    }
}

/**
 * ViewHolder for a single content row
 */
class DebridRowViewHolder(
    itemView: View,
    private val onItemClick: (DebridContentItem) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    
    private val tvRowTitle: TextView = itemView.findViewById(R.id.tv_row_title)
    private val rvRowItems: RecyclerView = itemView.findViewById(R.id.rv_row_items)
    private val itemsAdapter = DebridItemsAdapter(onItemClick) { currentLoadMoreCallback?.invoke() }
    private var currentLoadMoreCallback: (() -> Unit)? = null
    
    init {
        rvRowItems.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = itemsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            isNestedScrollingEnabled = false
        }
    }
    
    fun bind(row: DebridRow, onRowLoadMore: (String) -> Unit) {
        tvRowTitle.text = row.title
        currentLoadMoreCallback = { onRowLoadMore(row.id) }
        
        // Reset valid state for the sentinel
        itemsAdapter.setCanLoadMore(row.canLoadMore)
        itemsAdapter.submitList(row.items)
    }
}

/**
 * Adapter for horizontal list of content items within a row
 */

class DebridItemsAdapter(
    private val onItemClick: (DebridContentItem) -> Unit,
    private val onPrefetch: () -> Unit
) : ListAdapter<DebridContentItem, RecyclerView.ViewHolder>(DebridItemDiffCallback()) {
    
    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_SKELETON = 1
    }

    private var canLoadMore = false

    fun setCanLoadMore(canLoad: Boolean) {
        if (canLoadMore != canLoad) {
            canLoadMore = canLoad
            if (canLoad) {
                notifyItemInserted(itemCount) // Add sentinel
            } else {
                notifyItemRemoved(itemCount - 1) // Remove sentinel
            }
        }
    }

    override fun getItemCount(): Int {
        // Items + 1 sentinel if we can load more
        return super.getItemCount() + if (canLoadMore) 1 else 0
    }

    override fun getItemViewType(position: Int): Int {
        // Use standard list size for check
        if (canLoadMore && position == super.getItemCount()) {
            return VIEW_TYPE_SKELETON // This is now the "Sentinel"
        }
        return VIEW_TYPE_ITEM
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SKELETON) {
            val view = inflater.inflate(R.layout.item_debrid_loading, parent, false)
            DebridLoadingViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_debrid_content, parent, false)
            DebridItemViewHolder(view)
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DebridItemViewHolder) {
            // Safe check for index
            if (position < super.getItemCount()) {
                val item = getItem(position)
                holder.bind(item, onItemClick)
            }
        } else if (holder is DebridLoadingViewHolder) {
            holder.bind()
            // Trigger load when bound (and it will also be focusable)
            onPrefetch()
        }
    }
}



class DebridItemDiffCallback : DiffUtil.ItemCallback<DebridContentItem>() {
    override fun areItemsTheSame(oldItem: DebridContentItem, newItem: DebridContentItem): Boolean {
        // Determine unique identifier. Using URL as ID if ID is missing (fallback)
        val oldId = if (!oldItem.id.isNullOrEmpty()) oldItem.id else oldItem.streamUrl
        val newId = if (!newItem.id.isNullOrEmpty()) newItem.id else newItem.streamUrl
        return oldId == newId
    }

    override fun areContentsTheSame(oldItem: DebridContentItem, newItem: DebridContentItem): Boolean {
        return oldItem == newItem
    }
}

/**
 * ViewHolder for individual content item (poster card)
 */
class DebridItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    
    private val ivPoster: android.widget.ImageView = itemView.findViewById(R.id.iv_poster)
    private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
    private val tvYear: TextView = itemView.findViewById(R.id.tv_year)
    private val progressWatch: android.widget.ProgressBar =
        itemView.findViewById(R.id.progress_watch)
    private val tvProgress: TextView = itemView.findViewById(R.id.tv_progress)
    
    fun bind(item: DebridContentItem, onItemClick: (DebridContentItem) -> Unit) {
        tvTitle.text = item.title
        val subtitle = when {
            !item.episodeTitle.isNullOrBlank() -> item.episodeTitle
            !item.year.isNullOrBlank() -> item.year
            else -> null
        }
        tvYear.text = subtitle ?: ""
        tvYear.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        bindProgress(item)
        
        // Load poster with Glide
        Glide.with(itemView.context)
            .load(item.posterUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(320, 480)
            .thumbnail(0.1f)
            .dontAnimate()
            .placeholder(R.drawable.placeholder_poster)
            .error(R.drawable.placeholder_poster)
            .into(ivPoster)
        
        itemView.setOnClickListener {
            onItemClick(item)
        }
        
        // TV remote focus handling
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        
        // Cinematic Focus Animation
        itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .translationZ(16f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
                v.setBackgroundResource(R.drawable.card_border_gold_selector)
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(200)
                    .start()
                v.setBackgroundResource(0)
            }
        }
    }

    private fun bindProgress(item: DebridContentItem) {
        val resumePosition = item.resumePositionMs
        val duration = item.durationMs
        if (resumePosition == null || duration == null || duration <= 0L) {
            progressWatch.visibility = View.GONE
            tvProgress.visibility = View.GONE
            return
        }

        val percent = ((resumePosition.toDouble() / duration.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        progressWatch.progress = percent
        progressWatch.visibility = View.VISIBLE

        val leftMs = (duration - resumePosition).coerceAtLeast(0L)
        val leftLabel = formatTime(leftMs)
        val progressLabel = itemView.context.getString(R.string.progress_format, percent, leftLabel)
        val episodeLabel =
            if (item.seasonNumber != null && item.episodeNumber != null) {
                String.format("S%02dE%02d", item.seasonNumber, item.episodeNumber)
            } else {
                null
            }
        tvProgress.text = if (episodeLabel != null) {
            "$episodeLabel • $progressLabel"
        } else {
            progressLabel
        }
        tvProgress.visibility = View.VISIBLE
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}

/**
 * ViewHolder for Loading Spinner card
 */
class DebridLoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    init {
        // IMPORTANT: The Sentinel MUST be focusable.
        // It acts as the "End of List" anchor.
        // When focused, it triggers loading.
        // When new items arrive, they are inserted BEFORE it.
        // The focus stays on this item (pushed to the right) or moves naturally.
        // This prevents "Jumping Up" because there is always a valid focus target.
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
    }
    
    fun bind() {
        // Nothing to bind, just a spinner
    }
}

/**
 * Adapter specifically for the loading spinner footer
 */


