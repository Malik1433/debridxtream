package com.tvonnet.debridxtreamiptv.player

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

sealed class DebridResolutionState {
    object Idle : DebridResolutionState()
    object Loading : DebridResolutionState()
    data class Success(
        val url: String,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val infoHash: String? = null
    ) : DebridResolutionState()
    data class Error(val message: String) : DebridResolutionState()
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val seriesRepository: XtreamSeriesRepositoryV2,
    private val cacheManager: CacheManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _overlayState = MutableStateFlow(PlayerOverlayUiState())
    val overlayState: StateFlow<PlayerOverlayUiState> = _overlayState.asStateFlow()

    private var epgJob: Job? = null
    private var epgRefreshJob: Job? = null
    private val refreshAttempts = mutableMapOf<String, Long>()

    private val _zapState = MutableStateFlow<ZapState?>(null)
    val zapState: StateFlow<ZapState?> = _zapState.asStateFlow()

    // Series Playlist State (Plan C: Dynamic Queue)
    private val _seriesPlaylistState = MutableStateFlow<SeriesPlaylistState?>(null)
    val seriesPlaylistState: StateFlow<SeriesPlaylistState?> = _seriesPlaylistState.asStateFlow()

    private val _debridResolutionState = MutableStateFlow<DebridResolutionState>(DebridResolutionState.Idle)
    val debridResolutionState: StateFlow<DebridResolutionState> = _debridResolutionState.asStateFlow()

