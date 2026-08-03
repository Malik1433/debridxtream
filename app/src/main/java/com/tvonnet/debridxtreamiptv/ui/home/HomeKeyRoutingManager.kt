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
        if (area == HomeContentFocusArea.HERO) return false
        // Nearest populated rail above (same column), else up to the hero.
        return focusFirstPopulatedRail(frag, RAILS_ABOVE.getValue(area), position)
            ?: frag.focusManager.focusHeroPrimaryButton()
    }

    fun moveFocusDownFromArea(area: HomeContentFocusArea, position: Int): Boolean {
        val frag = fragment ?: return false
        if (area == HomeContentFocusArea.HERO) {
            return frag.focusManager.restoreLastRailFocus() || true
        }
        // Nearest populated rail below; from the bottom (or when nothing is below) the key
        // is consumed so focus can't escape the content column downwards.
        return focusFirstPopulatedRail(frag, RAILS_BELOW.getValue(area), position) ?: true
    }

    // Focus the first populated rail from the ordered candidates, keeping the column position.
    // Null when no candidate rail can take focus — the caller decides the fallback.
    private fun focusFirstPopulatedRail(
        frag: HomeFragment,
        rails: List<HomeContentFocusArea>,
        position: Int
    ): Boolean? {
        val target = rails.firstOrNull { railHasItems(frag, it) } ?: return null
        val rv = railRecycler(frag, target) ?: return null
        return frag.focusManager.requestContentFocus(rv, position)
    }

    // A rail can take focus only when its view is inflated AND it has items.
    private fun railHasItems(frag: HomeFragment, rail: HomeContentFocusArea): Boolean = when (rail) {
        HomeContentFocusArea.CONTINUE_WATCHING ->
            frag.isRvContinueWatchingInitialized() && frag.focusManager.getItemCount(frag.rvContinueWatching) > 0
        HomeContentFocusArea.RECENT_LIVE ->
            frag.isRvRecentLiveInitialized() && frag.focusManager.getItemCount(frag.rvRecentLive) > 0
        HomeContentFocusArea.MOVIES ->
            frag.isRvTop10MoviesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Movies) > 0
        HomeContentFocusArea.SERIES ->
            frag.isRvTop10SeriesInitialized() && frag.focusManager.getItemCount(frag.rvTop10Series) > 0
        HomeContentFocusArea.HERO -> false
    }

    private fun railRecycler(frag: HomeFragment, rail: HomeContentFocusArea): RecyclerView? = when (rail) {
        HomeContentFocusArea.CONTINUE_WATCHING -> frag.rvContinueWatching
        HomeContentFocusArea.RECENT_LIVE -> frag.rvRecentLive
        HomeContentFocusArea.MOVIES -> frag.rvTop10Movies
        HomeContentFocusArea.SERIES -> frag.rvTop10Series
        HomeContentFocusArea.HERO -> null
    }

    private companion object {
        // The old per-area when-chains as data: candidate rails in the order they were tried.
        val RAILS_ABOVE = mapOf(
            HomeContentFocusArea.CONTINUE_WATCHING to emptyList(),
            HomeContentFocusArea.MOVIES to listOf(HomeContentFocusArea.CONTINUE_WATCHING),
            HomeContentFocusArea.SERIES to listOf(
                HomeContentFocusArea.MOVIES, HomeContentFocusArea.CONTINUE_WATCHING
            ),
            HomeContentFocusArea.RECENT_LIVE to listOf(
                HomeContentFocusArea.SERIES, HomeContentFocusArea.MOVIES, HomeContentFocusArea.CONTINUE_WATCHING
            ),
        )
        val RAILS_BELOW = mapOf(
            HomeContentFocusArea.CONTINUE_WATCHING to listOf(
                HomeContentFocusArea.MOVIES, HomeContentFocusArea.SERIES, HomeContentFocusArea.RECENT_LIVE
            ),
            HomeContentFocusArea.MOVIES to listOf(
                HomeContentFocusArea.SERIES, HomeContentFocusArea.RECENT_LIVE
            ),
            HomeContentFocusArea.SERIES to listOf(HomeContentFocusArea.RECENT_LIVE),
            HomeContentFocusArea.RECENT_LIVE to emptyList(),
        )
    }
}
