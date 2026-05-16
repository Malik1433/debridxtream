package com.tvonnet.debridxtreamiptv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShow
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isEmpty: Boolean = false,
    val top10Movies: List<FeaturedItem> = emptyList(),
    val top10Series: List<FeaturedItem> = emptyList(),
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val recentLiveChannels: List<RecentLiveChannelItem> = emptyList(),
    val heroItem: HeroContent? = null,
    val sections: List<HomeSection> = emptyList()
)


sealed class HomeSection {
    data class MovieRow(val title: String, val items: List<Any>) : HomeSection() // Replace Any with actual model
    data class SeriesRow(val title: String, val items: List<Any>) : HomeSection()
    data class ChannelRow(val title: String, val items: List<Any>) : HomeSection()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val debridPrefs: DebridPreferences,
    private val homePrefs: HomePreferences,
    private val credentialsPrefs: CredentialsPreferences,
    private val watchHistoryPrefs: WatchHistoryPreferences,
    private val seriesDaoV2: SeriesDaoV2,
    private val episodeDaoV2: EpisodeDaoV2
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var loadHomeDataJob: Job? = null

    init {
        observeSyncAndLoad()
    }

    private fun observeSyncAndLoad() {
        viewModelScope.launch {
            repository.syncProgress.collect { progress ->
                // Refresh UI when sync completes or if we have cache
                if (progress.state == com.tvonnet.debridxtreamiptv.data.model.SyncState.SUCCESS || 
                    repository.readCache() != null) {
                    loadHomeData()
                }
            }
        }
        
        // Listen for preference changes from Settings
        viewModelScope.launch {
            homePrefs.getCategoriesChangedFlow().collect {
                loadHomeData()
            }
        }

        // Initial load attempt
        loadHomeData()
    }

