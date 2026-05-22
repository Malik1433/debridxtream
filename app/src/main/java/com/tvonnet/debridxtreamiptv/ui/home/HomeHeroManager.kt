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
        }
        fragment = null
    }

    fun updateHeroSection(item: FeaturedItem) {
        val frag = fragment ?: return
        frag.currentHeroItem = item
        frag.tvHeroTitle.text = item.title
        frag.tvHeroDescription.text = item.description ?: "Watch this amazing content on DebridXtream. Cinematic experience."

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

        btnWatch?.setOnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.05f)
            v.z = if (hasFocus) 10f else 0f
            if (hasFocus) {
                frag.focusManager.rememberHeroFocusIfUserDriven()
            }
        }
        btnWatch?.setOnClickListener {
            val heroItem = frag.currentHeroItem
            if (heroItem == null) {
                frag.navigationRouter.showHomeActionUnavailable()
            } else {
                frag.navigationRouter.onFeaturedItemClick(heroItem)
            }
        }
        btnWatch?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> return@setOnKeyListener frag.focusManager.returnToSidebar()
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@setOnKeyListener frag.focusManager.requestContentFocus(frag.rvTop10Movies, frag.focusManager.lastMovieIndex) ||
                            frag.focusManager.requestContentFocus(frag.rvTop10Series, frag.focusManager.lastSeriesIndex)
                    }
                }
            }
            false
        }

        btnDetails?.setOnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.05f)
            v.z = if (hasFocus) 10f else 0f
            if (hasFocus) {
                frag.focusManager.rememberHeroFocusIfUserDriven()
            }
        }
        btnDetails?.setOnClickListener {
            val heroItem = frag.currentHeroItem
            if (heroItem == null) {
                frag.navigationRouter.showHomeActionUnavailable()
            } else {
                frag.navigationRouter.openFeaturedDetails(heroItem)
            }
        }
        btnDetails?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> return@setOnKeyListener frag.focusManager.returnToSidebar()
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@setOnKeyListener frag.focusManager.requestContentFocus(frag.rvTop10Movies, frag.focusManager.lastMovieIndex) ||
                            frag.focusManager.requestContentFocus(frag.rvTop10Series, frag.focusManager.lastSeriesIndex)
                    }
                }
            }
            false
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
