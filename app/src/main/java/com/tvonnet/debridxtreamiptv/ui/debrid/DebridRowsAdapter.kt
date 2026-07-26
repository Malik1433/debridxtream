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
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Adapter for vertical list of horizontal content rows
 * Similar to Netflix/Stremio layout
 */
class DebridRowsAdapter(
    private val onItemClick: (DebridContentItem) -> Unit,
    private val onItemFocused: (DebridContentItem) -> Unit,
    private val onRowLoadMore: (String) -> Unit,
    private val onLeftBoundary: () -> Unit // Component 2: Return to sidebar callback
) : ListAdapter<DebridRow, DebridRowViewHolder>(DebridRowDiffCallback()) {
    
    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    
    fun updateRows(newRows: List<DebridRow>) {
        submitList(newRows)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebridRowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debrid_row, parent, false)
        return DebridRowViewHolder(view, onItemClick, onItemFocused, onLeftBoundary)
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
    private val onItemClick: (DebridContentItem) -> Unit,
    private val onItemFocused: (DebridContentItem) -> Unit,
    private val onLeftBoundary: () -> Unit
) : RecyclerView.ViewHolder(itemView) {
    
    private val tvRowTitle: TextView = itemView.findViewById(R.id.tv_row_title)
    private val rvRowItems: RecyclerView = itemView.findViewById(R.id.rv_row_items)
    private val itemsAdapter = DebridItemsAdapter(onItemClick, onItemFocused, onLeftBoundary) { currentLoadMoreCallback?.invoke() }
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
        itemsAdapter.rowId = row.id
        itemsAdapter.rowTitle = row.title
        
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
    private val onItemFocused: (DebridContentItem) -> Unit,
    private val onLeftBoundary: () -> Unit,
    private val onPrefetch: () -> Unit
) : ListAdapter<DebridContentItem, RecyclerView.ViewHolder>(DebridItemDiffCallback()) {
    
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        if (canLoadMore && position == super.getItemCount()) {
            return -1L // Stable ID for the Sentinel/Loading card
        }
        return getItem(position).id.hashCode().toLong()
    }

    
    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_SKELETON = 1
        private const val VIEW_TYPE_SEE_ALL = 2
    }

    private var canLoadMore = false
    var rowId: String = ""
    var rowTitle: String = ""

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
        if (canLoadMore && position == super.getItemCount()) {
            return VIEW_TYPE_SKELETON
        }
        val item = getItem(position)
        if (item.id.startsWith("SEE_ALL_")) {
            return VIEW_TYPE_SEE_ALL
        }
        return VIEW_TYPE_ITEM
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SKELETON -> {
                val view = inflater.inflate(R.layout.item_debrid_loading, parent, false)
                DebridLoadingViewHolder(view)
            }
            VIEW_TYPE_SEE_ALL -> {
                val view = inflater.inflate(R.layout.item_debrid_see_all, parent, false)
                DebridSeeAllViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_debrid_content, parent, false)
                DebridItemViewHolder(view, onLeftBoundary)
            }
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DebridItemViewHolder) {
            if (position < super.getItemCount()) {
                val item = getItem(position)
                holder.bind(item, onItemClick, onItemFocused)
            }
        } else if (holder is DebridSeeAllViewHolder) {
            holder.bind(rowId, rowTitle)
        } else if (holder is DebridLoadingViewHolder) {
            holder.bind()
            onPrefetch()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is DebridItemViewHolder) {
            holder.clearAnimations()
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

class DebridItemViewHolder(
    itemView: View,
    private val onLeftBoundary: () -> Unit
) : RecyclerView.ViewHolder(itemView) {
    
    private val ivPoster: android.widget.ImageView = itemView.findViewById(R.id.iv_poster)
    private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
    private val tvYear: TextView = itemView.findViewById(R.id.tv_year)
    private val progressWatch: android.widget.ProgressBar =
        itemView.findViewById(R.id.progress_watch)
    private val glowFocus: View? = itemView.findViewById(R.id.glow_focus)
    private val tvRatingBadge: TextView? = itemView.findViewById(R.id.tv_rating_badge)
    
    private var pulseAnimator: ValueAnimator? = null
    
    fun bind(
        item: DebridContentItem,
        onItemClick: (DebridContentItem) -> Unit,
        onItemFocused: (DebridContentItem) -> Unit
    ) {
        tvTitle.text = item.title
        
        // Consolidate metadata (S01E01 / Year)
        val episodeLabel = if (item.seasonNumber != null && item.episodeNumber != null) {
            String.format(java.util.Locale.US, "S%02dE%02d", item.seasonNumber, item.episodeNumber)
        } else null

        val subtitle = when {
            episodeLabel != null -> episodeLabel
            !item.year.isNullOrBlank() -> item.year
            else -> null
        }
        tvYear.text = subtitle ?: ""
        tvYear.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        bindProgress(item)
        
        // Rating Badge
        if (!item.rating.isNullOrBlank() && item.rating != "0.0" && item.rating != "0") {
            tvRatingBadge?.text = "⭐ ${item.rating}"
            tvRatingBadge?.visibility = View.VISIBLE
        } else {
            tvRatingBadge?.visibility = View.GONE
        }

        // Aspect ratio and image URL for Continue Watching
        val cardPoster: View? = itemView.findViewById(R.id.card_poster)
        var overrideWidth = 320
        var overrideHeight = 480
        val imageUrl = if (item.isContinueWatching) {
            item.backdropUrl ?: item.posterUrl
        } else {
            item.posterUrl
        }

        if (cardPoster != null) {
            val params = cardPoster.layoutParams
            if (item.isContinueWatching) {
                // 16:9 Aspect Ratio (280dp x 158dp)
                params.width = (280 * itemView.context.resources.displayMetrics.density).toInt()
                params.height = (158 * itemView.context.resources.displayMetrics.density).toInt()
                overrideWidth = 560
                overrideHeight = 315
            } else {
                // Standard 2:3 Aspect Ratio (140dp x 210dp)
                params.width = (140 * itemView.context.resources.displayMetrics.density).toInt()
                params.height = (210 * itemView.context.resources.displayMetrics.density).toInt()
                overrideWidth = 320
                overrideHeight = 480
            }
            cardPoster.layoutParams = params
        }
        
        // Load poster with Glide
        Glide.with(itemView.context)
            .load(imageUrl)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565) // IMG-1: posters no alpha
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(overrideWidth, overrideHeight)
            // IMG-5: dropped .thumbnail(0.1f) — doubled the decode on disk-cache hits.
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
        
        // Cinematic Focus Animation (Sync with Movies/Series)
        itemView.alpha = 0.8f
        
        itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .alpha(1f)
                    .setDuration(250)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
                v.z = 20f // Lift up for glow visibility
                startPulsingGlow()
                tvTitle.isSelected = true
                onItemFocused(item)
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(0.8f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                v.z = 0f
                stopPulsingGlow()
                tvTitle.isSelected = false
            }
        }

        // Component 2: Hardened Left Boundary Support
        itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                if (bindingAdapterPosition == 0) {
                    onLeftBoundary()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun startPulsingGlow() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.0f).apply {
            duration = 1000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                glowFocus?.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopPulsingGlow() {
        pulseAnimator?.cancel()
        glowFocus?.alpha = 0f
    }

    fun clearAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        itemView.animate().cancel()
        glowFocus?.animate()?.cancel()
        
        // Reset to default unfocused state
        itemView.scaleX = 1.0f
        itemView.scaleY = 1.0f
        itemView.alpha = 0.8f
        itemView.z = 0f
        glowFocus?.alpha = 0f
        tvTitle.isSelected = false
    }

    private fun bindProgress(item: DebridContentItem) {
        val resumePosition = item.resumePositionMs
        val duration = item.durationMs
        if (resumePosition == null || duration == null || duration <= 0L) {
            progressWatch.visibility = View.GONE
            return
        }

        val percent = ((resumePosition.toDouble() / duration.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        progressWatch.progress = percent
        progressWatch.visibility = View.VISIBLE
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
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



class DebridSeeAllViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    fun bind(rowId: String, rowTitle: String) {
        itemView.setOnClickListener {
            val intent = android.content.Intent(itemView.context, com.tvonnet.debridxtreamiptv.ui.debrid.seeall.DebridSeeAllActivity::class.java).apply {
                putExtra("EXTRA_ROW_ID", rowId)
                putExtra("EXTRA_ROW_TITLE", rowTitle)
            }
            itemView.context.startActivity(intent)
        }
    }
}
