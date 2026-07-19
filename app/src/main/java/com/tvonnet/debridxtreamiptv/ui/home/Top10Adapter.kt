package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
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
    private val onItemClick: (FeaturedItem) -> Unit,
    private val onLeftBoundary: () -> Unit
) : RecyclerView.Adapter<Top10Adapter.Top10ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    fun updateItems(newItems: List<FeaturedItem>) {
        if (items == newItems) return
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size

            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return stableIdFor(oldItems[oldItemPosition]) == stableIdFor(newItems[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItemPosition == newItemPosition && oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Top10ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_10_card, parent, false)
        return Top10ViewHolder(view)
    }

    override fun onBindViewHolder(holder: Top10ViewHolder, position: Int) {
        holder.bind(items[position], position, onItemFocused, onItemClick, onLeftBoundary)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long {
        return stableIdFor(items[position])
    }

    fun getStableItemIdAt(position: Int): Long? {
        return items.getOrNull(position)?.let { stableIdFor(it) }
    }

    fun findPositionByStableId(stableId: Long): Int {
        return items.indexOfFirst { stableIdFor(it) == stableId }
    }

    companion object {
        fun stableIdFor(item: FeaturedItem): Long {
            val identity = "${item.sourceType}:${item.contentType}:${item.contentId.ifBlank { item.title }}"
            var hash = 1125899906842597L
            identity.forEach { char ->
                hash = 31L * hash + char.code
            }
            return hash
        }
    }

    class Top10ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.iv_poster)
        private val tvRank: TextView = itemView.findViewById(R.id.tv_rank_number)
        private val tvCardTitle: TextView? = itemView.findViewById(R.id.tv_card_title)
        private val tvCardSub: TextView? = itemView.findViewById(R.id.tv_card_sub)
        private val quickInfo: View? = itemView.findViewById(R.id.quick_info_bubble)
        private var dwellRunnable: Runnable? = null

        init {
            com.tvonnet.debridxtreamiptv.util.FocusGlow.attachSelector(
                itemView.findViewById(R.id.view_focus_glow)
            )
        }

        fun bind(
            item: FeaturedItem,
            position: Int,
            onFocus: (Int, FeaturedItem) -> Unit,
            onClick: (FeaturedItem) -> Unit,
            onLeftBoundary: () -> Unit
        ) {
            tvRank.text = (position + 1).toString()
            tvCardTitle?.text = item.title
            val subText = item.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: ""
            tvCardSub?.text = subText

            quickInfo?.let { bubble ->
                bubble.findViewById<TextView>(R.id.qi_title)?.text = item.title
                bubble.findViewById<TextView>(R.id.qi_tag)?.text =
                    if (item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.SERIES) "SERIES" else "MOVIE"
                bubble.findViewById<TextView>(R.id.qi_sub)?.text = subText
            }

            val cornerRadius = (4 * itemView.context.resources.displayMetrics.density).toInt()

            Glide.with(itemView.context)
                .load(item.posterUrl)
                // IMG-1: posters RGB_565 (no alpha) — halve heap vs 8888 default.
                .apply(
                    RequestOptions()
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .transform(CenterCrop(), RoundedCorners(cornerRadius))
                )
                .placeholder(R.drawable.bg_card_focus_cyan)
                .error(R.drawable.bg_card_focus_cyan)
                .into(ivPoster)

            itemView.setOnClickListener { onClick(item) }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                FocusEffects.applyCinematicFocus(view, hasFocus, scale = 1.06f)

                if (hasFocus) {
                    view.z = 20f // Topmost layering for no-clipping scale-up
                    onFocus(position, item)
                    scheduleDwellInfo()
                } else {
                    view.z = 0f
                    cancelDwellInfo()
                }
            }

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

        /** Dwell quick-info: bubble appears only after ~450ms of resting on the card. */
        private fun scheduleDwellInfo() {
            val bubble = quickInfo ?: return
            cancelDwellInfo()
            val runnable = Runnable {
                if (itemView.hasFocus()) {
                    bubble.visibility = View.VISIBLE
                    bubble.alpha = 0f
                    bubble.translationY = 8f
                    bubble.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180)
                        .start()
                }
            }
            dwellRunnable = runnable
            itemView.postDelayed(runnable, 450L)
        }

        private fun cancelDwellInfo() {
            dwellRunnable?.let { itemView.removeCallbacks(it) }
            dwellRunnable = null
            quickInfo?.let { bubble ->
                bubble.animate().cancel()
                bubble.visibility = View.GONE
            }
        }
    }
}
