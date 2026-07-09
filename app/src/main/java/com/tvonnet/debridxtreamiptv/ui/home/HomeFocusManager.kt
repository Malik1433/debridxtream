package com.tvonnet.debridxtreamiptv.ui.home

import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

internal enum class HomeContentFocusArea {
    HERO,
    CONTINUE_WATCHING,
    RECENT_LIVE,
    MOVIES,
    SERIES
}

internal data class ContentFocusSnapshot(
    val area: HomeContentFocusArea,
    val stableId: Long?,
    val index: Int
)

internal class HomeFocusManager(private var fragment: HomeFragment?) {

    // Focus state properties relocated from HomeFragment
    internal var didRestoreFocusForThisView = false
    internal var hasAppliedInitialFocus = false
    internal var appliedLoadingFallbackFocus = false
    internal var isContentFocusRequestPending = false
    internal var contentFocusRequestSerial = 0
    internal var suppressNextHeroFocusMemory = false
    internal var lastFocusedRvIndex = 0
    internal var lastFocusedItemIndex = 0
    internal var lastContentFocusArea = HomeContentFocusArea.MOVIES
    internal var lastContinueWatchingIndex = 0
    internal var lastRecentLiveIndex = 0
    internal var lastMovieIndex = 0
    internal var lastSeriesIndex = 0
    internal var activeNavItemId: Int = HomeFragment.HOME_NAV_ITEM_ID
    internal var lastFocusedSidebarItemId: Int = HomeFragment.HOME_NAV_ITEM_ID

    fun cleanup() {
        fragment = null
    }

    fun resetFocusState() {
        hasAppliedInitialFocus = false
        appliedLoadingFallbackFocus = false
        isContentFocusRequestPending = false
        didRestoreFocusForThisView = false
        lastContentFocusArea = HomeContentFocusArea.MOVIES
        lastMovieIndex = 0
        lastSeriesIndex = 0
        lastContinueWatchingIndex = 0
        lastRecentLiveIndex = 0
    }

    fun applyInitialFocusIfNeeded(state: HomeUiState) {
        val frag = fragment ?: return
        if (hasAppliedInitialFocus || !frag.isAdded || frag.view == null) return

        if (state.isLoading) {
            if (frag.view?.findFocus() == null && returnToSidebar()) {
                appliedLoadingFallbackFocus = true
            }
            return
        }

        val currentFocus = frag.view?.findFocus()
        val hasContentRows = state.continueWatching.isNotEmpty() ||
            state.recentLiveChannels.isNotEmpty() ||
            state.top10Movies.isNotEmpty() ||
            state.top10Series.isNotEmpty()
        if (currentFocus != null && !appliedLoadingFallbackFocus && !(hasContentRows && isHeroButtonFocus(currentFocus))) {
            hasAppliedInitialFocus = true
            return
        }

        val focused = when {
            isRememberedContentFocusValid() -> restoreContentFocus()
            state.continueWatching.isNotEmpty() -> requestContentFocus(frag.rvContinueWatching, 0)
            state.recentLiveChannels.isNotEmpty() -> requestContentFocus(frag.rvRecentLive, 0)
            state.top10Movies.isNotEmpty() -> requestContentFocus(frag.rvTop10Movies, 0)
            state.top10Series.isNotEmpty() -> requestContentFocus(frag.rvTop10Series, 0)
            frag.currentHeroItem != null && requestFocusSafely(frag.view?.findViewById(R.id.btn_hero_watch)) -> true
            returnToSidebar() -> true
            else -> requestFocusSafely(frag.rvSidebar)
        }

        if (focused) {
            hasAppliedInitialFocus = true
            appliedLoadingFallbackFocus = false
            didRestoreFocusForThisView = true
        }
    }

    fun isRememberedContentFocusValid(): Boolean {
        val frag = fragment ?: return false
        return when (lastContentFocusArea) {
            HomeContentFocusArea.HERO -> frag.currentHeroItem != null
            HomeContentFocusArea.CONTINUE_WATCHING -> lastContinueWatchingIndex in 0 until getItemCount(frag.rvContinueWatching)
            HomeContentFocusArea.RECENT_LIVE -> lastRecentLiveIndex in 0 until getItemCount(frag.rvRecentLive)
            HomeContentFocusArea.MOVIES -> lastMovieIndex in 0 until getItemCount(frag.rvTop10Movies)
            HomeContentFocusArea.SERIES -> lastSeriesIndex in 0 until getItemCount(frag.rvTop10Series)
        }
    }

