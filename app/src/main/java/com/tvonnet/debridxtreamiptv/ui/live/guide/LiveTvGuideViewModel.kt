package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

data class GuideCategory(val id: String, val name: String, val count: Int)

data class GuideUiState(
    val categories: List<GuideCategory> = emptyList(),
    val selectedCategoryId: String = CATEGORY_ALL,
    val dayIndex: Int = 0,
    val channels: List<GuideChannel> = emptyList(),
    val windowStartMs: Long = 0L,
    val windowSpanMinutes: Int = DAY_SPAN_MINUTES,
    val nowMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val isToday: Boolean get() = dayIndex == 0

    companion object {
        const val CATEGORY_ALL = "all"
        const val DAY_SPAN_MINUTES = 24 * 60
    }
}

@HiltViewModel
class LiveTvGuideViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val epgDao: EpgDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuideUiState())
    val uiState: StateFlow<GuideUiState> = _uiState.asStateFlow()

    init {
        initializeRepository()
        // Build/refresh the LIVE index so "All" and search cover every category,
        // not just the first lazily-loaded one. Live-only (no VOD/series) so it
        // stays light and never competes with channel playback.
        runCatching { repository.scheduleLiveIndexSyncIfStale() }
        // Resume Last Channel: open directly on the category the last channel was
        // watched in, so it's present + selectable in the grid (not just the mini).
        runCatching {
            val sp = SettingsPreferences(context)
            if (sp.isResumeLastLiveEnabled()) {
                sp.getLastLiveCategoryId()
                    ?.takeIf { it != GuideUiState.CATEGORY_ALL && it != SEARCH_RESULT_CATEGORY_ID }
                    ?.let { cat -> _uiState.update { it.copy(selectedCategoryId = cat) } }
            }
        }
        load()
    }

    /** Channels matching [query] across the whole live index (for unified search). */
    suspend fun searchChannels(query: String): List<GuideChannel> {
        if (query.isBlank()) return emptyList()
        val favIds = favoriteIds()
        val streams = withContext(Dispatchers.IO) { repository.searchLive(query.trim()) }
        return streams.map { toGuideChannel(it, emptyList(), favIds) }
    }

    /**
     * Show an explicit channel list in the grid (e.g. the results picked from
     * search), bypassing category loading so the chosen channel is guaranteed
     * present and selectable → OK on its tile goes fullscreen. Search rows carry
     * no EPG, so program columns read "No Information" until a category reload.
     */
    fun showChannels(channels: List<GuideChannel>) {
        val window = dayWindow(0)
        _uiState.update {
            it.copy(
                selectedCategoryId = SEARCH_RESULT_CATEGORY_ID,
                channels = channels,
                windowStartMs = window.first,
                windowSpanMinutes = GuideUiState.DAY_SPAN_MINUTES,
                nowMs = System.currentTimeMillis(),
                isLoading = false,
                error = if (channels.isEmpty()) "No channels found." else null
            )
        }
    }

    /** A single channel by stream id (from the full index) — used by Resume Last
     *  Channel when the saved channel isn't in the currently loaded list. */
    suspend fun getChannelById(streamId: String): GuideChannel? {
        val stream = withContext(Dispatchers.IO) {
            runCatching { repository.getLiveStreamById(streamId) }.getOrNull()
        } ?: return null
        return toGuideChannel(stream, emptyList(), favoriteIds())
    }

    /** Categories whose name matches [query] (client-side filter for unified search). */
    fun filterCategories(query: String): List<GuideCategory> {
        val cats = _uiState.value.categories
        val q = query.trim()
        if (q.isBlank()) return cats
        return cats.filter { it.name.contains(q, ignoreCase = true) }
    }

    private fun initializeRepository() {
        val creds = CredentialsPreferences(context)
        val url = creds.getServerUrl()
        val user = creds.getUsername()
        val pass = creds.getPassword()
        if (url != null && user != null && pass != null) {
            repository.initialize(url, user, pass)
        }
    }

    fun selectCategory(categoryId: String) {
        if (categoryId == _uiState.value.selectedCategoryId) return
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
        loadChannels()
    }

    fun selectDay(dayIndex: Int) {
        if (dayIndex == _uiState.value.dayIndex) return
        _uiState.update { it.copy(dayIndex = dayIndex) }
        loadChannels()
    }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val cats = withContext(Dispatchers.IO) { buildCategories() }
            _uiState.update {
                val stillValid = cats.any { c -> c.id == it.selectedCategoryId }
                it.copy(
                    categories = cats,
                    selectedCategoryId = if (stillValid) it.selectedCategoryId else GuideUiState.CATEGORY_ALL
                )
            }
            loadChannels()
        }
    }

    private suspend fun buildCategories(): List<GuideCategory> {
        val cache = repository.readCache()
        val liveCats = cache?.live?.categories ?: repository.ensureLiveCategories()
        val streams = cache?.live?.streams ?: emptyList()
        val allCount = repository.getAllLiveChannels().size.takeIf { it > 0 } ?: streams.size
        val result = mutableListOf(
            GuideCategory(GuideUiState.CATEGORY_ALL, "All", allCount),
            GuideCategory(FAVORITES_CATEGORY_ID, "★ Favorites", favoriteIds().size)
        )
        // Per-category counts are unknown until the category is opened (channels are
        // fetched on demand), so hide the badge (-1) rather than showing a wrong "0".
        liveCats.forEach { c ->
            val id = c.category_id ?: return@forEach
            result.add(GuideCategory(id, c.category_name ?: "Unknown", -1))
        }
        return result
    }

    private suspend fun favoriteIds(): Set<String> {
        return runCatching {
            repository.getFavoritesByType("live").firstOrNull()?.map { it.streamId }?.toSet()
        }.getOrNull() ?: emptySet()
    }

    private fun loadChannels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val state = _uiState.value
            val window = dayWindow(state.dayIndex)
            val now = System.currentTimeMillis()

            val result = withContext(Dispatchers.IO) {
                val cache = repository.readCache()
                val allStreams = cache?.live?.streams ?: emptyList()
                val favIds = favoriteIds()

                // Match the classic screen: fetch a real category's channels on demand
                // (API/DB), not just whatever happens to be in the cache.
                val filtered = when (state.selectedCategoryId) {
                    // Full catalog from the index; fall back to the partial cache
                    // if the index hasn't synced yet (never worse than before).
                    GuideUiState.CATEGORY_ALL -> repository.getAllLiveChannels().ifEmpty { allStreams }
                    FAVORITES_CATEGORY_ID -> favIds.mapNotNull { id ->
                        runCatching { repository.getLiveStreamById(id) }.getOrNull()
                    }
                    else -> when (val r = repository.fetchLiveStreamsForCategoryStrict(state.selectedCategoryId)) {
                        is com.tvonnet.debridxtreamiptv.data.Result.Success -> r.data
                        else -> emptyList()
                    }
                }.take(MAX_CHANNELS)

                // EPG is stored keyed by epg_channel_id (XMLTV "channel" id), NOT stream_id.
                val epgKey = { s: XtreamStream -> s.epg_channel_id?.takeIf { it.isNotBlank() } ?: s.stream_id.orEmpty() }
                val channelIds = filtered.map { epgKey(it) }.filter { it.isNotEmpty() }.distinct()
                val programsByKey: Map<String, List<EpgEntity>> = if (channelIds.isEmpty()) {
                    emptyMap()
                } else {
                    epgDao.getProgramsByChannels(channelIds).firstOrNull()
                        ?.filter { it.stop > window.first && it.start < window.second }
                        ?.groupBy { it.channelId }
                        ?: emptyMap()
                }

                filtered.mapIndexed { idx, stream ->
                    val ch = toGuideChannel(stream, programsByKey[epgKey(stream)].orEmpty(), favIds)
                    // Per-category API responses often omit channel numbers; fall back to row order.
                    if (ch.number == null || ch.number <= 0) ch.copy(number = idx + 1) else ch
                }
            }

            _uiState.update {
                it.copy(
                    channels = result,
                    windowStartMs = window.first,
                    windowSpanMinutes = GuideUiState.DAY_SPAN_MINUTES,
                    nowMs = now,
                    isLoading = false,
                    error = if (result.isEmpty()) "No channels found. Please login and sync." else null
                )
            }
        }
    }

    private fun toGuideChannel(
        stream: XtreamStream,
        programs: List<EpgEntity>,
        favIds: Set<String>
    ): GuideChannel {
        val currentCategory = programs.firstOrNull { it.isPlaying() }?.category
        val genre = EpgGenre.from(currentCategory, stream.name)
        val guidePrograms = programs.sortedBy { it.start }.map { p ->
            GuideProgram(
                title = p.title?.takeIf { it.isNotBlank() } ?: "No Information",
                description = p.description,
                startMs = p.start,
                stopMs = p.stop,
                genre = EpgGenre.from(p.category, stream.name)
            )
        }
        return GuideChannel(
            streamId = stream.stream_id.orEmpty(),
            number = stream.num,
            name = stream.name?.takeIf { it.isNotBlank() } ?: "Channel",
            logoUrl = GlobalConfig.resolveIconUrl(stream.stream_icon),
            genre = genre,
            quality = GuideChannel.qualityOf(stream.name),
            isFavorite = stream.stream_id in favIds,
            stream = stream,
            programs = guidePrograms
        )
    }

    /** Returns start..end (Unix ms) of the local day at [dayIndex] offset from today. */
    private fun dayWindow(dayIndex: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayIndex)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return start to (start + GuideUiState.DAY_SPAN_MINUTES * 60_000L)
    }

    companion object {
        private const val MAX_CHANNELS = 300
        // Transient pseudo-category for showing an explicit list (search picks).
        private const val SEARCH_RESULT_CATEGORY_ID = "search_result"
    }
}
