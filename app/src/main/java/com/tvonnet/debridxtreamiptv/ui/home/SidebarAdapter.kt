package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SidebarItem

/**
 * Icon-only floating nav rail adapter (Home Screen design).
 * Focus: cyan icon + capsule highlight + floating label flyout.
 * Active (selected): left dot indicator + light icon.
 */
class SidebarAdapter(
    private val items: List<SidebarItem>,
    private val onFocusChange: (Boolean) -> Unit = {},
    private val onItemFocused: (SidebarItem) -> Unit = {},
    private val onItemFocusView: (View, SidebarItem, Boolean) -> Unit = { _, _, _ -> },
    private val onDpadRight: () -> Boolean = { false },
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.SidebarViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.toLong()
    }

    private var selectedPosition = items.indexOfFirst { it.id == 1 }.takeIf { it >= 0 } ?: 0

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

    inner class SidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val viewSelectionIndicator: View = itemView.findViewById(R.id.view_selection_indicator)
        private val ease = FastOutSlowInInterpolator()

        fun bind(item: SidebarItem, isSelected: Boolean) {
            tvTitle.text = item.title
            ivIcon.setImageResource(item.iconRes)

            if (bindingAdapterPosition == 0) {
                itemView.nextFocusUpId = itemView.id
            } else {
                itemView.nextFocusUpId = View.NO_ID
            }

            applyIdleState(isSelected)

            itemView.setOnFocusChangeListener { v, hasFocus ->
                onFocusChange(hasFocus)
                onItemFocusView(v, item, hasFocus)

                val targetColor = if (hasFocus) COLOR_FOCUSED else idleColor(isSelected)
                val startColor = if (hasFocus) idleColor(isSelected) else COLOR_FOCUSED
                android.animation.ValueAnimator.ofArgb(startColor, targetColor).apply {
                    duration = if (hasFocus) 200L else 160L
                    interpolator = ease
                    addUpdateListener { animator ->
                        ivIcon.setColorFilter(animator.animatedValue as Int)
                    }
                    start()
                }

                if (hasFocus) {
                    onItemFocused(item)
                    viewSelectionIndicator.visibility = View.INVISIBLE
                } else {
                    viewSelectionIndicator.visibility =
                        if (isSelected) View.VISIBLE else View.INVISIBLE
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

        private fun applyIdleState(isSelected: Boolean) {
            itemView.setBackgroundResource(0)
            ivIcon.setColorFilter(idleColor(isSelected))
            viewSelectionIndicator.visibility =
                if (isSelected && !itemView.hasFocus()) View.VISIBLE else View.INVISIBLE
            // M2b: on TV the label lives in the shared nav_flyout, which appears on FOCUS —
            // so the in-item title is hidden there. A touch bar has no focus to hang a
            // flyout on, so on the phone the label stays under the icon.
            val labelInItem = itemView.resources.getBoolean(R.bool.home_nav_is_horizontal)
            tvTitle.alpha = if (labelInItem) 1f else 0f
            tvTitle.visibility = if (labelInItem) View.VISIBLE else View.GONE
        }

        private fun idleColor(isSelected: Boolean): Int =
            if (isSelected) COLOR_ACTIVE else COLOR_IDLE
    }

    companion object {
        private const val COLOR_FOCUSED = 0xFF00F0FF.toInt()
        private const val COLOR_ACTIVE = 0xFFE2E8F0.toInt()
        private const val COLOR_IDLE = 0xFF64748B.toInt()
    }
}
