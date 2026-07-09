package com.tvonnet.debridxtreamiptv.ui.home

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Calendar
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

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            tickClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    // ── Hero auto-rotation (3 featured titles, 7s cadence, paused on hero focus) ──
    private var heroRotation: List<FeaturedItem> = emptyList()
    private var heroIdx = 0
    private val heroHandler = Handler(Looper.getMainLooper())
    private val heroRunnable = object : Runnable {
        override fun run() {
            rotateHero()
            heroHandler.postDelayed(this, 7_000L)
        }
    }

    private fun isHeroPaused(): Boolean {
        val focusedId = view?.findFocus()?.id ?: return false
        return focusedId == R.id.btn_hero_watch ||
            focusedId == R.id.btn_hero_details ||
            focusedId == R.id.btn_hero_fav
    }

    private fun rotateHero() {
        if (heroRotation.size < 2 || isHeroPaused()) return
        heroIdx = (heroIdx + 1) % heroRotation.size
        applyHero(animated = true)
    }

    private fun applyHero(animated: Boolean) {
        val item = heroRotation.getOrNull(heroIdx) ?: return
        heroManager.updateHeroSection(item)
        updateHeroDots()
        if (animated) {
            view?.findViewById<View>(R.id.hero_content)?.let { heroContent ->
                heroContent.animate().cancel()
                heroContent.alpha = 0f
                heroContent.translationY = 16f
                heroContent.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun updateHeroDots() {
        val v = view ?: return
        val dotIds = intArrayOf(R.id.dot_hero_0, R.id.dot_hero_1, R.id.dot_hero_2)
        val density = v.resources.displayMetrics.density
        dotIds.forEachIndexed { i, id ->
            val dot = v.findViewById<View>(id) ?: return@forEachIndexed
            dot.isVisible = heroRotation.size > 1 && i < heroRotation.size
            val lp = dot.layoutParams
            lp.width = ((if (i == heroIdx) 11f else 3f) * density).toInt()
            dot.layoutParams = lp
            dot.setBackgroundResource(
                if (i == heroIdx) R.drawable.bg_hero_dot_active else R.drawable.bg_hero_dot
            )
        }
    }

    private fun tickClock() {
        val view = view ?: return
        val tvClock = view.findViewById<TextView>(R.id.tv_status_clock) ?: return
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val min = cal.get(Calendar.MINUTE)
        val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        tvClock.text = String.format("%d:%02d %s", hour, min, amPm)
    }
    
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

        tickClock()
        clockHandler.postDelayed(clockRunnable, 30_000L)
        heroHandler.postDelayed(heroRunnable, 7_000L)
        loadIptvExpiry()

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
                        updateSectionCounts(state)
                        updateHistoryRowVisibility(state)
                        updateHeroNavigationTargets()
                        keyRoutingManager.refreshContentRowKeyRouting()
                        focusManager.restoreContentFocusAfterDataUpdate(focusSnapshot)
                        
                        // Hero rotation pool: 3 featured titles (spec: auto-rotate every 7s)
                        val newRotation = (state.top10Movies + state.top10Series).take(3)
                        if (newRotation != heroRotation) {
                            heroRotation = newRotation
                            heroIdx = 0
                            if (heroRotation.isEmpty()) {
                                heroManager.clearHeroSection()
                                updateHeroDots()
                            } else {
                                applyHero(animated = false)
                            }
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

    private fun updateSectionCounts(state: HomeUiState) {
        view?.findViewById<TextView>(R.id.tv_count_top_10_movies)?.text =
            if (state.top10Movies.isNotEmpty()) "${state.top10Movies.size} TITLES" else ""
        view?.findViewById<TextView>(R.id.tv_count_top_10_series)?.text =
            if (state.top10Series.isNotEmpty()) "${state.top10Series.size} TITLES" else ""
        view?.findViewById<TextView>(R.id.tv_count_recent_live)?.text =
            if (state.recentLiveChannels.isNotEmpty()) "${state.recentLiveChannels.size} TITLES" else ""
        view?.findViewById<TextView>(R.id.tv_count_continue_watching)?.text =
            if (state.continueWatching.isNotEmpty()) "${state.continueWatching.size} TITLES" else ""
    }

    private fun updateHistoryRowVisibility(state: HomeUiState) {
        val hasCW = state.continueWatching.isNotEmpty()
        val hasRL = state.recentLiveChannels.isNotEmpty()
        
        sectionContinueWatching.isVisible = hasCW
        rvContinueWatching.isVisible = hasCW
        
        sectionRecentLive.isVisible = hasRL
        rvRecentLive.isVisible = hasRL
    }

    // ── IPTV expiry chip: bound to real Xtream account expiry ──
    private fun loadIptvExpiry() {
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        if (!repository.isInitialized()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.login(username, password)
            val expSeconds = (result as? com.tvonnet.debridxtreamiptv.data.Result.Success)
                ?.data?.user_info?.exp_date?.toLongOrNull() ?: return@launch
            if (view != null) applyIptvExpiry(expSeconds)
        }
    }

    private fun applyIptvExpiry(expEpochSeconds: Long) {
        val v = view ?: return
        val nowSeconds = System.currentTimeMillis() / 1000L
        val daysLeft = kotlin.math.ceil((expEpochSeconds - nowSeconds) / 86_400.0).toInt()

        val label: String
        val color: Int
        val borderColor: Int
        when {
            daysLeft < 0 -> {
                label = "EXPIRED"
                color = 0xFFFF3355.toInt()
                borderColor = 0x73FF3355
            }
            daysLeft <= 7 -> {
                label = "${daysLeft}D LEFT"
                color = 0xFFFFAA00.toInt()
                borderColor = 0x66FFAA00
            }
            daysLeft <= 30 -> {
                label = "${daysLeft}D LEFT"
                color = 0xFFFFAA00.toInt()
                borderColor = 0x38FFAA00
            }
            else -> {
                label = "${daysLeft / 30}M ${daysLeft % 30}D"
                color = 0xFF00FF88.toInt()
                borderColor = 0x3800FF88
            }
        }

        v.findViewById<TextView>(R.id.tv_iptv_expiry)?.apply {
            text = label
            setTextColor(color)
        }
        v.findViewById<ImageView>(R.id.iv_iptv_icon)?.setColorFilter(color)
        val chip = v.findViewById<View>(R.id.ll_iptv_expiry)
        val bg = chip?.background?.mutate() as? android.graphics.drawable.GradientDrawable
        bg?.setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), borderColor)
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
            onItemFocused = { index, _ ->
                focusManager.rememberContentFocus(HomeContentFocusArea.MOVIES, index)
            },
            onItemClick = { item -> navigationRouter.onFeaturedItemClick(item) },
            onLeftBoundary = { focusManager.returnToSidebar() }
        )
        rvTop10Movies.adapter = top10MoviesAdapter

        top10SeriesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, _ ->
                focusManager.rememberContentFocus(HomeContentFocusArea.SERIES, index)
            },
            onItemClick = { item -> navigationRouter.onFeaturedItemClick(item) },
            onLeftBoundary = { focusManager.returnToSidebar() }
        )
        rvTop10Series.adapter = top10SeriesAdapter
    }

    override fun onDestroyView() {
        clockHandler.removeCallbacks(clockRunnable)
        heroHandler.removeCallbacks(heroRunnable)
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
