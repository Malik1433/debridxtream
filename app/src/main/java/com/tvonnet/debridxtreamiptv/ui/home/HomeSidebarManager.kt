package com.tvonnet.debridxtreamiptv.ui.home

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SidebarItem
import com.tvonnet.debridxtreamiptv.utils.SidebarFocusHelper

internal class HomeSidebarManager(private var fragment: HomeFragment?) {

    fun cleanup() {
        val frag = fragment
        if (frag != null) {
            if (frag.isRvSidebarInitialized()) {
                frag.rvSidebar.setOnKeyListener(null)
                frag.rvSidebar.adapter = null
            }
            frag.view?.findViewById<View>(R.id.sidebar_settings_item)?.apply {
                setOnClickListener(null)
                setOnFocusChangeListener(null)
                setOnKeyListener(null)
                animate().cancel()
            }
        }
        fragment = null
    }

    fun setupSidebar() {
        val frag = fragment ?: return

        val menuItems = listOf(
            SidebarItem(0, frag.getString(R.string.nav_search), R.drawable.ic_search),
            SidebarItem(1, frag.getString(R.string.nav_home), R.drawable.ic_home),
            SidebarItem(2, frag.getString(R.string.nav_live_tv), R.drawable.ic_live_tv),
            SidebarItem(3, frag.getString(R.string.nav_movies), R.drawable.ic_movie),
            SidebarItem(4, frag.getString(R.string.nav_series), R.drawable.ic_series),
            SidebarItem(5, frag.getString(R.string.nav_debrid), R.drawable.ic_dns)
        )

        frag.sidebarAdapter = SidebarAdapter(
            items = menuItems,
            onItemFocused = { item ->
                frag.focusManager.lastFocusedSidebarItemId = item.id
            },
            onDpadRight = {
                frag.focusManager.restoreContentFocusFromSidebar()
            },
            onItemSelected = { position ->
                when (position) {
                    0 -> frag.navigationRouter.navigateToSection("search")
                    1 -> {
                        frag.focusManager.activeNavItemId = HomeFragment.HOME_NAV_ITEM_ID
                        frag.sidebarAdapter.setActiveItemId(frag.focusManager.activeNavItemId)
                    }
                    2 -> frag.navigationRouter.navigateToSection("live")
                    3 -> frag.navigationRouter.navigateToSection("movies")
                    4 -> frag.navigationRouter.navigateToSection("series")
                    5 -> frag.navigationRouter.navigateToSection("debrid")
                }
            }
        )
        frag.rvSidebar.adapter = frag.sidebarAdapter
        frag.sidebarAdapter.setActiveItemId(frag.focusManager.activeNavItemId)

        // Settings navigation item
        val settingsItemView = frag.view?.findViewById<View>(R.id.sidebar_settings_item)
        if (settingsItemView != null) {
            val ivIcon = settingsItemView.findViewById<ImageView>(R.id.iv_icon)
            val tvTitle = settingsItemView.findViewById<TextView>(R.id.tv_title)
            val indicator = settingsItemView.findViewById<View>(R.id.view_selection_indicator)

            ivIcon.setImageResource(R.drawable.ic_settings)
            tvTitle.text = frag.getString(R.string.nav_settings)
            indicator.visibility = View.INVISIBLE

            settingsItemView.setOnClickListener { frag.navigationRouter.navigateToSection("settings") }

            // Match list micro-interactions
            settingsItemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    frag.focusManager.lastFocusedSidebarItemId = HomeFragment.SETTINGS_NAV_ITEM_ID
                }
                val density = settingsItemView.context.resources.displayMetrics.density

                settingsItemView.alpha = if (hasFocus) 1f else 0.6f
                settingsItemView.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_sidebar_nav_focused else R.drawable.bg_sidebar_nav_default
                )
                settingsItemView.animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .translationZ(if (hasFocus) 4f * density else 0f)
                    .setDuration(if (hasFocus) 220 else 180)
                    .start()
            }

            settingsItemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return@setOnKeyListener frag.focusManager.restoreContentFocusFromSidebar()
                }
                false
            }
        }

        // Standard Sidebar animations
        val sidebarPanel = frag.view?.findViewById<View>(R.id.sidebar_panel)
        val titleArea = frag.view?.findViewById<View>(R.id.tv_sidebar_app_name)

        if (sidebarPanel != null && titleArea != null) {
            SidebarFocusHelper.attachStandardSidebarAnimation(
                sidebarContainer = sidebarPanel,
                focusTrigger = frag.rvSidebar,
                titleArea = titleArea,
                focusGroupRoot = sidebarPanel,
                onExpandedChanged = { expanded ->
                    val f = fragment ?: return@attachStandardSidebarAnimation
                    if (f.isAdded && f.view != null) {
                        f.sidebarAdapter.setExpanded(expanded)

                        val header = f.view?.findViewById<LinearLayout>(R.id.sidebar_header)
                        val logo = f.view?.findViewById<ImageView>(R.id.iv_sidebar_logo)
                        val appName = f.view?.findViewById<TextView>(R.id.tv_sidebar_app_name)

                        if (expanded) {
                            header?.gravity = Gravity.CENTER_VERTICAL
                            (logo?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                                lp.marginStart = 0
                                logo.layoutParams = lp
                            }
                            appName?.visibility = View.VISIBLE

                            settingsItemView?.findViewById<TextView>(R.id.tv_title)?.apply {
                                alpha = 1f
                                visibility = View.VISIBLE
                                translationX = -20f
                                animate()
                                    .translationX(0f)
                                    .setDuration(150)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                                    .start()
                            }
                        } else {
                            header?.gravity = Gravity.CENTER
                            (logo?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                                lp.marginStart = 0
                                logo.layoutParams = lp
                            }
                            appName?.visibility = View.GONE

                            settingsItemView?.findViewById<TextView>(R.id.tv_title)?.apply {
                                alpha = 0f
                                visibility = View.GONE
                            }
                        }

                        // Dim Hero Background aggressively when sidebar is expanded
                        if (f.isIvHeroBackgroundInitialized()) {
                            f.ivHeroBackground.animate()
                                .alpha(if (expanded) 0.3f else 0.8f)
                                .setDuration(250)
                                .start()
                        }
                    }
                }
            )
        }
    }
}