    private fun loadHomeData() {
        if (loadHomeDataJob?.isActive == true) return
        loadHomeDataJob = viewModelScope.launch {
            android.util.Log.e("HISTORY_DEBUG", "HomeViewModel: refreshHomeData starting")
            runCatching {
                val continueWatching = watchHistoryPrefs.getContinueWatchingList()
                android.util.Log.e("HISTORY_DEBUG", "HomeViewModel: continueWatching size=${continueWatching.size}")
                val recentLiveChannels = watchHistoryPrefs.getRecentLiveChannelsList()
                android.util.Log.e("HISTORY_DEBUG", "HomeViewModel: recentLiveChannels size=${recentLiveChannels.size}")

                val cache = repository.readCache()
                if (cache == null) {
                    val hasLocalContent = continueWatching.isNotEmpty() ||
                        recentLiveChannels.isNotEmpty()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (hasLocalContent) null else "Content is not available yet.",
                            isEmpty = !hasLocalContent,
                            top10Movies = emptyList(),
                            top10Series = emptyList(),
                            continueWatching = continueWatching,
                            recentLiveChannels = recentLiveChannels,
                            heroItem = null,
                            sections = emptyList()
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                val serverUrl = credentialsPrefs.getServerUrl() ?: ""
                val username = credentialsPrefs.getUsername() ?: ""
                val password = credentialsPrefs.getPassword() ?: ""

                // 1. Fetch TMDB Trending Movies
                val trendingMoviesResult = tmdbRemoteDataSource.getTrendingMovies()
                val top10MoviesList = if (trendingMoviesResult.isSuccess) {
                    val list = trendingMoviesResult.getOrNull()?.results?.take(10)?.map { it.toFeaturedItem() } ?: emptyList()
                    android.util.Log.e("HISTORY_DEBUG", "Fetched ${list.size} TMDB Trending Movies")
                    list
                } else {
                    // Fallback to IPTV VOD sorted by added date
                    val vods = cache.vod?.streams ?: emptyList()
                    val list = vods.sortedByDescending { it.added }.take(10).map {
                        it.toFeaturedItem(serverUrl, username, password)
                    }
                    android.util.Log.w("HISTORY_DEBUG", "TMDB Trending Movies failed, fallback to cache: ${list.size} items")
                    list
                }

                // 2. Fetch TMDB Trending Series
                val trendingSeriesResult = tmdbRemoteDataSource.getTrendingTvShows()
                val top10SeriesList = if (trendingSeriesResult.isSuccess) {
                    val list = trendingSeriesResult.getOrNull()?.results?.take(10)?.map { it.toFeaturedItem() } ?: emptyList()
                    android.util.Log.e("HISTORY_DEBUG", "Fetched ${list.size} TMDB Trending Series")
                    list
                } else {
                    // Fallback to IPTV Series
                    val series = cache.series?.streams ?: emptyList()
                    val list = series.take(10).map { it.toFeaturedItem(serverUrl) }
                    android.util.Log.w("HISTORY_DEBUG", "TMDB Trending Series failed, fallback to cache: ${list.size} items")
                    list
                }

                val heroFeatured = top10MoviesList.firstOrNull() ?: top10SeriesList.firstOrNull()
                val heroContent = heroFeatured?.let { f ->
                    HeroContent(
                        title = f.title,
                        description = f.description ?: "Experience high-quality streaming on DebridXtream.",
                        imageUrl = f.backdropUrl ?: f.posterUrl,
                        rating = f.rating ?: "N/A",
                        type = if (f.contentType == ContentType.MOVIE) "MOVIE" else "SERIES",
                        streamId = f.contentId
                    )
                }

                // 3. Enrich History Data (Artwork Restoration)
                android.util.Log.e("HISTORY_DEBUG", "Enriching ${continueWatching.size} Continue Watching items and ${recentLiveChannels.size} Recent Live items")
                
                val enrichedContinueWatching = mutableListOf<ContinueWatchingItem>()
                for (item in continueWatching) {
                    val enriched = if (item.source == "xtream" && (item.posterUrl.isNullOrBlank() || !item.posterUrl.startsWith("http"))) {
                        val serverUrl = credentialsPrefs.getServerUrl() ?: ""
                        android.util.Log.e("HISTORY_DEBUG", "Enriching Xtream VOD/EP: ${item.title} (id=${item.contentId}) using server: $serverUrl")
                        when (item.contentType) {
                            ContentType.MOVIE -> {
                                repository.getVodById(item.contentId)?.let { vod ->
                                    val icon = vod.stream_icon.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
                                    val cover = vod.cover.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
                                    android.util.Log.e("HISTORY_DEBUG", "Resolved VOD artwork: icon=$icon | cover=$cover")
                                    item.copy(posterUrl = icon ?: cover, backdropUrl = cover ?: icon)
                                } ?: item.also { android.util.Log.w("HISTORY_DEBUG", "VOD not found in repository: ${item.contentId}") }
                            }
                            ContentType.SERIES, ContentType.EPISODE -> {
                                enrichSeriesContinueWatchingArtwork(item, serverUrl)
                            }
                            else -> item
                        }
                    } else {
                        item
                    }
                    enrichedContinueWatching.add(enriched)
                }

                val enrichedRecentLive = mutableListOf<RecentLiveChannelItem>()
                for (item in recentLiveChannels) {
                    val enriched = if (item.channelLogo.isNullOrBlank() || !item.channelLogo.startsWith("http")) {
                        val serverUrl = credentialsPrefs.getServerUrl() ?: ""
                        android.util.Log.e("HISTORY_DEBUG", "Enriching Live Channel: ${item.channelName} (id=${item.channelId}) using server: $serverUrl")
                        repository.getLiveStreamById(item.channelId)?.let { stream ->
                            val icon = stream.stream_icon.toAbsoluteUrl(ContentType.LIVE_TV, serverUrl)
                            android.util.Log.e("HISTORY_DEBUG", "Resolved Live logo: $icon")
                            item.copy(channelLogo = icon)
                        } ?: item.also { android.util.Log.w("HISTORY_DEBUG", "Live stream not found in repository: ${item.channelId}") }
                    } else {
                        item
                    }
                    enrichedRecentLive.add(enriched)
                }

                val newSections = emptyList<HomeSection>()
                // Legacy rows removed as requested to focus on Cinematic Top 10s
                val hasAnyContent = top10MoviesList.isNotEmpty() ||
                    top10SeriesList.isNotEmpty() ||
                    enrichedContinueWatching.isNotEmpty() ||
                    enrichedRecentLive.isNotEmpty() ||
                    heroContent != null

                HomeUiState(
                    isLoading = false,
                    errorMessage = if (hasAnyContent) null else "Content is not available yet.",
                    isEmpty = !hasAnyContent,
                    top10Movies = top10MoviesList,
                    top10Series = top10SeriesList,
                    continueWatching = enrichedContinueWatching,
                    recentLiveChannels = enrichedRecentLive,
                    heroItem = heroContent,
                    sections = newSections
                )
            }.onSuccess { nextState ->
                _uiState.update { current ->
                    if (
                        !current.isLoading &&
                        current.errorMessage == nextState.errorMessage &&
                        current.isEmpty == nextState.isEmpty &&
                        current.top10Movies == nextState.top10Movies &&
                        current.top10Series == nextState.top10Series &&
                        current.continueWatching == nextState.continueWatching &&
                        current.recentLiveChannels == nextState.recentLiveChannels &&
                        current.heroItem == nextState.heroItem &&
                        current.sections == nextState.sections
                    ) {
                        current
                    } else {
                        nextState
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    val hasLocalContent = it.top10Movies.isNotEmpty() ||
                        it.top10Series.isNotEmpty() ||
                        it.continueWatching.isNotEmpty() ||
                        it.recentLiveChannels.isNotEmpty()
                    it.copy(
                        isLoading = false,
                        errorMessage = if (hasLocalContent) null else error.message ?: "Home content could not be loaded.",
                        isEmpty = !hasLocalContent
                    )
                }
            }
        }
    }

    private suspend fun enrichSeriesContinueWatchingArtwork(
        item: ContinueWatchingItem,
        serverUrl: String
    ): ContinueWatchingItem {
        val seriesId = resolveContinueWatchingSeriesId(item)
        val legacySeries = seriesId?.let { repository.getSeriesById(it) }
        val v2Series = seriesId?.let { seriesDaoV2.getSeriesById(it) }

        val providerPoster = firstUsableUrl(
            item.posterUrl.toAbsoluteUrl(ContentType.SERIES, serverUrl),
            legacySeries?.cover.toAbsoluteUrl(ContentType.SERIES, serverUrl),
            v2Series?.cover.toAbsoluteUrl(ContentType.SERIES, serverUrl)
        )
        val providerBackdrop = firstUsableUrl(
            item.backdropUrl.toAbsoluteUrl(ContentType.SERIES, serverUrl),
            v2Series?.backdropPath?.firstOrNull().toAbsoluteUrl(ContentType.SERIES, serverUrl),
            legacySeries?.cover.toAbsoluteUrl(ContentType.SERIES, serverUrl),
            v2Series?.cover.toAbsoluteUrl(ContentType.SERIES, serverUrl)
        )
        val resolvedTitle = firstNonBlank(
            item.seriesTitle,
            v2Series?.title,
            v2Series?.name,
            legacySeries?.name,
            item.title
        )
        val tmdbArtwork = if (providerPoster == null) findTmdbSeriesArtwork(resolvedTitle) else null
        val poster = providerPoster ?: tmdbArtwork?.first
        val backdrop = providerBackdrop ?: tmdbArtwork?.second ?: poster

        android.util.Log.e(
            "CW_SERIES_ARTWORK",
            "resolved contentId=${item.contentId} seriesId=$seriesId poster=${if (poster.isNullOrBlank()) "missing" else "present"} source=${if (providerPoster != null) "provider" else if (tmdbArtwork != null) "tmdb" else "none"}"
        )

        return item.copy(
            posterUrl = poster,
            backdropUrl = backdrop,
            seriesId = item.seriesId ?: seriesId,
            seriesTitle = item.seriesTitle ?: resolvedTitle
        )
    }

    private suspend fun resolveContinueWatchingSeriesId(item: ContinueWatchingItem): String? {
        item.seriesId?.takeIf { it.isNotBlank() }?.let { return it }
        if (item.contentType == ContentType.SERIES) return item.contentId.takeIf { it.isNotBlank() }
        if (item.contentType != ContentType.EPISODE) return null
        return runCatching { episodeDaoV2.getEpisodeById(item.contentId)?.seriesId }.getOrNull()
    }

    private suspend fun findTmdbSeriesArtwork(title: String?): Pair<String?, String?>? {
        val cleanTitle = cleanTmdbSeriesTitle(title) ?: return null
        return runCatching {
            withTimeoutOrNull(5000L) {
                val response = tmdbRemoteDataSource.searchTvShows(cleanTitle).getOrNull()
                val target = normalizeTmdbTitle(cleanTitle) ?: return@withTimeoutOrNull null
                val best = response?.results.orEmpty()
                    .asSequence()
                    .mapNotNull { show ->
                        val candidateTitle = show.name ?: show.originalName ?: return@mapNotNull null
                        val candidate = normalizeTmdbTitle(candidateTitle) ?: return@mapNotNull null
                        val score = when {
                            candidate == target -> 0
                            candidate.contains(target) || target.contains(candidate) -> 1
                            else -> 5
                        }
                        Triple(score, -(show.popularity ?: 0.0), show)
                    }
                    .filter { it.first <= 1 }
                    .minWithOrNull(compareBy<Triple<Int, Double, TmdbTvShow>> { it.first }.thenBy { it.second })
                    ?.third

                if (best == null) {
                    android.util.Log.d("CW_SERIES_ARTWORK", "TMDB no match title=$cleanTitle")
                    null
                } else {
                    android.util.Log.d("CW_SERIES_ARTWORK", "TMDB match title=$cleanTitle id=${best.id}")
                    TmdbImageUrl.getPosterUrl(best.posterPath) to TmdbImageUrl.getBackdropUrl(best.backdropPath)
                }
            }
        }.getOrNull()
    }

    private fun firstUsableUrl(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() && !it.equals("null", ignoreCase = true) }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun cleanTmdbSeriesTitle(rawTitle: String?): String? =
        rawTitle
            ?.replace(Regex("\\bS\\d{1,2}\\s*E\\d{1,2}\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\b\\d{1,2}x\\d{1,2}\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\bSeason\\s+\\d+\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\bEpisode\\s+\\d+\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\b(4k|2160p|1080p|720p|480p|hdr|hevc|x265|x264|web[- ]?dl|webrip|bluray)\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length >= 2 }

    private fun normalizeTmdbTitle(value: String?): String? =
        value
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\bthe\\b"), " ")
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }

    fun refreshHomeData() {
        loadHomeData()
    }
}
