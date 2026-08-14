package com.tvonnet.debridxtreamiptv.ui.live.phone

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator

/**
 * The two things every phone screen in this design needs, in one place so the second screen
 * cannot drift from the first.
 */
object PhoneUi {

    /**
     * MainActivity multiplies every `sp` by 1.6 to rescue the TV layout's 7sp type in the hand.
     * Screens drawn at real phone sizes must opt out of that, and they inflate against the
     * APPLICATION configuration to do it — which still carries the user's own accessibility font
     * scale, just not our rescue factor.
     */
    fun unscaled(fragment: Fragment, inflater: LayoutInflater): LayoutInflater {
        val context = fragment.requireContext()
        val userScale = context.applicationContext.resources.configuration.fontScale
        val override = Configuration().apply { fontScale = userScale }
        val unscaled = context.createConfigurationContext(override)
        return inflater.cloneInContext(ContextThemeWrapper(unscaled, R.style.AppTheme))
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun showKeyboard(view: View?) {
        val target = view ?: return
        target.post {
            val imm = ContextCompat.getSystemService(target.context, InputMethodManager::class.java)
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard(view: View?) {
        val target = view ?: return
        val imm = ContextCompat.getSystemService(target.context, InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
    }
}

/**
 * The bottom bar, shared by every phone destination.
 *
 * Five slots when Debrid is entitled and four when it is not — the cells are equally weighted, so
 * the bar re-weights rather than leaving a gap where the fifth used to be. Built once here because
 * a second copy of it would be a second place for the destinations to drift.
 */
object PhoneBottomNav {

    private data class Item(val labelRes: Int, val iconRes: Int, val section: String)

    fun build(
        bar: LinearLayout?,
        inflater: LayoutInflater,
        active: String,
        onSelect: (String) -> Unit,
    ) {
        val target = bar ?: return
        target.removeAllViews()
        val items = buildList {
            add(Item(R.string.nav_home, R.drawable.ic_stremio_home, SectionNavigator.SECTION_HOME))
            add(Item(R.string.nav_live_tv, R.drawable.ic_live_tv, SectionNavigator.SECTION_LIVE))
            add(Item(R.string.nav_movies, R.drawable.ic_movie, SectionNavigator.SECTION_MOVIES))
            add(Item(R.string.nav_series, R.drawable.ic_series, SectionNavigator.SECTION_SERIES))
            if (Entitlements.isDebridAllowed(target.context)) {
                add(
                    Item(
                        R.string.nav_debrid_label,
                        R.drawable.ic_player_sources,
                        SectionNavigator.SECTION_DEBRID,
                    )
                )
            }
        }
        items.forEach { item ->
            val selected = item.section == active
            val cell = inflater.inflate(R.layout.item_phone_nav, target, false)
            val tint = ContextCompat.getColor(
                target.context,
                if (selected) R.color.phone_cyan else R.color.phone_text_muted,
            )
            cell.findViewById<ImageView>(R.id.phone_nav_icon).apply {
                setImageResource(item.iconRes)
                setColorFilter(tint)
            }
            cell.findViewById<TextView>(R.id.phone_nav_label).apply {
                setText(item.labelRes)
                setTextColor(tint)
            }
            cell.findViewById<View>(R.id.phone_nav_indicator).isVisible = selected
            if (!selected) cell.setOnClickListener { onSelect(item.section) }
            target.addView(
                cell,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }
}
