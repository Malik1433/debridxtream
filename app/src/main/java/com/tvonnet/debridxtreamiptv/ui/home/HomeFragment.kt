package com.tvonnet.debridxtreamiptv.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import android.util.Log
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment

import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.util.FocusEffects
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private enum class HomeContentFocusArea {
        HERO,
        CONTINUE_WATCHING,
        RECENT_LIVE,
        MOVIES,
        SERIES
    }

    private data class ContentFocusSnapshot(
        val area: HomeContentFocusArea,
        val stableId: Long?,
        val index: Int
    )

    private companion object {
        private const val HOME_NAV_ITEM_ID = 1
        private const val SETTINGS_NAV_ITEM_ID = -1
    }
    
    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var episodeDaoV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var credentialsPrefs: CredentialsPreferences
    
    // UI Components
    private lateinit var ivHeroBackground: ImageView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroDescription: TextView
    private lateinit var rvSidebar: RecyclerView
    private lateinit var rvContinueWatching: RecyclerView
    private lateinit var rvRecentLive: RecyclerView
    private lateinit var rvTop10Movies: RecyclerView
    private lateinit var rvTop10Series: RecyclerView
    private lateinit var sectionContinueWatching: View
    private lateinit var sectionRecentLive: View
    private var didRestoreFocusForThisView = false
    private var hasAppliedInitialFocus = false
    private var appliedLoadingFallbackFocus = false
    private var isContentFocusRequestPending = false
    private var contentFocusRequestSerial = 0
    private var suppressNextHeroFocusMemory = false
    private var lastFocusedRvIndex = 0
    private var lastFocusedItemIndex = 0
    private var lastContentFocusArea = HomeContentFocusArea.MOVIES
    private var lastContinueWatchingIndex = 0
    private var lastRecentLiveIndex = 0
    private var lastMovieIndex = 0
    private var lastSeriesIndex = 0
    private var activeNavItemId: Int = HOME_NAV_ITEM_ID
    private var lastFocusedSidebarItemId: Int = HOME_NAV_ITEM_ID
    private var currentHeroItem: FeaturedItem? = null
    private var isNavigatingFromHome = false
    private var continueWatchingChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    private var recentLiveChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    private var moviesChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    private var seriesChildKeyListener: RecyclerView.OnChildAttachStateChangeListener? = null
    


    
    // Adapters
    private lateinit var sidebarAdapter: SidebarAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var recentLiveAdapter: RecentLiveChannelsAdapter
    private lateinit var top10MoviesAdapter: Top10Adapter
    private lateinit var top10SeriesAdapter: Top10Adapter

    
    override fun onAttach(context: Context) {
        super.onAttach(context)
        android.util.Log.e("HISTORY_DEBUG", "HomeFragment: onAttach")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("HISTORY_DEBUG", "HomeFragment: onCreate")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        android.util.Log.e("HISTORY_DEBUG", "HomeFragment: onCreateView")
        // Use the new Cinematic layout
        return inflater.inflate(R.layout.fragment_home_cinematic, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        android.util.Log.e("HISTORY_DEBUG", "HomeFragment: onViewCreated entry")
        
        credentialsPrefs = CredentialsPreferences(requireContext())
        
        initializeRepository()
        initializeViews(view)
        setupSidebar()
        loadData()
        setupObservers()
        view.post {
            applyInitialFocusIfNeeded(viewModel.uiState.value)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHomeData()
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    android.util.Log.e("HISTORY_DEBUG", "HomeFragment: uiState collected, isLoading=${state.isLoading}")
                    if (!state.isLoading) {
                        val focusSnapshot = captureContentFocusSnapshot()
                        continueWatchingAdapter.updateItems(state.continueWatching)
                        recentLiveAdapter.updateItems(state.recentLiveChannels)
                        top10MoviesAdapter.updateItems(state.top10Movies)
                        top10SeriesAdapter.updateItems(state.top10Series)
                        updateHistoryRowVisibility(state)
                        updateHeroNavigationTargets()
                        refreshContentRowKeyRouting()
                        restoreContentFocusAfterDataUpdate(focusSnapshot)
                        
                        // Update Hero with first available top row item
                        val heroCandidate = state.top10Movies.firstOrNull() ?: state.top10Series.firstOrNull()
                        if (heroCandidate != null) {
                            updateHeroSection(heroCandidate)
                        } else {
                            clearHeroSection()
                        }
                        
                        applyInitialFocusIfNeeded(state)
                    }
                }
            }
        }
    }
    
    private fun initializeRepository() {
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
        }
    }
    
    private fun initializeViews(view: View) {
        ivHeroBackground = view.findViewById(R.id.iv_hero_background)
        tvHeroTitle = view.findViewById(R.id.tv_hero_title)
        tvHeroDescription = view.findViewById(R.id.tv_hero_description)
        
        rvSidebar = view.findViewById(R.id.rv_sidebar)
        sectionContinueWatching = view.findViewById(R.id.section_continue_watching)
        rvContinueWatching = view.findViewById(R.id.rv_continue_watching)
        sectionRecentLive = view.findViewById(R.id.section_recent_live)
        rvRecentLive = view.findViewById(R.id.rv_recent_live_channels)

        rvTop10Movies = view.findViewById(R.id.rv_top_10_movies)
        rvTop10Series = view.findViewById(R.id.rv_top_10_series)

        setupRecyclerViews()
    }
    private fun setupRecyclerViews() {
        // Sidebar
        rvSidebar.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        
        rvContinueWatching.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        // Removed setHasFixedSize(true) to allow dynamic growth

        rvRecentLive.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        // Removed setHasFixedSize(true) to allow dynamic growth

        // Top 10s (Horizontal)
        rvTop10Movies.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvTop10Movies.setHasFixedSize(true)
        
        rvTop10Series.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvTop10Series.setHasFixedSize(true)

        // Null Animators: No-blink/jitter data updates
        rvSidebar.itemAnimator = null
        rvContinueWatching.itemAnimator = null
        rvRecentLive.itemAnimator = null
        rvTop10Movies.itemAnimator = null
        rvTop10Series.itemAnimator = null

        installContentRowKeyRouting(rvContinueWatching, HomeContentFocusArea.CONTINUE_WATCHING)
        installContentRowKeyRouting(rvRecentLive, HomeContentFocusArea.RECENT_LIVE)
        installContentRowKeyRouting(rvTop10Movies, HomeContentFocusArea.MOVIES)
        installContentRowKeyRouting(rvTop10Series, HomeContentFocusArea.SERIES)

        // Add Lateral Routing to Sidebar
        rvSidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                return@setOnKeyListener restoreContentFocusFromSidebar()
            }
            false
        }
    }

    private fun applyInitialFocusIfNeeded(state: HomeUiState) {
        if (hasAppliedInitialFocus || !isAdded || view == null) return

        if (state.isLoading) {
            if (view?.findFocus() == null && returnToSidebar()) {
                appliedLoadingFallbackFocus = true
            }
            return
        }

        val currentFocus = view?.findFocus()
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
            state.continueWatching.isNotEmpty() -> requestContentFocus(rvContinueWatching, 0)
            state.recentLiveChannels.isNotEmpty() -> requestContentFocus(rvRecentLive, 0)
            state.top10Movies.isNotEmpty() -> requestContentFocus(rvTop10Movies, 0)
            state.top10Series.isNotEmpty() -> requestContentFocus(rvTop10Series, 0)
            currentHeroItem != null && requestFocusSafely(view?.findViewById(R.id.btn_hero_watch)) -> true
            returnToSidebar() -> true
            else -> requestFocusSafely(rvSidebar)
        }

        if (focused) {
            hasAppliedInitialFocus = true
            appliedLoadingFallbackFocus = false
            didRestoreFocusForThisView = true
        }
    }

    private fun isRememberedContentFocusValid(): Boolean {
        return when (lastContentFocusArea) {
            HomeContentFocusArea.HERO -> currentHeroItem != null
            HomeContentFocusArea.CONTINUE_WATCHING -> lastContinueWatchingIndex in 0 until getItemCount(rvContinueWatching)
            HomeContentFocusArea.RECENT_LIVE -> lastRecentLiveIndex in 0 until getItemCount(rvRecentLive)
            HomeContentFocusArea.MOVIES -> lastMovieIndex in 0 until getItemCount(rvTop10Movies)
            HomeContentFocusArea.SERIES -> lastSeriesIndex in 0 until getItemCount(rvTop10Series)
        }
    }

    private fun isHeroButtonFocus(currentFocus: View): Boolean {
        return currentFocus.id == R.id.btn_hero_watch || currentFocus.id == R.id.btn_hero_details
    }

    private fun restoreContentFocus(): Boolean {
        if (!isAdded || view == null) return false

        if (lastContentFocusArea == HomeContentFocusArea.HERO && focusHeroPrimaryButton()) {
            return true
        }

        val rememberedRv = recyclerFor(lastContentFocusArea)
        val rememberedIndex = rememberedIndexFor(lastContentFocusArea)
        val rememberedCount = getItemCount(rememberedRv)

        val targetRv = when {
            rememberedCount > 0 -> rememberedRv
            getItemCount(rvContinueWatching) > 0 -> rvContinueWatching
            getItemCount(rvRecentLive) > 0 -> rvRecentLive
            getItemCount(rvTop10Movies) > 0 -> rvTop10Movies
            getItemCount(rvTop10Series) > 0 -> rvTop10Series
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

    private fun requestContentFocus(targetRv: RecyclerView, targetIndex: Int): Boolean {
        val itemCount = getItemCount(targetRv)
        if (itemCount <= 0) return false

        val safeIndex = targetIndex.coerceIn(0, itemCount - 1)
        targetRv.scrollToPosition(safeIndex)
        isContentFocusRequestPending = true
        val requestSerial = ++contentFocusRequestSerial
        targetRv.post {
            if (requestSerial != contentFocusRequestSerial) return@post
            isContentFocusRequestPending = false
            val target = targetRv.findViewHolderForAdapterPosition(safeIndex)?.itemView
            if (!requestFocusSafely(target)) {
                requestFocusSafely(targetRv.getChildAt(0)) || returnToSidebar()
            }
        }
        return true
    }

    private fun requestFocusSafely(target: View?): Boolean {
        if (!isAdded || view == null || target == null || !target.isShown || !target.isFocusable) {
            return false
        }
        return target.requestFocus()
    }

    private fun captureContentFocusSnapshot(): ContentFocusSnapshot? {
        val focused = view?.findFocus() ?: return null
        return when {
            isDescendantOf(focused, rvContinueWatching) -> {
                val holder = rvContinueWatching.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.CONTINUE_WATCHING,
                    stableId = continueWatchingAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            isDescendantOf(focused, rvRecentLive) -> {
                val holder = rvRecentLive.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.RECENT_LIVE,
                    stableId = recentLiveAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            isDescendantOf(focused, rvTop10Movies) -> {
                val holder = rvTop10Movies.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.MOVIES,
                    stableId = top10MoviesAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            isDescendantOf(focused, rvTop10Series) -> {
                val holder = rvTop10Series.findContainingViewHolder(focused) ?: return null
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return null
                ContentFocusSnapshot(
                    area = HomeContentFocusArea.SERIES,
                    stableId = top10SeriesAdapter.getStableItemIdAt(position),
                    index = position
                )
            }
            else -> null
        }
    }

    private fun restoreContentFocusAfterDataUpdate(snapshot: ContentFocusSnapshot?) {
        if (snapshot == null || !isAdded || view == null) return

        view?.post {
            if (!isAdded || view == null) return@post
            val currentFocus = view?.findFocus()
            if (currentFocus != null && currentFocus.isShown && isFocusedInsideContentRows(currentFocus)) {
                return@post
            }

            val targetRv = recyclerFor(snapshot.area)
            val itemCount = getItemCount(targetRv)
            if (itemCount <= 0) {
                applyInitialFocusIfNeeded(viewModel.uiState.value)
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

    private fun isDescendantOf(candidate: View?, ancestor: View): Boolean {
        var current = candidate
        while (current != null) {
            if (current === ancestor) return true
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return false
    }

    private fun isFocusedInsideContentRows(candidate: View?): Boolean {
        return candidate != null && (
            isDescendantOf(candidate, rvContinueWatching) ||
                isDescendantOf(candidate, rvRecentLive) ||
                isDescendantOf(candidate, rvTop10Movies) ||
                isDescendantOf(candidate, rvTop10Series)
            )
    }

    private fun recyclerFor(area: HomeContentFocusArea): RecyclerView {
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> rvContinueWatching
            HomeContentFocusArea.RECENT_LIVE -> rvRecentLive
            HomeContentFocusArea.MOVIES -> rvTop10Movies
            HomeContentFocusArea.SERIES -> rvTop10Series
            HomeContentFocusArea.HERO -> rvTop10Movies
        }
    }

    private fun findPositionByStableId(area: HomeContentFocusArea, stableId: Long): Int {
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> continueWatchingAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.RECENT_LIVE -> recentLiveAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.MOVIES -> top10MoviesAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.SERIES -> top10SeriesAdapter.findPositionByStableId(stableId)
            HomeContentFocusArea.HERO -> RecyclerView.NO_POSITION
        }
    }

    private fun rememberedIndexFor(area: HomeContentFocusArea): Int {
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> lastContinueWatchingIndex
            HomeContentFocusArea.RECENT_LIVE -> lastRecentLiveIndex
            HomeContentFocusArea.MOVIES -> lastMovieIndex
            HomeContentFocusArea.SERIES -> lastSeriesIndex
            HomeContentFocusArea.HERO -> 0
        }
    }

    private fun firstAvailableContentArea(): HomeContentFocusArea? {
        return when {
            getItemCount(rvContinueWatching) > 0 -> HomeContentFocusArea.CONTINUE_WATCHING
            getItemCount(rvRecentLive) > 0 -> HomeContentFocusArea.RECENT_LIVE
            getItemCount(rvTop10Movies) > 0 -> HomeContentFocusArea.MOVIES
            getItemCount(rvTop10Series) > 0 -> HomeContentFocusArea.SERIES
            else -> null
        }
    }

    private fun moveFocusUpFromArea(area: HomeContentFocusArea, position: Int): Boolean {
        return when (area) {
            HomeContentFocusArea.CONTINUE_WATCHING -> {
                when {
                    getItemCount(rvRecentLive) > 0 -> requestContentFocus(rvRecentLive, position)
                    getItemCount(rvTop10Series) > 0 -> requestContentFocus(rvTop10Series, position)
                    getItemCount(rvTop10Movies) > 0 -> requestContentFocus(rvTop10Movies, position)
                    else -> focusHeroPrimaryButton()
                }
            }
            HomeContentFocusArea.RECENT_LIVE -> {
                when {
                    getItemCount(rvTop10Series) > 0 -> requestContentFocus(rvTop10Series, position)
                    getItemCount(rvTop10Movies) > 0 -> requestContentFocus(rvTop10Movies, position)
                    else -> focusHeroPrimaryButton()
                }
            }
            HomeContentFocusArea.SERIES -> {
                when {
                    getItemCount(rvTop10Movies) > 0 -> requestContentFocus(rvTop10Movies, position)
                    else -> focusHeroPrimaryButton()
                }
            }
            HomeContentFocusArea.MOVIES -> {
                focusHeroPrimaryButton()
            }
            HomeContentFocusArea.HERO -> false
        }
    }

    private fun moveFocusDownFromArea(area: HomeContentFocusArea, position: Int): Boolean {
        return when (area) {
            HomeContentFocusArea.HERO -> {
                when {
                    getItemCount(rvTop10Movies) > 0 -> requestContentFocus(rvTop10Movies, position)
                    getItemCount(rvTop10Series) > 0 -> requestContentFocus(rvTop10Series, position)
                    getItemCount(rvRecentLive) > 0 -> requestContentFocus(rvRecentLive, position)
                    getItemCount(rvContinueWatching) > 0 -> requestContentFocus(rvContinueWatching, position)
                    else -> true
                }
            }
            HomeContentFocusArea.MOVIES -> {
                when {
                    getItemCount(rvTop10Series) > 0 -> requestContentFocus(rvTop10Series, position)
                    getItemCount(rvRecentLive) > 0 -> requestContentFocus(rvRecentLive, position)
                    getItemCount(rvContinueWatching) > 0 -> requestContentFocus(rvContinueWatching, position)
                    else -> true
                }
            }
            HomeContentFocusArea.SERIES -> {
                when {
                    getItemCount(rvRecentLive) > 0 -> requestContentFocus(rvRecentLive, position)
                    getItemCount(rvContinueWatching) > 0 -> requestContentFocus(rvContinueWatching, position)
                    else -> true
                }
            }
            HomeContentFocusArea.RECENT_LIVE -> {
                when {
                    getItemCount(rvContinueWatching) > 0 -> requestContentFocus(rvContinueWatching, position)
                    else -> true
                }
            }
            HomeContentFocusArea.CONTINUE_WATCHING -> true
        }
    }

    private fun restoreContentFocusFromSidebar(): Boolean {
        if (!isAdded || view == null) return true

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

    private fun focusHeroPrimaryButton(): Boolean {
        if (currentHeroItem == null) return false
        return requestFocusSafely(view?.findViewById(R.id.btn_hero_watch))
    }

    private fun getItemCount(recyclerView: RecyclerView): Int {
        return recyclerView.adapter?.itemCount ?: 0
    }
    
    private fun returnToSidebar(): Boolean {
        val sidebarRoot = view?.findViewById<RecyclerView>(R.id.rv_sidebar)
        val target = findSidebarTarget(lastFocusedSidebarItemId)
            ?: findSidebarTarget(activeNavItemId)
        return requestFocusSafely(target) || requestFocusSafely(sidebarRoot)
    }

    private fun findSidebarTarget(itemId: Int): View? {
        if (itemId == SETTINGS_NAV_ITEM_ID) {
            return view?.findViewById(R.id.sidebar_settings_item)
        }
        return rvSidebar.findViewHolderForItemId(itemId.toLong())?.itemView
    }

    private fun rememberContentFocus(area: HomeContentFocusArea, index: Int = 0) {
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

    private fun rememberHeroFocusIfUserDriven() {
        if (suppressNextHeroFocusMemory) {
            suppressNextHeroFocusMemory = false
            return
        }
        rememberContentFocus(HomeContentFocusArea.HERO)
    }

    private fun installContentRowKeyRouting(
        recyclerView: RecyclerView,
        area: HomeContentFocusArea
    ) {
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

    private fun installContentItemKeyRouting(
        recyclerView: RecyclerView,
        child: View,
        area: HomeContentFocusArea
    ) {
        child.setOnKeyListener { itemView, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val position = recyclerView.getChildViewHolder(itemView).bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (position == 0) returnToSidebar() else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    moveFocusUpFromArea(area, position)
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveFocusDownFromArea(area, position)
                }
                else -> false
            }
        }
    }

    private fun refreshContentRowKeyRouting() {
        rvContinueWatching.post {
            for (index in 0 until rvContinueWatching.childCount) {
                installContentItemKeyRouting(rvContinueWatching, rvContinueWatching.getChildAt(index), HomeContentFocusArea.CONTINUE_WATCHING)
            }
        }
        rvRecentLive.post {
            for (index in 0 until rvRecentLive.childCount) {
                installContentItemKeyRouting(rvRecentLive, rvRecentLive.getChildAt(index), HomeContentFocusArea.RECENT_LIVE)
            }
        }

        rvTop10Movies.post {
            for (index in 0 until rvTop10Movies.childCount) {
                installContentItemKeyRouting(rvTop10Movies, rvTop10Movies.getChildAt(index), HomeContentFocusArea.MOVIES)
            }
        }
        rvTop10Series.post {
            for (index in 0 until rvTop10Series.childCount) {
                installContentItemKeyRouting(rvTop10Series, rvTop10Series.getChildAt(index), HomeContentFocusArea.SERIES)
            }
        }
    }

    private fun updateHistoryRowVisibility(state: HomeUiState) {
        val hasCW = state.continueWatching.isNotEmpty()
        val hasRL = state.recentLiveChannels.isNotEmpty()
        android.util.Log.e("HISTORY_DEBUG", "updateHistoryRowVisibility: hasCW=$hasCW, hasRL=$hasRL")
        
        sectionContinueWatching.isVisible = hasCW
        rvContinueWatching.isVisible = hasCW
        
        sectionRecentLive.isVisible = hasRL
        rvRecentLive.isVisible = hasRL
    }

    private fun updateHeroNavigationTargets() {
        val heroDownTargetId = firstAvailableContentArea()?.let { recyclerFor(it).id } ?: rvSidebar.id
        view?.findViewById<View>(R.id.btn_hero_watch)?.nextFocusDownId = heroDownTargetId
        view?.findViewById<View>(R.id.btn_hero_details)?.nextFocusDownId = heroDownTargetId
    }
    
    private fun setupSidebar() {
        val menuItems = listOf(
            SidebarItem(0, getString(R.string.nav_search), R.drawable.ic_search),
            SidebarItem(1, getString(R.string.nav_home), R.drawable.ic_home),
            SidebarItem(2, getString(R.string.nav_live_tv), R.drawable.ic_live_tv),
            SidebarItem(3, getString(R.string.nav_movies), R.drawable.ic_movie),
            SidebarItem(4, getString(R.string.nav_series), R.drawable.ic_series),
            SidebarItem(5, getString(R.string.nav_debrid), R.drawable.ic_dns)
        )
        
        sidebarAdapter = SidebarAdapter(
            items = menuItems,
            onItemFocused = { item ->
                lastFocusedSidebarItemId = item.id
            },
            onDpadRight = {
                restoreContentFocusFromSidebar()
            },
            onItemSelected = { position ->
                when (position) {
                    0 -> navigateToSection("search")
                    1 -> {
                        activeNavItemId = HOME_NAV_ITEM_ID
                        sidebarAdapter.setActiveItemId(activeNavItemId)
                    }
                    2 -> navigateToSection("live")
                    3 -> navigateToSection("movies")
                    4 -> navigateToSection("series")
                    5 -> navigateToSection("debrid")
                }
            }
        )
        rvSidebar.adapter = sidebarAdapter
        sidebarAdapter.setActiveItemId(activeNavItemId)

        // Bottom Settings item (same visual style, pinned)
        val settingsItemView = view?.findViewById<android.view.View>(R.id.sidebar_settings_item)
        if (settingsItemView != null) {
            val ivIcon = settingsItemView.findViewById<android.widget.ImageView>(R.id.iv_icon)
            val tvTitle = settingsItemView.findViewById<android.widget.TextView>(R.id.tv_title)
            val indicator = settingsItemView.findViewById<android.view.View>(R.id.view_selection_indicator)

            ivIcon.setImageResource(R.drawable.ic_settings)
            tvTitle.text = getString(R.string.nav_settings)
            indicator.visibility = android.view.View.INVISIBLE

            settingsItemView.setOnClickListener { navigateToSection("settings") }

            // Match list micro-interactions
            settingsItemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    lastFocusedSidebarItemId = SETTINGS_NAV_ITEM_ID
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
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return@setOnKeyListener restoreContentFocusFromSidebar()
                }
                false
            }
        }

        // Week 15 Audit: Standardize Sidebar Focus expansion
        val sidebarPanel = view?.findViewById<android.view.View>(R.id.sidebar_panel)
        val titleArea = view?.findViewById<android.view.View>(R.id.tv_sidebar_app_name)
        
        if (sidebarPanel != null && titleArea != null) {
            com.tvonnet.debridxtreamiptv.utils.SidebarFocusHelper.attachStandardSidebarAnimation(
                sidebarContainer = sidebarPanel,
                focusTrigger = rvSidebar,
                titleArea = titleArea,
                focusGroupRoot = sidebarPanel,
                onExpandedChanged = { expanded ->
                    if (isAdded && view != null) {
                        sidebarAdapter.setExpanded(expanded)
        
                        // Header alignment: logo centers when collapsed, app name fades via titleArea
                        val header = view?.findViewById<android.widget.LinearLayout>(R.id.sidebar_header)
                        val logo = view?.findViewById<android.widget.ImageView>(R.id.iv_sidebar_logo)
                        val appName = view?.findViewById<android.widget.TextView>(R.id.tv_sidebar_app_name)
        
                        if (expanded) {
                            header?.gravity = android.view.Gravity.CENTER_VERTICAL
                            (logo?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                                lp.marginStart = 0
                                logo.layoutParams = lp
                            }
                            appName?.visibility = android.view.View.VISIBLE
        
                            // Settings label visible in expanded mode
                            settingsItemView?.findViewById<android.widget.TextView>(R.id.tv_title)?.apply {
                                alpha = 1f
                                visibility = android.view.View.VISIBLE
                                translationX = -20f
                                animate()
                                    .translationX(0f)
                                    .setDuration(150)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                                    .start()
                            }
                        } else {
                            header?.gravity = android.view.Gravity.CENTER
                            (logo?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                                lp.marginStart = 0
                                logo.layoutParams = lp
                            }
                            appName?.visibility = android.view.View.GONE
        
                            // Settings label hidden in collapsed mode (prevents truncation)
                            settingsItemView?.findViewById<android.widget.TextView>(R.id.tv_title)?.apply {
                                alpha = 0f
                                visibility = android.view.View.GONE
                            }
                        }
                        
                        // Deep Integration: Dim Hero Background aggressively when sidebar is expanded
                        ivHeroBackground.animate()
                            .alpha(if (expanded) 0.3f else 0.8f)
                            .setDuration(250)
                            .start()
                    }
                }
            )
        }
    }
    
    private fun updateHeroSection(item: FeaturedItem) {
        currentHeroItem = item
        tvHeroTitle.text = item.title
        tvHeroDescription.text = item.description ?: "Watch this amazing content on DebridXtream. Cinematic experience." 
        
        val heroUrl = item.backdropUrl ?: item.posterUrl

        Glide.with(this)
            .load(heroUrl)
            .transition(DrawableTransitionOptions.withCrossFade())
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }
            })
            .into(ivHeroBackground)

        // Hero Button Polishing
        val btnWatch = view?.findViewById<View>(R.id.btn_hero_watch)
        val btnDetails = view?.findViewById<View>(R.id.btn_hero_details)
        
        btnWatch?.setOnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.05f)
            v.z = if (hasFocus) 10f else 0f
            if (hasFocus) {
                rememberHeroFocusIfUserDriven()
            }
        }
        btnWatch?.setOnClickListener {
            val heroItem = currentHeroItem
            if (heroItem == null) {
                showHomeActionUnavailable()
            } else {
                onFeaturedItemClick(heroItem)
            }
        }
        btnWatch?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> return@setOnKeyListener returnToSidebar()
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@setOnKeyListener requestContentFocus(rvTop10Movies, lastMovieIndex) ||
                            requestContentFocus(rvTop10Series, lastSeriesIndex)
                    }
                }
            }
            false
        }
        
        btnDetails?.setOnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.05f)
            v.z = if (hasFocus) 10f else 0f
            if (hasFocus) {
                rememberHeroFocusIfUserDriven()
            }
        }
        btnDetails?.setOnClickListener {
            val heroItem = currentHeroItem
            if (heroItem == null) {
                showHomeActionUnavailable()
            } else {
                openFeaturedDetails(heroItem)
            }
        }
        btnDetails?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> return@setOnKeyListener returnToSidebar()
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@setOnKeyListener requestContentFocus(rvTop10Movies, lastMovieIndex) ||
                            requestContentFocus(rvTop10Series, lastSeriesIndex)
                    }
                }
            }
            false
        }
    }

    private fun showHomeActionUnavailable() {
        if (!isAdded) return
        Toast.makeText(requireContext(), "Not available", Toast.LENGTH_SHORT).show()
    }

    private fun startActivityPreservingContentFocus(intent: android.content.Intent) {
        suppressNextHeroFocusMemory = true
        startActivity(intent)
    }
    

    // Navigation and Click Handlers
    fun handleBackPress(): Boolean {
        val scrollContent = view?.findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll_content)
        
        // Priority 1: Scroll to top if we are scrolled down
        if (scrollContent != null && scrollContent.scrollY > 0) {
            scrollContent.smoothScrollTo(0, 0)
            return true
        }

        // Priority 2: If focus is on content (RVs or Hero), move focus to Sidebar
        val currentFocus = view?.findFocus()
        if (currentFocus != null && isFocusedInsideContentRowsOrHero(currentFocus)) {
            returnToSidebar()
            return true
        }

        return false
    }

    private fun isFocusedInsideContentRowsOrHero(candidate: View?): Boolean {
        if (candidate == null) return false
        return isFocusedInsideContentRows(candidate) || isHeroButtonFocus(candidate)
    }

    private fun navigateToSection(section: String) {
        if (isNavigatingFromHome || !isAdded || parentFragmentManager.isStateSaved) return

        val fragment = when (section) {
            "live" -> LiveFragment()
            "movies" -> VodFragment()
            "series" -> SeriesFragment()
            "debrid" -> com.tvonnet.debridxtreamiptv.ui.debrid.DebridFragment()
            "search" -> SearchFragment()
            "settings" -> com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragment()
            else -> return
        }
        isNavigatingFromHome = true
        parentFragmentManager.commit {
            replace(R.id.content_container, fragment)
            addToBackStack(null)
        }
    }

    private fun navigateToFragment(fragment: Fragment) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        isNavigatingFromHome = true
        parentFragmentManager.commit {
            replace(R.id.content_container, fragment)
            addToBackStack(null)
        }
    }
    
    private fun onFeaturedItemClick(item: FeaturedItem) {
        // Update Hero immediately on click as well
        updateHeroSection(item)
        
        if (item.sourceType == SourceType.TMDB) {
            if (item.contentType == ContentType.MOVIE) {
                val intent = android.content.Intent(requireContext(), MovieDetailActivity::class.java).apply {
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.contentId)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                }
                startActivityPreservingContentFocus(intent)
            } else if (item.contentType == ContentType.SERIES) {
                val intent = android.content.Intent(requireContext(), SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.contentId)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                    putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                }
                startActivityPreservingContentFocus(intent)
            } else {
                showHomeActionUnavailable()
            }
        } else {
            // IPTV Routing
            when (item.contentType) {
                ContentType.MOVIE -> {
                    val fragment = MovieDetailFragmentV2.newInstance(
                        streamId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl,
                        plot = item.description,
                        
                        directSource = item.streamUrl
                    )
                    navigateToFragment(fragment)
                }
                ContentType.SERIES -> {
                    val fragment = SeriesDetailFragmentV2.newInstance(
                        seriesId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl
                    )
                    navigateToFragment(fragment)
                }
                ContentType.LIVE_TV -> {
                    launchLiveStream(item.contentId, item.title, item.posterUrl, item.streamUrl)
                }
                else -> showHomeActionUnavailable()
            }
        }
    }

    private fun clearHeroSection() {
        currentHeroItem = null
        tvHeroTitle.text = getString(R.string.section_featured)
        tvHeroDescription.text = ""
        ivHeroBackground.setImageDrawable(null)
    }

    private fun onContinueWatchingItemClick(item: ContinueWatchingItem) {
        val streamUrl = item.streamUrl?.takeIf { it.isNotBlank() }
        val isDebrid = item.source == "debrid"
        val hasResolutionInfo = !item.debridInfoHash.isNullOrBlank() || !item.debridMagnet.isNullOrBlank()
        val expired = item.isExpired()

        android.util.Log.e("HISTORY_DEBUG", "Click: ${item.title} | source=${item.source} | stream=$streamUrl | expired=$expired | hasResInfo=$hasResolutionInfo")

        viewLifecycleOwner.lifecycleScope.launch {
            when (item.contentType) {
                ContentType.MOVIE, ContentType.EPISODE -> {
                    val credentialsPrefs = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext())
                    val serverUrl = credentialsPrefs.getServerUrl() ?: ""

                    // Direct play if URL is valid and not expired, OR if it's debrid and we have re-resolution info
                    // Note: Xtream items are never 'expired' (fixed in model)
                    val canResumeDirectly = (streamUrl != null && !expired) || (isDebrid && hasResolutionInfo)
                    
                    android.util.Log.e("HISTORY_DEBUG", "RESUME_DECISION: canResumeDirectly=$canResumeDirectly | isDebrid=$isDebrid | hasResInfo=$hasResolutionInfo | streamUrl=$streamUrl")

                    if (!canResumeDirectly) {
                        android.util.Log.e("HISTORY_DEBUG", "RESUME_PATH: FALLBACK to Detail (canResumeDirectly=false)")
                        if (item.contentType == ContentType.MOVIE) {
                            if (isDebrid) {
                                val movieIntent = android.content.Intent(requireContext(), MovieDetailActivity::class.java).apply {
                                    putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.tmdbId ?: item.contentId)
                                    putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                                    putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                                    putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                                    putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                                }
                                startActivityPreservingContentFocus(movieIntent)
                            } else {
                                val fragment = MovieDetailFragmentV2.newInstance(
                                    streamId = item.contentId,
                                    title = item.title,
                                    backdropUrl = item.backdropUrl,
                                    posterUrl = item.posterUrl
                                )
                                navigateToFragment(fragment)
                            }
                        } else {
                            if (isDebrid) {
                                val seriesIntent = android.content.Intent(requireContext(), SeriesDetailActivity::class.java).apply {
                                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.tmdbId ?: item.contentId)
                                    putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.seriesTitle ?: item.title)
                                    putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                                    putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                                    putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                                }
                                startActivityPreservingContentFocus(seriesIntent)
                            } else {
                                val resolvedSeriesId = resolveIptvSeriesIdForContinueWatching(item)
                                val fragment = SeriesDetailFragmentV2.newInstance(
                                    seriesId = resolvedSeriesId ?: item.contentId,
                                    title = item.seriesTitle ?: item.title,
                                    backdropUrl = item.backdropUrl,
                                    posterUrl = item.posterUrl
                                )
                                navigateToFragment(fragment)
                            }
                        }
                        return@launch
                    }

                    android.util.Log.e("HISTORY_DEBUG", "RESUME_PATH: DIRECT to PlayerActivity")
                    val resumeSeriesId = if (!isDebrid && item.contentType == ContentType.EPISODE) {
                        resolveIptvSeriesIdForContinueWatching(item)
                    } else {
                        null
                    }
                    android.util.Log.e(
                        "TASK030_CW_SERIES",
                        "DIRECT_RESUME type=${item.contentType} source=${item.source} contentId=${item.contentId} seriesId=$resumeSeriesId season=${item.seasonNumber} episode=${item.episodeNumber}"
                    )
                    val intent = PlayerActivity.createIntent(
                        context = requireContext(),
                        streamUrl = streamUrl ?: "",
                        title = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title,
                        startPositionMs = item.currentPosition,
                        contentId = item.tmdbId?.takeIf { it.isNotBlank() } ?: item.contentId,
                        contentType = item.contentType,
                        playbackSource = if (isDebrid) {
                            com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID
                        } else {
                            com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.IPTV
                        },
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        tmdbId = item.tmdbId,
                        imdbId = item.imdbId,
                        seriesTitle = item.seriesTitle,
                        episodeTitle = item.episodeTitle,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                        debridInfoHash = item.debridInfoHash,
                        debridMagnet = item.debridMagnet,
                        expiresAt = item.expiresAt,
                        baseServerUrl = serverUrl,
                        seriesId = resumeSeriesId
                    )
                    startActivityPreservingContentFocus(intent)
                }
                ContentType.SERIES -> {
                    val seriesId = if (isDebrid) {
                        item.tmdbId?.takeIf { it.isNotBlank() } ?: item.contentId
                    } else {
                        resolveIptvSeriesIdForContinueWatching(item) ?: item.contentId
                    }
                    if (seriesId == null) {
                        showHomeActionUnavailable()
                        return@launch
                    }
                    if (isDebrid) {
                        val seriesIntent = android.content.Intent(requireContext(), SeriesDetailActivity::class.java).apply {
                            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
                            putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.seriesTitle ?: item.title)
                            putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                            putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                            putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                        }
                        startActivityPreservingContentFocus(seriesIntent)
                    } else {
                        val fragment = com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2.newInstance(
                            seriesId = seriesId,
                            title = item.seriesTitle ?: item.title,
                            backdropUrl = item.backdropUrl,
                            posterUrl = item.posterUrl
                        )
                        navigateToFragment(fragment)
                    }
                }
                else -> {
                    android.util.Log.w("HISTORY_DEBUG", "Unsupported content type for resume: ${item.contentType}")
                    showHomeActionUnavailable()
                }
            }
        }
    }

    private suspend fun resolveIptvSeriesIdForContinueWatching(item: ContinueWatchingItem): String? {
        item.seriesId?.takeIf { it.isNotBlank() }?.let { return it }
        if (item.source == "debrid") return null
        if (item.contentType != ContentType.EPISODE && item.contentType != ContentType.SERIES) return null
        val resolved = withContext(Dispatchers.IO) {
            runCatching { episodeDaoV2.getEpisodeById(item.contentId)?.seriesId }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        Log.d(
            "TASK030_CW_SERIES",
            "RESOLVE_SERIES_ID contentId=${item.contentId} saved=${item.seriesId} resolved=$resolved"
        )
        return resolved
    }

    private fun onRecentLiveItemClick(item: RecentLiveChannelItem) {
        android.util.Log.e("HISTORY_DEBUG", "Click Recent Live: ${item.channelName} | id=${item.channelId} | stream=${item.streamUrl}")
        launchLiveStream(
            streamId = item.channelId,
            fallbackTitle = item.channelName,
            fallbackLogo = item.channelLogo,
            fallbackUrl = item.streamUrl,
            epgChannelId = item.epgChannelId
        )
    }

    private fun openFeaturedDetails(item: FeaturedItem) {
        if (item.sourceType == SourceType.TMDB) {
            when (item.contentType) {
                ContentType.MOVIE -> {
                    val intent = android.content.Intent(requireContext(), MovieDetailActivity::class.java).apply {
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.contentId)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                    }
                    startActivityPreservingContentFocus(intent)
                }
                ContentType.SERIES -> {
                    val intent = android.content.Intent(requireContext(), SeriesDetailActivity::class.java).apply {
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.contentId)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                        putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                    }
                    startActivityPreservingContentFocus(intent)
                }
                else -> showHomeActionUnavailable()
            }
        } else {
            // IPTV Details
            when (item.contentType) {
                ContentType.MOVIE -> {
                    val fragment = MovieDetailFragmentV2.newInstance(
                        streamId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl,
                        plot = item.description,
                        
                        directSource = item.streamUrl
                    )
                    navigateToFragment(fragment)
                }
                ContentType.SERIES -> {
                    val fragment = SeriesDetailFragmentV2.newInstance(
                        seriesId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl
                    )
                    navigateToFragment(fragment)
                }
                else -> showHomeActionUnavailable()
            }
        }
    }

    private fun launchLiveStream(
        streamId: String?,
        fallbackTitle: String?,
        fallbackLogo: String?,
        fallbackUrl: String?,
        epgChannelId: String? = null
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
        val stream = streamId?.let { repository.getLiveStreamById(it) }
        val serverUrl = credentialsPrefs.getServerUrl()
        val resolvedUrl = when {
            stream != null && !serverUrl.isNullOrBlank() -> repository.buildLiveStreamUrl(stream, serverUrl)
            else -> fallbackUrl
        }

        if (resolvedUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Stream unavailable", Toast.LENGTH_SHORT).show()
                return@launch
        }

        val finalServerUrl = serverUrl ?: ""
        val absoluteIcon = (stream?.stream_icon ?: fallbackLogo).toAbsoluteUrl(finalServerUrl)

        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = resolvedUrl,
            title = fallbackTitle ?: stream?.name ?: getString(R.string.player_epg_channel_unknown),
            channelName = stream?.name ?: fallbackTitle,
            channelLogo = absoluteIcon,
            epgChannelId = stream?.epg_channel_id?.takeIf { it.isNotBlank() } ?: epgChannelId ?: streamId,
            contentId = stream?.stream_id ?: streamId ?: resolvedUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = absoluteIcon,
            liveCategoryId = stream?.category_id
        )
            startActivityPreservingContentFocus(intent)
        }
    }


    private fun loadData() {
        // Initialize Adapters with empty lists first
        continueWatchingAdapter = ContinueWatchingAdapter(
            items = emptyList(),
            onItemClick = { item -> onContinueWatchingItemClick(item) },
            onItemFocused = { index, _ ->
                rememberContentFocus(HomeContentFocusArea.CONTINUE_WATCHING, index)
            }
        )
        rvContinueWatching.adapter = continueWatchingAdapter

        recentLiveAdapter = RecentLiveChannelsAdapter(
            items = emptyList(),
            onItemClick = { item -> onRecentLiveItemClick(item) },
            onItemFocused = { index, _ ->
                rememberContentFocus(HomeContentFocusArea.RECENT_LIVE, index)
            }
        )
        rvRecentLive.adapter = recentLiveAdapter

        top10MoviesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, item -> 
                rememberContentFocus(HomeContentFocusArea.MOVIES, index)
                updateHeroSection(item) 
            },
            onItemClick = { item -> onFeaturedItemClick(item) },
            onLeftBoundary = { returnToSidebar() }
        )
        rvTop10Movies.adapter = top10MoviesAdapter

        top10SeriesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, item -> 
                rememberContentFocus(HomeContentFocusArea.SERIES, index)
                updateHeroSection(item) 
            },
            onItemClick = { item -> onFeaturedItemClick(item) },
            onLeftBoundary = { returnToSidebar() }
        )
        rvTop10Series.adapter = top10SeriesAdapter
    }


    override fun onDestroyView() {
        if (::rvContinueWatching.isInitialized) {
            continueWatchingChildKeyListener?.let { rvContinueWatching.removeOnChildAttachStateChangeListener(it) }
            continueWatchingChildKeyListener = null
            rvContinueWatching.setOnKeyListener(null)
            rvContinueWatching.adapter = null
        }
        if (::rvRecentLive.isInitialized) {
            recentLiveChildKeyListener?.let { rvRecentLive.removeOnChildAttachStateChangeListener(it) }
            recentLiveChildKeyListener = null
            rvRecentLive.setOnKeyListener(null)
            rvRecentLive.adapter = null
        }
        if (::rvTop10Movies.isInitialized) {
            moviesChildKeyListener?.let { rvTop10Movies.removeOnChildAttachStateChangeListener(it) }
            moviesChildKeyListener = null
            rvTop10Movies.setOnKeyListener(null)
            rvTop10Movies.adapter = null
        }
        if (::rvTop10Series.isInitialized) {
            seriesChildKeyListener?.let { rvTop10Series.removeOnChildAttachStateChangeListener(it) }
            seriesChildKeyListener = null
            rvTop10Series.setOnKeyListener(null)
            rvTop10Series.adapter = null
        }
        if (::rvSidebar.isInitialized) {
            rvSidebar.setOnKeyListener(null)
            rvSidebar.adapter = null
        }

        view?.findViewById<View>(R.id.btn_hero_watch)?.apply {
            setOnClickListener(null)
            setOnFocusChangeListener(null)
            setOnKeyListener(null)
            animate().cancel()
        }
        view?.findViewById<View>(R.id.btn_hero_details)?.apply {
            setOnClickListener(null)
            setOnFocusChangeListener(null)
            setOnKeyListener(null)
            animate().cancel()
        }
        view?.findViewById<View>(R.id.sidebar_settings_item)?.apply {
            setOnClickListener(null)
            setOnFocusChangeListener(null)
            setOnKeyListener(null)
            animate().cancel()
        }

        hasAppliedInitialFocus = false
        appliedLoadingFallbackFocus = false
        isContentFocusRequestPending = false
        didRestoreFocusForThisView = false
        currentHeroItem = null
        isNavigatingFromHome = false
        lastContentFocusArea = HomeContentFocusArea.MOVIES
        lastMovieIndex = 0
        lastSeriesIndex = 0
        lastContinueWatchingIndex = 0
        lastRecentLiveIndex = 0
        super.onDestroyView()
    }
}
