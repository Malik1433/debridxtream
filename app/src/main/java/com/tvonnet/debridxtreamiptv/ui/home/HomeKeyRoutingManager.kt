package com.tvonnet.debridxtreamiptv.ui.home

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

internal class HomeKeyRoutingManager(private var fragment: HomeFragment?) {

    internal var continueWatchingChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    internal var recentLiveChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    internal var moviesChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    internal var seriesChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null

    fun cleanup() {
        val frag = fragment
        if (frag != null) {
            if (frag.isRvContinueWatchingInitialized()) {
                continueWatchingChildKeyListener?.let { frag.rvContinueWatching.removeOnChildAttachStateChangeListener(it) }
                frag.rvContinueWatching.setOnKeyListener(null)
            }
            if (frag.isRvRecentLiveInitialized()) {
                recentLiveChildKeyListener?.let { frag.rvRecentLive.removeOnChildAttachStateChangeListener(it) }
                frag.rvRecentLive.setOnKeyListener(null)
            }
            if (frag.isRvTop10MoviesInitialized()) {
                moviesChildKeyListener?.let { frag.rvTop10Movies.removeOnChildAttachStateChangeListener(it) }
                frag.rvTop10Movies.setOnKeyListener(null)
            }
            if (frag.isRvTop10SeriesInitialized()) {
                seriesChildKeyListener?.let { frag.rvTop10Series.removeOnChildAttachStateChangeListener(it) }
                frag.rvTop10Series.setOnKeyListener(null)
            }
        }
        continueWatchingChildKeyListener = null
        recentLiveChildKeyListener = null
        moviesChildKeyListener = null
        seriesChildKeyListener = null
        fragment = null
    }

