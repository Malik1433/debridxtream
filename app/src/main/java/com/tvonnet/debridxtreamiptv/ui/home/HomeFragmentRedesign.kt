package com.tvonnet.debridxtreamiptv.ui.home

import android.app.AlertDialog
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
// import com.tvonnet.debridxtreamiptv.ui.recommendations.RecommendationsFragment
// import com.tvonnet.debridxtreamiptv.ui.recommendations.RecommendationCardAdapterFactory
// import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationEngine
import com.tvonnet.debridxtreamiptv.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragmentRedesign : Fragment() {
    
    @Inject
    lateinit var repository: XtreamRepository
    // @Inject
    // lateinit var recommendationEngine: RecommendationEngine
    // @Inject
    // lateinit var homeRecommendationsManager: HomeRecommendationsManager
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences
    private lateinit var credentialsPrefs: CredentialsPreferences
    
    // Navigation buttons
    private lateinit var btnNavLiveTv: TextView
    private lateinit var btnNavMovies: TextView
    private lateinit var btnNavSeries: TextView
    private lateinit var btnNavDebrid: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var btnSettings: ImageView
    private lateinit var btnSeeAllRecommendations: TextView

    // RecyclerViews
    private lateinit var rvFeatured: RecyclerView
    private lateinit var rvNewAdded: RecyclerView
    private lateinit var rvRecentLive: RecyclerView
    private lateinit var rvContinueWatching: RecyclerView
    private lateinit var rvFavorites: RecyclerView
    private lateinit var rvRecommendations: RecyclerView
    
    // Adapters
    private lateinit var featuredAdapter: FeaturedAdapter
    private lateinit var newAddedAdapter: NewAddedAdapter
    private lateinit var recentLiveAdapter: RecentLiveChannelsAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var favoritesAdapter: FavoritesAdapter
    // private lateinit var recommendationsAdapter: com.tvonnet.debridxtreamiptv.ui.recommendations.RecommendationCardAdapter
    private var favoritesJob: Job? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home_redesign, container, false)
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
        
        // Set default focus on LiveTV button (post to ensure view is ready)
        view.post {
            btnNavLiveTv.requestFocus()
        }
        
        // Load data for all sections asynchronously
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Load cache in background
                val cache = repository.readCache()
                
                val featuredItems = generateFeaturedItems(cache).toMutableList()
                val newAddedItems = generateNewAddedItems(cache).toMutableList()
                
                // Fix: If cache is empty (lazy loading), fetch real data from first categories
                if (featuredItems.isEmpty() && repository.isInitialized()) {
                     val serverUrl = credentialsPrefs.getServerUrl() ?: ""
                     val username = credentialsPrefs.getUsername() ?: ""
                     val password = credentialsPrefs.getPassword() ?: ""

                     // 1. Try fetching Live TV (First Category)
                     val liveCats = cache?.live?.categories ?: emptyList()
                     if (liveCats.isNotEmpty()) {
                         val firstCatId = liveCats.first().category_id
                         if (firstCatId != null) {
                             val result = repository.fetchLiveStreamsForCategory(firstCatId)
                             if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                                 result.data.take(4).forEach { stream ->
                                     featuredItems.add(stream.toFeaturedItem(serverUrl, username, password))
                                 }
                             }
                         }
                     }

                     // 2. Try fetching VOD (First Category) if still needed
                     if (featuredItems.size < 4 || newAddedItems.isEmpty()) {
                         val vodCats = cache?.vod?.categories ?: emptyList()
                         if (vodCats.isNotEmpty()) {
                             val firstCatId = vodCats.first().category_id
                             if (firstCatId != null) {
                                 val result = repository.fetchVodStreamsForCategory(firstCatId)
                                 if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                                     val vods = result.data
                                     vods.take(4).forEach { vod ->
                                         featuredItems.add(vod.toFeaturedItem(serverUrl, username, password))
                                     }
                                     
                                     // Populate New Added from this fresh batch
                                     vods.take(10).forEach { vod ->
                                        val addedDate = vod.added
                                        if (!addedDate.isNullOrEmpty()) {
                                            try {
                                                val timestamp = addedDate.toLongOrNull() ?: 0L
                                                val streamUrl = "$serverUrl/movie/$username/$password/${vod.stream_id ?: ""}.${vod.container_extension ?: "mp4"}"
                                                newAddedItems.add(
                                                    NewAddedItem(
                                                        contentId = vod.stream_id ?: "",
                                                        contentType = ContentType.MOVIE,
                                                        title = vod.name ?: "Unknown Movie",
                                                        posterUrl = vod.stream_icon,
                                                        addedDate = addedDate,
                                                        addedTimestamp = timestamp,
                                                        year = vod.releaseDate?.take(4),
                                                        quality = if (vod.name?.contains("4K", ignoreCase = true) == true) "4K" else "HD",
                                                        streamUrl = streamUrl
                                                    )
                                                )
                                            } catch (e: Exception) { }
                                        }
                                     }
                                 }
                             }
                         }
                     }
                     
                     featuredItems.shuffle()
                }

                val finalFeatured = if (featuredItems.isNotEmpty()) featuredItems else HomeSampleData.featuredItems()
                val finalNewAdded = if (newAddedItems.isNotEmpty()) newAddedItems.sortedByDescending { it.addedTimestamp } else HomeSampleData.newAddedItems()

                withContext(Dispatchers.Main) {
                    // Update UI on main thread
                    featuredAdapter.updateItems(finalFeatured.take(4))
                    newAddedAdapter.updateItems(finalNewAdded.take(10))
                    loadRecentLiveChannels()
                    loadContinueWatching()
                    loadFavorites()

                    // Load recommendations
                    // setupHomeRecommendations()
                }
            }
        }
    }
    
    private fun initializeViews(view: View) {
        // Navigation buttons
        btnNavLiveTv = view.findViewById(R.id.btn_nav_live_tv)
        btnNavMovies = view.findViewById(R.id.btn_nav_movies)
        btnNavSeries = view.findViewById(R.id.btn_nav_series)
        btnNavDebrid = view.findViewById(R.id.btn_nav_debrid)
        btnSearch = view.findViewById(R.id.btn_search)
        btnSettings = view.findViewById(R.id.btn_settings)
        
        // RecyclerViews
        rvFeatured = view.findViewById(R.id.rv_featured)
        rvNewAdded = view.findViewById(R.id.rv_new_added)
        rvRecentLive = view.findViewById(R.id.rv_recent_live)
        rvContinueWatching = view.findViewById(R.id.rv_continue_watching)
        rvFavorites = view.findViewById(R.id.rv_favorites)
        
        // Setup RecyclerViews with optimization
        setupRecyclerView(rvFeatured, 4)
        setupRecyclerView(rvNewAdded, 10)
        setupRecyclerView(rvRecentLive, 6)
        setupRecyclerView(rvContinueWatching, 5)
        setupRecyclerView(rvFavorites, 5)
        
        // Initialize adapters
        featuredAdapter = FeaturedAdapter(emptyList()) { item ->
            onFeaturedItemClick(item)
        }
        newAddedAdapter = NewAddedAdapter(emptyList()) { item ->
            onNewAddedItemClick(item)
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
        rvNewAdded.adapter = newAddedAdapter
        rvRecentLive.adapter = recentLiveAdapter
        rvContinueWatching.adapter = continueWatchingAdapter
        rvFavorites.adapter = favoritesAdapter
    }
    
    private fun setupRecyclerView(recyclerView: RecyclerView, cacheSize: Int) {
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(cacheSize)
        }
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
        
        btnNavDebrid.setOnClickListener {
            selectNavButton(3)
            navigateToSection("debrid")
        }
        
        btnSearch.setOnClickListener {
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
        btnNavDebrid.isSelected = false
        
        // Select the clicked button
        when (index) {
            0 -> btnNavLiveTv.isSelected = true
            1 -> btnNavMovies.isSelected = true
            2 -> btnNavSeries.isSelected = true
            3 -> btnNavDebrid.isSelected = true
        }
    }
    
    private fun showDebridComingSoonDialog() {
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(R.string.coming_soon_title)
            .setMessage(R.string.coming_soon_message)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun navigateToSection(section: String) {
        // Navigate to different sections by replacing this fragment
        val fragment = when (section) {
            "live" -> LiveFragment()
            "movies" -> VodFragment()
            "series" -> SeriesFragment()
            "debrid" -> com.tvonnet.debridxtreamiptv.ui.debrid.DebridFragment()
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
    
    private fun generateNewAddedItems(cache: IptvCache?): List<NewAddedItem> {
        val newAddedItems = mutableListOf<NewAddedItem>()
        val serverUrl = credentialsPrefs.getServerUrl() ?: ""
        val username = credentialsPrefs.getUsername() ?: ""
        val password = credentialsPrefs.getPassword() ?: ""
        
        // Collect VOD content with "added" field
        cache?.vod?.streams?.mapNotNull { vod ->
            val addedDate = vod.added
            if (!addedDate.isNullOrEmpty()) {
                try {
                    val timestamp = addedDate.toLongOrNull() ?: 0L
                    val streamUrl = "$serverUrl/movie/$username/$password/${vod.stream_id ?: ""}.${vod.container_extension ?: "mp4"}"
                    NewAddedItem(
                        contentId = vod.stream_id ?: "",
                        contentType = ContentType.MOVIE,
                        title = vod.name ?: "Unknown Movie",
                        posterUrl = vod.stream_icon,
                        addedDate = addedDate,
                        addedTimestamp = timestamp,
                        year = vod.releaseDate?.take(4),
                        quality = if (vod.name?.contains("4K", ignoreCase = true) == true) "4K" else "HD",
                        streamUrl = streamUrl
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        }?.let { newAddedItems.addAll(it) }
        
        // Note: Series data doesn't have "added" field in the API
        // Only VOD (movies) have it, so we only show movies in "New Added"
        
        // Sort by timestamp descending (newest first) and take top 10
        return newAddedItems.sortedByDescending { it.addedTimestamp }.take(10)
    }
    
    private fun loadRecentLiveChannels() {
        val historyItems = watchHistoryPrefs.getRecentLiveChannelsList()
            .sortedByDescending { it.lastWatchedTimestamp }
            .take(6)
        val items = if (historyItems.isNotEmpty()) historyItems else HomeSampleData.recentLiveChannels()
        recentLiveAdapter.updateItems(items)
        view?.findViewById<View>(R.id.section_recent_live)?.visibility = 
            if (items.isNotEmpty()) View.VISIBLE else View.GONE
    }
    
    private fun loadContinueWatching() {
        val historyItems = watchHistoryPrefs.getContinueWatchingList()
            .sortedByDescending { it.lastWatchedTimestamp }
            .take(5)
        val items = if (historyItems.isNotEmpty()) historyItems else HomeSampleData.continueWatchingItems()
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
                        HomeFavoriteMapper.mapToFavoriteItem(
                            entity = favorite, 
                            repository = repository, 
                            serverUrl = serverUrl,
                            username = username,
                            password = password
                        )
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
                // Series handled via detail navigation
            }
        }
    }
    
    private fun onNewAddedItemClick(item: NewAddedItem) {
        // Play new added item (or navigate to details for series)
        if (item.contentType == ContentType.MOVIE) {
            item.streamUrl?.let { url ->
                val intent = PlayerActivity.createIntent(
                    context = requireContext(),
                    streamUrl = url,
                    title = item.title,
                    contentId = item.contentId,
                    contentType = item.contentType,
                    posterUrl = item.posterUrl
                )
                startActivity(intent)
            }
        } else {
            // For series, need to navigate to series detail screen
            val fragment = com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2().apply {
                arguments = android.os.Bundle().apply {
                    putString("series_id", item.contentId)
                    putString("title", item.title)
                    putString("backdrop_url", item.posterUrl)
                    putString("poster_url", item.posterUrl)
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.content_container, fragment)
                .addToBackStack(null)
                .commit()
        }
    }
    
    private fun onContinueWatchingItemClick(item: ContinueWatchingItem) {
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
    
    private fun onRecentLiveChannelClick(item: RecentLiveChannelItem) {
        launchLiveStream(
            streamId = item.channelId,
            fallbackTitle = item.channelName,
            fallbackLogo = item.channelLogo,
            fallbackUrl = item.streamUrl,
            epgChannelId = item.epgChannelId
        )
    }
    
    private fun onFavoriteItemClick(item: FavoriteItem) {
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
