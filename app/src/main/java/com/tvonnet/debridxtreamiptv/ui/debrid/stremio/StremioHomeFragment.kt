package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridUiState
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridViewModel
import com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverViewModel
import com.tvonnet.debridxtreamiptv.ui.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Stremio-styled Debrid home: top-nav-tabs + hero-carousel, wired to the real Debrid backends
 * (`DebridViewModel` rows, `DebridDiscoverViewModel`).
 */
@AndroidEntryPoint
class StremioHomeFragment : Fragment() {

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var episodeDaoV2: EpisodeDaoV2
    @Inject lateinit var playbackResolver: PlaybackResolver
    @Inject lateinit var catalogRepo: com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository

    // Kept for the search overlay's IPTV result navigation (StremioNavigationRouter).
    internal val viewModel: HomeViewModel by viewModels()
    internal val debridVm: DebridViewModel by viewModels()
    internal val discoverVm: DebridDiscoverViewModel by viewModels()

    internal lateinit var credentialsPrefs: CredentialsPreferences
    internal lateinit var router: StremioNavigationRouter
    internal lateinit var actions: StremioDebridActions
    internal lateinit var heroManager: StremioHeroManager
    internal var homeSection: StremioHomeSection? = null
    internal var discoverSection: StremioDiscoverSection? = null
    internal var librarySection: StremioLibrarySection? = null
    internal var searchOverlay: StremioSearchOverlay? = null

    internal var isNavigatingAway = false
    private var activeNav = "home"

    private lateinit var homeContent: View
    private lateinit var discoverContent: View
    private lateinit var libraryContent: View

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() { tickClock(); clockHandler.postDelayed(this, 1_000L) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stremio_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        credentialsPrefs = CredentialsPreferences(requireContext())
        router = StremioNavigationRouter(this)
        actions = StremioDebridActions(this, playbackResolver, view.findViewById(R.id.stremio_status_overlay))

        applyGradients(view)
        setupNav(view)

        homeContent = view.findViewById(R.id.home_content)
        discoverContent = view.findViewById(R.id.discover_content)
        libraryContent = view.findViewById(R.id.library_content)

        heroManager = StremioHeroManager(this, view)
        heroManager.setup()

        homeSection = StremioHomeSection(this, view, actions)
        discoverSection = StremioDiscoverSection(this, view, discoverVm, actions)
        librarySection = StremioLibrarySection(this, view, actions)
        searchOverlay = StremioSearchOverlay(this, view, catalogRepo, actions)

        view.findViewById<View>(R.id.searchBar).setOnClickListener { searchOverlay?.open() }
        wireHeroKeys(view)

        tickClock()
        clockHandler.postDelayed(clockRunnable, 1_000L)
        selectNav("home", initial = true)
        setupObservers()

        debridVm.checkAuthStatus()
        discoverVm  // touch to init
        view.post { view.findViewById<View>(R.id.navTabHome)?.requestFocus() }
        showSetupGuideIfNoDebridService(view)
    }

    /**
     * With one tier, every device may open Debrid — but it does nothing until the customer adds
     * their own addons. Rather than leaving them on rows that never fill, say what is missing and
     * how to fix it (Qadam 3).
     *
     * Re-checked in [onResume] because addons can arrive while this screen sits in the background:
     * from the phone, or from Settings on this TV.
     */
    private fun showSetupGuideIfNoDebridService(view: View) {
        val configured = com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
            .isDebridConfigured(requireContext())
        view.findViewById<View>(R.id.debrid_setup_overlay)?.visibility =
            if (configured) View.GONE else View.VISIBLE
        // D3/DB-7: the gate ends in a "BACK · RETURN" D-pad legend. Third offender after M14b's
        // home and M16a's series detail; the phone rulebook bans them by name.
        if (!resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            view.findViewById<View>(R.id.debrid_setup_hint)?.visibility = View.GONE
        }
    }

    // The rotation timer only runs where the hero is actually on screen.
    private fun heroAllowed(): Boolean =
        activeNav != "discover" && resources.getBoolean(R.bool.debrid_home_shows_hero)

    override fun onResume() {
        super.onResume()
        isNavigatingAway = false
        debridVm.refreshContinueWatching()
        if (heroAllowed()) heroManager.onResumeTimer()
        view?.let { showSetupGuideIfNoDebridService(it) }
    }

