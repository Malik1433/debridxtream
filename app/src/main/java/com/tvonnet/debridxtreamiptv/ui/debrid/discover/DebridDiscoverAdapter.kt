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

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Replaces the current item list with new items.
     *
     * @param newItems  The full new list of catalog items.
     * @param canLoadMore  Whether to show the loading sentinel at the end.
     */
    fun submitItems(newItems: List<CatalogItem>, canLoadMore: Boolean) {
        items.clear()
        items.addAll(newItems)
        showLoadingFooter = canLoadMore
        notifyDataSetChanged()
    }

    // ── Adapter overrides ────────────────────────────────────────────────────────

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
        private val tvRating: TextView = view.findViewById(R.id.tv_rating)
        private val ivStar: ImageView = view.findViewById(R.id.iv_star)
        private val tvDotDivider: TextView = view.findViewById(R.id.tv_dot_divider)
        private val glowView: View = view.findViewById(R.id.glow_focus)

        /**
         * Binds a [CatalogItem] to this ViewHolder, loading the poster and setting up
         * click/focus listeners.
         */
        fun bind(item: CatalogItem) {
            tvTitle.text = item.title
            tvYear.text = item.year ?: ""
            
            // Handle Rating visibility
            if (!item.rating.isNullOrBlank() && item.rating != "0" && item.rating != "0.0") {
                tvRating.text = item.rating
                tvRating.visibility = View.VISIBLE
                ivStar.visibility = View.VISIBLE
                tvDotDivider.visibility = View.VISIBLE
            } else {
                tvRating.visibility = View.GONE
                ivStar.visibility = View.GONE
                tvDotDivider.visibility = View.GONE
            }

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
            val scale = if (hasFocus) 1.08f else 1.0f
            val glowAlpha = if (hasFocus) 1f else 0f
            
            itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            glowView.animate().alpha(glowAlpha).setDuration(150).start()
            
            // Trigger Marquee on focus
            tvTitle.isSelected = hasFocus
            
            if (hasFocus) onItemFocused(item)
        }
    }

    inner class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
