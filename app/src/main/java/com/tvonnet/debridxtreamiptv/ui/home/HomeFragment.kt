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
class HomeFragment : Fragment(), HomeRouterHost {

    // ── HomeRouterHost ── the TV Home's half of the contract described on the interface.
    override val routerFragment: Fragment get() = this
    override val routerRepository get() = repository
    override val routerCredentials get() = credentialsPrefs
    override val routerEpisodeDao get() = episodeDaoV2
    override fun routerMayNavigate(): Boolean = !isNavigatingFromHome
    override fun onRouterNavigationCommitted() { isNavigatingFromHome = true }
    override fun onRouterHeroSelected(item: FeaturedItem) { heroManager.updateHeroSection(item) }
    override fun onRouterLeavingForActivity() { focusManager.suppressNextHeroFocusMemory = true }

    internal companion object {
        internal const val HOME_NAV_ITEM_ID = 1
        internal const val SETTINGS_NAV_ITEM_ID = -1

        /** Once per app open (process): the big ≤30-days renewal reminder. */
        private var expiryWarningShown = false
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
        tvClock.text = String.format(java.util.Locale.US, "%d:%02d %s", hour, min, amPm)
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
        // Only the history rows — see refreshHistoryRows. Rebuilding the whole screen here is what
        // made returning from a video flash: the rails swapped out and back, and the hero reloaded
        // and jumped to slide 1. New catalog content still arrives on its own, via the sync observer.
        viewModel.refreshHistoryRows()
        updateStatusBadge()
    }