    fun loadSeriesPlaylist(seriesId: String, seasonNum: Int, startEpisodeId: String) {
        viewModelScope.launch {
             try {
                 val episodes = seriesRepository.getSeasonEpisodes(seriesId, seasonNum)
                 if (episodes.isNotEmpty()) {
                     val startIndex = episodes.indexOfFirst { it.episodeId == startEpisodeId }.takeIf { it >= 0 } ?: 0
                     val current = episodes[startIndex]
                     
                     _seriesPlaylistState.value = SeriesPlaylistState(
                         originalList = episodes,
                         currentIndex = startIndex,
                         currentEpisode = current,
                         hasNext = startIndex < episodes.size - 1,
                         hasPrev = startIndex > 0
                     )
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
        currentSeason: Int,
        currentEpisode: Int,
        seriesTitle: String?,
        infoHash: String?
    ) {
        // Stub for now to allow build to pass
        _debridResolutionState.value = DebridResolutionState.Error("Next Debrid Episode logic not yet implemented")
    }

    fun reResolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        season: Int?,
        episode: Int?,
        title: String?
    ) {
        // Stub for now to allow build to pass
        _debridResolutionState.value = DebridResolutionState.Error("Re-resolve logic not yet implemented")
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

    // Helper to clear playlist when leaving player
    fun clearSeriesPlaylist() {
        _seriesPlaylistState.value = null
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

        // Immediately show a lightweight "loading guide" state so the player overlay can appear right away.
        _overlayState.value = PlayerOverlayUiState(
            epgAvailable = false,
            lastSyncTime = readLastSyncTime(),
            isSyncing = true,
            errorMessage = null
        )

        epgJob = viewModelScope.launch {
            val flow = repository.getEpgByChannel(epgChannelId)

            if (flow == null) {
                // Room not available; use lightweight per-stream short EPG if possible.
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
                        errorMessage = null
                    )
                } else {
                    _overlayState.value = PlayerOverlayUiState(
                        epgAvailable = false,
                        errorMessage = context.getString(R.string.player_epg_unavailable_generic)
                    )
                    // Avoid auto-triggering full XMLTV sync on low-memory devices; keep as manual/worker task.
                }
                return@launch
            }

            flow.collect { programs ->
                val nowMs = System.currentTimeMillis()
                if (programs.isEmpty()) {
                    // Fallback to short-EPG (fast) instead of forcing full XMLTV sync (heavy).
                    _overlayState.value = _overlayState.value.copy(
                        epgAvailable = false,
                        isSyncing = true,
                        errorMessage = null
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
                            errorMessage = null
                        )
                    } else {
                        _overlayState.value = PlayerOverlayUiState(
                            epgAvailable = false,
                            lastSyncTime = readLastSyncTime(),
                            isSyncing = false,
                            errorMessage = context.getString(R.string.player_epg_no_guide)
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
                    errorMessage = null
                )
            }
        }
    }

    private fun triggerGuideRefresh(channelId: String, force: Boolean = false) {
        if (epgRefreshJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force) {
            val lastAttempt = refreshAttempts[channelId] ?: 0L
            if (now - lastAttempt < REFRESH_COOLDOWN_MS) return
        }

        epgRefreshJob = viewModelScope.launch {
            val hasLocalData = withContext(Dispatchers.IO) {
                repository.hasEpgData(channelId)
            }
            if (hasLocalData && !force) return@launch

            refreshAttempts[channelId] = now
            _overlayState.value = _overlayState.value.copy(
                isSyncing = true,
                epgAvailable = false,
                errorMessage = null
            )

            val result = withContext(Dispatchers.IO) {
                repository.fetchAndSaveEpg()
            }

            when (result) {
                is Result.Success -> {
                    saveLastSyncTime()
                    _overlayState.value = _overlayState.value.copy(isSyncing = false, errorMessage = null)
                }
                is Result.Error -> {
                    _overlayState.value = _overlayState.value.copy(
                        isSyncing = false,
                        errorMessage = result.exception.localizedMessage
                            ?: context.getString(R.string.player_epg_sync_failed)
                    )
                }
                else -> {
                    _overlayState.value = _overlayState.value.copy(isSyncing = false)
                }
            }
        }
    }

    private fun readLastSyncTime(): Long? {
        return context
            .getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
            .getLong("last_sync_time", 0L)
            .takeIf { it > 0 }
    }

    private fun saveLastSyncTime() {
        context
            .getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_sync_time", System.currentTimeMillis())
            .apply()
    }

    fun updatePlaybackStatus(contentId: String, isWatched: Boolean, resumePosition: Long, duration: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.updateEpisodePlaybackStatus(contentId, isWatched, resumePosition, duration)
            } catch (e: Exception) {
                // Log error but don't crash
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
                        logoUrl = stream.stream_icon,
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

    companion object {
        private const val REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    }
    // Browser State
    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    fun toggleBrowser(visible: Boolean, currentCategoryId: String? = null, currentChannelId: String? = null) {
        if (visible) {
             // If we have a current category, prioritize selecting it
             if (currentCategoryId != null && currentCategoryId != _browserState.value.selectedCategoryId) {
                 // Trigger category load/select
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
                // Try cache first
                var categories = cacheManager.getCategories("live")
                
                // If cache miss, try network (via repository which handles caching)
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
                    // Select default or requested category
                    val targetId = initialCategoryId ?: _browserState.value.selectedCategoryId ?: categories.first().category_id
                    if (targetId != null) {
                       selectBrowserCategory(targetId)
                    }
                } else {
                    _browserState.value = _browserState.value.copy(
                        isLoading = false,
                        error = context.getString(R.string.series_category_unavailable_generic) // Generic error
                    )
                }
            } catch (e: Exception) {
                _browserState.value = _browserState.value.copy(
                    isLoading = false,
                    error = e.message
                )
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
                 // Try cache first
                var channels = cacheManager.getChannels(categoryId, "live")
                
                // If cache miss, try network
                if (channels == null) {
                    val result = repository.fetchLiveStreamsForCategory(categoryId)
                    if (result is Result.Success) {
                        channels = result.data
                    }
                }

                // Enrich with EPG
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
                    // Don't show global error, just empty channels
                    channels = emptyList()
                )
            }
        }
    }
}

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
    val errorMessage: String? = null
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
    val currentEpisode: EpisodeEntityV2,
    val hasNext: Boolean,
    val hasPrev: Boolean
)