    fun isHeroButtonFocus(currentFocus: View): Boolean {
        return currentFocus.id == R.id.btn_hero_watch ||
            currentFocus.id == R.id.btn_hero_details ||
            currentFocus.id == R.id.btn_hero_fav
    }

    fun restoreContentFocus(): Boolean {
        val frag = fragment ?: return false
        if (!frag.isAdded || frag.view == null) return false

        if (lastContentFocusArea == HomeContentFocusArea.HERO && focusHeroPrimaryButton()) {
            return true
        }

        val rememberedRv = recyclerFor(lastContentFocusArea)
        val rememberedIndex = rememberedIndexFor(lastContentFocusArea)
        val rememberedCount = getItemCount(rememberedRv)

        val targetRv = when {
            rememberedCount > 0 -> rememberedRv
            getItemCount(frag.rvContinueWatching) > 0 -> frag.rvContinueWatching
            getItemCount(frag.rvRecentLive) > 0 -> frag.rvRecentLive
            getItemCount(frag.rvTop10Movies) > 0 -> frag.rvTop10Movies
            getItemCount(frag.rvTop10Series) > 0 -> frag.rvTop10Series
            else -> {
                returnToSidebar()
                return false
            }
        }

        val itemCount = getItemCount(targetRv)
        if (itemCount <= 0) {
            returnToSidebar()
            return false
        }

        val targetIndex = if (targetRv === rememberedRv) {
            rememberedIndex.coerceIn(0, itemCount - 1)
        } else {
            0
        }

        targetRv.scrollToPosition(targetIndex)
        targetRv.post {
            val target = targetRv.findViewHolderForAdapterPosition(targetIndex)?.itemView
            if (requestFocusSafely(target)) return@post

            val firstAttachedChild = (0 until targetRv.childCount)
                .asSequence()
                .map { targetRv.getChildAt(it) }
                .firstOrNull { it?.isFocusable == true }
            if (!requestFocusSafely(firstAttachedChild)) {
                returnToSidebar()
            }
        }
        return true
    }

    fun requestContentFocus(targetRv: RecyclerView, targetIndex: Int): Boolean {
        val frag = fragment ?: return false
        val itemCount = getItemCount(targetRv)
        if (itemCount <= 0) return false

        val safeIndex = targetIndex.coerceIn(0, itemCount - 1)
        targetRv.scrollToPosition(safeIndex)
        isContentFocusRequestPending = true
        val requestSerial = ++contentFocusRequestSerial
        
        targetRv.post(object : Runnable {
            var attempts = 0
            override fun run() {
                val f = fragment ?: return
                if (requestSerial != contentFocusRequestSerial) return
                
                val target = targetRv.findViewHolderForAdapterPosition(safeIndex)?.itemView
                if (target != null && target.isShown && target.isFocusable) {
                    isContentFocusRequestPending = false
                    if (!requestFocusSafely(target)) {
                        requestFocusSafely(targetRv.getChildAt(0)) || returnToSidebar()
                    }
                } else if (attempts < 5) {
                    attempts++
                    targetRv.post(this)
                } else {
                    isContentFocusRequestPending = false
                    val lmTarget = targetRv.layoutManager?.findViewByPosition(safeIndex)
                    if (lmTarget != null && requestFocusSafely(lmTarget)) {
                        return
                    }
                    requestFocusSafely(targetRv.getChildAt(0)) || returnToSidebar()
                }
            }
        })
        return true
    }

    fun requestFocusSafely(target: View?): Boolean {
        val frag = fragment ?: return false
        if (!frag.isAdded || frag.view == null || target == null || !target.isShown || !target.isFocusable) {
            return false
        }
        return target.requestFocus()
    }

