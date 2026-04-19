package com.tvonnet.debridxtreamiptv.ui.debrid.discover

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.view.animation.Animation
import android.view.animation.Interpolator

/**
 * RecyclerView adapter for the Discover grid.
 *
 * Displays content cards in a fixed-size grid. At the end of the list,
 * a "Load More" sentinel triggers [onLoadMore] for infinite scroll.
 *
 * @param onItemClick  Called when a content card is clicked.
 * @param onItemFocused  Called when a content card gains focus (for backdrop updates).
 * @param onLoadMore  Called when the last visible item is the sentinel.
 */
class DebridDiscoverAdapter(
    private val onItemClick: (CatalogItem) -> Unit,
    private val onItemFocused: (CatalogItem) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_CONTENT = 0
        private const val VIEW_TYPE_LOADING = 1
    }

    private val items = mutableListOf<CatalogItem>()
    private var showLoadingFooter = false

    init {
        setHasStableIds(true)
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Replaces the current item list with new items.
     *
     * @param newItems  The full new list of catalog items.
     * @param canLoadMore  Whether to show the loading sentinel at the end.
     */
    fun submitItems(newItems: List<CatalogItem>, canLoadMore: Boolean) {
        val oldSize = items.size
        val newSize = newItems.size
        
        items.clear()
        items.addAll(newItems)
        
        val wasShowingFooter = showLoadingFooter
        showLoadingFooter = canLoadMore
        
        if (oldSize == 0) {
            notifyDataSetChanged() // Initial load
        } else {
            // Simplified: if size is larger, notify additions. If smaller, notify removals.
            // For Discover grid, usually we append.
            if (newSize > oldSize) {
                notifyItemRangeInserted(oldSize, newSize - oldSize)
            } else if (newSize < oldSize) {
                notifyItemRangeRemoved(newSize, oldSize - newSize)
            } else {
                 notifyItemRangeChanged(0, newSize)
            }
            
            // Handle footer toggle
            if (wasShowingFooter != canLoadMore) {
                if (canLoadMore) notifyItemInserted(newSize)
                else notifyItemRemoved(newSize)
            }
        }
    }

    // ── Adapter overrides ────────────────────────────────────────────────────────

    override fun getItemId(position: Int): Long {
        if (position < items.size) {
            return items[position].id.hashCode().toLong()
        }
        return RecyclerView.NO_ID
    }

    override fun getItemCount(): Int = items.size + if (showLoadingFooter) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position < items.size) VIEW_TYPE_CONTENT else VIEW_TYPE_LOADING

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_CONTENT) {
            val view = inflater.inflate(R.layout.item_debrid_content, parent, false)
            ContentViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_debrid_loading, parent, false)
            LoadingViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ContentViewHolder) {
            holder.bind(items[position])
        } else if (holder is LoadingViewHolder) {
            // Trigger load more when sentinel is bound
            onLoadMore()
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────────────────

    inner class ContentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivPoster: ImageView = view.findViewById(R.id.iv_poster)
        private val tvTitle: TextView = view.findViewById(R.id.tv_title)
        private val tvYear: TextView = view.findViewById(R.id.tv_year)
        private val glowView: View = view.findViewById(R.id.glow_focus)
        private var pulseAnimator: ValueAnimator? = null

        /**
         * Binds a [CatalogItem] to this ViewHolder, loading the poster and setting up
         * click/focus listeners.
         */
        fun bind(item: CatalogItem) {
            tvTitle.text = item.title
            tvYear.text = item.year ?: ""

            Glide.with(ivPoster.context)
                .load(item.posterUrl)
                .placeholder(R.color.surface_dark)
                .error(R.color.surface_dark)
                .into(ivPoster)

            itemView.setOnClickListener { onItemClick(item) }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                onFocusChanged(hasFocus, item)
            }
        }

        private fun onFocusChanged(hasFocus: Boolean, item: CatalogItem) {
            val scale = if (hasFocus) 1.15f else 1.0f // Increased scale for premium feel
            val glowAlpha = if (hasFocus) 1f else 0f
            val lift = if (hasFocus) 15f else 0f
            
            val duration = 250L // Slightly slower for cinematic feel
            val interpolator: Interpolator = if (hasFocus) 
                OvershootInterpolator(1.2f) 
                else DecelerateInterpolator()
            
            itemView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .z(lift)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start()
                
            glowView.animate()
                .alpha(glowAlpha)
                .setDuration(duration)
                .start()
            
            if (hasFocus) {
                startPulsingGlow()
            } else {
                stopPulsingGlow()
            }
            
            // Trigger Marquee on focus
            tvTitle.isSelected = hasFocus
            
            if (hasFocus) onItemFocused(item)
        }

        private fun startPulsingGlow() {
            pulseAnimator?.cancel()
            pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.0f).apply {
                duration = 1000L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    glowView.alpha = animator.animatedValue as Float
                }
                start()
            }
        }

        private fun stopPulsingGlow() {
            pulseAnimator?.cancel()
            glowView.alpha = 0f
        }
    }

    inner class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val vShimmer: View = view.findViewById(R.id.v_shimmer)
        
        init {
            startShimmer()
        }
        
        private fun startShimmer() {
            val anim = TranslateAnimation(
                Animation.RELATIVE_TO_PARENT, 0f,
                Animation.RELATIVE_TO_PARENT, 1f,
                Animation.RELATIVE_TO_PARENT, 0f,
                Animation.RELATIVE_TO_PARENT, 0f
            ).apply {
                duration = 1200L
                repeatCount = Animation.INFINITE
            }
            vShimmer.startAnimation(anim)
        }
    }
}
