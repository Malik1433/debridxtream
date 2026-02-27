package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SidebarItem


class SidebarAdapter(
    private val items: List<SidebarItem>,
    private val onFocusChange: (Boolean) -> Unit = {},
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.SidebarViewHolder>() {

    private var selectedPosition = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_nav, parent, false)
        return SidebarViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount() = items.size

    fun selectItem(position: Int) {
        val previousPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(previousPosition)
        notifyItemChanged(selectedPosition)
        onItemSelected(position)
    }

    inner class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val viewSelectionIndicator: View = itemView.findViewById(R.id.view_selection_indicator)

        fun bind(item: SidebarItem, isSelected: Boolean) {
            tvTitle.text = item.title
            ivIcon.setImageResource(item.iconRes)
            
            // Selection state
            // Selection (active tab): Tint Icon Cyan.
            // Focus: Expand width, Show Text, Dark Background.
            
            val tintColor = if (isSelected) {
                ContextCompat.getColor(itemView.context, R.color.brand_cyan)
            } else {
                ContextCompat.getColor(itemView.context, R.color.selector_sidebar_text)
            }
            ivIcon.setColorFilter(tintColor)
            viewSelectionIndicator.visibility = if (isSelected && itemView.isFocused) View.VISIBLE else View.INVISIBLE
            
            // Dimensions
            val density = itemView.context.resources.displayMetrics.density
            val iconWidth = (24 * density).toInt()
            val paddingStart = (12 * density).toInt()
            val paddingEnd = (8 * density).toInt()
            val textMarginStart = (16 * density).toInt()
            val collapsedWidth = paddingStart + iconWidth + paddingEnd

            // Initial State
            if (!itemView.isFocused) {
                itemView.layoutParams.width = collapsedWidth
                itemView.requestLayout()
                itemView.setBackgroundResource(android.R.color.transparent)
                tvTitle.visibility = View.GONE
                tvTitle.alpha = 0f
                viewSelectionIndicator.visibility = View.INVISIBLE
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                onFocusChange(hasFocus)
                
                if (hasFocus) {
                    // 1. Measure Text
                    tvTitle.measure(0, 0)
                    val textWidth = tvTitle.measuredWidth
                    val targetWidth = paddingStart + iconWidth + textMarginStart + textWidth + paddingEnd

                    // 2. Animate Width
                    val widthAnimator = android.animation.ValueAnimator.ofInt(itemView.width, targetWidth)
                    widthAnimator.addUpdateListener { animator ->
                        val value = animator.animatedValue as Int
                        itemView.layoutParams.width = value
                        itemView.requestLayout()
                    }
                    widthAnimator.duration = 150
                    widthAnimator.interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    widthAnimator.start()

                    // 3. Visuals
                    itemView.setBackgroundResource(R.drawable.bg_sidebar_item_expanded)
                    if (isSelected) viewSelectionIndicator.visibility = View.VISIBLE
                    
                    // 4. Text Reveal
                    tvTitle.visibility = View.VISIBLE
                    tvTitle.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .start()

                    // 5. Scale/Elevation
                    itemView.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .translationZ(8f * density)
                        .setDuration(150)
                        .start()

                } else {
                    // Collapse
                    val widthAnimator = android.animation.ValueAnimator.ofInt(itemView.width, collapsedWidth)
                    widthAnimator.addUpdateListener { animator ->
                        val value = animator.animatedValue as Int
                        itemView.layoutParams.width = value
                        itemView.requestLayout()
                    }
                    widthAnimator.duration = 150
                    widthAnimator.interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    widthAnimator.start()

                    // Reset Visuals
                    itemView.setBackgroundResource(android.R.color.transparent)
                    viewSelectionIndicator.visibility = View.INVISIBLE
                    
                    // Text Hide
                    tvTitle.animate()
                        .alpha(0f)
                        .setDuration(100)
                        .withEndAction { tvTitle.visibility = View.GONE }
                        .start()
                        
                    // Reset Scale
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(150)
                        .start()
                }
            }
            
            itemView.setOnClickListener {
                selectItem(adapterPosition)
            }
        }
    }
}