    override fun onPause() {
        super.onPause()
        heroManager.onPauseTimer()
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                debridVm.uiState.collect { state -> onDebridState(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                discoverVm.uiState.collect { discoverSection?.onDiscoverState(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                discoverVm.genres.collect { discoverSection?.setGenres(it) }
            }
        }
    }

    private val statusOverlay: View? get() = view?.findViewById(R.id.stremio_status_overlay)

    private fun onDebridState(state: DebridUiState) {
        when (state) {
            is DebridUiState.Loading -> setStatus(true, "Loading…")
            is DebridUiState.NotAuthenticated -> {
                setStatus(false, null)
                if (!isNavigatingAway && isAdded && !parentFragmentManager.isStateSaved) {
                    isNavigatingAway = true
                    parentFragmentManager.commit {
                        replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.debrid.DebridAuthFragment())
                        addToBackStack(null)
                    }
                }
            }
            is DebridUiState.Error -> setStatus(false, null)
            is DebridUiState.Content -> {
                setStatus(false, null)
                bindRows(state.rows)
            }
            else -> setStatus(false, null)
        }
    }

    private fun setStatus(loading: Boolean, text: String?) {
        val ov = statusOverlay ?: return
        ov.isVisible = loading
        if (text != null) ov.findViewById<TextView>(R.id.stremio_status_text)?.text = text
    }

    private var lastBoundRows: List<DebridRow> = emptyList()

    private fun bindRows(rows: List<DebridRow>) {
        if (rows == lastBoundRows) return  // skip redundant rebuilds (e.g. onResume CW refresh)
        lastBoundRows = rows
        val trending = rows.filter { it.id.startsWith("trending") }
            .flatMap { it.items }.filter { it.type != "see_all" }
            .ifEmpty { rows.flatMap { it.items }.filter { it.type != "see_all" } }

        heroManager.setSlides(trending.take(5).map { item ->
            StremioHeroSlide(
                title = item.title,
                rating = item.rating,
                meta = listOfNotNull(item.year, if (item.type == "series") "Series" else "Movie").joinToString(" · "),
                desc = item.overview,
                backdropUrl = item.backdropUrl ?: item.posterUrl,
                onPlay = { actions.navigateToDetail(item) }
            )
        })

        homeSection?.bind(rows)
        librarySection?.bind(rows)
        discoverSection?.setTop10(trending)
        wireContentFocus()
    }

    // ── Top nav ──────────────────────────────────────────────────────────
    private val navKeys = listOf("home", "discover", "library")

    private fun setupNav(view: View) {
        val tabs = listOf(
            Triple(view.findViewById<View>(R.id.navTabHome), R.drawable.ic_stremio_home, "Home"),
            Triple(view.findViewById<View>(R.id.navTabDiscover), R.drawable.ic_stremio_discover, "Discover"),
            Triple(view.findViewById<View>(R.id.navTabLibrary), R.drawable.ic_stremio_library, "My Library")
        )
        tabs.forEachIndexed { i, (tab, icon, label) ->
            tab.findViewById<ImageView>(R.id.nav_tab_icon).setImageResource(icon)
            tab.findViewById<TextView>(R.id.nav_tab_label).text = label
            tab.setOnClickListener { selectNav(navKeys[i]) }
            tab.nextFocusDownId = R.id.heroPlayBtn
        }
    }

    internal fun selectNav(key: String, initial: Boolean = false) {
        if (!initial && key == activeNav) return
        activeNav = key
        searchOverlay?.close()
        homeContent.isVisible = key == "home"
        discoverContent.isVisible = key == "discover"
        libraryContent.isVisible = key == "library"
        if (key == "discover") discoverSection?.onShown()

        val view = view ?: return
        // Owner decision (2026-08-10): a phone never shows the hero — it ate ~70% of a 411dp
        // screen and left one row peeking. The TV keeps it on Home, and loses it on Discover
        // as before.
        val showHero = key != "discover" && resources.getBoolean(R.bool.debrid_home_shows_hero)
        applyHeroVisibility(view, showHero)
        styleNavTabs(view, key)
        wireContentFocus()
        wireNavDownTargets(view, showHero)
    }

    // The hero is a Home feature. Hide it on Discover for a cleaner, less busy page and pull the
    // content up to just below the nav bar.
    private fun applyHeroVisibility(view: View, showHero: Boolean) {
        view.findViewById<View>(R.id.heroBanner)?.isVisible = showHero
        if (showHero) heroManager.onResumeTimer() else heroManager.onPauseTimer()
        view.findViewById<View>(R.id.content_host)?.let { host ->
            (host.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                // With the hero: the TV's 318dp (the old literal, now a qualified dimen).
                // Without: below the nav bar — 52dp on TV, 70dp on a phone whose bar sits
                // under the status inset (D3 measured the 67dp floor).
                lp.topMargin = resources.getDimensionPixelSize(
                    if (showHero) R.dimen.stremio_content_top else R.dimen.stremio_content_top_no_hero
                )
                host.layoutParams = lp
            }
        }
    }

    private fun styleNavTabs(view: View, key: String) {
        val tabViews = mapOf(
            "home" to view.findViewById<View>(R.id.navTabHome),
            "discover" to view.findViewById<View>(R.id.navTabDiscover),
            "library" to view.findViewById<View>(R.id.navTabLibrary)
        )
        val density = resources.displayMetrics.density
        tabViews.forEach { (k, tab) ->
            val active = k == key
            tab.isActivated = active
            val label = tab.findViewById<TextView>(R.id.nav_tab_label)
            val icon = tab.findViewById<ImageView>(R.id.nav_tab_icon)
            label.setTextColor(if (active) 0xFFF1F5F9.toInt() else 0xFF64748B.toInt())
            StremioFonts.apply(label, if (active) R.font.outfit_bold else R.font.outfit_medium)
            icon.setColorFilter(if (active) 0xFFF1F5F9.toInt() else 0xFF64748B.toInt())
            animateIndicator(tab.findViewById(R.id.nav_tab_indicator), if (active) (10f * density).toInt() else 0)
        }
    }

    // With the hero hidden on Discover, nav DOWN must reach the content directly (the hero Play
    // button is GONE and can't be focused).
    private fun wireNavDownTargets(view: View, showHero: Boolean) {
        val navDownId = if (showHero) R.id.heroPlayBtn else {
            val f = discoverSection?.firstFocusable()
            if (f != null) { if (f.id == View.NO_ID) f.id = View.generateViewId(); f.id } else R.id.heroPlayBtn
        }
        listOf(R.id.navTabHome, R.id.navTabDiscover, R.id.navTabLibrary).forEach {
            view.findViewById<View>(it)?.nextFocusDownId = navDownId
        }
    }

    private fun firstContentFocusable(): View? {
        val first = when (activeNav) {
            "home" -> homeSection?.firstFocusable()
            "discover" -> discoverSection?.firstFocusable()
            "library" -> librarySection?.firstFocusable()
            else -> null
        }
        if (first != null && first.id == View.NO_ID) first.id = View.generateViewId()
        return first
    }

    private fun activeNavTabId(): Int = when (activeNav) {
        "discover" -> R.id.navTabDiscover
        "library" -> R.id.navTabLibrary
        else -> R.id.navTabHome
    }

    /** hero buttons DOWN → the active tab's first content focusable; UP → active nav tab. */
    private fun wireContentFocus() {
        val v = view ?: return
        val downId = firstContentFocusable()?.id ?: View.NO_ID
        val upId = activeNavTabId()
        listOf(R.id.heroPlayBtn, R.id.heroTrailerBtn, R.id.heroWatchlistBtn).forEach { id ->
            v.findViewById<View>(id)?.apply {
                nextFocusUpId = upId
                if (downId != View.NO_ID) nextFocusDownId = downId
            }
        }
    }

    private fun wireHeroKeys(view: View) {
        // NOTE: no LEFT/RIGHT key interception here — RIGHT on Play must move focus to Trailer, not
        // step the hero. The hero auto-advances on its own timer.
        listOf(R.id.heroPlayBtn, R.id.heroTrailerBtn, R.id.heroWatchlistBtn).forEach {
            view.findViewById<View>(it)?.let { b -> StremioFocus.attach(b, 1.05f) }
        }
    }

    private fun animateIndicator(indicator: View, targetWidth: Int) {
        ValueAnimator.ofInt(indicator.layoutParams.width, targetWidth).apply {
            duration = 250
            addUpdateListener { indicator.layoutParams = indicator.layoutParams.apply { width = it.animatedValue as Int } }
            start()
        }
    }

    private fun tickClock() {
        val tv = view?.findViewById<TextView>(R.id.nav_clock) ?: return
        // D2: a television has no system status bar, so this bar draws its own clock. A phone
        // already shows the time a few hundred pixels away — the same duplication M15 removed from
        // the IPTV home, and the same flag decides it.
        if (!resources.getBoolean(R.bool.home_shows_tv_status_strip)) {
            tv.visibility = View.GONE
            return
        }
        val cal = Calendar.getInstance()
        tv.text = String.format(java.util.Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun applyGradients(view: View) {
        view.findViewById<View>(R.id.ambient_glow).background = StremioGradients.ambientGlow()
        view.findViewById<View>(R.id.hero_fade_lr).background = StremioGradients.heroFadeLr()
        view.findViewById<View>(R.id.hero_fade_bottom).background = StremioGradients.heroFadeBottom()
        view.findViewById<View>(R.id.top_nav_bar).background = StremioGradients.navBarBg()
    }

    fun handleBackPress(): Boolean {
        if (searchOverlay?.isOpen() == true) { searchOverlay?.close(); return true }
        if (activeNav != "home") { selectNav("home"); view?.findViewById<View>(R.id.navTabHome)?.requestFocus(); return true }
        return false
    }

    override fun onDestroyView() {
        clockHandler.removeCallbacks(clockRunnable)
        heroManager.cleanup()
        router.cleanup()
        homeSection = null
        discoverSection = null
        librarySection = null
        searchOverlay = null
        super.onDestroyView()
    }
}
