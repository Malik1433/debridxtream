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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
    private val episodeDaoV2: EpisodeDaoV2,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
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
                // Refresh UI when sync completes or if we have cache.
                // ST-1: hasCache() is a cheap in-memory/file-stat check — NEVER a
                // main-thread Gson parse (readCache() would have parsed here on cold start).
                if (progress.state == com.tvonnet.debridxtreamiptv.data.model.SyncState.SUCCESS ||
                    repository.hasCache()) {
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
        // Phase 1: run the whole home build OFF the main thread. It decodes the
        // continue-watching / recent-live prefs (Gson) and sorts the full VOD catalog for
        // the top-10 below — all of which used to run on Main. No View is touched here
        // (this is a ViewModel); _uiState is a thread-safe StateFlow.
        loadHomeDataJob = viewModelScope.launch(Dispatchers.Default) {
            android.util.Log.d("HISTORY_DEBUG", "HomeViewModel: refreshHomeData starting")
            runCatching {
                val continueWatching = watchHistoryPrefs.getContinueWatchingList()
                android.util.Log.d("HISTORY_DEBUG", "HomeViewModel: continueWatching size=${continueWatching.size}")
                val recentLiveChannels = watchHistoryPrefs.getRecentLiveChannelsList()
                android.util.Log.d("HISTORY_DEBUG", "HomeViewModel: recentLiveChannels size=${recentLiveChannels.size}")

                // ST-1: parse the multi-MB catalog OFF the main thread and warm the
                // in-memory cache, so the synchronous readCache() below is instant.
                repository.prewarmCache()
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
                // Tier gating: NORMAL devices are IPTV-only — the home rows (and hero,
                // which derives from them) must come from the Xtream catalog, never TMDB
                // (the debrid-side source). The IPTV path is the same proven fallback
                // used when TMDB is unreachable.
                // CONFIGURED, not allowed: one tier means every device may use debrid, so the row
                // content has to follow whether the customer actually supplied a debrid service —
                // otherwise these rows fetch and render sources that can never play.
                val debridAllowed = com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
                    .isDebridConfigured(appContext)

                // ST-2 Phase 1 (instant): build rows straight from the IPTV cache and
                // emit NOW — cached content appears in ms instead of waiting on the
                // 2 TMDB round-trips + per-item enrichment below. Emitted directly via
                // _uiState.update (NOT the guarded onSuccess path, which could collapse
                // it into the Phase-2 emission). This same snapshot is the fallback if
                // Phase 2 times out — so hero/top10 are NEVER wiped (audit F2/F10).
                val phase1Movies = (cache.vod?.streams ?: emptyList())
                    .sortedByDescending { it.added }.take(10)
                    .map { it.toFeaturedItem(serverUrl, username, password) }
                val phase1Series = (cache.series?.streams ?: emptyList()).take(10)
                    .map { it.toFeaturedItem(serverUrl) }
                val phase1Hero = (phase1Movies.firstOrNull() ?: phase1Series.firstOrNull())?.let { f ->
                    HeroContent(
                        title = f.title,
                        description = f.description ?: "Experience high-quality streaming on DebridXtream.",
                        imageUrl = f.backdropUrl ?: f.posterUrl,
                        rating = f.rating ?: "N/A",
                        type = if (f.contentType == ContentType.MOVIE) "MOVIE" else "SERIES",
                        streamId = f.contentId
                    )
                }
                val phase1HasContent = phase1Movies.isNotEmpty() || phase1Series.isNotEmpty() ||
                    continueWatching.isNotEmpty() || recentLiveChannels.isNotEmpty() || phase1Hero != null
                val phase1State = HomeUiState(
                    isLoading = false,
                    errorMessage = if (phase1HasContent) null else "Content is not available yet.",
                    isEmpty = !phase1HasContent,
                    top10Movies = phase1Movies,
                    top10Series = phase1Series,
                    continueWatching = continueWatching,
                    recentLiveChannels = recentLiveChannels,
                    heroItem = phase1Hero,
                    sections = emptyList()
                )
                _uiState.update { phase1State }

                val nextState = withTimeoutOrNull(12_000L) { coroutineScope {
                    // ST-2: fire both TMDB trending calls CONCURRENTLY (were sequential)
                    // so Phase-2 latency is one round-trip, not two.
                    val trendingMoviesDeferred = if (debridAllowed) async { tmdbRemoteDataSource.getTrendingMovies() } else null
                    val trendingSeriesDeferred = if (debridAllowed) async { tmdbRemoteDataSource.getTrendingTvShows() } else null

                    // 1. Trending Movies row — TMDB (premium/trial) or IPTV recently-added
                    val trendingMoviesResult = trendingMoviesDeferred?.await()
                    val top10MoviesList = if (trendingMoviesResult?.isSuccess == true) {
                        val list = trendingMoviesResult.getOrNull()?.results?.take(10)?.map { it.toFeaturedItem() } ?: emptyList()
                        android.util.Log.e("HISTORY_DEBUG", "Fetched ${list.size} TMDB Trending Movies")
                        list
                    } else {
                        // IPTV VOD sorted by added date (also the normal-tier primary source)
                        val vods = cache.vod?.streams ?: emptyList()
                        val list = vods.sortedByDescending { it.added }.take(10).map {
                            it.toFeaturedItem(serverUrl, username, password)
                        }
                        android.util.Log.w("HISTORY_DEBUG", "Movies row from IPTV cache (debridAllowed=$debridAllowed): ${list.size} items")
                        list
                    }

                    // 2. Trending Series row — TMDB (premium/trial) or IPTV series
                    val trendingSeriesResult = trendingSeriesDeferred?.await()
                    val top10SeriesList = if (trendingSeriesResult?.isSuccess == true) {
                        val list = trendingSeriesResult.getOrNull()?.results?.take(10)?.map { it.toFeaturedItem() } ?: emptyList()
                        android.util.Log.e("HISTORY_DEBUG", "Fetched ${list.size} TMDB Trending Series")
                        list
                    } else {
                        // IPTV Series (also the normal-tier primary source)
                        val series = cache.series?.streams ?: emptyList()
                        val list = series.take(10).map { it.toFeaturedItem(serverUrl) }
                        android.util.Log.w("HISTORY_DEBUG", "Series row from IPTV cache (debridAllowed=$debridAllowed): ${list.size} items")
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
                    android.util.Log.d("HISTORY_DEBUG", "Enriching ${continueWatching.size} Continue Watching items and ${recentLiveChannels.size} Recent Live items")

                    // Phase 2: enrich Continue-Watching artwork CONCURRENTLY (was an N+1 sequence of
                    // repo/DB lookups re-run on every home load / return). async+awaitAll preserves
                    // order; N is bounded (CW is capped) so no extra throttle is needed, and a
                    // Phase-2 timeout cancels these children via structured concurrency.
                    val enrichedContinueWatching = coroutineScope {
                        continueWatching.map { item ->
                            async {
                                if (needsArtworkEnrichment(item)) {
                                    enrichContinueWatchingArtwork(item, credentialsPrefs.getServerUrl() ?: "")
                                } else {
                                    item
                                }
                            }
                        }.awaitAll()
                    }

                    val enrichedRecentLive = coroutineScope {
                        recentLiveChannels.map { item ->
                            async {
                                if (item.channelLogo.isNullOrBlank() || !item.channelLogo.startsWith("http")) {
                                    val serverUrl = credentialsPrefs.getServerUrl() ?: ""
                                    repository.getLiveStreamById(item.channelId)?.let { stream ->
                                        val icon = stream.stream_icon.toAbsoluteUrl(ContentType.LIVE_TV, serverUrl)
                                        item.copy(channelLogo = icon)
                                    } ?: item
                                } else {
                                    item
                                }
                            }
                        }.awaitAll()
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
                } }

                if (nextState != null) {
                    nextState
                } else {
                    // ST-2 (audit F2/F10): on Phase-2 timeout, KEEP the Phase-1 cache
                    // snapshot (hero + top10 + CW) instead of the old empty state that
                    // wiped them. Phase 1 already rendered; this just stops the enrich
                    // pass from blanking it.
                    android.util.Log.w("HISTORY_DEBUG", "HomeViewModel: enrich timed out, keeping Phase-1 cache snapshot")
                    phase1State
                }
            }.onSuccess { nextState ->
                _uiState.update { current ->
                    if (rendersIdenticallyTo(current, nextState)) current else nextState
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

    /**
     * The home-flicker rule (e9e2ca9): hand the UI the SAME state object when nothing it renders
     * differs, so observers never re-bind. A loading state must always be replaced.
     */
    private fun rendersIdenticallyTo(current: HomeUiState, next: HomeUiState): Boolean =
        !current.isLoading &&
            current.errorMessage == next.errorMessage &&
            current.isEmpty == next.isEmpty &&
            current.top10Movies == next.top10Movies &&
            current.top10Series == next.top10Series &&
            current.continueWatching == next.continueWatching &&
            current.recentLiveChannels == next.recentLiveChannels &&
            current.heroItem == next.heroItem &&
            current.sections == next.sections

    /**
     * Only a provider (Xtream) row can be missing artwork — a debrid row already carries an absolute
     * URL, so re-deriving one would cost a lookup and change nothing.
     */
    private fun needsArtworkEnrichment(item: ContinueWatchingItem): Boolean =
        item.source == "xtream" &&
            (item.posterUrl.isNullOrBlank() || !item.posterUrl.startsWith("http"))

    private suspend fun enrichContinueWatchingArtwork(
        item: ContinueWatchingItem,
        serverUrl: String
    ): ContinueWatchingItem = when (item.contentType) {
        ContentType.MOVIE -> repository.getVodById(item.contentId)?.let { vod ->
            val icon = vod.stream_icon.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
            val cover = vod.cover.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
            item.copy(posterUrl = icon ?: cover, backdropUrl = cover ?: icon)
        } ?: item
        ContentType.SERIES, ContentType.EPISODE -> enrichSeriesContinueWatchingArtwork(item, serverUrl)
        else -> item
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

    /**
     * What coming back from the player is allowed to change: the history rows, and nothing else.
     *
     * Returning to the home screen used to re-run the whole build, and that is what made it flash.
     * [loadHomeData] emits TWICE by design — first a snapshot from the IPTV cache so something is on
     * screen immediately, then the TMDB-trending pass — and those two produce *different* top-10
     * lists. So every trip back from a video swapped the rails out and back, which re-derived the
     * hero rotation, reset it to the first slide, and made Glide reload the full-screen backdrop.
     * A ~110ms GC freeing 20MB of bitmaps sat right in the middle of it.
     *
     * Nothing up there can have changed while the user was watching. What can is the resume position,
     * whether a title finished, and which channel was last played — all of which live in prefs. So
     * this reads those, and touches only those two fields.
     *
     * Artwork already resolved for a row is carried over rather than re-derived, or the tile would
     * blink back to a placeholder — the flicker in miniature. A row that is genuinely new (the title
     * just watched) is enriched here, so it does not sit without a poster until the next full load.
     */
    fun refreshHistoryRows() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val freshContinueWatching = watchHistoryPrefs.getContinueWatchingList()
                val freshRecentLive = watchHistoryPrefs.getRecentLiveChannelsList()
                val alreadyResolved = _uiState.value.continueWatching.associateBy { it.contentId }
                val serverUrl = credentialsPrefs.getServerUrl() ?: ""

                val merged = freshContinueWatching.map { item ->
                    val known = alreadyResolved[item.contentId]
                    when {
                        known != null -> item.copy(
                            posterUrl = known.posterUrl ?: item.posterUrl,
                            backdropUrl = known.backdropUrl ?: item.backdropUrl
                        )
                        needsArtworkEnrichment(item) -> enrichContinueWatchingArtwork(item, serverUrl)
                        else -> item
                    }
                }

                _uiState.update { current ->
                    if (merged == current.continueWatching &&
                        freshRecentLive == current.recentLiveChannels
                    ) {
                        current   // nothing changed — do not hand the UI a new state to re-bind
                    } else {
                        current.copy(continueWatching = merged, recentLiveChannels = freshRecentLive)
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.w("HomeViewModel", "History-row refresh failed", error)
            }
        }
    }

    fun clearContinueWatchingItem(item: ContinueWatchingItem) {
        watchHistoryPrefs.removeContinueWatchingItem(item)
        watchHistoryPrefs.suppressContinueWatchingWrite(item)
        loadHomeDataJob?.cancel()
        loadHomeDataJob = null
        loadHomeData()
    }
}
