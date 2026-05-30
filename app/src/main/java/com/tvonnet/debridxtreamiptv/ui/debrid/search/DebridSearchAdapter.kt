package com.tvonnet.debridxtreamiptv.ui.debrid.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

class DebridSearchAdapter(
    private val onItemClick: (DebridContentItem) -> Unit
) : ListAdapter<DebridContentItem, DebridSearchViewHolder>(DebridSearchDiffCallback()) {

    init {
        setHasStableIds(true)
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
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
        holder.bind(getItem(position), onItemClick)
    }

    override fun onViewRecycled(holder: DebridSearchViewHolder) {
        super.onViewRecycled(holder)
        holder.clearAnimations()
    }
}

class DebridSearchDiffCallback : DiffUtil.ItemCallback<DebridContentItem>() {
    override fun areItemsTheSame(oldItem: DebridContentItem, newItem: DebridContentItem): Boolean {
        val oldId = if (!oldItem.id.isNullOrEmpty()) oldItem.id else oldItem.streamUrl
        val newId = if (!newItem.id.isNullOrEmpty()) newItem.id else newItem.streamUrl
        return oldId == newId
    }

    override fun areContentsTheSame(oldItem: DebridContentItem, newItem: DebridContentItem): Boolean {
        return oldItem == newItem
    }
}

class DebridSearchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val ivPoster: ImageView = itemView.findViewById(R.id.iv_poster)
    private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
    private val tvYear: TextView = itemView.findViewById(R.id.tv_year)
    private val glowView: View = itemView.findViewById(R.id.glow_focus)
    private var pulseAnimator: android.animation.ValueAnimator? = null

    fun bind(item: DebridContentItem, onItemClick: (DebridContentItem) -> Unit) {
        tvTitle.text = item.title
        tvYear.text = item.year ?: ""
        tvYear.visibility = if (item.year.isNullOrBlank()) View.GONE else View.VISIBLE

        Glide.with(itemView.context)
            .load(item.posterUrl)
            .placeholder(R.color.surface_dark)
            .error(R.color.surface_dark)
            .into(ivPoster)

        itemView.setOnClickListener { onItemClick(item) }
        
        itemView.setOnFocusChangeListener { _, hasFocus ->
            onFocusChanged(hasFocus)
        }
    }

    private fun onFocusChanged(hasFocus: Boolean) {
        val scale = if (hasFocus) 1.15f else 1.0f
        val glowAlpha = if (hasFocus) 1f else 0f
        val lift = if (hasFocus) 15f else 0f
        
        val duration = 250L
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

        tvTitle.isSelected = hasFocus

        if (hasFocus) {
            startPulsingGlow()
        } else {
            stopPulsingGlow()
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
                glowView.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopPulsingGlow() {
        pulseAnimator?.cancel()
        glowView.alpha = 0f
    }

    fun clearAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        itemView.animate().cancel()
        glowView.animate().cancel()
        
        itemView.scaleX = 1.0f
        itemView.scaleY = 1.0f
        itemView.z = 0f
        glowView.alpha = 0f
        tvTitle.isSelected = false
    }
}