    fun captureContentFocusSnapshot(): ContentFocusSnapshot? {
        val frag = fragment ?: return null
        val focused = frag.view?.findFocus() ?: return null
        return when {
            isDescendantOf(focused, frag.rvContinueWatching) -> {
                val holder = frag.rvContinueWatching.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.CONTINUE_WATCHING,
                    stableId = frag.continueWatchingAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            isDescendantOf(focused, frag.rvRecentLive) -> {
                val holder = frag.rvRecentLive.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.RECENT_LIVE,
                    stableId = frag.recentLiveAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            isDescendantOf(focused, frag.rvTop10Movies) -> {
                val holder = frag.rvTop10Movies.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.MOVIES,
                    stableId = frag.top10MoviesAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            isDescendantOf(focused, frag.rvTop10Series) -> {
                val holder = frag.rvTop10Series.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.SERIES,
                    stableId = frag.top10SeriesAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            else -> null
        }
    }

    fun restoreContentFocusAfterDataUpdate(snapshot: ContentFocusSnapshot?) {
        val frag = fragment ?: return
        if (snapshot == null || !frag.isAdded || frag.view == null) return

        frag.view?.post {
            val f = fragment ?: return@post
            if (!f.isAdded || f.view == null) return@post
            val currentFocus = f.view?.findFocus()
            if (currentFocus != null && currentFocus.isShown && isFocusedInsideContentRows(currentFocus)) {
                return@post
            }

            val targetRv = recyclerFor(snapshot.area)
            val itemCount = getItemCount(targetRv)
            if (itemCount <= 0) {
                applyInitialFocusIfNeeded(f.viewModel.uiState.value)
                return@post
            }

            val stablePosition = snapshot.stableId?.let { findPositionByStableId(snapshot.area, it) }
                ?: RecyclerView.NO_POSITION
            val targetIndex = if (stablePosition != RecyclerView.NO_POSITION) {
                stablePosition
            } else {
                snapshot.index.coerceIn(0, itemCount - 1)
            }
            requestContentFocus(targetRv, targetIndex)
        }
    }

    fun isDescendantOf(candidate: View?, ancestor: View): Boolean {
        var current = candidate
        while (current != null) {
            if (current === ancestor) return true
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return false
    }

    fun isFocusedInsideContentRows(candidate: View?): Boolean {
        val frag = fragment ?: return false
        return candidate != null && (
            isDescendantOf(candidate, frag.rvContinueWatching) ||
                isDescendantOf(candidate, frag.rvRecentLive) ||
                isDescendantOf(candidate, frag.rvTop10Movies) ||
                isDescendantOf(candidate, frag.rvTop10Series)
            )
    }

    fun recyclerFor(area: HomeContentFocusArea): RecyclerView {
        val frag = fragment ?: throw IllegalStateException("Fragment has been cleared")
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> frag.rvContinueWatching
            HomeContentFocusArea.RECENT_LIVE -> frag.rvRecentLive
            HomeContentFocusArea.MOVIES -> frag.rvTop10Movies
            HomeContentFocusArea.SERIES -> frag.rvTop10Series
            HomeContentFocusArea.HERO -> frag.rvTop10Movies
        }
    }

    fun findPositionByStableId(area: HomeContentFocusArea, stableId: Long): Int {
        val frag = fragment ?: return RecyclerView.NO_POSITION
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> frag.continueWatchingAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.RECENT_LIVE -> frag.recentLiveAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.MOVIES -> frag.top10MoviesAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.SERIES -> frag.top10SeriesAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.HERO -> RecyclerView.NO_POSITION
        }
    }

    fun rememberedIndexFor(area: HomeContentFocusArea): Int {
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> lastContinueWatchingIndex
            HomeContentFocusArea.RECENT_LIVE -> lastRecentLiveIndex
            HomeContentFocusArea.MOVIES -> lastMovieIndex
            HomeContentFocusArea.SERIES -> lastSeriesIndex
            HomeContentFocusArea.HERO -> 0
        }
    }

    fun firstAvailableContentArea(): HomeContentFocusArea? {
        val frag = fragment ?: return null
        return when {
            getItemCount(frag.rvContinueWatching) > 0 -> HomeContentFocusArea.CONTINUE_WATCHING
            getItemCount(frag.rvRecentLive) > 0 -> HomeContentFocusArea.RECENT_LIVE
            getItemCount(frag.rvTop10Movies) > 0 -> HomeContentFocusArea.MOVIES
            getItemCount(frag.rvTop10Series) > 0 -> HomeContentFocusArea.SERIES
            else -> null
        }
    }

    fun restoreContentFocusFromSidebar(): Boolean {
        val frag = fragment ?: return true
        if (!frag.isAdded || frag.view == null) return true

        if (isRememberedContentFocusValid()) {
            when (lastContentFocusArea) {
                HomeContentFocusArea.HERO -> {
                    if (focusHeroPrimaryButton()) return true
                }
                else -> {
                    if (requestContentFocus(recyclerFor(lastContentFocusArea), rememberedIndexFor(lastContentFocusArea))) return true
                }
            }
        }

        firstAvailableContentArea()?.let { area ->
            if (requestContentFocus(recyclerFor(area), 0)) return true
        }

        if (lastContentFocusArea == HomeContentFocusArea.HERO && focusHeroPrimaryButton()) return true

        if (focusHeroPrimaryButton()) return true

        return true
    }

    fun focusHeroPrimaryButton(): Boolean {
        val frag = fragment ?: return false
        if (frag.currentHeroItem == null) return false
        val focused = requestFocusSafely(frag.view?.findViewById(R.id.btn_hero_watch))
        if (focused) {
            // Spec: reaching the hero scrolls the page fully to top
            frag.view?.findViewById<NestedScrollView>(R.id.scroll_content)?.smoothScrollTo(0, 0)
        }
        return focused
    }

    /**
     * Focus memory: DOWN from a hero button returns to the LAST focused rail card
     * (any rail), not card[0].
     */
    fun restoreLastRailFocus(): Boolean {
        val frag = fragment ?: return false
        if (lastContentFocusArea != HomeContentFocusArea.HERO && isRememberedContentFocusValid()) {
            return requestContentFocus(
                recyclerFor(lastContentFocusArea),
                rememberedIndexFor(lastContentFocusArea)
            )
        }
        firstAvailableContentArea()?.let { area ->
            return requestContentFocus(recyclerFor(area), rememberedIndexFor(area))
        }
        return false
    }

    fun getItemCount(recyclerView: RecyclerView): Int {
        return recyclerView.adapter?.itemCount ?: 0
    }

    fun returnToSidebar(): Boolean {
        val frag = fragment ?: return false
        val sidebarRoot = frag.view?.findViewById<RecyclerView>(R.id.rv_sidebar)
        val target = findSidebarTarget(lastFocusedSidebarItemId)
            ?: findSidebarTarget(activeNavItemId)
        return requestFocusSafely(target) || requestFocusSafely(sidebarRoot)
    }

    fun findSidebarTarget(itemId: Int): View? {
        val frag = fragment ?: return null
        if (itemId == HomeFragment.SETTINGS_NAV_ITEM_ID) {
            return frag.view?.findViewById(R.id.sidebar_settings_item)
        }
        return frag.rvSidebar.findViewHolderForItemId(itemId.toLong())?.itemView
    }

    fun rememberContentFocus(area: HomeContentFocusArea, index: Int = 0) {
        val frag = fragment ?: return
        if (area != HomeContentFocusArea.HERO) {
            suppressNextHeroFocusMemory = false
        }
        lastContentFocusArea = area
        when (area) {
            HomeContentFocusArea.HERO -> Unit
            HomeContentFocusArea.CONTINUE_WATCHING -> {
                lastContinueWatchingIndex = index
                lastFocusedRvIndex = 0
                lastFocusedItemIndex = index
            }
            HomeContentFocusArea.RECENT_LIVE -> {
                lastRecentLiveIndex = index
                lastFocusedRvIndex = 1
                lastFocusedItemIndex = index
            }
            HomeContentFocusArea.MOVIES -> {
                lastMovieIndex = index
                lastFocusedRvIndex = 2
                lastFocusedItemIndex = index
            }
            HomeContentFocusArea.SERIES -> {
                lastSeriesIndex = index
                lastFocusedRvIndex = 3
                lastFocusedItemIndex = index
            }
        }
    }

    fun rememberHeroFocusIfUserDriven() {
        if (suppressNextHeroFocusMemory) {
            suppressNextHeroFocusMemory = false
            return
        }
        rememberContentFocus(HomeContentFocusArea.HERO)
    }

    fun handleBackPress(): Boolean {
        val frag = fragment ?: return false
        val scrollContent = frag.view?.findViewById<NestedScrollView>(R.id.scroll_content)

        // Priority 1: Scroll to top if we are scrolled down
        if (scrollContent != null && scrollContent.scrollY > 0) {
            scrollContent.smoothScrollTo(0, 0)
            return true
        }

        // Priority 2: If focus is on content (RVs or Hero), move focus to Sidebar
        val currentFocus = frag.view?.findFocus()
        if (currentFocus != null && isFocusedInsideContentRowsOrHero(currentFocus)) {
            returnToSidebar()
            return true
        }

        return false
    }

    fun isFocusedInsideContentRowsOrHero(candidate: View?): Boolean {
        if (candidate == null) return false
        return isFocusedInsideContentRows(candidate) || isHeroButtonFocus(candidate)
    }
}
