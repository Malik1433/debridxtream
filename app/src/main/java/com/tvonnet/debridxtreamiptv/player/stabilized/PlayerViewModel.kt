package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor

sealed class DebridResolutionState {
    object Idle : DebridResolutionState()
    object Loading : DebridResolutionState()
    data class Success(
        val url: String,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val infoHash: String? = null,
        val magnet: String? = null,
        val headers: Map<String, String>? = null,
        val directDebridPlayback: Boolean = false,
        val sourceProfile: DebridSourceProfile? = null
    ) : DebridResolutionState()
    data class Error(val message: String) : DebridResolutionState()
}

data class DebridSourceProfile(
    val provider: String? = null,
    val sourceType: String? = null,
    val sourceName: String? = null,
    val languages: List<String>? = null,
    val quality: String? = null,
    val streamId: String? = null,
    val bingeGroup: String? = null,
    val fileIdx: Int? = null,
    val directPlayback: Boolean = false
)

data class BrowserUiState(
    val isVisible: Boolean = false,
    val categories: List<XtreamCategory> = emptyList(),
    val channels: List<XtreamStream> = emptyList(),
    val selectedCategoryId: String? = null,
    val isLoading: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val error: String? = null
)

data class ZapChannel(
    val streamId: String,
    val name: String,
    val logoUrl: String?,
    val epgChannelId: String?,
    val streamUrl: String
)

data class ZapState(
    val categoryId: String,
    val categoryName: String?,
    val channels: List<ZapChannel>,
    val index: Int
)

data class PlayerOverlayUiState(
    val now: EpgEntity? = null,
    val next: EpgEntity? = null,
    val upcoming: List<EpgEntity> = emptyList(),
    val epgAvailable: Boolean = true,
    val lastSyncTime: Long? = null,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val streamId: String? = null
) {
    fun formattedLastSync(): String? {
        return lastSyncTime?.let {
            val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            formatter.format(Date(it))
        }
    }
}

