package com.tvonnet.debridxtreamiptv.ui.debrid

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tvonnet.debridxtreamiptv.R

/**
 * Focus Controller for the "Alive" Sidebar.
 * Encapsulates the logic for the floating focus pill and micro-animations.
 */
class SidebarFocusController(
    private val sidebarContainer: View,
    private val focusPill: View,
    private val colorSelected: Int,
    private val colorMuted: Int
) {
    private var isFirstFocus = true
    private var lastActiveTarget: View? = null
    private val fastInterpolator = DecelerateInterpolator()
    private val overshootInterpolator = OvershootInterpolator(1.2f)

    /**
     * Updates the focus pill position and triggers micro-animations on the item.
     * Use [isEnteringSidebar] to determine if we should brighten or dim the pill.
     */
    fun onFocusChanged(target: View, hasFocus: Boolean, isEnteringSidebar: Boolean = true) {
        val icon = target.findViewById<ImageView>(R.id.iv_icon)
        val text = target.findViewById<TextView>(R.id.tv_label)

        if (hasFocus) {
            lastActiveTarget = target
            // 1. Move the Focus Pill and Brighten (Active + Focused)
            moveFocusPill(target, alpha = 0.6f)

            // 2. Icon Micro-Animation: Scale + TranslationX + Alpha
            icon?.animate()
                ?.scaleX(1.06f)
                ?.scaleY(1.06f)
                ?.translationX(6f * target.resources.displayMetrics.density)
                ?.setDuration(160)
                ?.setInterpolator(overshootInterpolator)
                ?.start()
            icon?.imageTintList = android.content.res.ColorStateList.valueOf(colorSelected)
            icon?.alpha = 1f

            // 3. Text Micro-Animation: Color + Alpha
            text?.setTextColor(colorSelected)
            if (text?.visibility == View.VISIBLE) {
                text.animate()?.alpha(1f)?.setDuration(160)?.start()
            }

        } else {
            // Reset Item Visuals
            icon?.animate()
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.translationX(0f)
                ?.setDuration(160)
                ?.setInterpolator(fastInterpolator)
                ?.start()
            icon?.imageTintList = android.content.res.ColorStateList.valueOf(colorMuted)
            icon?.alpha = if (target == lastActiveTarget) 1f else 0.6f

            text?.setTextColor(colorMuted)
            
            // If we are leaving the sidebar entirely, dim the pill (Active but NOT Focused)
            if (!isEnteringSidebar && lastActiveTarget != null) {
                moveFocusPill(lastActiveTarget!!, alpha = 0.2f)
            }
        }
    }

    /**
     * Explicitly sets the active sidebar item (e.g. from restore state).
     */
    fun setSelected(target: View?) {
        target?.let {
            lastActiveTarget = it
            moveFocusPill(it, alpha = 0.2f)
        }
    }

    private fun moveFocusPill(target: View, alpha: Float) {
        val parent = target.parent as? ViewGroup ?: return
        
        // Safety: Ensure target is measured and laid out
        if (!target.isLaidOut || target.height <= 0) {
            target.post { moveFocusPill(target, alpha) }
            return
        }

        val targetY = target.top.toFloat() + parent.top.toFloat()
        val targetHeight = target.height

        // Update Pill Height dynamically (with bounds check)
        if (targetHeight > 0 && focusPill.height != targetHeight) {
            val params = focusPill.layoutParams
            params.height = targetHeight
            focusPill.layoutParams = params
        }

        if (isFirstFocus) {
            // Snap instantly on first focus
            focusPill.translationY = targetY
            focusPill.visibility = View.VISIBLE
            focusPill.alpha = alpha
            isFirstFocus = false
        } else {
            // Smoothly slide for subsequent focus events
            focusPill.animate().cancel() // Prevents focus-move ghosting
            focusPill.animate()
                .translationY(targetY)
                .alpha(alpha)
                .setDuration(160)
                .setInterpolator(fastInterpolator)
                .start()
        }
    }

    /**
     * Toggles the visibility of labels without width animation (Layout-Safe).
     */
    fun updateLabelState(isExpanded: Boolean, navItems: List<View?>) {
        navItems.forEach { item ->
            val label = item?.findViewById<TextView>(R.id.tv_label) ?: return@forEach
            if (isExpanded) {
                label.visibility = View.VISIBLE
                label.animate().alpha(1f).setDuration(200).start()
            } else {
                label.animate().alpha(0f).setDuration(150).withEndAction {
                    label.visibility = View.GONE
                }.start()
            }
        }
    }
}
