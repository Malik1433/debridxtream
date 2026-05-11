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
    private val onItemFocused: (SidebarItem) -> Unit = {},
    private val onDpadRight: () -> Boolean = { false },
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.SidebarViewHolder>() {
    
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.toLong()
    }

    private val attachedHolders = mutableSetOf<SidebarViewHolder>()
    private var selectedPosition = items.indexOfFirst { it.id == 1 }.takeIf { it >= 0 } ?: 0
    private var isExpanded: Boolean = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_nav, parent, false)
        return SidebarViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount() = items.size

    fun setActiveItemId(itemId: Int) {
        val position = items.indexOfFirst { it.id == itemId }
        if (position == RecyclerView.NO_POSITION || position == selectedPosition) return
        val previousPosition = selectedPosition
        selectedPosition = position
        if (previousPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(previousPosition)
        }
        notifyItemChanged(selectedPosition)
    }

    fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        if (expanded == isExpanded) return
        isExpanded = expanded
        attachedHolders.forEach { it.applyExpandedState(expanded, animate) }
    }

    override fun onViewAttachedToWindow(holder: SidebarViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachedHolders.add(holder)
        holder.applyExpandedState(isExpanded, animate = false)
    }

    override fun onViewDetachedFromWindow(holder: SidebarViewHolder) {
        attachedHolders.remove(holder)
        super.onViewDetachedFromWindow(holder)
    }

    inner class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val viewSelectionIndicator: View = itemView.findViewById(R.id.view_selection_indicator)
        private val ease = FastOutSlowInInterpolator()

        fun bind(item: SidebarItem, isSelected: Boolean) {
            tvTitle.text = item.title
            ivIcon.setImageResource(item.iconRes)
            
            // Selection state
            // Selection (active tab): Tint Icon Cyan.
            // Focus: Expand width, Show Text, Dark Background.
            
            val tintColor = if (isSelected) {
                ContextCompat.getColor(itemView.context, R.color.sidebar_text_primary)
            } else {
                ContextCompat.getColor(itemView.context, R.color.sidebar_text_dim)
            }
            ivIcon.setColorFilter(tintColor)
            viewSelectionIndicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            val density = itemView.context.resources.displayMetrics.density

            applyExpandedState(expanded = isExpanded, animate = false)

            // Initial State
            itemView.setBackgroundResource(
                if (isSelected) R.drawable.bg_sidebar_nav_active else R.drawable.bg_sidebar_nav_default
            )
            // Persistent Dimmed Active state when grid is focused
            itemView.alpha = if (isSelected && !itemView.hasFocus()) 0.6f else 1.0f

            itemView.setOnFocusChangeListener { _, hasFocus ->
                onFocusChange(hasFocus)
                
                if (hasFocus) {
                    onItemFocused(item)
                    itemView.alpha = 1f
                    // Visuals
                    itemView.setBackgroundResource(
                        if (isSelected) R.drawable.bg_sidebar_nav_active else R.drawable.bg_sidebar_nav_focused
                    )
                    
                    // Text Reveal
                    tvTitle.visibility = View.VISIBLE
                    tvTitle.alpha = 1f

                    // Scale/Elevation
                    itemView.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .translationZ(4f * density)
                        .setInterpolator(ease)
                        .setDuration(220)
                        .start()

                } else {
                    // Persistent Active visibility
                    itemView.alpha = if (isSelected) 0.6f else 1f
                    
                    // Reset Visuals
                    itemView.setBackgroundResource(
                        if (isSelected) R.drawable.bg_sidebar_nav_active else R.drawable.bg_sidebar_nav_default
                    )
                    
                    // Reset Scale
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
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                onItemSelected(position)
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    return@setOnKeyListener onDpadRight()
                }
                false
            }
        }

        fun applyExpandedState(expanded: Boolean, animate: Boolean) {
            applyRowLayout(expanded, animate)
        }

        private fun applyRowLayout(expanded: Boolean, animate: Boolean) {
            val iconLp = ivIcon.layoutParams as? ConstraintLayout.LayoutParams ?: return
            val titleLp = tvTitle.layoutParams as? ConstraintLayout.LayoutParams ?: return

            if (expanded) {
                // Icon pinned left, title visible
                iconLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.endToEnd = ConstraintLayout.LayoutParams.UNSET
                ivIcon.layoutParams = iconLp

                titleLp.startToEnd = ivIcon.id
                tvTitle.layoutParams = titleLp

                tvTitle.visibility = View.VISIBLE
                if (animate) {
                    tvTitle.animate().alpha(1f).setDuration(220).setInterpolator(ease).start()
                } else {
                    tvTitle.alpha = 1f
                }
                itemView.setPadding(
                    (16 * itemView.resources.displayMetrics.density).toInt(),
                    itemView.paddingTop,
                    (12 * itemView.resources.displayMetrics.density).toInt(),
                    itemView.paddingBottom
                )
            } else {
                // Icons-only, perfectly centered; title fully hidden (no truncation)
                iconLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                ivIcon.layoutParams = iconLp

                if (animate) {
                    tvTitle.animate()
                        .alpha(0f)
                        .setDuration(180)
                        .setInterpolator(ease)
                        .withEndAction { tvTitle.visibility = View.GONE }
                        .start()
                } else {
                    tvTitle.alpha = 0f
                    tvTitle.visibility = View.GONE
                }
                itemView.setPadding(0, itemView.paddingTop, 0, itemView.paddingBottom)
            }
        }
    }
}