data class SeriesPlaylistState(
    val originalList: List<EpisodeEntityV2>,
    val currentIndex: Int,
    val currentEpisode: EpisodeEntityV2?,
    val hasNext: Boolean,
    val hasPrev: Boolean,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val seriesRepository: XtreamSeriesRepositoryV2,
    private val cacheManager: CacheManager,
    private val debridPlaybackRepository: DebridPlaybackRepository,
    private val unifiedSourceProvider: UnifiedSourceProvider,
    private val playbackResolver: com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver,
    private val tmdbRemote: com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource,
    private val releaseLanguageRepository: com.tvonnet.debridxtreamiptv.data.debrid.language.ReleaseLanguageRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * H4: persist the audio languages the player actually exposed for this debrid
     * release, keyed by its stable identity. Fire-and-forget off the main thread;
     * the source list overlays these over claimed/MULTI chips next time.
     */
    fun recordVerifiedLanguages(hash: String, languages: List<String>) {
        viewModelScope.launch {
            try {
                releaseLanguageRepository.recordVerified(hash, languages)
                android.util.Log.i("PlayerViewModel", "H4 learned languages for $hash: $languages")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PlayerViewModel", "H4 language record failed", e)
            }
        }
    }

    /** Every EPG surface in the player: now/next overlay, surf strip, guide grid (C10). */
    private val epg = PlayerEpgController(
        repository = repository,
        context = context,
        scope = viewModelScope,
        zapChannels = { zap.currentChannels() },
    )

    val overlayState: StateFlow<PlayerOverlayUiState> = epg.overlayState
    val surfEpg: StateFlow<Map<String, EpgEntity>> = epg.surfEpg
    val guideEpg: StateFlow<Map<String, List<EpgEntity>>> = epg.guideEpg

    fun observeEpg(epgChannelId: String?, streamId: String? = null) =
        epg.observeEpg(epgChannelId, streamId)

    fun refreshSurfEpg() = epg.refreshSurfEpg()

    fun loadGuideEpg(windowStart: Long, windowEnd: Long) = epg.loadGuideEpg(windowStart, windowEnd)

    private val refreshAttempts = mutableMapOf<String, Long>()

    /** Which channel is next: zap list, category picker, favourites, browser (C10). */
    private val zap = PlayerZapController(
        repository = repository,
        cacheManager = cacheManager,
        context = context,
        scope = viewModelScope,
    )

    val zapState: StateFlow<ZapState?> = zap.zapState
    val surfCategories: StateFlow<List<XtreamCategory>> = zap.surfCategories
    val liveFavoriteIds: StateFlow<Set<String>> = zap.liveFavoriteIds
    val browserState: StateFlow<BrowserUiState> = zap.browserState

    fun initLiveZapping(
        categoryId: String?,
        currentStreamId: String?,
        baseServerUrl: String?,
        orderedStreamIds: List<String>?
    ) = zap.initLiveZapping(categoryId, currentStreamId, baseServerUrl, orderedStreamIds)

    fun switchZapCategory(categoryId: String, baseServerUrl: String?) =
        zap.switchZapCategory(categoryId, baseServerUrl)

    fun moveZap(direction: Int): ZapChannel? = zap.moveZap(direction)

    fun zapTo(index: Int): ZapChannel? = zap.zapTo(index)

    fun loadSurfCategories() = zap.loadSurfCategories()

    fun observeLiveFavorites() = zap.observeLiveFavorites()

    suspend fun toggleLiveFavorite(streamId: String?, name: String?, iconUrl: String?): Boolean? =
        zap.toggleLiveFavorite(streamId, name, iconUrl)

    fun toggleBrowser(visible: Boolean, currentCategoryId: String? = null, currentChannelId: String? = null) =
        zap.toggleBrowser(visible, currentCategoryId, currentChannelId)

    fun selectBrowserCategory(categoryId: String) = zap.selectBrowserCategory(categoryId)

    private val _seriesPlaylistState = MutableStateFlow<SeriesPlaylistState?>(null)
    val seriesPlaylistState: StateFlow<SeriesPlaylistState?> = _seriesPlaylistState.asStateFlow()

    /** Turning a chosen debrid source into a playable URL — the resume path (C10). */
    private val debrid = PlayerDebridResolver(
        repository = repository,
        unifiedSourceProvider = unifiedSourceProvider,
        playbackResolver = playbackResolver,
        scope = viewModelScope,
    )
    val debridResolutionState: StateFlow<DebridResolutionState> = debrid.debridResolutionState

    fun loadSeriesPlaylist(
        seriesId: String,
        seasonNum: Int,
        startEpisodeId: String,
        seriesTitle: String? = null,
        startEpisodeNumber: Int? = null
    ) {
        android.util.Log.d("IPTV_EP_LOAD_FIX", "START series=${SensitiveLogRedactor.describeHash(seriesId)} season=$seasonNum currentEpisode=${SensitiveLogRedactor.describeHash(startEpisodeId)}")
        viewModelScope.launch {
            android.util.Log.d("IPTV_EP_LOAD_FIX", "EMIT loading=true")
            _seriesPlaylistState.value = SeriesPlaylistState(
                originalList = emptyList(),
                currentIndex = -1,
                currentEpisode = null,
                hasNext = false,
                hasPrev = false,
                isLoading = true,
                error = null
            )

            try {
                val localBefore = withContext(Dispatchers.IO) {
                    seriesRepository.getSeasonEpisodes(seriesId, seasonNum)
                }
                android.util.Log.d("IPTV_EP_LOAD_FIX", "LOCAL_BEFORE count=${localBefore.size}")

                if (localBefore.isNotEmpty()) {
                    emitSeriesPlaylist(
                        enrichIptvEpisodeArtwork(localBefore, seriesTitle, seasonNum),
                        startEpisodeId,
                        null,
                        startEpisodeNumber
                    )
                    return@launch
                }

                android.util.Log.d("IPTV_EP_LOAD_FIX", "NETWORK_FETCH_START timeoutMs=8000")
                val result = withTimeoutOrNull(8000L) {
                    seriesRepository.getSeriesById(seriesId).firstOrNull()
                }
                android.util.Log.d("IPTV_EP_LOAD_FIX", "NETWORK_FETCH_DONE result=${result?.javaClass?.simpleName ?: "timeout"}")

                val localAfter = withContext(Dispatchers.IO) {
                    seriesRepository.getSeasonEpisodes(seriesId, seasonNum)
                }
                android.util.Log.d("IPTV_EP_LOAD_FIX", "LOCAL_AFTER count=${localAfter.size}")

                val mappedEpisodes = enrichIptvEpisodeArtwork(localAfter, seriesTitle, seasonNum)
                val error = if (mappedEpisodes.isEmpty()) "No episodes available" else null
                emitSeriesPlaylist(mappedEpisodes, startEpisodeId, error, startEpisodeNumber)
            } catch (e: Exception) {
                android.util.Log.d("IPTV_EP_LOAD_FIX", "NETWORK_FETCH_DONE result=error:${e.javaClass.simpleName}")
                android.util.Log.d("IPTV_EP_LOAD_FIX", "LOCAL_AFTER count=0")
                emitSeriesPlaylist(emptyList(), startEpisodeId, "Episodes unavailable", startEpisodeNumber)
            }
        }
    }

    private fun emitSeriesPlaylist(
        episodes: List<EpisodeEntityV2>,
        startEpisodeId: String,
        error: String?,
        startEpisodeNumber: Int? = null
    ) {
        // Exact id match first. When playback started from a SIBLING category listing
        // (multi-source stream panel), the playing stream id doesn't exist in the
        // primary listing's episode list — fall back to the episode NUMBER so
        // hasNext/auto-next still work instead of silently dying at index -1.
        val startIndex = episodes.indexOfFirst { it.episodeId == startEpisodeId }
            .takeIf { it >= 0 }
            ?: startEpisodeNumber?.let { num ->
                episodes.indexOfFirst { it.episodeNumber == num }.takeIf { it >= 0 }
            }
            ?: -1
        val current = if (startIndex >= 0) episodes[startIndex] else null
        android.util.Log.d("IPTV_EP_LOAD_FIX", "MAPPED count=${episodes.size}")
        android.util.Log.d("IPTV_EP_LOAD_FIX", "EMIT loading=false count=${episodes.size} currentFound=${current != null} error=$error")
        _seriesPlaylistState.value = SeriesPlaylistState(
            originalList = episodes,
            currentIndex = startIndex,
            currentEpisode = current,
            hasNext = startIndex >= 0 && startIndex < episodes.size - 1,
            hasPrev = startIndex > 0,
            isLoading = false,
            error = error
        )
    }

    private suspend fun enrichIptvEpisodeArtwork(
        episodes: List<EpisodeEntityV2>,
        seriesTitle: String?,
        seasonNum: Int
    ): List<EpisodeEntityV2> {
        if (episodes.isEmpty()) return episodes
        if (episodes.all { SeriesTmdbMatching.hasUsableArtworkUrl(it.thumbnail) }) {
            return episodes
        }
        val cleanTitle = SeriesTmdbMatching.cleanSeriesTitleForTmdb(seriesTitle)
            ?: return episodes

        return withContext(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(7000L) {
                    val tvId = findBestTmdbTvMatch(cleanTitle)
                    if (tvId == null) {
                        android.util.Log.d("IPTV_TMDB_POSTERS", "NO_TV_MATCH title=$cleanTitle")
                        return@withTimeoutOrNull episodes
                    }

                    val season = tmdbRemote.getSeasonDetails(tvId, seasonNum)
                    val stillsByEpisode = (season as? Result.Success)
                        ?.data
                        ?.episodes
                        ?.mapNotNull { tmdbEpisode ->
                            val episodeNumber = tmdbEpisode.episodeNumber ?: return@mapNotNull null
                            val stillPath = tmdbEpisode.stillPath ?: return@mapNotNull null
                            episodeNumber to "https://image.tmdb.org/t/p/w500$stillPath"
                        }
                        ?.toMap()
                        .orEmpty()

                    android.util.Log.d("IPTV_TMDB_POSTERS", "MATCH tvId=$tvId stills=${stillsByEpisode.size}")
                    episodes.map { episode ->
                        val stillUrl = stillsByEpisode[episode.episodeNumber]
                        if (SeriesTmdbMatching.hasUsableArtworkUrl(episode.thumbnail) || stillUrl == null) {
                            episode
                        } else {
                            episode.copy(thumbnail = stillUrl)
                        }
                    }
                } ?: episodes
            }.getOrElse { error ->
                android.util.Log.d("IPTV_TMDB_POSTERS", "ERROR ${error.javaClass.simpleName}")
                episodes
            }
        }
    }

    private suspend fun findBestTmdbTvMatch(title: String): Int? {
        val queries = SeriesTmdbMatching.buildTmdbSearchQueries(title).take(4)
        for (query in queries) {
            android.util.Log.d("IPTV_TMDB_POSTERS", "SEARCH query=$query")
            val response = tmdbRemote.searchTvShows(query).getOrNull() ?: continue
            val results = response.results.orEmpty()
            if (results.isEmpty()) continue

            val target = SeriesTmdbMatching.normalizeTmdbTitle(query) ?: continue
            val best = bestTvCandidate(results, target)
            if (best != null) {
                android.util.Log.d("IPTV_TMDB_POSTERS", "TV_MATCH query=$query id=${best.third} score=${best.first}")
                return best.third
            }
        }
        return null
    }

    // Exact normalized-title match first (0), then containment (1), then token overlap (2);
    // anything worse (5) is filtered out. Popularity breaks ties within a score band.
    private fun bestTvCandidate(
        results: List<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShow>,
        target: String
    ): Triple<Int, Double, Int>? = results
        .asSequence()
        .mapNotNull { show ->
            val id = show.id ?: return@mapNotNull null
            val candidateTitle = show.name ?: show.originalName ?: return@mapNotNull null
            val candidate = SeriesTmdbMatching.normalizeTmdbTitle(candidateTitle) ?: return@mapNotNull null
            val titleScore = when {
                candidate == target -> 0
                candidate.contains(target) || target.contains(candidate) -> 1
                SeriesTmdbMatching.tokenOverlapScore(target, candidate) >= 0.75f -> 2
                else -> 5
            }
            val popularity = show.popularity ?: 0.0
            Triple(titleScore, -popularity, id)
        }
        .filter { it.first <= 2 }
        .minWithOrNull(compareBy<Triple<Int, Double, Int>> { it.first }.thenBy { it.second })

    fun loadDebridSeriesPlaylist(tmdbId: String, seasonNum: Int, currentEpisodeNumber: Int) {
        android.util.Log.d("PlayerViewModel", "loadDebridSeriesPlaylist: tmdbId=$tmdbId, season=$seasonNum")
        viewModelScope.launch {
            try {
                val cleanId = tmdbId.replace("tmdb:", "").replace("imdb:", "")
                val tmdbIdInt = cleanId.toIntOrNull() ?: run {
                    android.util.Log.e("PlayerViewModel", "Invalid TMDB ID: $cleanId")
                    return@launch
                }
                val result = tmdbRemote.getSeasonDetails(tmdbIdInt, seasonNum)
                if (result is Result.Success) {
                    val tmdbEpisodes = result.data.episodes ?: emptyList()
                    val episodes = tmdbEpisodes.map { tmdbEpisode ->
                        val epId = "$tmdbId:$seasonNum:${tmdbEpisode.episodeNumber ?: 0}"
                        EpisodeEntityV2(
                            episodeId = epId,
                            seriesId = tmdbId,
                            seasonNumber = seasonNum,
                            episodeNumber = tmdbEpisode.episodeNumber ?: 0,
                            title = tmdbEpisode.name,
                            containerExtension = "mp4",
                            streamType = "series",
                            durationSecs = (tmdbEpisode.runtime?.times(60))?.toString(),
                            added = null,
                            customSid = null,
                            directSource = null,
                            thumbnail = "https://image.tmdb.org/t/p/w500${tmdbEpisode.stillPath}",
                            plot = tmdbEpisode.overview,
                            cast = null,
                            director = null,
                            genre = null,
                            releaseDate = tmdbEpisode.airDate,
                            rating = tmdbEpisode.voteAverage?.toString()
                        )
                    }

                    if (episodes.isNotEmpty()) {
                        val startIndex = episodes.indexOfFirst { it.episodeNumber == currentEpisodeNumber }.takeIf { it >= 0 } ?: -1
                        val current = if (startIndex >= 0) episodes[startIndex] else null

                        _seriesPlaylistState.value = SeriesPlaylistState(
                            originalList = episodes,
                            currentIndex = startIndex,
                            currentEpisode = current,
                            hasNext = startIndex >= 0 && startIndex < episodes.size - 1,
                            hasPrev = startIndex > 0
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Series playlist load failed", e)
            }
        }
    }

    fun getNextEpisode(): EpisodeEntityV2? {
        val state = _seriesPlaylistState.value ?: return null
        if (!state.hasNext) return null

        val nextIndex = state.currentIndex + 1
        val nextEpisode = state.originalList[nextIndex]

        _seriesPlaylistState.value = state.copy(
            currentIndex = nextIndex,
            currentEpisode = nextEpisode,
            hasNext = nextIndex < state.originalList.size - 1,
            hasPrev = true
        )
        return nextEpisode
    }

    fun loadNextDebridEpisode(
        seriesId: String,
        targetSeason: Int,
        targetEpisode: Int,
        seriesTitle: String?,
        infoHash: String?,
        sourceProfile: DebridSourceProfile? = null,
        allowDirectHttpPassthrough: Boolean = true,
        excludeSourceIds: Set<String> = emptySet()
    ) {
        viewModelScope.launch {
            debrid.setLoading()
            try {
                // If the countdown pre-warm for this exact episode is still running,
                // let it finish instead of firing a duplicate RD resolution chain —
                // its result is waiting in the resolved-link cache.
                if (lastPreResolvedEpisodeKey == "$seriesId:$targetSeason:$targetEpisode") {
                    nextEpisodePreResolveJob?.join()
                }
                // On failover the saved source is dead — verify the candidate
                // pool instead of fast-pathing verification to the dead hash.
                val savedId = sourceProfile?.streamId ?: infoHash
                val sources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getSeriesEpisodeSources(
                        seriesId = seriesId,
                        seasonNumber = targetSeason,
                        episodeNumber = targetEpisode,
                        title = seriesTitle ?: "",
                        yearHint = null,
                        priorityInfoHash = savedId.takeUnless { it in excludeSourceIds },
                        preferredSourceType = sourceProfile?.sourceType
                    )
                }

                if (sources.isEmpty()) {
                    debrid.setError("No sources found for the next episode.")
                    return@launch
                }

                val targetSource = debrid.pickSourceForContinuity(sources, infoHash, sourceProfile, excludeSourceIds)
                resolveDebridMovieSource(
                    targetSource = targetSource,
                    season = targetSeason,
                    episode = targetEpisode,
                    title = seriesTitle,
                    directPreferred = sourceProfile?.directPlayback == true,
                    allowDirectHttpPassthrough = allowDirectHttpPassthrough,
                    // Fresh next-episode loads may reuse the pre-warmed link;
                    // failover (excluded sources) must always re-resolve.
                    forceFresh = excludeSourceIds.isNotEmpty()
                )
            } catch (e: Exception) {
                debrid.setError("Failed to fetch sources: ${e.message}")
            }
        }
    }

    private var nextEpisodePreResolveJob: kotlinx.coroutines.Job? = null
    private var lastPreResolvedEpisodeKey: String? = null

    /**
     * Silently warm the debrid resolution for the upcoming episode while the
     * next-episode countdown is on screen. The resolved URL lands in the
     * repository's resolved-link cache, so the real resolution on confirm is a
     * cache hit. Never emits DebridResolutionState — failures stay invisible
     * and the confirm path remains the single source of truth.
     */
    fun preResolveNextDebridEpisode(
        seriesId: String,
        targetSeason: Int,
        targetEpisode: Int,
        seriesTitle: String?,
        infoHash: String?,
        sourceProfile: DebridSourceProfile? = null
    ) {
        val key = "$seriesId:$targetSeason:$targetEpisode"
        if (lastPreResolvedEpisodeKey == key) return
        lastPreResolvedEpisodeKey = key
        nextEpisodePreResolveJob?.cancel()
        nextEpisodePreResolveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sources = unifiedSourceProvider.getSeriesEpisodeSources(
                    seriesId = seriesId,
                    seasonNumber = targetSeason,
                    episodeNumber = targetEpisode,
                    title = seriesTitle ?: "",
                    yearHint = null,
                    priorityInfoHash = sourceProfile?.streamId ?: infoHash,
                    preferredSourceType = sourceProfile?.sourceType
                )
                if (sources.isEmpty()) return@launch
                val targetSource = debrid.pickSourceForContinuity(sources, infoHash, sourceProfile)
                val magnet = targetSource.stream.direct_source
                val hashToResolve = targetSource.stream.stream_id
                // Direct-playable URLs start instantly anyway; only magnet
                // resolution benefits from warming.
                if (DebridSourceScoring.isDirectPlayableUrl(magnet)) return@launch
                if (magnet.isNullOrBlank() && hashToResolve.isNullOrBlank()) return@launch
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = null,
                    isExpired = false,
                    infoHash = hashToResolve,
                    magnet = magnet,
                    seasonNumber = targetSeason,
                    episodeNumber = targetEpisode,
                    episodeTitle = seriesTitle,
                    allowDirectHttpPassthrough = true
                )
            } catch (_: Exception) {
                // Pre-warm only — the confirm path will resolve for real.
            }
        }
    }

    /**
     * Fetch the full movie source list for the in-player "Sources" panel (language/quality
     * switch mid-playback). Unlike [refreshDebridMovieSource] this does NOT auto-pick or play
     * — it just returns the list for the picker to display.
     */
    suspend fun fetchMovieSourcesForPanel(
        streamId: String?,
        title: String?,
        imdbId: String?,
        cleanTitle: String?
    ): List<MovieSource> = debrid.fetchMovieSourcesForPanel(streamId, title, imdbId, cleanTitle)

    @Suppress("LongParameterList") // established public API
    fun refreshDebridMovieSource(
        streamId: String?,
        title: String?,
        imdbId: String?,
        sourceProfile: DebridSourceProfile?,
        allowDirectHttpPassthrough: Boolean = true,
        excludeSourceIds: Set<String> = emptySet()
    ) = debrid.refreshMovieSource(
        streamId, title, imdbId, sourceProfile, allowDirectHttpPassthrough, excludeSourceIds
    )

    suspend fun getDebridLanguageOptions(
        streamId: String?,
        title: String?,
        imdbId: String?,
        sourceProfile: DebridSourceProfile?
    ): List<String> = debrid.getLanguageOptions(streamId, title, imdbId, sourceProfile)

    /**
     * Kept on the ViewModel with this exact signature: PlayerViewModelDebridDirectPassthroughTest
     * reaches it through `PlayerViewModel::class.declaredFunctions`, and that test pins the
     * direct-passthrough rule that history/resume depends on.
     */
    @Suppress("LongParameterList") // signature is pinned by the reflection-based test above
    private suspend fun resolveDebridMovieSource(
        targetSource: MovieSource,
        season: Int?,
        episode: Int?,
        title: String?,
        directPreferred: Boolean,
        allowDirectHttpPassthrough: Boolean,
        forceFresh: Boolean = true
    ) = debrid.resolveMovieSource(
        targetSource, season, episode, title, directPreferred, allowDirectHttpPassthrough, forceFresh
    )

    @Suppress("LongParameterList") // established public API
    fun reResolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        allowDirectHttpPassthrough: Boolean = true
    ) = debrid.reResolveUrl(infoHash, magnet, season, episode, title, allowDirectHttpPassthrough)

    fun getPrevEpisode(): EpisodeEntityV2? {
        val state = _seriesPlaylistState.value ?: return null
        if (!state.hasPrev) return null

        val prevIndex = state.currentIndex - 1
        val prevEpisode = state.originalList[prevIndex]

        _seriesPlaylistState.value = state.copy(
            currentIndex = prevIndex,
            currentEpisode = prevEpisode,
            hasNext = true,
            hasPrev = prevIndex > 0
        )
        return prevEpisode
    }

    fun getSeriesStreamUrl(episode: EpisodeEntityV2): String {
        return seriesRepository.getStreamUrl(episode.episodeId, episode.containerExtension)
    }

    fun clearSeriesPlaylist() {
        _seriesPlaylistState.value = null
    }

    fun updatePlaybackStatus(contentId: String, isWatched: Boolean, resumePosition: Long, duration: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.updateEpisodePlaybackStatus(contentId, isWatched, resumePosition, duration)
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Playback status update failed", e)
            }
        }
    }

}
