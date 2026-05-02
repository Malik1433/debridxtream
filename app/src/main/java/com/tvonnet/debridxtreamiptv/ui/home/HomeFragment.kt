package com.tvonnet.debridxtreamiptv.ui.home

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
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {
    
    @Inject
    lateinit var repository: XtreamRepository
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences
    private lateinit var credentialsPrefs: CredentialsPreferences
    
    // UI Components
    private lateinit var ivHeroBackground: ImageView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroDescription: TextView
    private lateinit var rvTop10Movies: RecyclerView
    private lateinit var rvTop10Series: RecyclerView
    private var didRestoreFocusForThisView = false
    private var lastFocusedRvIndex = 0
    private var lastFocusedItemIndex = 0
    private var activeNavItemId: Int = 1 // 1 = Home (based on SidebarItem id in list)


    // Adapters
    private lateinit var top10MoviesAdapter: Top10Adapter
    private lateinit var top10SeriesAdapter: Top10Adapter

    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Use the new Cinematic layout
        return inflater.inflate(R.layout.fragment_home_cinematic, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        watchHistoryPrefs = WatchHistoryPreferences(requireContext())
        credentialsPrefs = CredentialsPreferences(requireContext())
        
        initializeRepository()
        initializeViews(view)
        setupSidebar()
        setupObservers()
        loadData()
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (!state.isLoading) {
                        top10MoviesAdapter.updateItems(state.top10Movies)
                        top10SeriesAdapter.updateItems(state.top10Series)
                        
                        // Update Hero with first available item
                        (state.top10Movies.firstOrNull() ?: state.top10Series.firstOrNull())?.let { 
                            updateHeroSection(it) 
                        }
                        
                        // Robust restoration logic
                        if (!didRestoreFocusForThisView && state.top10Movies.isNotEmpty()) {
                            // Only hijack if sidebar is not currently focused by the user
                            val sidebarPanel = view?.findViewById<View>(R.id.sidebar_panel)
                            if (sidebarPanel?.hasFocus() != true) {
                                restoreContentFocus()
                                didRestoreFocusForThisView = true
                            }
                        }
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

        rvTop10Movies = view.findViewById(R.id.rv_top_10_movies)
        rvTop10Series = view.findViewById(R.id.rv_top_10_series)

        setupRecyclerViews()
    }

    private fun setupRecyclerViews() {
        rvTop10Movies.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvTop10Movies.setHasFixedSize(true)

        rvTop10Series.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvTop10Series.setHasFixedSize(true)

        // Null Animators: No-blink/jitter data updates
        rvTop10Movies.itemAnimator = null
        rvTop10Series.itemAnimator = null

        // DPad-right routing from the cinematic sidebar to content is handled by
        // Android's natural focus search (nav_X items are focusable LinearLayouts);
        // the legacy rvSidebar.setOnKeyListener is gone with the RecyclerView.
    }

    private fun restoreContentFocus() {
        if (!isAdded || !isResumed || view == null) return
        
        val targetRv = if (lastFocusedRvIndex == 1) rvTop10Series else rvTop10Movies
        
        if (lastFocusedRvIndex == 0) {
            val scrollContent = view?.findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll_content)
            scrollContent?.smoothScrollTo(0, 0)
        }

        targetRv.post {
            val vh = targetRv.findViewHolderForAdapterPosition(lastFocusedItemIndex)
            if (vh != null && vh.itemView.isFocusable) {
                vh.itemView.requestFocus()
            } else {
                // Fallback to first item in the target row
                targetRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }
    
    private fun returnToSidebar() {
        // Cinematic sidebar uses hardcoded nav_X LinearLayouts (no RecyclerView).
        // Home is the active section while in HomeFragment, so focus nav_home.
        view?.findViewById<View>(R.id.nav_home)?.requestFocus()
    }
    
    private fun setupSidebar() {
        // Cinematic sidebar: hardcoded nav items, gold pill on active state, no expand/collapse.
        // Each nav_X is a focusable LinearLayout in cin_view_sidebar.xml; clicks navigate to the matching fragment.
        val root = requireView()

        val navHome = root.findViewById<View>(R.id.nav_home)
        val navLive = root.findViewById<View>(R.id.nav_live)
        val navMovies = root.findViewById<View>(R.id.nav_movies)
        val navSeries = root.findViewById<View>(R.id.nav_series)
        val navSearch = root.findViewById<View>(R.id.nav_search)
        val navDebrid = root.findViewById<View>(R.id.nav_debrid)
        val navSettings = root.findViewById<View>(R.id.nav_settings)

        // Mark Home as the currently-active section so the cin_sidebar_item_selector renders the gold-pill state.
        navHome?.isSelected = true

        navHome?.setOnClickListener { /* already on Home */ }
        navLive?.setOnClickListener { navigateToSection("live") }
        navMovies?.setOnClickListener { navigateToSection("movies") }
        navSeries?.setOnClickListener { navigateToSection("series") }
        navSearch?.setOnClickListener { navigateToSection("search") }
        navDebrid?.setOnClickListener { navigateToSection("debrid") }
        navSettings?.setOnClickListener { navigateToSection("settings") }
    }
    
    private fun updateHeroSection(item: FeaturedItem) {
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
        }
        btnWatch?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                returnToSidebar()
                return@setOnKeyListener true
            }
            false
        }
        
        btnDetails?.setOnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.05f)
            v.z = if (hasFocus) 10f else 0f
        }
        btnDetails?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                returnToSidebar()
                return@setOnKeyListener true
            }
            false
        }
    }
    

    // Navigation and Click Handlers
    fun handleBackPress(): Boolean {
        val scrollContent = view?.findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll_content)
        if (scrollContent != null && scrollContent.scrollY > 0) {
            scrollContent.smoothScrollTo(0, 0)
            return true
        }
        return false
    }

    private fun navigateToSection(section: String) {
        val fragment = when (section) {
            "live" -> LiveFragment()
            "movies" -> VodFragment()
            "series" -> SeriesFragment()
            "debrid" -> com.tvonnet.debridxtreamiptv.ui.debrid.DebridFragment()
            "search" -> SearchFragment()
            "settings" -> com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragment()
            else -> return
        }
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
                startActivity(intent)
            } else if (item.contentType == ContentType.SERIES) {
                val intent = android.content.Intent(requireContext(), SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.contentId)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                    putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                }
                startActivity(intent)
            }
        } else {
            // Legacy IPTV logic
            if (item.contentType == ContentType.MOVIE) {
                 item.streamUrl?.let { url ->
                    val intent = PlayerActivity.createIntent(
                        context = requireContext(),
                        streamUrl = url,
                        title = item.title,
                        contentId = item.contentId,
                        contentType = item.contentType,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl
                    )
                    startActivity(intent)
                }
            } else if (item.contentType == ContentType.LIVE_TV) {
                 launchLiveStream(item.contentId, item.title, item.posterUrl, item.streamUrl)
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
        val stream = streamId?.let { repository.getLiveStreamById(it) }
        val serverUrl = credentialsPrefs.getServerUrl()
        val resolvedUrl = when {
            stream != null && !serverUrl.isNullOrBlank() -> repository.buildLiveStreamUrl(stream, serverUrl)
            else -> fallbackUrl
        }

        if (resolvedUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Stream unavailable", Toast.LENGTH_SHORT).show()
            return
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
            posterUrl = absoluteIcon
        )
        startActivity(intent)
    }


    private fun loadData() {
        // Initialize Adapters with empty lists first
        top10MoviesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, item -> 
                lastFocusedRvIndex = 0
                lastFocusedItemIndex = index
                updateHeroSection(item) 
            },
            onItemClick = { item -> onFeaturedItemClick(item) },
            onLeftBoundary = { returnToSidebar() }
        )
        rvTop10Movies.adapter = top10MoviesAdapter

        top10SeriesAdapter = Top10Adapter(
            items = emptyList(),
            onItemFocused = { index, item -> 
                lastFocusedRvIndex = 1
                lastFocusedItemIndex = index
                updateHeroSection(item) 
            },
            onItemClick = { item -> onFeaturedItemClick(item) },
            onLeftBoundary = { returnToSidebar() }
        )
        rvTop10Series.adapter = top10SeriesAdapter
    }


    override fun onDestroyView() {
        super.onDestroyView()
    }
    
    override fun onResume() {
        super.onResume()
    }
}
