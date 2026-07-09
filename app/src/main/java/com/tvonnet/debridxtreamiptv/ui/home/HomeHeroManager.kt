package com.tvonnet.debridxtreamiptv.ui.home

import android.view.KeyEvent
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.FeaturedItem
import com.tvonnet.debridxtreamiptv.util.FocusEffects

internal class HomeHeroManager(private var fragment: HomeFragment?) {

    fun cleanup() {
        val frag = fragment
        if (frag != null) {
            frag.view?.findViewById<View>(R.id.btn_hero_watch)?.apply {
                setOnClickListener(null)
                setOnFocusChangeListener(null)
                setOnKeyListener(null)
                animate().cancel()
            }
            frag.view?.findViewById<View>(R.id.btn_hero_details)?.apply {
                setOnClickListener(null)
                setOnFocusChangeListener(null)
                setOnKeyListener(null)
                animate().cancel()
            }
            frag.view?.findViewById<View>(R.id.btn_hero_fav)?.apply {
                setOnFocusChangeListener(null)
                setOnKeyListener(null)
                animate().cancel()
            }
        }
        fragment = null
    }

    fun updateHeroSection(item: FeaturedItem) {
        val frag = fragment ?: return
        frag.currentHeroItem = item
        frag.tvHeroTitle.text = item.title
        frag.tvHeroDescription.text = item.description ?: "Watch this amazing content on DebridXtream. Cinematic experience."

        item.rating?.takeIf { it.isNotBlank() }?.let { rating ->
            frag.view?.findViewById<android.widget.TextView>(R.id.tv_hero_rating)?.text = "$rating ★"
        }

        val heroUrl = item.backdropUrl ?: item.posterUrl

        Glide.with(frag)
            .load(heroUrl)
            .transition(DrawableTransitionOptions.withCrossFade())
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(frag.ivHeroBackground)

        val btnWatch = frag.view?.findViewById<View>(R.id.btn_hero_watch)
        val btnDetails = frag.view?.findViewById<View>(R.id.btn_hero_details)
        val btnFav = frag.view?.findViewById<View>(R.id.btn_hero_fav)

        val heroKeyListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (v.id == R.id.btn_hero_watch) {
                            return@OnKeyListener frag.focusManager.returnToSidebar()
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Focus memory: land back on the LAST focused rail card
                        return@OnKeyListener frag.focusManager.restoreLastRailFocus()
                    }
                }
            }
            false
        }

        val heroFocusListener = View.OnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.05f)
            v.z = if (hasFocus) 10f else 0f
            toggleHeroGlow(v.id, hasFocus)
            if (hasFocus) {
                frag.focusManager.rememberHeroFocusIfUserDriven()
            }
        }

        btnWatch?.onFocusChangeListener = heroFocusListener
        btnWatch?.setOnClickListener {
            val heroItem = frag.currentHeroItem
            if (heroItem == null) {
                frag.navigationRouter.showHomeActionUnavailable()
            } else {
                frag.navigationRouter.onFeaturedItemClick(heroItem)
            }
        }
        btnWatch?.setOnKeyListener(heroKeyListener)

        btnDetails?.onFocusChangeListener = heroFocusListener
        btnDetails?.setOnClickListener {
            val heroItem = frag.currentHeroItem
            if (heroItem == null) {
                frag.navigationRouter.showHomeActionUnavailable()
            } else {
                frag.navigationRouter.openFeaturedDetails(heroItem)
            }
        }
        btnDetails?.setOnKeyListener(heroKeyListener)

        btnFav?.onFocusChangeListener = heroFocusListener
        btnFav?.setOnKeyListener(heroKeyListener)
    }

    private fun toggleHeroGlow(buttonId: Int, hasFocus: Boolean) {
        val frag = fragment ?: return
        val glowId = when (buttonId) {
            R.id.btn_hero_watch -> R.id.glow_hero_watch
            R.id.btn_hero_details -> R.id.glow_hero_details
            R.id.btn_hero_fav -> R.id.glow_hero_fav
            else -> return
        }
        val root = frag.view ?: return
        val glow = root.findViewById<View>(glowId) ?: return
        val button = root.findViewById<View>(buttonId) ?: return
        if (glow.background == null) {
            com.tvonnet.debridxtreamiptv.util.FocusGlow.attachAlways(glow)
        }

        // Size the glow to button + 12dp halo on each side (a plain match_parent View
        // would fill all available space inside the wrap_content wrapper).
        if (hasFocus && button.width > 0) {
            val pad = (2 * com.tvonnet.debridxtreamiptv.util.FocusGlow.GLOW_PAD_DP *
                button.resources.displayMetrics.density).toInt()
            val lp = glow.layoutParams
            if (lp.width != button.width + pad || lp.height != button.height + pad) {
                lp.width = button.width + pad
                lp.height = button.height + pad
                glow.layoutParams = lp
            }
        }

        glow.animate().cancel()
        if (hasFocus) {
            glow.visibility = View.VISIBLE
            glow.alpha = 0f
            glow.animate().alpha(1f).setDuration(180).start()
        } else {
            glow.animate().alpha(0f).setDuration(140)
                .withEndAction { glow.visibility = View.INVISIBLE }
                .start()
        }
    }

    fun clearHeroSection() {
        val frag = fragment ?: return
        frag.currentHeroItem = null
        frag.tvHeroTitle.text = frag.getString(R.string.section_featured)
        frag.tvHeroDescription.text = ""
        frag.ivHeroBackground.setImageDrawable(null)
    }
}
