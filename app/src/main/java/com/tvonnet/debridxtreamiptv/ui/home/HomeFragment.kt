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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragmentNew
import com.tvonnet.debridxtreamiptv.player.PlayerActivity
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
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences
    private lateinit var credentialsPrefs: CredentialsPreferences
    
    // Navigation buttons
    private lateinit var btnNavLiveTv: TextView
    private lateinit var btnNavMovies: TextView
    private lateinit var btnNavSeries: TextView
    private lateinit var btnNavSearch: TextView
    private lateinit var btnSettings: ImageView
    
    // RecyclerViews
    private lateinit var rvFeatured: RecyclerView
    private lateinit var rvRecentLive: RecyclerView
    private lateinit var rvContinueWatching: RecyclerView
    private lateinit var rvFavorites: RecyclerView
    
    // Adapters
    private lateinit var featuredAdapter: FeaturedAdapter
    private lateinit var recentLiveAdapter: RecentLiveChannelsAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var favoritesAdapter: FavoritesAdapter
    private var favoritesJob: Job? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_new_home, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize preferences (repository already injected by Hilt)
        watchHistoryPrefs = WatchHistoryPreferences(requireContext())
        credentialsPrefs = CredentialsPreferences(requireContext())
        
        // Initialize credentials for repository
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
        }
        
        // Initialize views
        initializeViews(view)
        
        // Setup navigation buttons
        setupNavigationButtons()
        
        // Load data for all sections asynchronously
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Load cache in background
                val cache = repository.readCache()
                val featuredItems = generateFeaturedItems(cache)
                
                withContext(Dispatchers.Main) {
                    // Update UI on main thread
                    featuredAdapter.updateItems(featuredItems.take(4))
                    loadRecentLiveChannels()
                    loadContinueWatching()
                    loadFavorites()
                }
            }
        }
    }
    
    private fun initializeViews(view: View) {
        // Navigation buttons
        btnNavLiveTv = view.findViewById(R.id.btn_nav_live_tv)
        btnNavMovies = view.findViewById(R.id.btn_nav_movies)
        btnNavSeries = view.findViewById(R.id.btn_nav_series)
        btnNavSearch = view.findViewById(R.id.btn_nav_search)
        btnSettings = view.findViewById(R.id.btn_settings)
        
        // RecyclerViews
        rvFeatured = view.findViewById(R.id.rv_featured)
        rvRecentLive = view.findViewById(R.id.rv_recent_live)
        rvContinueWatching = view.findViewById(R.id.rv_continue_watching)
        rvFavorites = view.findViewById(R.id.rv_favorites)
        
        // Setup RecyclerViews with optimization
        rvFeatured.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(4)
        }
        rvRecentLive.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(6)
        }
        rvContinueWatching.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(5)
        }
        rvFavorites.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(5)
        }
        
        // Initialize adapters
        featuredAdapter = FeaturedAdapter(emptyList()) { item ->
            onFeaturedItemClick(item)
        }
        recentLiveAdapter = RecentLiveChannelsAdapter(emptyList()) { item ->
            onRecentLiveChannelClick(item)
        }
        continueWatchingAdapter = ContinueWatchingAdapter(emptyList()) { item ->
            onContinueWatchingItemClick(item)
        }
        favoritesAdapter = FavoritesAdapter(emptyList()) { item ->
            onFavoriteItemClick(item)
        }
        
        rvFeatured.adapter = featuredAdapter
        rvRecentLive.adapter = recentLiveAdapter
        rvContinueWatching.adapter = continueWatchingAdapter
        rvFavorites.adapter = favoritesAdapter
    }
    
    private fun generateFeaturedItems(cache: IptvCache?): List<FeaturedItem> {
        val featuredItems = mutableListOf<FeaturedItem>()
        val serverUrl = credentialsPrefs.getServerUrl() ?: ""
        val username = credentialsPrefs.getUsername() ?: ""
        val password = credentialsPrefs.getPassword() ?: ""
        
        // Get featured content from different sources
        cache?.live?.streams?.take(2)?.forEach { stream ->
            featuredItems.add(stream.toFeaturedItem(serverUrl, username, password))
        }
        
        cache?.vod?.streams?.take(3)?.forEach { vod ->
            featuredItems.add(vod.toFeaturedItem(serverUrl, username, password))
        }
        
        cache?.series?.streams?.take(2)?.forEach { series ->
            featuredItems.add(series.toFeaturedItem())
        }
        
        featuredItems.shuffle()
        return featuredItems
    }
    
    private fun setupNavigationButtons() {
        btnNavLiveTv.setOnClickListener {
            selectNavButton(0)
            navigateToSection("live")
        }
        
        btnNavMovies.setOnClickListener {
            selectNavButton(1)
            navigateToSection("movies")
        }
        
        btnNavSeries.setOnClickListener {
            selectNavButton(2)
            navigateToSection("series")
        }
        
        btnNavSearch.setOnClickListener {
            selectNavButton(3)
            navigateToSection("search")
        }
        
        btnSettings.setOnClickListener {
            navigateToSection("settings")
        }
        
        // Set Live TV as selected by default
        selectNavButton(0)
    }
    
    private fun selectNavButton(index: Int) {
        // Reset all buttons
        btnNavLiveTv.isSelected = false
        btnNavMovies.isSelected = false
        btnNavSeries.isSelected = false
        btnNavSearch.isSelected = false
        
        // Select the clicked button
        when (index) {
            0 -> btnNavLiveTv.isSelected = true
            1 -> btnNavMovies.isSelected = true
            2 -> btnNavSeries.isSelected = true
            3 -> btnNavSearch.isSelected = true
        }
    }
    
    private fun navigateToSection(section: String) {
        // Navigate to different sections by replacing this fragment
        val fragment = when (section) {
            "live" -> LiveFragment()
            "movies" -> VodFragment()
            "series" -> SeriesFragment()
            "search" -> SearchFragment()
            "settings" -> SettingsFragmentNew()
            else -> return
        }
        
        // Replace current fragment with the selected one
        parentFragmentManager.commit {
            replace(R.id.content_container, fragment)
            addToBackStack(null) // Allow back navigation to home
        }
    }
    
    private fun generateSampleContinueWatching(): List<ContinueWatchingItem> {
        return listOf(
            ContinueWatchingItem(
                contentId = "sample_movie_1",
                contentType = ContentType.MOVIE,
                title = "Action Movie",
                posterUrl = null,
                backdropUrl = null,
                currentPosition = 900000L, // 15 min
                totalDuration = 5400000L, // 90 min
                lastWatchedTimestamp = System.currentTimeMillis(),
                streamUrl = null
            ),
            ContinueWatchingItem(
                contentId = "sample_series_1",
                contentType = ContentType.SERIES,
                title = "Drama Series S01E03",
                posterUrl = null,
                backdropUrl = null,
                currentPosition = 1200000L, // 20 min
                totalDuration = 2700000L, // 45 min
                lastWatchedTimestamp = System.currentTimeMillis() - 3600000,
                streamUrl = null
            ),
            ContinueWatchingItem(
                contentId = "sample_movie_2",
                contentType = ContentType.MOVIE,
                title = "Comedy Movie",
                posterUrl = null,
                backdropUrl = null,
                currentPosition = 600000L, // 10 min
                totalDuration = 7200000L, // 120 min
                lastWatchedTimestamp = System.currentTimeMillis() - 7200000,
                streamUrl = null
            ),
            ContinueWatchingItem(
                contentId = "sample_movie_3",
                contentType = ContentType.MOVIE,
                title = "Thriller Movie",
                posterUrl = null,
                backdropUrl = null,
                currentPosition = 3000000L, // 50 min
                totalDuration = 6300000L, // 105 min
                lastWatchedTimestamp = System.currentTimeMillis() - 86400000,
                streamUrl = null
            ),
            ContinueWatchingItem(
                contentId = "sample_series_2",
                contentType = ContentType.SERIES,
                title = "Sci-Fi Series S02E05",
                posterUrl = null,
                backdropUrl = null,
                currentPosition = 1800000L, // 30 min
                totalDuration = 3600000L, // 60 min
                lastWatchedTimestamp = System.currentTimeMillis() - 172800000,
                streamUrl = null
            )
        )
    }
    
    private fun generateSampleRecentLiveChannels(): List<RecentLiveChannelItem> {
        return listOf(
            RecentLiveChannelItem(
                channelId = "live_sample_1",
                channelName = "News Channel",
                channelLogo = null,
                lastWatchedTimestamp = System.currentTimeMillis(),
                streamUrl = null,
                epgChannelId = "live_sample_1"
            ),
            RecentLiveChannelItem(
                channelId = "live_sample_2",
                channelName = "Sports TV",
                channelLogo = null,
                lastWatchedTimestamp = System.currentTimeMillis() - 3600000,
                streamUrl = null,
                epgChannelId = "live_sample_2"
            ),
            RecentLiveChannelItem(
                channelId = "live_sample_3",
                channelName = "Entertainment",
                channelLogo = null,
                lastWatchedTimestamp = System.currentTimeMillis() - 7200000,
                streamUrl = null,
                epgChannelId = "live_sample_3"
            ),
            RecentLiveChannelItem(
                channelId = "live_sample_4",
                channelName = "Kids Channel",
                channelLogo = null,
                lastWatchedTimestamp = System.currentTimeMillis() - 86400000,
                streamUrl = null,
                epgChannelId = "live_sample_4"
            ),
            RecentLiveChannelItem(
                channelId = "live_sample_5",
                channelName = "Music Channel",
                channelLogo = null,
                lastWatchedTimestamp = System.currentTimeMillis() - 172800000,
                streamUrl = null,
                epgChannelId = "live_sample_5"
            ),
            RecentLiveChannelItem(
                channelId = "live_sample_6",
                channelName = "Movies 24/7",
                channelLogo = null,
                lastWatchedTimestamp = System.currentTimeMillis() - 259200000,
                streamUrl = null,
                epgChannelId = "live_sample_6"
            )
        )
    }
    
    private fun loadRecentLiveChannels() {
        val items = watchHistoryPrefs.getRecentLiveChannelsList()
            .sortedByDescending { it.lastWatchedTimestamp }
            .take(6)

        recentLiveAdapter.updateItems(items)
        view?.findViewById<View>(R.id.section_recent_live)?.visibility =
            if (items.isNotEmpty()) View.VISIBLE else View.GONE
    }
    
    private fun loadContinueWatching() {
        val items = watchHistoryPrefs.getContinueWatchingList()
            .sortedByDescending { it.lastWatchedTimestamp }
            .take(5)

        continueWatchingAdapter.updateItems(items)
        view?.findViewById<View>(R.id.section_continue_watching)?.visibility =
            if (items.isNotEmpty()) View.VISIBLE else View.GONE
    }
    
    private fun loadFavorites() {
        favoritesJob?.cancel()
        val favoritesFlow = kotlin.runCatching { repository.getAllFavorites() }.getOrNull()
        if (favoritesFlow == null) {
            favoritesAdapter.updateItems(emptyList())
            view?.findViewById<View>(R.id.section_favorites)?.visibility = View.GONE
            return
        }

        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        favoritesJob = viewLifecycleOwner.lifecycleScope.launch {
            favoritesFlow.collect { entities ->
                val items = withContext(Dispatchers.Default) {
                    entities.mapNotNull { favorite ->
                        HomeFavoriteMapper.mapToFavoriteItem(favorite, repository, serverUrl, username, password)
                    }
                }.take(10)
                favoritesAdapter.updateItems(items)
                view?.findViewById<View>(R.id.section_favorites)?.visibility =
                    if (items.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
    
    
    private fun onFeaturedItemClick(item: FeaturedItem) {
        when (item.contentType) {
            ContentType.LIVE_TV -> launchLiveStream(
                streamId = item.contentId,
                fallbackTitle = item.title,
                fallbackLogo = item.posterUrl,
                fallbackUrl = item.streamUrl
            )
            ContentType.MOVIE -> item.streamUrl?.let { url ->
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
            else -> {
                // Series and others require detail screen
            }
        }
    }
    
    private fun onContinueWatchingItemClick(item: com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem) {
        item.streamUrl?.let { url ->
            val playbackSource = if (item.source == "debrid") {
                com.tvonnet.debridxtreamiptv.player.PlaybackSource.DEBRID
            } else {
                com.tvonnet.debridxtreamiptv.player.PlaybackSource.IPTV
            }
            val intent = PlayerActivity.createIntent(
                context = requireContext(),
                streamUrl = url,
                title = item.title,
                startPositionMs = item.currentPosition,
                contentId = item.contentId,
                contentType = item.contentType,
                playbackSource = playbackSource,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                tmdbId = item.tmdbId,
                imdbId = item.imdbId,
                seriesTitle = item.seriesTitle,
                episodeTitle = item.episodeTitle,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                debridInfoHash = item.debridInfoHash,
                debridMagnet = item.debridMagnet
            )
            startActivity(intent)
        }
    }
    
    private fun onRecentLiveChannelClick(item: com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem) {
        launchLiveStream(
            streamId = item.channelId,
            fallbackTitle = item.channelName,
            fallbackLogo = item.channelLogo,
            fallbackUrl = item.streamUrl,
            epgChannelId = item.epgChannelId
        )
    }
    
    private fun onFavoriteItemClick(item: com.tvonnet.debridxtreamiptv.data.model.FavoriteItem) {
        when (item.contentType) {
            ContentType.LIVE_TV -> launchLiveStream(
                streamId = item.contentId,
                fallbackTitle = item.title,
                fallbackLogo = item.posterUrl,
                fallbackUrl = item.streamUrl
            )
            else -> item.streamUrl?.let { url ->
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

        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = resolvedUrl,
            title = fallbackTitle ?: stream?.name ?: getString(R.string.player_epg_channel_unknown),
            channelName = stream?.name ?: fallbackTitle,
            channelLogo = stream?.stream_icon ?: fallbackLogo,
            epgChannelId = stream?.epg_channel_id?.takeIf { it.isNotBlank() } ?: epgChannelId ?: streamId,
            contentId = stream?.stream_id ?: streamId ?: resolvedUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = stream?.stream_icon ?: fallbackLogo
        )
        startActivity(intent)
    }

    override fun onDestroyView() {
        favoritesJob?.cancel()
        favoritesJob = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when returning to this screen
        loadRecentLiveChannels()
        loadContinueWatching()
        loadFavorites()
    }
}
