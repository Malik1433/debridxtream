package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SidebarItem


class SidebarAdapter(
    private val items: List<SidebarItem>,
    private val onFocusChange: (Boolean) -> Unit = {},
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.SidebarViewHolder>() {
    
    private var selectedPosition = 1
    private var isExpanded: Boolean = true
    private val viewHolders = mutableSetOf<SidebarViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_nav, parent, false)
        return SidebarViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
        viewHolders.add(holder)
    }

    override fun onViewRecycled(holder: SidebarViewHolder) {
        super.onViewRecycled(holder)
        viewHolders.remove(holder)
    }

    override fun getItemCount() = items.size

    fun selectItem(position: Int) {
        val previousPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(previousPosition)
        notifyItemChanged(selectedPosition)
        onItemSelected(position)
    }

    fun setExpanded(expanded: Boolean) {
        if (expanded == isExpanded) return
        isExpanded = expanded
        // Update all active view holders smoothly without full rebind
        viewHolders.forEach { it.applyRowLayout(expanded, animate = true) }
    }

    inner class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val viewSelectionIndicator: View = itemView.findViewById(R.id.view_selection_indicator)
        private val ease = FastOutSlowInInterpolator()

        fun bind(item: SidebarItem, isSelected: Boolean) {
            tvTitle.text = item.title
            ivIcon.setImageResource(item.iconRes)
            
            // Visual state: Text color and indicator
            val tintColor = if (isSelected) {
                ContextCompat.getColor(itemView.context, R.color.sidebar_text_primary)
            } else {
                ContextCompat.getColor(itemView.context, R.color.sidebar_text_dim)
            }
            ivIcon.setColorFilter(tintColor)
            tvTitle.setTextColor(tintColor)
            
            // The selection indicator visibility is now redundant with the pill background, 
            // but we keep it available if needed for specific designs.
            viewSelectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            
            // Apply initial layout state
            applyRowLayout(expanded = isExpanded, animate = false)

            // Background based on state
            itemView.setBackgroundResource(
                if (isSelected) R.drawable.bg_sidebar_nav_active else R.drawable.bg_sidebar_nav_default
            )

            itemView.setOnFocusChangeListener { _, hasFocus ->
                onFocusChange(hasFocus)
                
                // Focus Scale and Background Logic
                val density = itemView.context.resources.displayMetrics.density
                if (hasFocus) {
                    itemView.setBackgroundResource(
                        if (isSelected) R.drawable.bg_sidebar_nav_active else R.drawable.bg_sidebar_nav_focused
                    )
                    
                    itemView.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .translationZ(4f * density)
                        .setInterpolator(ease)
                        .setDuration(220)
                        .start()
                } else {
                    itemView.setBackgroundResource(
                        if (isSelected) R.drawable.bg_sidebar_nav_active else R.drawable.bg_sidebar_nav_default
                    )
                    
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setInterpolator(ease)
                        .setDuration(180)
                        .start()
                }
            }
            
            itemView.setOnClickListener {
                selectItem(absoluteAdapterPosition)
            }
        }

        fun applyRowLayout(expanded: Boolean, animate: Boolean) {
            val iconLp = ivIcon.layoutParams as? ConstraintLayout.LayoutParams ?: return

            if (expanded) {
                // Expanded: Icon left, Title visible
                iconLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.endToEnd = ConstraintLayout.LayoutParams.UNSET
                ivIcon.layoutParams = iconLp

                tvTitle.visibility = View.VISIBLE
                if (animate) {
                    tvTitle.animate().alpha(1f).setDuration(220).setInterpolator(ease).start()
                } else {
                    tvTitle.alpha = 1f
                }
            } else {
                // Collapsed: Icon centered, Title hidden (no truncation)
                iconLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                ivIcon.layoutParams = iconLp

                if (animate) {
                    tvTitle.animate()
                        .alpha(0f)
                        .setDuration(180)
                        .setInterpolator(ease)
                        .withEndAction { 
                            if (!isExpanded) tvTitle.visibility = View.GONE 
                        }
                        .start()
                } else {
                    tvTitle.alpha = 0f
                    tvTitle.visibility = View.GONE
                }
            }
        }
    }
}
