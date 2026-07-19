package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tvonnet.debridxtreamiptv.core.network.NetworkResult
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchedStateDao
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeriesDetailViewModelV2 @Inject constructor(
    private val repository: XtreamSeriesRepositoryV2,
    private val episodeDao: EpisodeDaoV2,
    private val watchedStateDao: WatchedStateDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val seriesId: String = checkNotNull(savedStateHandle["series_id"]) { "series_id is required" }
    // These args might be null if coming from a deep link, but usually required for Context
    private val username: String? = savedStateHandle["username"]
    private val password: String? = savedStateHandle["password"]

    // Navigation Events
    private val _navigationEvents = Channel<SeriesNavigationEvent>()
    val navigationEvents = _navigationEvents.receiveAsFlow()

    // Season State
    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason

    val seriesWatchedState: StateFlow<SeriesWatchedState> = combine(
        episodeDao.observeSeasonEpisodeCounts(seriesId),
        watchedStateDao.observeWatchedCountsForSeries(seriesId)
    ) { totalCounts, watchedCounts ->
        val total = totalCounts.sumOf { it.totalEpisodes }
        val watched = watchedCounts.sumOf { it.watchedEpisodes }
        when {
            watched == 0 -> SeriesWatchedState.UNWATCHED
            watched >= total && total > 0 -> SeriesWatchedState.WATCHED
            else -> SeriesWatchedState.IN_PROGRESS
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SeriesWatchedState.UNWATCHED
    )

    val seasons: StateFlow<List<SeasonUiModel>> = combine(
        repository.getSeasons(seriesId),
        episodeDao.observeSeasonEpisodeCounts(seriesId),
        watchedStateDao.observeWatchedCountsForSeries(seriesId)
    ) { seasonsList, totalCounts, watchedCounts ->
        val totalMap = totalCounts.associate { it.season_number to it.totalEpisodes }
        val watchedMap = watchedCounts.associate { it.season_number to it.watchedEpisodes }
        
        seasonsList.map { seasonNum ->
            val total = totalMap[seasonNum] ?: 0
            val watched = watchedMap[seasonNum] ?: 0
            
            val state = when {
                watched == 0 -> SeasonWatchedState.UNWATCHED
                watched >= total && total > 0 -> SeasonWatchedState.WATCHED
                else -> SeasonWatchedState.IN_PROGRESS
            }
            SeasonUiModel(seasonNum, state)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** (seasons, episodes) totals for the metadata line. */
    val seriesTotals: StateFlow<Pair<Int, Int>> = episodeDao.observeSeasonEpisodeCounts(seriesId)
        .map { counts -> counts.size to counts.sumOf { it.totalEpisodes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0)

    /** identityKey -> saved progress ms, for in-progress episodes (episode-strip resume bars). */
    val episodeProgress: StateFlow<Map<String, Long>> = watchedStateDao
        .observeInProgressEpisodesForSeries(
            seriesId,
            com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM
        )
        .map { rows -> rows.associate { it.identityKey to it.progressMs } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** (watched, total) for the currently-selected season. */
    val seasonProgress: StateFlow<Pair<Int, Int>> = combine(
        episodeDao.observeSeasonEpisodeCounts(seriesId),
        watchedStateDao.observeWatchedCountsForSeries(seriesId),
        _selectedSeason
    ) { totals, watched, selected ->
        val total = totals.firstOrNull { it.season_number == selected }?.totalEpisodes ?: 0
        val done = watched.firstOrNull { it.season_number == selected }?.watchedEpisodes ?: 0
        done to total
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0)

    // Episodes Stream (Paging Data) - Filtered by Season Logic
    // If _selectedSeason is null, we might show "All" or just "Season 1" default.
    // For now, let's assume if null, show all (or handle per requirements). 
    // BUT user asked for "Separated Seasons". 
    // Best practice: Default to first season once seasons are loaded.
    
    // We need a way to filter paging source by season.
    // The current DAO paging source gets ALL episodes.
    // We need a filtered query in DAO or a filter step here.
    // Paging 3 filtering is best done at Query level.
    
    // Changing approach: Pass season filter to repository.
    val episodes: Flow<PagingData<EpisodeEntityV2>> = _selectedSeason.flatMapLatest { seasonNum ->
        // #region agent log
        android.util.Log.d("SeriesDetailViewModelV2", "Episodes flow triggered: seriesId=$seriesId, seasonNum=$seasonNum, hasSeason=${seasonNum != null}")
        // #endregion
        if (seasonNum != null) {
            repository.getPagedEpisodesForSeason(seriesId, seasonNum)
        } else {
             // If no season selected yet (initial load), maybe show all or auto-select first?
             // Users usually want to see "Season 1" first.
             // We can use a special flow that waits for 'seasons' to emit, then selects first.
             // For simplicity/robustness: Show ALL until a season is picked.
             repository.getPagedEpisodes(seriesId)
        }
    }.cachedIn(viewModelScope)
    
    init {
        // Auto-select first season when seasons list loads (if nothing selected)
        viewModelScope.launch {
            seasons.collect { list ->
                // #region agent log
                android.util.Log.d("SeriesDetailViewModelV2", "Seasons collected: seriesId=$seriesId, count=${list.size}, seasons=$list, currentSelected=${_selectedSeason.value}")
                // #endregion
                if (list.isNotEmpty() && _selectedSeason.value == null) {
                    val preferredSeason = list.firstOrNull { it.seasonNum > 0 }?.seasonNum ?: list.first().seasonNum
                    // #region agent log
                    android.util.Log.d("SeriesDetailViewModelV2", "Auto-selecting season: seriesId=$seriesId, preferredSeason=$preferredSeason")
                    // #endregion
                    _selectedSeason.value = preferredSeason
                }
            }
        }
    }

    fun onSeasonSelected(seasonNum: Int) {
        _selectedSeason.value = seasonNum
    }

    // Internal trigger for refreshing metadata
    private val refreshTrigger = MutableStateFlow(0)
    
    // UI State
    // UI State
    val uiState: StateFlow<SeriesDetailUiState> = refreshTrigger.flatMapLatest { 
        repository.getSeriesById(seriesId)
    }.map { result ->
        when (result) {
            is Result.Success -> SeriesDetailUiState.Success(result.data)
            is Result.Error -> SeriesDetailUiState.Error(result.exception.message ?: "Unknown Error")
            is Result.Loading -> SeriesDetailUiState.Loading 
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SeriesDetailUiState.Loading
    )

    // ── Stream selection panel (multi-category aggregation) ────────────────────

    private val _streamPanel = MutableStateFlow(StreamPanelState())
    val streamPanel: StateFlow<StreamPanelState> = _streamPanel

    /** Distinct category listings the show appears in — drives the hero summary. */
    private val _categoryListingCount = MutableStateFlow(0)
    val categoryListingCount: StateFlow<Int> = _categoryListingCount

    private var pendingEpisode: EpisodeEntityV2? = null
    private var aggregateJob: kotlinx.coroutines.Job? = null
    private var resolveJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            _categoryListingCount.value = runCatching { repository.countCategoryListings(seriesId) }.getOrDefault(0)
        }
    }

    /**
     * Opening an episode no longer plays immediately — it presents the aggregated
     * streams for that episode across every playlist category.
     */
    fun onEpisodeClicked(episode: EpisodeEntityV2) {
        pendingEpisode = episode
        val label = "S%02d · E%02d".format(episode.seasonNumber, episode.episodeNumber)
        val title = episode.title?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?: "Episode ${episode.episodeNumber}"

        _streamPanel.value = StreamPanelState(
            visible = true,
            loading = true,
            episodeHeaderRight = label,
            episodeLabel = "$label · $title",
            filters = _streamPanel.value.filters
        )

        aggregateJob?.cancel()
        aggregateJob = viewModelScope.launch {
            // DB-only aggregation — effectively instant. Unresolved rows get their
            // playable URL on selection (resolveStreamOption), never up front.
            val groups = runCatching {
                repository.aggregateEpisodeStreams(seriesId, episode.seasonNumber, episode.episodeNumber)
            }.getOrElse {
                _streamPanel.value = _streamPanel.value.copy(loading = false, error = it.message ?: "Failed to load streams")
                return@launch
            }

            // Only apply if the panel is still targeting this episode
            if (pendingEpisode?.episodeId != episode.episodeId) return@launch
            _streamPanel.value = _streamPanel.value.copy(
                loading = false,
                allGroups = groups,
                error = if (groups.isEmpty()) "NO STREAMS AVAILABLE" else null
            )

            // Background sweep (throttled): fill in playable URLs and silently drop
            // listings that genuinely lack this episode, so dead rows never get
            // clicked. Results persist in the DB — next opens are pre-verified.
            val verified = runCatching {
                repository.verifyStreamGroups(groups, episode.seasonNumber, episode.episodeNumber)
            }.getOrNull() ?: return@launch
            if (pendingEpisode?.episodeId != episode.episodeId) return@launch
            if (verified != _streamPanel.value.allGroups) {
                _streamPanel.value = _streamPanel.value.copy(
                    allGroups = verified,
                    error = if (verified.isEmpty()) "NO STREAMS AVAILABLE" else null
                )
            }
        }
    }

    fun cycleFilter(key: FilterKey) {
        val f = _streamPanel.value.filters
        val next = when (key) {
            FilterKey.QUALITY -> f.copy(quality = QUALITY_OPTIONS.next(f.quality))
            FilterKey.LANGUAGE -> f.copy(language = LANGUAGE_OPTIONS.next(f.language))
            FilterKey.CONTAINER -> f.copy(container = CONTAINER_OPTIONS.next(f.container))
        }
        _streamPanel.value = _streamPanel.value.copy(filters = next)
    }

    fun closeStreamPanel() {
        aggregateJob?.cancel()
        resolveJob?.cancel()
        pendingEpisode = null
        _streamPanel.value = StreamPanelState(filters = _streamPanel.value.filters)
    }

    fun onStreamSelected(option: IptvStreamOption) {
        val episode = pendingEpisode ?: return
        // Repeat click on the row that's already resolving — let it finish.
        if (resolveJob?.isActive == true && _streamPanel.value.resolvingStreamId == option.streamId) return
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            // Unresolved rows need one network fetch for that sibling only.
            val needsResolve = option.playUrl.isBlank()
            if (needsResolve) {
                _streamPanel.value = _streamPanel.value.copy(resolvingStreamId = option.streamId)
            }
            val resolved = try {
                repository.resolveStreamOption(option, episode.seasonNumber, episode.episodeNumber)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer click superseded this one — don't report a false
                // "not available"; just stop quietly.
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SeriesDetailViewModelV2", "resolveStreamOption failed: ${e.message}")
                null
            }

            if (resolved == null) {
                _streamPanel.value = _streamPanel.value.copy(resolvingStreamId = null)
                _navigationEvents.send(
                    SeriesNavigationEvent.ShowMessage("Episode not available in this category")
                )
                return@launch
            }

            // Real saved progress for this episode. The player saves under the
            // content id it receives (resolved.streamId), so read back with the
            // same WatchedIdentityBuilder key to make Resume start at the right spot.
            val resumeMs = resolveEpisodeResumeMs(
                episodeStreamId = resolved.streamId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                fallbackDiscriminator = episode.episodeId.ifBlank { episode.title }
            )

            closeStreamPanel()
            _navigationEvents.send(
                SeriesNavigationEvent.NavigateToPlayer(
                    streamUrl = resolved.playUrl,
                    episodeId = resolved.streamId,
                    seriesId = seriesId,
                    title = episode.title ?: "Episode ${episode.episodeNumber}",
                    posterUrl = episode.thumbnail,
                    containerExtension = resolved.containerExtension,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    resumePositionMs = resumeMs
                )
            )
        }
    }

    /**
     * Reads the real saved playback position for an IPTV episode from the
     * watched-state store. Mirrors the key the player saves under
     * (WatchedIdentityBuilder.iptvEpisode with the stream/content id), because
     * EpisodeEntityV2.resumePosition is never populated in the V2 flow.
     * Returns 0 when there is no in-progress state (unwatched with progress).
     */
    private suspend fun resolveEpisodeResumeMs(
        episodeStreamId: String,
        seasonNumber: Int,
        episodeNumber: Int?,
        fallbackDiscriminator: String?
    ): Long {
        val key = WatchedIdentityBuilder.iptvEpisode(
            episodeId = episodeStreamId,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            fallbackDiscriminator = fallbackDiscriminator
        )
        val state = watchedStateDao.getState(key) ?: return 0L
        if (state.isWatched) return 0L
        return state.progressMs.takeIf { it > 0L } ?: 0L
    }

    fun onRetry() {
        refreshTrigger.value += 1
    }

    companion object {
        val QUALITY_OPTIONS = listOf("All", "4K", "1080P", "720P", "SD")
        val LANGUAGE_OPTIONS = listOf("All", "EN", "HI", "FR", "AR", "MULTI")
        val CONTAINER_OPTIONS = listOf("All", "MP4", "MKV", "TS")

        private fun List<String>.next(current: String): String {
            val i = indexOf(current)
            return this[(if (i < 0) 0 else (i + 1) % size)]
        }
    }
}

enum class FilterKey { QUALITY, LANGUAGE, CONTAINER }

data class StreamFilters(
    val quality: String = "All",
    val language: String = "All",
    val container: String = "All"
)

data class StreamPanelState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val episodeLabel: String = "",
    val episodeHeaderRight: String = "",
    val allGroups: List<IptvStreamGroup> = emptyList(),
    val filters: StreamFilters = StreamFilters(),
    val error: String? = null,
    /** Stream row currently being resolved to a playable URL (shows a spinner). */
    val resolvingStreamId: String? = null
) {
    /** Groups after applying the active quality / language / container filters. */
    val filteredGroups: List<IptvStreamGroup>
        get() {
            if (filters.quality == "All" && filters.language == "All" && filters.container == "All") return allGroups
            return allGroups.mapNotNull { g ->
                val kept = g.streams.filter { s ->
                    (filters.quality == "All" || s.quality.label.equals(filters.quality, true)) &&
                        (filters.language == "All" || s.languages.any { it.code.equals(filters.language, true) }) &&
                        (filters.container == "All" || s.container.equals(filters.container, true))
                }
                if (kept.isEmpty()) null else g.copy(streams = kept)
            }
        }

    val filteredStreamCount: Int get() = filteredGroups.sumOf { it.streams.size }
}

enum class SeasonWatchedState { UNWATCHED, IN_PROGRESS, WATCHED }
data class SeasonUiModel(val seasonNum: Int, val state: SeasonWatchedState)

enum class SeriesWatchedState { UNWATCHED, IN_PROGRESS, WATCHED }
