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
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
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

data class XRayMetadataUiState(
    val title: String,
    val overview: String?,
    val meta: String?,
    val director: String?,
    val cast: List<XRayCastMember>
)

data class XRayCastMember(
    val name: String,
    val character: String?,
    val avatarUrl: String?
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _overlayState = MutableStateFlow(PlayerOverlayUiState())
    val overlayState: StateFlow<PlayerOverlayUiState> = _overlayState.asStateFlow()

    private var epgJob: Job? = null
    private var epgRefreshJob: Job? = null
    private val refreshAttempts = mutableMapOf<String, Long>()

    private val _zapState = MutableStateFlow<ZapState?>(null)
    val zapState: StateFlow<ZapState?> = _zapState.asStateFlow()

    private val _seriesPlaylistState = MutableStateFlow<SeriesPlaylistState?>(null)
    val seriesPlaylistState: StateFlow<SeriesPlaylistState?> = _seriesPlaylistState.asStateFlow()

    private val _debridResolutionState = MutableStateFlow<DebridResolutionState>(DebridResolutionState.Idle)
    val debridResolutionState: StateFlow<DebridResolutionState> = _debridResolutionState.asStateFlow()

    private val _xrayMetadata = MutableStateFlow<XRayMetadataUiState?>(null)
    val xrayMetadata: StateFlow<XRayMetadataUiState?> = _xrayMetadata.asStateFlow()

    fun loadSeriesPlaylist(
        seriesId: String,
        seasonNum: Int,
        startEpisodeId: String,
        seriesTitle: String? = null
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
                        null
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
                emitSeriesPlaylist(mappedEpisodes, startEpisodeId, error)
            } catch (e: Exception) {
                android.util.Log.d("IPTV_EP_LOAD_FIX", "NETWORK_FETCH_DONE result=error:${e.javaClass.simpleName}")
                android.util.Log.d("IPTV_EP_LOAD_FIX", "LOCAL_AFTER count=0")
                emitSeriesPlaylist(emptyList(), startEpisodeId, "Episodes unavailable")
            }
        }
    }

    private fun emitSeriesPlaylist(
        episodes: List<EpisodeEntityV2>,
        startEpisodeId: String,
        error: String?
    ) {
        val startIndex = episodes.indexOfFirst { it.episodeId == startEpisodeId }.takeIf { it >= 0 } ?: -1
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
        if (episodes.all { hasUsableArtworkUrl(it.thumbnail) }) {
            return episodes
        }
        val cleanTitle = cleanSeriesTitleForTmdb(seriesTitle)
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
                        if (hasUsableArtworkUrl(episode.thumbnail) || stillUrl == null) {
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
        val queries = buildTmdbSearchQueries(title).take(4)
        for (query in queries) {
            android.util.Log.d("IPTV_TMDB_POSTERS", "SEARCH query=$query")
            val response = tmdbRemote.searchTvShows(query).getOrNull() ?: continue
            val results = response.results.orEmpty()
            if (results.isEmpty()) continue

            val target = normalizeTmdbTitle(query) ?: continue
            val best = results
                .asSequence()
                .mapNotNull { show ->
                    val id = show.id ?: return@mapNotNull null
                    val candidateTitle = show.name ?: show.originalName ?: return@mapNotNull null
                    val candidate = normalizeTmdbTitle(candidateTitle) ?: return@mapNotNull null
                    val titleScore = when {
                        candidate == target -> 0
                        candidate.contains(target) || target.contains(candidate) -> 1
                        tokenOverlapScore(target, candidate) >= 0.75f -> 2
                        else -> 5
                    }
                    val popularity = show.popularity ?: 0.0
                    Triple(titleScore, -popularity, id)
                }
                .filter { it.first <= 2 }
                .minWithOrNull(compareBy<Triple<Int, Double, Int>> { it.first }.thenBy { it.second })

            if (best != null) {
                android.util.Log.d("IPTV_TMDB_POSTERS", "TV_MATCH query=$query id=${best.third} score=${best.first}")
                return best.third
            }
        }
        return null
    }

    private fun hasUsableArtworkUrl(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("n/a", ignoreCase = true)
    }

    private fun cleanSeriesTitleForTmdb(rawTitle: String?): String? {
        return rawTitle
            ?.replace(Regex("\\bS\\d{1,2}\\s*E\\d{1,2}\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\b\\d{1,2}x\\d{1,2}\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\bSeason\\s+\\d+\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\bEpisode\\s+\\d+\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("^\\[[A-Za-z]{2,3}]\\s*"), " ")
            ?.replace(Regex("^[A-Za-z]{2,3}\\s*[-|]\\s*"), " ")
            ?.replace(Regex("\\b(multi|dual|audio|dubbed|hindi|english|french|spanish|arabic|turkish|portuguese)\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\b(4k|2160p|1080p|720p|480p|hdr|dv|hevc|x265|x264|h\\.265|h\\.264|web[- ]?dl|webrip|bluray|brrip)\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\(\\s*\\d{4}\\s*\\)$"), " ")
            ?.replace(Regex("\\s+\\d{4}$"), " ")
            ?.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length >= 2 }
    }

    private fun buildTmdbSearchQueries(title: String): List<String> {
        val withoutColonSuffix = title.substringBefore(":").trim()
        val withoutDashSuffix = title.substringBefore(" - ").trim()
        return listOf(title, withoutColonSuffix, withoutDashSuffix)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun normalizeTmdbTitle(value: String?): String? {
        return value
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\bthe\\b"), " ")
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun tokenOverlapScore(target: String, candidate: String): Float {
        val targetTokens = target.chunked(3).toSet()
        val candidateTokens = candidate.chunked(3).toSet()
        if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0f
        return targetTokens.intersect(candidateTokens).size.toFloat() / targetTokens.size.toFloat()
    }

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
                e.printStackTrace()
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
            _debridResolutionState.value = DebridResolutionState.Loading
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
                    _debridResolutionState.value = DebridResolutionState.Error("No sources found for the next episode.")
                    return@launch
                }

                val targetSource = pickDebridSourceForContinuity(sources, infoHash, sourceProfile, excludeSourceIds)
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
                _debridResolutionState.value = DebridResolutionState.Error("Failed to fetch sources: ${e.message}")
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
                val targetSource = pickDebridSourceForContinuity(sources, infoHash, sourceProfile)
                val magnet = targetSource.stream.direct_source
                val hashToResolve = targetSource.stream.stream_id
                // Direct-playable URLs start instantly anyway; only magnet
                // resolution benefits from warming.
                if (isDirectPlayableUrl(magnet)) return@launch
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

    fun refreshDebridMovieSource(
        streamId: String?,
        title: String?,
        imdbId: String?,
        sourceProfile: DebridSourceProfile?,
        allowDirectHttpPassthrough: Boolean = true,
        excludeSourceIds: Set<String> = emptySet()
    ) {
        viewModelScope.launch {
            _debridResolutionState.value = DebridResolutionState.Loading
            if (streamId.isNullOrBlank() && imdbId.isNullOrBlank() && title.isNullOrBlank()) {
                _debridResolutionState.value = DebridResolutionState.Error("Missing metadata to refresh direct source.")
                return@launch
            }
            try {
                val savedId = sourceProfile?.streamId
                val sources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getMovieSources(
                        streamId = streamId,
                        title = title,
                        primaryCategoryId = "debrid",
                        yearHint = null,
                        imdbId = imdbId,
                        priorityInfoHash = savedId.takeUnless { it in excludeSourceIds },
                        preferredSourceType = sourceProfile?.sourceType
                    )
                }
                if (sources.isEmpty()) {
                    _debridResolutionState.value = DebridResolutionState.Error("No fresh movie sources found.")
                    return@launch
                }
                resolveDebridMovieSource(
                    targetSource = pickDebridSourceForContinuity(sources, savedId, sourceProfile, excludeSourceIds),
                    season = null,
                    episode = null,
                    title = title,
                    directPreferred = sourceProfile?.directPlayback == true,
                    allowDirectHttpPassthrough = allowDirectHttpPassthrough
                )
            } catch (e: Exception) {
                _debridResolutionState.value = DebridResolutionState.Error("Failed to refresh source: ${e.message}")
            }
        }
    }

    suspend fun getDebridLanguageOptions(
        streamId: String?,
        title: String?,
        imdbId: String?,
        sourceProfile: DebridSourceProfile?
    ): List<String> {
        return withContext(Dispatchers.IO) {
            val sources = unifiedSourceProvider.getMovieSources(
                streamId = streamId,
                title = title,
                primaryCategoryId = "debrid",
                yearHint = null,
                imdbId = imdbId
            )

            val languageSet = linkedSetOf<String>()
            sourceProfile?.languages.orEmpty().forEach { languageSet.add(it) }
            sources.forEach { source ->
                source.languages.orEmpty().forEach { languageSet.add(it) }
            }

            languageSet.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }

    private suspend fun resolveDebridMovieSource(
        targetSource: MovieSource,
        season: Int?,
        episode: Int?,
        title: String?,
        directPreferred: Boolean,
        allowDirectHttpPassthrough: Boolean,
        forceFresh: Boolean = true
    ) {
        val magnet = targetSource.stream.direct_source
        val hashToResolve = targetSource.stream.stream_id
        val profile = targetSource.toDebridSourceProfile(
            directPlayback = directPreferred && isDirectPlayableUrl(magnet)
        )

        if (allowDirectHttpPassthrough && directPreferred && isDirectPlayableUrl(magnet)) {
            _debridResolutionState.value = DebridResolutionState.Success(
                url = magnet!!,
                season = season,
                episode = episode,
                title = title,
                infoHash = hashToResolve,
                magnet = magnet,
                headers = targetSource.headers,
                directDebridPlayback = true,
                sourceProfile = profile
            )
            return
        }

        if (magnet.isNullOrBlank() && hashToResolve.isNullOrBlank()) {
            _debridResolutionState.value = DebridResolutionState.Error("Invalid source data.")
            return
        }

        val result = withContext(Dispatchers.IO) {
            playbackResolver.resolve(
                source = "debrid",
                streamUrl = null,
                // forceFresh bypasses the resolved-link cache (failure refresh);
                // a fresh next-episode load may hit a pre-warmed cached link.
                isExpired = forceFresh,
                infoHash = hashToResolve,
                magnet = magnet,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = title,
                allowDirectHttpPassthrough = allowDirectHttpPassthrough
            )
        }

        when (result) {
            is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                _debridResolutionState.value = DebridResolutionState.Success(
                    url = result.url,
                    season = season,
                    episode = episode,
                    title = title,
                    infoHash = hashToResolve,
                    magnet = magnet,
                    headers = targetSource.headers,
                    directDebridPlayback = false,
                    sourceProfile = targetSource.toDebridSourceProfile(directPlayback = false)
                )
            }
            is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                _debridResolutionState.value = DebridResolutionState.Error("Refresh required to play this episode.")
            }
            is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                _debridResolutionState.value = DebridResolutionState.Error(result.message)
            }
        }
    }

    private fun pickDebridSourceForContinuity(
        sources: List<MovieSource>,
        infoHash: String?,
        profile: DebridSourceProfile?,
        excludeSourceIds: Set<String> = emptySet()
    ): MovieSource {
        // Failover support: sources that already failed playback in this session
        // are skipped so an error-triggered refresh moves to the next source
        // instead of re-resolving the same broken one. If exclusion would leave
        // nothing, fall back to the full list rather than failing hard.
        val excludedStableIds = excludeSourceIds.mapNotNull { stableDebridIdentity(it) }.toSet()
        val candidates = if (excludedStableIds.isEmpty()) {
            sources
        } else {
            sources.filterNot { source ->
                stableDebridIdentity(source.stream.stream_id) in excludedStableIds
            }.ifEmpty { sources }
        }

        val exactId = profile?.streamId ?: infoHash
        candidates.firstOrNull { source ->
            !exactId.isNullOrBlank() && source.stream.stream_id.equals(exactId, ignoreCase = true)
        }?.let { return it }

        // stream_id carries a volatile "_<list index>" suffix, so the saved id
        // rarely matches verbatim across fetches. Compare on the stable part
        // (bare infoHash / title hash) so resume re-picks the same source.
        val stableExactId = stableDebridIdentity(exactId)
        if (stableExactId != null) {
            candidates.firstOrNull { source ->
                stableDebridIdentity(source.stream.stream_id) == stableExactId
            }?.let { return it }
        }

        // Language tier: when the exact source is gone (or excluded after a
        // failure), keep the user's audio language — restrict ranking to
        // candidates sharing a requested language (or multi-audio) when any
        // exist, instead of letting provider/quality outrank language.
        val requestedLanguages = normalizeLanguageSet(profile?.languages)
        val languagePool = if (requestedLanguages.isEmpty()) {
            candidates
        } else {
            candidates.filter { source ->
                val candidateLanguages = normalizeLanguageSet(source.languages)
                candidateLanguages.contains("multi") ||
                    candidateLanguages.intersect(requestedLanguages).isNotEmpty()
            }.ifEmpty { candidates }
        }

        val ranked = languagePool.sortedWith(
            compareByDescending<MovieSource> { debridContinuityScore(it, profile) }
                .thenByDescending { SourceFilterUtils.isPlaybackReady(it) }
                .thenByDescending { qualityScore(it.quality) }
                .thenByDescending { it.seeders ?: -1 }
        )
        return ranked.first()
    }

    private fun stableDebridIdentity(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return id.trim()
            .replace(Regex("_\\d+$"), "")
            .lowercase()
            .takeIf { it.isNotBlank() }
    }

    private fun debridContinuityScore(source: MovieSource, profile: DebridSourceProfile?): Int {
        if (profile == null) return 0
        var score = 0
        if (!profile.sourceType.isNullOrBlank() && source.sourceType.equals(profile.sourceType, ignoreCase = true)) score += 40
        if (!profile.provider.isNullOrBlank() && source.provider.equals(profile.provider, ignoreCase = true)) score += 35
        if (!profile.bingeGroup.isNullOrBlank() && source.bingeGroup.equals(profile.bingeGroup, ignoreCase = true)) score += 30
        if (!profile.sourceName.isNullOrBlank() && source.sourceName.equals(profile.sourceName, ignoreCase = true)) score += 15
        if (!profile.quality.isNullOrBlank() && source.quality.equals(profile.quality, ignoreCase = true)) score += 10
        val requestedLanguages = normalizeLanguageSet(profile.languages)
        val candidateLanguages = normalizeLanguageSet(source.languages)
        if (requestedLanguages.isNotEmpty()) {
            val overlap = requestedLanguages.intersect(candidateLanguages)
            score += overlap.size * 20
            if (candidateLanguages.contains("multi")) score += 8
        }
        if (profile.directPlayback && isDirectPlayableUrl(source.stream.direct_source)) score += 20
        if (SourceFilterUtils.isPlaybackReady(source)) score += 12
        return score
    }

    private fun normalizeLanguageSet(languages: List<String>?): Set<String> {
        return languages.orEmpty()
            .flatMap { it.split(',', '/', '|', '+', '&') }
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .map {
                when (it) {
                    "hindi", "hin" -> "hi"
                    "german", "deu", "ger" -> "de"
                    "english", "eng" -> "en"
                    "multi", "dual audio", "dual" -> "multi"
                    else -> it
                }
            }
            .toSet()
    }

    private fun qualityScore(quality: String?): Int {
        return when {
            quality?.contains("2160", ignoreCase = true) == true || quality?.contains("4K", ignoreCase = true) == true -> 4
            quality?.contains("1080", ignoreCase = true) == true -> 3
            quality?.contains("720", ignoreCase = true) == true -> 2
            quality?.contains("480", ignoreCase = true) == true -> 1
            else -> 0
        }
    }

    private fun isDirectPlayableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http", ignoreCase = true) && !url.endsWith(".torrent", ignoreCase = true)
    }

    private fun MovieSource.toDebridSourceProfile(directPlayback: Boolean): DebridSourceProfile {
        return DebridSourceProfile(
            provider = provider,
            sourceType = sourceType,
            sourceName = sourceName,
            languages = languages,
            quality = quality,
            streamId = stream.stream_id,
            bingeGroup = bingeGroup,
            fileIdx = fileIdx,
            directPlayback = directPlayback
        )
    }

    fun reResolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        allowDirectHttpPassthrough: Boolean = true
    ) {
        android.util.Log.d(
            "PlayerViewModel",
            "reResolveDebridUrl: infoHash=${SensitiveLogRedactor.describeHash(infoHash)}, magnet=${SensitiveLogRedactor.describeUrl(magnet)}, season=$season, episode=$episode, hasTitle=${!title.isNullOrBlank()}"
        )
        viewModelScope.launch {
            _debridResolutionState.value = DebridResolutionState.Loading
            try {
                val result = withContext(Dispatchers.IO) {
                    playbackResolver.resolve(
                        source = "debrid",
                        streamUrl = null,
                        isExpired = true, // Force re-resolution on retry
                        infoHash = infoHash,
                        magnet = magnet,
                        seasonNumber = season,
                        episodeNumber = episode,
                        episodeTitle = title,
                        allowDirectHttpPassthrough = allowDirectHttpPassthrough
                    )
                }

                when (result) {
                    is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                        _debridResolutionState.value = DebridResolutionState.Success(
                            url = result.url,
                            season = season,
                            episode = episode,
                            title = title,
                            infoHash = infoHash
                        )
                    }
                    is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                        _debridResolutionState.value = DebridResolutionState.Error("Retry failed: Refresh required.")
                    }
                    is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                        _debridResolutionState.value = DebridResolutionState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                _debridResolutionState.value = DebridResolutionState.Error("Failed to re-resolve: ${e.message}")
            }
        }
    }

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

    fun loadXRayMetadata(contentId: String?, tmdbId: String?, isMovie: Boolean, title: String?) {
        val lookupId = tmdbId?.replace("tmdb:", "")?.replace("imdb:", "")
            ?: contentId?.replace("tmdb:", "")?.replace("imdb:", "")
            ?: return

        viewModelScope.launch {
            try {
                if (isMovie) {
                    val movieIdInt = lookupId.toIntOrNull()
                    if (movieIdInt != null) {
                        val result = tmdbRemote.getMovieDetails(movieIdInt)
                        if (result is Result.Success) {
                            val details = result.data
                            val castList = details.credits?.cast?.take(8)?.map { cast ->
                                XRayCastMember(
                                    name = cast.name ?: "Unknown",
                                    character = cast.character,
                                    avatarUrl = com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl.getProfileUrl(cast.profilePath)
                                )
                            } ?: emptyList()

                            val directorName = details.credits?.cast?.firstOrNull { 
                                it.character?.contains("director", ignoreCase = true) == true 
                            }?.name

                            val durationText = details.runtime?.let {
                                val h = it / 60
                                val m = it % 60
                                if (h > 0) "${h}h ${m}m" else "${m}m"
                            }
                            val releaseYear = details.releaseDate?.take(4)
                            val metaText = listOfNotNull(releaseYear, durationText).joinToString(" • ")

                            _xrayMetadata.value = XRayMetadataUiState(
                                title = details.title ?: title ?: "Movie Details",
                                overview = details.overview,
                                meta = metaText,
                                director = directorName,
                                cast = castList
                            )
                        }
                    }
                } else {
                    val tvIdInt = lookupId.toIntOrNull()
                    if (tvIdInt != null) {
                        val result = tmdbRemote.getSeriesDetails(tvIdInt)
                        if (result is Result.Success) {
                            val details = result.data
                            _xrayMetadata.value = XRayMetadataUiState(
                                title = details.name ?: title ?: "Series Details",
                                overview = details.overview,
                                meta = details.firstAirDate?.take(4),
                                director = null,
                                cast = emptyList()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearXRayMetadata() {
        _xrayMetadata.value = null
    }

    fun observeEpg(epgChannelId: String?, streamId: String? = null) {
        epgJob?.cancel()
        epgRefreshJob?.cancel()

        if (epgChannelId.isNullOrBlank()) {
            _overlayState.value = PlayerOverlayUiState(
                epgAvailable = false,
                errorMessage = context.getString(R.string.player_epg_channel_unknown)
            )
            return
        }

        _overlayState.value = PlayerOverlayUiState(
            epgAvailable = false,
            lastSyncTime = readLastSyncTime(),
            isSyncing = true,
            errorMessage = null,
            streamId = streamId
        )

        epgJob = viewModelScope.launch {
            val flow = repository.getEpgByChannel(epgChannelId)

            if (flow == null) {
                val fallback = streamId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { id ->
                        withContext(Dispatchers.IO) {
                            repository.fetchShortEpgPrograms(streamId = id, channelKey = epgChannelId, limit = 12)
                        }
                    }

                if (fallback is Result.Success && fallback.data.isNotEmpty()) {
                    val list = fallback.data
                    val nowMs = System.currentTimeMillis()
                    val nowProgram = list.firstOrNull { nowMs in it.start..it.stop } ?: list.firstOrNull()
                    val nextProgram = list.firstOrNull { it.start > nowMs }
                    val upcoming = list.filter { it.start > nowMs }.take(6)
                    _overlayState.value = PlayerOverlayUiState(
                        now = nowProgram,
                        next = nextProgram,
                        upcoming = upcoming,
                        epgAvailable = true,
                        lastSyncTime = readLastSyncTime(),
                        isSyncing = false,
                        errorMessage = null,
                        streamId = streamId
                    )
                } else {
                    _overlayState.value = PlayerOverlayUiState(
                        epgAvailable = false,
                        errorMessage = context.getString(R.string.player_epg_unavailable_generic),
                        streamId = streamId
                    )
                }
                return@launch
            }

            flow.collect { programs ->
                val nowMs = System.currentTimeMillis()
                if (programs.isEmpty()) {
                    _overlayState.value = _overlayState.value.copy(
                        epgAvailable = false,
                        isSyncing = true,
                        errorMessage = null,
                        streamId = streamId
                    )
                    val fallback = streamId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { id ->
                            withContext(Dispatchers.IO) {
                                repository.fetchShortEpgPrograms(streamId = id, channelKey = epgChannelId, limit = 12)
                            }
                        }

                    if (fallback is Result.Success && fallback.data.isNotEmpty()) {
                        val list = fallback.data
                        val nowMs = System.currentTimeMillis()
                        val nowProgram = list.firstOrNull { nowMs in it.start..it.stop } ?: list.firstOrNull()
                        val nextProgram = list.firstOrNull { it.start > nowMs }
                        val upcoming = list.filter { it.start > nowMs }.take(6)
                        _overlayState.value = PlayerOverlayUiState(
                            now = nowProgram,
                            next = nextProgram,
                            upcoming = upcoming,
                            epgAvailable = true,
                            lastSyncTime = readLastSyncTime(),
                            isSyncing = false,
                            errorMessage = null,
                            streamId = streamId
                        )
                    } else {
                        _overlayState.value = PlayerOverlayUiState(
                            epgAvailable = false,
                            lastSyncTime = readLastSyncTime(),
                            isSyncing = false,
                            errorMessage = context.getString(R.string.player_epg_no_guide),
                            streamId = streamId
                        )
                    }
                    return@collect
                }

                val nowProgram = programs.firstOrNull { nowMs in it.start..it.stop }
                    ?: programs.firstOrNull()
                val nextProgram = programs.firstOrNull { it.start > nowMs }
                val upcoming = programs.filter { it.start > nowMs }.take(6)

                _overlayState.value = PlayerOverlayUiState(
                    now = nowProgram,
                    next = nextProgram,
                    upcoming = upcoming,
                    epgAvailable = true,
                    lastSyncTime = readLastSyncTime(),
                    isSyncing = false,
                    errorMessage = null,
                    streamId = streamId
                )
            }
        }
    }

    private fun readLastSyncTime(): Long? {
        return context
            .getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
            .getLong("last_sync_time", 0L)
            .takeIf { it > 0 }
    }

    fun updatePlaybackStatus(contentId: String, isWatched: Boolean, resumePosition: Long, duration: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.updateEpisodePlaybackStatus(contentId, isWatched, resumePosition, duration)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun initLiveZapping(
        categoryId: String?,
        currentStreamId: String?,
        baseServerUrl: String?,
        orderedStreamIds: List<String>?
    ) {
        if (categoryId.isNullOrBlank() || currentStreamId.isNullOrBlank() || baseServerUrl.isNullOrBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val prefs = CredentialsPreferences(context)
            repository.ensureInitialized(baseServerUrl, prefs.getUsername(), prefs.getPassword())

            val streams = cacheManager.getChannels(categoryId, streamType = "live").orEmpty()
            if (streams.isEmpty()) return@launch

            val byId = streams.associateBy { it.stream_id }
            val ordered = orderedStreamIds
                ?.mapNotNull { id -> byId[id] }
                ?.takeIf { it.isNotEmpty() }

            val list = (ordered ?: streams)
                .filter { !it.stream_id.isNullOrBlank() }
                .map { stream ->
                    ZapChannel(
                        streamId = stream.stream_id!!,
                        name = stream.name ?: context.getString(R.string.player_epg_channel_unknown),
                        logoUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
                        epgChannelId = stream.epg_channel_id,
                        streamUrl = repository.buildLiveStreamUrl(stream, baseServerUrl)
                    )
                }

            if (list.isEmpty()) return@launch

            val index = list.indexOfFirst { it.streamId == currentStreamId }.takeIf { it >= 0 } ?: 0
            _zapState.value = ZapState(categoryId = categoryId, channels = list, index = index)
        }
    }

    fun moveZap(direction: Int): ZapChannel? {
        val state = _zapState.value ?: return null
        if (state.channels.size <= 1) return null

        val size = state.channels.size
        val normalized = if (direction >= 0) 1 else -1
        val nextIndex = (state.index + normalized + size) % size

        val updated = state.copy(index = nextIndex)
        _zapState.value = updated
        return updated.channels[nextIndex]
    }

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    fun toggleBrowser(visible: Boolean, currentCategoryId: String? = null, currentChannelId: String? = null) {
        if (visible) {
             if (currentCategoryId != null && currentCategoryId != _browserState.value.selectedCategoryId) {
                 if (_browserState.value.categories.isEmpty()) {
                     loadBrowserCategories(initialCategoryId = currentCategoryId)
                 } else {
                     selectBrowserCategory(currentCategoryId)
                 }
             } else if (_browserState.value.categories.isEmpty()) {
                 loadBrowserCategories()
             }
        }
        _browserState.value = _browserState.value.copy(isVisible = visible)
    }

    private fun loadBrowserCategories(initialCategoryId: String? = null) {
        viewModelScope.launch {
            _browserState.value = _browserState.value.copy(isLoading = true, error = null)
            try {
                var categories = cacheManager.getCategories("live")
                if (categories == null) {
                    val result = repository.ensureLiveCategories()
                    if (result.isNotEmpty()) {
                        categories = result
                    }
                }

                if (!categories.isNullOrEmpty()) {
                    _browserState.value = _browserState.value.copy(
                        categories = categories,
                        isLoading = false
                    )
                    val targetId = initialCategoryId ?: _browserState.value.selectedCategoryId ?: categories.first().category_id
                    if (targetId != null) {
                       selectBrowserCategory(targetId)
                    }
                } else {
                    _browserState.value = _browserState.value.copy(
                        isLoading = false,
                        error = context.getString(R.string.series_category_unavailable_generic)
                    )
                }
            } catch (e: Exception) {
                _browserState.value = _browserState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun selectBrowserCategory(categoryId: String) {
        if (_browserState.value.selectedCategoryId == categoryId && _browserState.value.channels.isNotEmpty()) return

        viewModelScope.launch {
            _browserState.value = _browserState.value.copy(
                selectedCategoryId = categoryId,
                isLoadingChannels = true
            )
            
            try {
                var channels = cacheManager.getChannels(categoryId, "live")
                if (channels == null) {
                    val result = repository.fetchLiveStreamsForCategory(categoryId)
                    if (result is Result.Success) {
                        channels = result.data
                    }
                }

                val enrichedChannels = if (!channels.isNullOrEmpty()) {
                     withContext(Dispatchers.IO) {
                         repository.enrichChannelsWithCurrentEpg(channels!!)
                     }
                } else {
                     emptyList()
                }

                _browserState.value = _browserState.value.copy(
                    channels = enrichedChannels,
                    isLoadingChannels = false
                )
            } catch (e: Exception) {
                  _browserState.value = _browserState.value.copy(
                    isLoadingChannels = false,
                    channels = emptyList()
                )
            }
        }
    }
}