    /**
     * Header status chip reflects the device's tier: premium (or enforcement off)
     * shows the full "DEBRID · IPTV", a trial device shows the days left, and a
     * normal (IPTV-only) device never sees the word Debrid.
     */
    private fun updateStatusBadge() {
        val badge = view?.findViewById<TextView>(R.id.tv_status_badge) ?: return
        val lm = com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager.getInstance(requireContext())
        badge.text = when {
            lm.isTrialActive() -> "TRIAL · ${lm.trialDaysLeft()}D LEFT"
            // Reads CONFIGURED, not allowed: with one tier every device is allowed Debrid, so
            // "DEBRID · IPTV" on a device with no addons would be telling the customer they have
            // something they do not.
            com.tvonnet.debridxtreamiptv.data.licensing.Entitlements.isDebridConfigured(requireContext()) -> "DEBRID · IPTV"
            else -> "IPTV"
        }
        // Permanent device key, always readable from the home screen.
        view?.findViewById<TextView>(R.id.tv_device_key)?.text = lm.activationCode
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { rawState ->
                    // A device with no debrid addons must not show debrid-sourced Continue Watching
                    // entries — they would be rows that cannot play. (Under the old tier policy this
                    // read isDebridAllowed; with one tier the honest test is whether the customer
                    // actually has a debrid service.)
                    val state = if (com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
                            .isDebridConfigured(requireContext())
                    ) rawState
                    else rawState.copy(
                        continueWatching = rawState.continueWatching.filter { it.source != "debrid" }
                    )
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

        // M15: home's own clock / key / expiry strip is TV chrome — a television has no system
        // status bar. On a phone it duplicates the system clock AND the hero's two-line title
        // renders straight through it. See R.bool.home_shows_tv_status_strip for the measurement.
        view.findViewById<View>(R.id.status_bar)?.isVisible =
            resources.getBoolean(R.bool.home_shows_tv_status_strip)
        
        rvSidebar = view.findViewById(R.id.rv_sidebar)
        sectionContinueWatching = view.findViewById(R.id.section_continue_watching)
        rvContinueWatching = view.findViewById(R.id.rv_continue_watching)
        sectionRecentLive = view.findViewById(R.id.section_recent_live)
        rvRecentLive = view.findViewById(R.id.rv_recent_live_channels)

        rvTop10Movies = view.findViewById(R.id.rv_top_10_movies)
        rvTop10Series = view.findViewById(R.id.rv_top_10_series)

        // M14: D-pad legends are a 10-foot idiom and the phone rulebook bans them outright
        // ("no focus-first, no D-pad legends"). The layout is shared with TV now, so the hint bar
        // is hidden here rather than removed.
        if (!resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            view.findViewById<View>(R.id.hint_bar)?.visibility = View.GONE
        }

        setupRecyclerViews()
    }

    private fun setupRecyclerViews() {
        // M2: the nav is a left rail on TV and a bottom bar on the phone. The layout
        // qualifier already picks the geometry; this reads the same decision as a bool so
        // no mode has to be plumbed through the fragment.
        val navOrientation =
            if (resources.getBoolean(R.bool.home_nav_is_horizontal)) LinearLayoutManager.HORIZONTAL
            else LinearLayoutManager.VERTICAL
        rvSidebar.layoutManager = LinearLayoutManager(context, navOrientation, false)
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
            if (state.top10Movies.isNotEmpty()) getString(R.string.code_titles_count, state.top10Movies.size.toString()) else ""
        view?.findViewById<TextView>(R.id.tv_count_top_10_series)?.text =
            if (state.top10Series.isNotEmpty()) getString(R.string.code_titles_count, state.top10Series.size.toString()) else ""
        view?.findViewById<TextView>(R.id.tv_count_recent_live)?.text =
            if (state.recentLiveChannels.isNotEmpty()) getString(R.string.code_titles_count, state.recentLiveChannels.size.toString()) else ""
        view?.findViewById<TextView>(R.id.tv_count_continue_watching)?.text =
            if (state.continueWatching.isNotEmpty()) getString(R.string.code_titles_count, state.continueWatching.size.toString()) else ""
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
        // Stale-while-revalidate, not a validity TTL. The cached value paints the chip instantly —
        // and it is fingerprint-keyed, so after a server switch there IS no cached value and the
        // new provider is asked straight away. Then, unless a refresh ran in the last 15 minutes,
        // the server is re-asked in the background so an extension bought on the portal shows up
        // on the next Home visit instead of hiding behind the old 12h TTL.
        val cached = credentialsPrefs.getCachedIptvExpiry()
        cached?.let { applyIptvExpiry(it) }
        if (cached != null && credentialsPrefs.isIptvExpiryFresh()) return
        if (!repository.isInitialized()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.login(username, password)
            val expSeconds = (result as? com.tvonnet.debridxtreamiptv.data.Result.Success)
                ?.data?.user_info?.exp_date?.toLongOrNull() ?: return@launch
            credentialsPrefs.saveIptvExpiry(expSeconds)
            if (view != null && expSeconds != cached) applyIptvExpiry(expSeconds)
        }
    }

    private fun applyIptvExpiry(expEpochSeconds: Long) {
        val v = view ?: return
        val nowSeconds = System.currentTimeMillis() / 1000L
        val daysLeft = kotlin.math.ceil((expEpochSeconds - nowSeconds) / 86_400.0).toInt()

        // Owner requirement: show the actual EXPIRY DATE, not remaining time.
        val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
        val expiryDate = dateFmt.format(java.util.Date(expEpochSeconds * 1000L)).uppercase()

        val label: String
        val color: Int
        val borderColor: Int
        when {
            daysLeft < 0 -> {
                label = "EXPIRED · $expiryDate"
                color = 0xFFFF3355.toInt()
                borderColor = 0x73FF3355
            }
            daysLeft <= 30 -> {
                label = expiryDate
                color = 0xFFFFAA00.toInt()
                borderColor = 0x66FFAA00
            }
            else -> {
                label = expiryDate
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

        // Final month: gently pulse the chip so the looming expiry is unmissable.
        expiryPulseAnimator?.cancel()
        expiryPulseAnimator = null
        if (chip != null) {
            if (daysLeft <= 30) {
                expiryPulseAnimator = android.animation.ObjectAnimator
                    .ofFloat(chip, View.ALPHA, 1f, 0.35f).apply {
                        duration = 850
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        repeatMode = android.animation.ValueAnimator.REVERSE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        start()
                    }
            } else {
                chip.alpha = 1f
            }
        }

        maybeShowExpiryWarning(daysLeft, expiryDate)
    }

    private var expiryPulseAnimator: android.animation.ObjectAnimator? = null

    /**
     * Big renewal reminder, once per app open (process): when the IPTV subscription
     * has ≤30 days left (or has expired), tell the user loudly to contact their
     * provider.
     */
    private fun maybeShowExpiryWarning(daysLeft: Int, expiryDate: String) {
        if (daysLeft > 30 || expiryWarningShown) return
        expiryWarningShown = true
        val ctx = context ?: return

        val title = android.widget.TextView(ctx).apply {
            text = if (daysLeft < 0) "⚠  SUBSCRIPTION EXPIRED" else "⚠  SUBSCRIPTION EXPIRING SOON"
            setTextColor(if (daysLeft < 0) 0xFFFF3355.toInt() else 0xFFFFAA00.toInt())
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(48, 40, 48, 8)
        }
        val message = if (daysLeft < 0) {
            "Your IPTV subscription expired on $expiryDate.\n\nPlease contact your provider to renew and continue watching."
        } else {
            "Your IPTV subscription expires on $expiryDate — only $daysLeft day${if (daysLeft == 1) "" else "s"} left.\n\nPlease contact your provider to renew in time."
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setCustomTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .findViewById<android.widget.TextView>(android.R.id.message)?.textSize = 17f
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
            onOpenDetail = { item ->
                viewLifecycleOwner.lifecycleScope.launch { navigationRouter.openContinueWatchingDetail(item) }
            },
            onRemoveItem = { item ->
                viewModel.clearContinueWatchingItem(item)
                android.widget.Toast.makeText(requireContext(), requireContext().getString(R.string.c_removed_from_continue_watching), android.widget.Toast.LENGTH_SHORT).show()
            }
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
        expiryPulseAnimator?.cancel()
        expiryPulseAnimator = null
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
