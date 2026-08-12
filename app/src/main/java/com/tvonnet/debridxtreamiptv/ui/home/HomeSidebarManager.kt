package com.tvonnet.debridxtreamiptv.ui.home

import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SidebarItem

internal class HomeSidebarManager(private var fragment: HomeFragment?) {

    // Design spec easing: cubic-bezier(0.22, 1, 0.36, 1)
    private val magneticEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)

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
            frag.view?.findViewById<View>(R.id.sidebar_profile_mark)?.apply {
                setOnFocusChangeListener(null)
                animate().cancel()
            }
            frag.view?.findViewById<View>(R.id.sidebar_capsule)?.animate()?.cancel()
        }
        fragment = null
    }

    fun setupSidebar() {
        val frag = fragment ?: return
        applyPhoneRailRules(frag)

        // Tier gating: NORMAL devices are IPTV-only — the Debrid entry must not exist
        // for them at all (owner policy; see Entitlements.isDebridAllowed).
        val debridAllowed = com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
            .isDebridAllowed(frag.requireContext())
        val menuItems = buildList {
            add(SidebarItem(0, frag.getString(R.string.nav_search), R.drawable.ic_search))
            add(SidebarItem(1, frag.getString(R.string.nav_home), R.drawable.ic_home))
            add(SidebarItem(2, frag.getString(R.string.nav_live_tv), R.drawable.ic_live_tv))
            add(SidebarItem(3, frag.getString(R.string.nav_movies), R.drawable.ic_movie))
            add(SidebarItem(4, frag.getString(R.string.nav_series), R.drawable.ic_series))
            if (debridAllowed) add(SidebarItem(5, frag.getString(R.string.nav_debrid), R.drawable.ic_dns))
        }

        frag.sidebarAdapter = SidebarAdapter(
            items = menuItems,
            onItemFocused = { item ->
                frag.focusManager.lastFocusedSidebarItemId = item.id
            },
            onItemFocusView = { itemView, item, hasFocus ->
                if (hasFocus) {
                    moveCapsuleTo(itemView)
                    showFlyout(item.title, null, itemView)
                } else {
                    scheduleCapsuleFadeCheck()
                    hideFlyout()
                }
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

        setupSettingsItem(frag)
        setupProfileMark(frag)
    }

    /**
     * Shows the shared flyout pill (lives in the fragment root, outside the 48dp rail,
     * because ConstraintLayout clamps children to parent bounds). Vertically centered
     * on the anchor; slides in from -8px per design.
     */
    private fun showFlyout(title: String, sub: String?, anchor: View) {
        val frag = fragment ?: return
        val root = frag.view ?: return
        // M13: the flyout is a FOCUS affordance — it names the rail item the D-pad is sitting on.
        // A touch device has no focused item, so on a phone it opened with nothing to describe and
        // parked itself across the middle of the hero. The rail's own labels are shown there
        // instead (SidebarAdapter puts them in the item).
        if (!root.resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
        val flyout = root.findViewById<View>(R.id.nav_flyout) ?: return
        val tvTitle = root.findViewById<TextView>(R.id.nav_flyout_title) ?: return
        val tvSub = root.findViewById<TextView>(R.id.nav_flyout_sub)

        tvTitle.text = title
        if (sub != null) {
            tvSub?.text = sub
            tvSub?.visibility = View.VISIBLE
        } else {
            tvSub?.visibility = View.GONE
        }

        flyout.animate().cancel()
        flyout.visibility = View.VISIBLE
        flyout.alpha = 0f
        flyout.translationX = -8f
        flyout.post {
            val f = fragment ?: return@post
            val rootView = f.view ?: return@post
            if (!anchor.isAttachedToWindow) return@post
            val anchorLoc = IntArray(2)
            val rootLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            rootView.getLocationInWindow(rootLoc)
            val anchorCenterY = (anchorLoc[1] - rootLoc[1]) + anchor.height / 2f
            flyout.y = anchorCenterY - flyout.height / 2f
            flyout.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(200)
                .setInterpolator(magneticEase)
                .start()
        }
    }

    private fun hideFlyout() {
        val flyout = fragment?.view?.findViewById<View>(R.id.nav_flyout) ?: return
        flyout.animate().cancel()
        flyout.animate()
            .alpha(0f)
            .setDuration(140)
            .withEndAction { flyout.visibility = View.GONE }
            .start()
    }

    /** Slides the shared highlight capsule to the focused nav item (magnetic dock effect). */
    private fun moveCapsuleTo(itemView: View) {
        val frag = fragment ?: return
        val capsule = frag.view?.findViewById<View>(R.id.sidebar_capsule) ?: return
        if (!frag.isRvSidebarInitialized()) return
        val targetY = frag.rvSidebar.y + itemView.y

        capsule.animate().cancel()
        if (capsule.alpha < 0.05f) {
            // First entry: appear in place, no cross-rail slide
            capsule.y = targetY
            capsule.animate()
                .alpha(1f)
                .setDuration(220)
                .start()
        } else {
            capsule.animate()
                .y(targetY)
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(magneticEase)
                .start()
        }
    }

    /** Fades the capsule out once focus has fully left the nav list. */
    private fun scheduleCapsuleFadeCheck() {
        val frag = fragment ?: return
        val capsule = frag.view?.findViewById<View>(R.id.sidebar_capsule) ?: return
        capsule.postDelayed({
            val f = fragment ?: return@postDelayed
            if (!f.isRvSidebarInitialized() || f.view == null) return@postDelayed
            if (f.rvSidebar.findFocus() == null) {
                capsule.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .start()
            }
        }, 80)
    }

    private fun setupSettingsItem(frag: HomeFragment) {
        val settingsItemView = frag.view?.findViewById<View>(R.id.sidebar_settings_item) ?: return
        val ivIcon = settingsItemView.findViewById<ImageView>(R.id.iv_icon)
        val tvTitle = settingsItemView.findViewById<TextView>(R.id.tv_title)
        val indicator = settingsItemView.findViewById<View>(R.id.view_selection_indicator)
        val ease = FastOutSlowInInterpolator()

        ivIcon.setImageResource(R.drawable.ic_settings)
        ivIcon.setColorFilter(COLOR_IDLE)
        tvTitle.text = frag.getString(R.string.nav_settings)
        // Settings is an <include> of the same item layout, so it needs the same answer the
        // adapter gives the list — otherwise the phone would label five destinations and leave
        // this one, the one furthest down the rail, as a bare cog.
        val labelInItem = frag.resources.getBoolean(R.bool.nav_items_show_label)
        tvTitle.visibility = if (labelInItem) View.VISIBLE else View.GONE
        tvTitle.alpha = if (labelInItem) 1f else 0f
        indicator.visibility = View.INVISIBLE

        settingsItemView.setOnClickListener { frag.navigationRouter.navigateToSection("settings") }

        settingsItemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                frag.focusManager.lastFocusedSidebarItemId = HomeFragment.SETTINGS_NAV_ITEM_ID
            }

            val targetColor = if (hasFocus) COLOR_FOCUSED else COLOR_IDLE
            val startColor = if (hasFocus) COLOR_IDLE else COLOR_FOCUSED
            android.animation.ValueAnimator.ofArgb(startColor, targetColor).apply {
                duration = if (hasFocus) 200L else 160L
                interpolator = ease
                addUpdateListener { animator ->
                    ivIcon.setColorFilter(animator.animatedValue as Int)
                }
                start()
            }

            if (hasFocus) {
                // Bottom items highlight in place (no capsule per design)
                settingsItemView.setBackgroundResource(R.drawable.bg_nav_item_focused)
                showFlyout(frag.getString(R.string.nav_settings), null, settingsItemView)
            } else {
                settingsItemView.setBackgroundResource(0)
                hideFlyout()
            }
        }

        settingsItemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return@setOnKeyListener frag.focusManager.restoreContentFocusFromSidebar()
            }
            false
        }
    }

    /**
     * Two things the rail has to do differently in the hand.
     *
     * The list draws with clipping OFF, because the TV scales a focused item and the glow has to
     * spill past the rail. Nothing focuses on a phone, so that permission buys nothing there — and
     * it cost something real: with labels the list is taller than its box, and the overflow painted
     * ON TOP of the divider and the Settings item, so "Series" and "Settings" landed on the same
     * pixels. Clipping the list on touch confines it to its own space.
     *
     * The profile circle is removed outright (owner, 2026-08-13). It was a lettered dot with no
     * name next to five labelled destinations, and it is not a destination at all — the account
     * belongs in Settings, not in the navigation. Removing it also returns its height to the list,
     * which is the difference between six destinations fitting and not.
     */
    private fun applyPhoneRailRules(frag: HomeFragment) {
        if (frag.resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
        frag.view?.findViewById<android.view.ViewGroup>(R.id.rv_sidebar)?.apply {
            clipChildren = true
            clipToPadding = true
        }
        frag.view?.findViewById<View>(R.id.sidebar_profile_mark)?.visibility = View.GONE
    }

    private fun setupProfileMark(frag: HomeFragment) {
        val profile = frag.view?.findViewById<View>(R.id.sidebar_profile_mark) ?: return
        // Gone on a phone; nothing to wire up.
        if (profile.visibility == View.GONE) return

        // Prefer the ACCOUNT's email: that is the thing a customer can act on — it is what they
        // sign in with on the web and what support asks for. The Xtream username is the fallback
        // for a device that was set up with credentials and never linked to an account.
        val accountEmail = runCatching {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val profileName = accountEmail
            ?: frag.credentialsPrefs.getUsername()?.replaceFirstChar { it.uppercaseChar() }
            ?: frag.getString(R.string.c_profile_unknown)

        // The initial was a literal "A" in the layout, so every user saw the same letter.
        frag.view?.findViewById<android.widget.TextView>(R.id.sidebar_profile_initial)?.text =
            profileName.firstOrNull { it.isLetterOrDigit() }
                ?.uppercaseChar()?.toString()
                ?: "?"

        // The tier was the literal "PREMIUM" for everyone, including a device that is not.
        val tier = frag.getString(
            if (com.tvonnet.debridxtreamiptv.data.licensing.Entitlements.isPremium(frag.requireContext())) {
                R.string.c_tier_premium
            } else {
                R.string.c_tier_standard
            }
        )

        profile.setOnFocusChangeListener { _, hasFocus ->
            profile.setBackgroundResource(
                if (hasFocus) R.drawable.bg_profile_mark_focused else R.drawable.bg_profile_mark
            )
            if (hasFocus) {
                showFlyout(profileName, tier, profile)
            } else {
                hideFlyout()
            }
        }

        // On a touchscreen this view is never focused, and the flyout is deliberately suppressed
        // there (M13) — so on a phone there was NO way to see which account the app was signed in
        // as. The rail's other items carry their own labels; this one is a bare circle. A tap
        // therefore has to answer the question itself.
        profile.setOnClickListener {
            if (profile.resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
                showFlyout(profileName, tier, profile)
            } else {
                android.app.AlertDialog.Builder(frag.requireContext())
                    .setTitle(R.string.c_signed_in_as)
                    .setMessage("$profileName\n$tier")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }

        profile.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return@setOnKeyListener frag.focusManager.restoreContentFocusFromSidebar()
            }
            false
        }
    }

    private companion object {
        const val COLOR_FOCUSED = 0xFF00F0FF.toInt()
        const val COLOR_IDLE = 0xFF64748B.toInt()
    }
}