    fun installContentRowKeyRouting(
        recyclerView: RecyclerView,
        area: HomeContentFocusArea
    ) {
        val frag = fragment ?: return
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                installContentItemKeyRouting(recyclerView, view, area)
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }
        recyclerView.addOnChildAttachStateChangeListener(listener)
        when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> continueWatchingChildKeyListener = listener
            HomeContentFocusArea.RECENT_LIVE -> recentLiveChildKeyListener = listener
            HomeContentFocusArea.MOVIES -> moviesChildKeyListener = listener
            HomeContentFocusArea.SERIES -> seriesChildKeyListener = listener
            HomeContentFocusArea.HERO -> Unit
        }
    }

    fun installContentItemKeyRouting(
        recyclerView: RecyclerView,
        child: View,
        area: HomeContentFocusArea
    ) {
        val frag = fragment ?: return
        child.setOnKeyListener { itemView, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val position = recyclerView.getChildViewHolder(itemView).bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (position == 0) frag.focusManager.returnToSidebar() else false
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveFocusUpFromArea(area, position)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveFocusDownFromArea(area, position)
                }
                else -> false
            }
        }
    }

    fun refreshContentRowKeyRouting() {
        val frag = fragment ?: return
        if (frag.isRvContinueWatchingInitialized()) {
            frag.rvContinueWatching.post {
                val f = fragment ?: return@post
                for (index in 0 until f.rvContinueWatching.childCount) {
                    installContentItemKeyRouting(f.rvContinueWatching, f.rvContinueWatching.getChildAt(index), HomeContentFocusArea.CONTINUE_WATCHING)
                }
            }
        }
        if (frag.isRvRecentLiveInitialized()) {
            frag.rvRecentLive.post {
                val f = fragment ?: return@post
                for (index in 0 until f.rvRecentLive.childCount) {
                    installContentItemKeyRouting(f.rvRecentLive, f.rvRecentLive.getChildAt(index), HomeContentFocusArea.RECENT_LIVE)
                }
            }
        }
        if (frag.isRvTop10MoviesInitialized()) {
            frag.rvTop10Movies.post {
                val f = fragment ?: return@post
                for (index in 0 until f.rvTop10Movies.childCount) {
                    installContentItemKeyRouting(f.rvTop10Movies, f.rvTop10Movies.getChildAt(index), HomeContentFocusArea.MOVIES)
                }
            }
        }
        if (frag.isRvTop10SeriesInitialized()) {
            frag.rvTop10Series.post {
                val f = fragment ?: return@post
                for (index in 0 until f.rvTop10Series.childCount) {
                    installContentItemKeyRouting(f.rvTop10Series, f.rvTop10Series.getChildAt(index), HomeContentFocusArea.SERIES)
                }
            }
        }
    }

    // Visual rail order (top → bottom): CONTINUE_WATCHING, MOVIES, SERIES, RECENT_LIVE
    fun moveFocusUpFromArea(area: HomeContentFocusArea, position: Int): Boolean {
        val frag = fragment ?: return false
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> {
                frag.focusManager.focusHeroPrimaryButton()
            }
            HomeContentFocusArea.MOVIES -> {
                when {
                    frag.isRvContinueWatchingInitialized() && frag.focusManager.getItemCount(frag.rvContinueWatching) > 0 -> frag.focusManager.requestContentFocus(frag.rvContinueWatching, position)
                    else -> frag.focusManager.focusHeroPrimaryButton()
                }
            }
            HomeContentFocusArea.SERIES -> {
                when {
                    frag.isRvTop10MoviesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Movies) > 0 -> frag.focusManager.requestContentFocus(frag.rvTop10Movies, position)
                    frag.isRvContinueWatchingInitialized() && frag.focusManager.getItemCount(frag.rvContinueWatching) > 0 -> frag.focusManager.requestContentFocus(frag.rvContinueWatching, position)
                    else -> frag.focusManager.focusHeroPrimaryButton()
                }
            }
            HomeContentFocusArea.RECENT_LIVE -> {
                when {
                    frag.isRvTop10SeriesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Series) > 0 -> frag.focusManager.requestContentFocus(frag.rvTop10Series, position)
                    frag.isRvTop10MoviesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Movies) > 0 -> frag.focusManager.requestContentFocus(frag.rvTop10Movies, position)
                    frag.isRvContinueWatchingInitialized() && frag.focusManager.getItemCount(frag.rvContinueWatching) > 0 -> frag.focusManager.requestContentFocus(frag.rvContinueWatching, position)
                    else -> frag.focusManager.focusHeroPrimaryButton()
                }
            }
            HomeContentFocusArea.HERO -> false
        }
    }

    fun moveFocusDownFromArea(area: HomeContentFocusArea, position: Int): Boolean {
        val frag = fragment ?: return false
        return when (area) {
            HomeContentFocusArea.HERO -> {
                frag.focusManager.restoreLastRailFocus() || true
            }
            HomeContentFocusArea.CONTINUE_WATCHING -> {
                when {
                    frag.isRvTop10MoviesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Movies) > 0 -> frag.focusManager.requestContentFocus(frag.rvTop10Movies, position)
                    frag.isRvTop10SeriesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Series) > 0 -> frag.focusManager.requestContentFocus(frag.rvTop10Series, position)
                    frag.isRvRecentLiveInitialized() && frag.focusManager.getItemCount(frag.rvRecentLive) > 0 -> frag.focusManager.requestContentFocus(frag.rvRecentLive, position)
                    else -> true
                }
            }
            HomeContentFocusArea.MOVIES -> {
                when {
                    frag.isRvTop10SeriesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Series) > 0 -> frag.focusManager.requestContentFocus(frag.rvTop10Series, position)
                    frag.isRvRecentLiveInitialized() && frag.focusManager.getItemCount(frag.rvRecentLive) > 0 -> frag.focusManager.requestContentFocus(frag.rvRecentLive, position)
                    else -> true
                }
            }
            HomeContentFocusArea.SERIES -> {
                when {
                    frag.isRvRecentLiveInitialized() && frag.focusManager.getItemCount(frag.rvRecentLive) > 0 -> frag.focusManager.requestContentFocus(frag.rvRecentLive, position)
                    else -> true
                }
            }
            HomeContentFocusArea.RECENT_LIVE -> true
        }
    }
}
