package com.tvonnet.debridxtreamiptv.player

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.util.GlideUtils

class BrowserCategoryAdapter(
    private val onCategoryClick: (XtreamCategory) -> Unit
) : ListAdapter<XtreamCategory, BrowserCategoryAdapter.ViewHolder>(CategoryDiffCallback) {

    private var selectedCategoryId: String? = null

    fun setSelectedId(id: String?) {
        selectedCategoryId = id
        notifyDataSetChanged() // Inefficient but simple for now
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_sidebar, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = getItem(position)
        val isSelected = category.category_id == selectedCategoryId
        holder.bind(category, isSelected)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_category_name)
        private val indicator: View = itemView.findViewById(R.id.v_active_indicator)

        fun bind(category: XtreamCategory, isSelected: Boolean) {
            tvName.text = category.category_name ?: "Unknown"

            if (isSelected) {
                tvName.setTextColor(itemView.context.getColor(R.color.white))
                tvName.setTypeface(null, Typeface.BOLD)
                indicator.visibility = View.VISIBLE
                indicator.alpha = 1f
            } else {
                tvName.setTextColor(itemView.context.getColor(R.color.white_opacity_70))
                tvName.setTypeface(null, Typeface.NORMAL)
                indicator.visibility = View.INVISIBLE
                indicator.alpha = 0f
            }

            itemView.setOnClickListener {
                onCategoryClick(category)
            }
        }
    }

    object CategoryDiffCallback : DiffUtil.ItemCallback<XtreamCategory>() {
        override fun areItemsTheSame(oldItem: XtreamCategory, newItem: XtreamCategory): Boolean =
            oldItem.category_id == newItem.category_id

        override fun areContentsTheSame(oldItem: XtreamCategory, newItem: XtreamCategory): Boolean =
            oldItem == newItem
    }
}

class BrowserChannelAdapter(
    private val onChannelClick: (XtreamStream) -> Unit
) : ListAdapter<XtreamStream, BrowserChannelAdapter.ViewHolder>(ChannelDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Use the new modern card layout
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browser_channel_modern, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = getItem(position)
        holder.bind(channel)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo_modern)
        private val tvName: TextView = itemView.findViewById(R.id.tv_channel_name_modern)
        private val tvPlaying: TextView = itemView.findViewById(R.id.tv_now_playing_modern)
        
        // Optional badges
        private val badgeHd: TextView? = itemView.findViewById(R.id.badge_hd_modern)
        private val badge4k: TextView? = itemView.findViewById(R.id.badge_4k_modern)

        fun bind(channel: XtreamStream) {
            tvName.text = channel.name ?: "Unknown Channel"
            tvPlaying.text = channel.currentProgramTitle ?: "No Information"
            
            // Use App Logo as fallback for missing channels
            GlideUtils.loadChannelLogo(
                ivLogo, 
                channel.stream_icon, 
                com.tvonnet.debridxtreamiptv.R.drawable.app_logo, 
                com.tvonnet.debridxtreamiptv.R.drawable.app_logo
            )

            // Basic badges
            val name = channel.name ?: ""
            val is4k = name.contains("4k", ignoreCase = true) || name.contains("uhd", ignoreCase = true)
            val isHd = !is4k && (name.contains("hd", ignoreCase = true) || name.contains("fhd", ignoreCase = true))

            badge4k?.visibility = if (is4k) View.VISIBLE else View.GONE
            badgeHd?.visibility = if (isHd) View.VISIBLE else View.GONE

            // Code-based focus animation with bounce effect
            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate()
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .translationZ(10f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator())
                        .start()
                    // Optional: Add border or glow if needed via background change
                } else {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .start()
                }
            }
            
            itemView.setOnClickListener {
                onChannelClick(channel)
            }
        }
    }

    object ChannelDiffCallback : DiffUtil.ItemCallback<XtreamStream>() {
        override fun areItemsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean =
            oldItem.stream_id == newItem.stream_id

        override fun areContentsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean =
            oldItem == newItem
    }
}
