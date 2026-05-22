package com.tvonnet.debridxtreamiptv.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    internal companion object {
        internal const val HOME_NAV_ITEM_ID = 1
        internal const val SETTINGS_NAV_ITEM_ID = -1
    }
    
    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var episodeDaoV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2

    internal val viewModel: HomeViewModel by viewModels()
    internal lateinit var credentialsPrefs: CredentialsPreferences
    
    // UI Components (Internal for manager visibility)
    internal lateinit var ivHeroBackground: ImageView
    internal lateinit var tvHeroTitle: TextView
    internal lateinit var tvHeroDescription: TextView
    internal lateinit var rvSidebar: RecyclerView
    internal lateinit var rvContinueWatching: RecyclerView
    internal lateinit var rvRecentLive: RecyclerView
    internal lateinit var rvTop10Movies: RecyclerView
    internal lateinit var rvTop10Series: RecyclerView
    internal lateinit var sectionContinueWatching: View
    internal lateinit var sectionRecentLive: View

    internal var currentHeroItem: FeaturedItem? = null
    internal var isNavigatingFromHome = false
    
    internal fun isRvContinueWatchingInitialized() = ::rvContinueWatching.isInitialized
    internal fun isRvRecentLiveInitialized() = ::rvRecentLive.isInitialized
    internal fun isRvTop10MoviesInitialized() = ::rvTop10Movies.isInitialized
    internal fun isRvTop10SeriesInitialized() = ::rvTop10Series.isInitialized
    internal fun isRvSidebarInitialized() = ::rvSidebar.isInitialized
    internal fun isIvHeroBackgroundInitialized() = ::ivHeroBackground.isInitialized

    // Adapters (Internal)
    internal lateinit var sidebarAdapter: SidebarAdapter
    internal lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    internal lateinit var recentLiveAdapter: RecentLiveChannelsAdapter
    internal lateinit var top10MoviesAdapter: Top10Adapter
    internal lateinit var top10SeriesAdapter: Top10Adapter

    // Managers
    internal lateinit var navigationRouter: HomeNavigationRouter
    internal lateinit var sidebarManager: HomeSidebarManager
    internal lateinit var heroManager: HomeHeroManager
    internal lateinit var focusManager: HomeFocusManager
    internal lateinit var keyRoutingManager: HomeKeyRoutingManager

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
    ): View = inflater.inflate(R.layout.fragment_home_cinematic, container, false)
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        credentialsPrefs = CredentialsPreferences(requireContext())
        
        // 1. Initialize specialized managers in sequential order
        navigationRouter = HomeNavigationRouter(this)
        sidebarManager = HomeSidebarManager(this)
        heroManager = HomeHeroManager(this)
        focusManager = HomeFocusManager(this)
        keyRoutingManager = HomeKeyRoutingManager(this)
        
        initializeRepository()
        initializeViews(view)
        
        // 2. Setup sidebar first
        sidebarManager.setupSidebar()
        
        loadData()
        setupObservers()
        
        view.post {
            focusManager.applyInitialFocusIfNeeded(viewModel.uiState.value)
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
                    if (!state.isLoading) {
                        val focusSnapshot = focusManager.captureContentFocusSnapshot()
                        continueWatchingAdapter.updateItems(state.continueWatching)
                        recentLiveAdapter.updateItems(state.recentLiveChannels)
                        top10MoviesAdapter.updateItems(state.top10Movies)
                        top10SeriesAdapter.updateItems(state.top10Series)
                        updateHistoryRowVisibility(state)
                        updateHeroNavigationTargets()
                        keyRoutingManager.refreshContentRowKeyRouting()
                        focusManager.restoreContentFocusAfterDataUpdate(focusSnapshot)
                        
                        // Update Hero with first available top row item
                        val heroCandidate = state.top10Movies.firstOrNull() ?: state.top10Series.firstOrNull()
                        if (heroCandidate != null) {
                            heroManager.updateHeroSection(heroCandidate)
                        } else {
                            heroManager.clearHeroSection()
                        }
                        
                        focusManager.applyInitialFocusIfNeeded(state)
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
        rvSidebar.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        rvContinueWatching.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvRecentLive.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        
        rvTop10Movies.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvTop10Movies.setHasFixedSize(true)
        
        rvTop10Series.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvTop10Series.setHasFixedSize(true)

        rvSidebar.itemAnimator = null
        rvContinueWatching.itemAnimator = null
        rvRecentLive.itemAnimator = null
        rvTop10Movies.itemAnimator = null
        rvTop10Series.itemAnimator = null

        keyRoutingManager.installContentRowKeyRouting(rvContinueWatching, HomeContentFocusArea.CONTINUE_WATCHING)
        keyRoutingManager.installContentRowKeyRouting(rvRecentLive, HomeContentFocusArea.RECENT_LIVE)
        keyRoutingManager.installContentRowKeyRouting(rvTop10Movies, HomeContentFocusArea.MOVIES)
        keyRoutingManager.installContentRowKeyRouting(rvTop10Series, HomeContentFocusArea.SERIES)

        rvSidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                return@setOnKeyListener focusManager.restoreContentFocusFromSidebar()
            }
            false
        }
    }

    private fun updateHistoryRowVisibility(state: HomeUiState) {
        val hasCW = state.continueWatching.isNotEmpty()
        val hasRL = state.recentLiveChannels.isNotEmpty()
        
        sectionContinueWatching.isVisible = hasCW
        rvContinueWatching.isVisible = hasCW
        
        sectionRecentLive.isVisible = hasRL
        rvRecentLive.isVisible = hasRL
    }

    private fun updateHeroNavigationTargets() {
        val heroDownTargetId = focusManager.firstAvailableContentArea()?.let { focusManager.recyclerFor(it).id } ?: rvSidebar.id
        view?.findViewById<View>(R.id.btn_hero_watch)?.nextFocusDownId = heroDownTargetId
        view?.findViewById<View>(R.id.btn_hero_details)?.nextFocusDownId = heroDownTargetId
    }

    fun handleBackPress(): Boolean {
        return focusManager.handleBackPress()
    }

    private fun loadData() {
        continueWatchingAdapter = ContinueWatchingAdapter(
            items = emptyList(),
            onItemClick = { item -> navigationRouter.onContinueWatchingItemClick(item) },
            onItemFocused = { index, _ ->
                focusManager.rememberContentFocus(HomeContentFocusArea.CONTINUE_WATCHING, index)
            },
            onItemLongPress = { item -> navigationRouter.showContinueWatchingActions(item) }
        )
        rvContinueWatching.adapter = continueWatchingAdapter

        recentLiveAdapter = RecentLiveChannelsAdapter(
            items = emptyList(),
            onItemClick = { item -> navigationRouter.onRecentLiveItemClick(item) },
            onItemFocused = { index, _ ->
                focusManager.rememberContentFocus(HomeContentFocusArea.RECENT_LIVE, index)
            }
        )
        rvRecentLive.adapter = recentLiveAdapter

        top10MoviesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, item -> 
                focusManager.rememberContentFocus(HomeContentFocusArea.MOVIES, index)
                heroManager.updateHeroSection(item) 
            },
            onItemClick = { item -> navigationRouter.onFeaturedItemClick(item) },
            onLeftBoundary = { focusManager.returnToSidebar() }
        )
        rvTop10Movies.adapter = top10MoviesAdapter

        top10SeriesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, item -> 
                focusManager.rememberContentFocus(HomeContentFocusArea.SERIES, index)
                heroManager.updateHeroSection(item) 
            },
            onItemClick = { item -> navigationRouter.onFeaturedItemClick(item) },
            onLeftBoundary = { focusManager.returnToSidebar() }
        )
        rvTop10Series.adapter = top10SeriesAdapter
    }

    override fun onDestroyView() {
        if (::navigationRouter.isInitialized) navigationRouter.cleanup()
        if (::sidebarManager.isInitialized) sidebarManager.cleanup()
        if (::heroManager.isInitialized) heroManager.cleanup()
        if (::focusManager.isInitialized) focusManager.cleanup()
        if (::keyRoutingManager.isInitialized) keyRoutingManager.cleanup()
        
        if (::rvSidebar.isInitialized) rvSidebar.adapter = null
        if (::rvContinueWatching.isInitialized) rvContinueWatching.adapter = null
        if (::rvRecentLive.isInitialized) rvRecentLive.adapter = null
        if (::rvTop10Movies.isInitialized) rvTop10Movies.adapter = null
        if (::rvTop10Series.isInitialized) rvTop10Series.adapter = null
        
        currentHeroItem = null
        isNavigatingFromHome = false
        
        super.onDestroyView()
    }
}
