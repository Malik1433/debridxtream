package com.tvonnet.debridxtreamiptv.ui.live

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.paging.ChannelPagingSource
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.di.IoDispatcher
import com.tvonnet.debridxtreamiptv.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID

enum class LiveFocusArea {
    CATEGORIES,
    CHANNELS
}

/**
 * UI State for Live TV screen
 * Represents all possible states of the Live TV UI
 */
data class LiveUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<XtreamStream> = emptyList(),
    val categoryChannelCounts: Map<String, Int> = emptyMap(),
    val isLoadingCategories: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val lastFocusedCategoryId: String? = null,
    val lastFocusedCategoryPosition: Int? = null,
    val lastFocusedChannelId: String? = null,
    val lastFocusedChannelPosition: Int? = null,
    val lastFocusedChannelCategoryId: String? = null,
    val lastPlayedChannelId: String? = null,
    val lastPlayedChannelName: String? = null,
    val lastPlayedChannelLogo: String? = null,
    val lastPlayedEpgChannelId: String? = null,
    val lastFocusArea: LiveFocusArea = LiveFocusArea.CATEGORIES,
    val restoreFromFullscreenPending: Boolean = false
)

/**
 * UI Events for Live TV screen
 * Represents all user actions on the Live TV screen
 */
sealed class LiveEvent {
    object LoadCategories : LiveEvent()
    data class SelectCategory(val categoryId: String) : LiveEvent()
    data class PlayChannel(val stream: XtreamStream) : LiveEvent()
    data class SearchChannels(val query: String) : LiveEvent()
    object ClearSearch : LiveEvent()
    object Retry : LiveEvent()
    data class RememberCategoryFocus(val categoryId: String, val position: Int) : LiveEvent()
    data class RememberChannelFocus(val streamId: String, val position: Int) : LiveEvent()
    data class RememberPreviewStream(val stream: XtreamStream) : LiveEvent()
    data class RememberFullscreenLaunch(val stream: XtreamStream) : LiveEvent()
    object ConsumeReturnFromFullscreen : LiveEvent()
}

/**
 * ViewModel for Live TV screen
 * Handles business logic and state management for Live TV
 * 
 * @param repository XtreamRepository for data operations (injected via Hilt)
 * @param context Application context for preferences (injected via Hilt)
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: XtreamRepository,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BaseViewModel<LiveUiState, LiveEvent>() {

    companion object {
        private const val KEY_SELECTED_CATEGORY_ID = "live.selectedCategoryId"
        private const val KEY_LAST_FOCUSED_CATEGORY_ID = "live.lastFocusedCategoryId"
        private const val KEY_LAST_FOCUSED_CATEGORY_POSITION = "live.lastFocusedCategoryPosition"
        private const val KEY_LAST_FOCUSED_CHANNEL_ID = "live.lastFocusedChannelId"
        private const val KEY_LAST_FOCUSED_CHANNEL_POSITION = "live.lastFocusedChannelPosition"
        private const val KEY_LAST_FOCUSED_CHANNEL_CATEGORY_ID = "live.lastFocusedChannelCategoryId"
        private const val KEY_LAST_PLAYED_CHANNEL_ID = "live.lastPlayedChannelId"
        private const val KEY_LAST_PLAYED_CHANNEL_NAME = "live.lastPlayedChannelName"
        private const val KEY_LAST_PLAYED_CHANNEL_LOGO = "live.lastPlayedChannelLogo"
        private const val KEY_LAST_PLAYED_EPG_CHANNEL_ID = "live.lastPlayedEpgChannelId"
        private const val KEY_LAST_FOCUS_AREA = "live.lastFocusArea"
        private const val KEY_RESTORE_FROM_FULLSCREEN_PENDING = "live.restoreFromFullscreenPending"
    }

    override fun getInitialState() = LiveUiState()
    
    /**
     * Navigation event for playing a channel
     * Using SharedFlow for one-time events (not state)
     */
    private val _navigationEvent = MutableSharedFlow<XtreamStream?>()
    val navigationEvent: SharedFlow<XtreamStream?> = _navigationEvent.asSharedFlow()
    
    /**
     * Paged channels flow for current selected category
     * Using Paging3 for efficient memory usage and smooth scrolling
     */
    private val _selectedCategoryFlow = MutableStateFlow<String?>(null)
    private val _searchQueryFlow = MutableStateFlow("")
    
    val pagedChannels: Flow<PagingData<XtreamStream>> = combine(
        _selectedCategoryFlow.filterNotNull(),
        _searchQueryFlow
    ) { categoryId, searchQuery ->
        Pair(categoryId, searchQuery)
    }.flatMapLatest { (categoryId, searchQuery) ->
            if (categoryId == FAVORITES_CATEGORY_ID) {
                repository.getFavoritesByType("live")
                    .map { favorites ->
                        val channels = buildFavoriteChannels(favorites, searchQuery)
                        PagingData.from(channels)
                    }
            } else {
                val restoreOffset = uiState.value.lastFocusedChannelPosition
                    ?.takeIf { searchQuery.isBlank() && uiState.value.lastFocusedChannelCategoryId == categoryId }
                Pager(
                    config = PagingConfig(
                        pageSize = 20,
                        enablePlaceholders = false,
                        prefetchDistance = 5
                    ),
                    initialKey = restoreOffset,
                    pagingSourceFactory = {
                        ChannelPagingSource(repository, categoryId, searchQuery)
                    }
                ).flow
            }
    }.cachedIn(viewModelScope)
    
    init {
        android.util.Log.d("LiveViewModel", "Initializing LiveViewModel")
        restoreStateFromSavedStateHandle()
        // Initialize repository with credentials before loading data
        initializeRepository()
        viewModelScope.launch(exceptionHandler) {
            onEvent(LiveEvent.LoadCategories)
        }
    }

    private fun restoreStateFromSavedStateHandle() {
        val focusArea = when (savedStateHandle.get<String>(KEY_LAST_FOCUS_AREA)) {
            LiveFocusArea.CHANNELS.name -> LiveFocusArea.CHANNELS
            else -> LiveFocusArea.CATEGORIES
        }
        val restorePending = savedStateHandle.get<Boolean>(KEY_RESTORE_FROM_FULLSCREEN_PENDING) ?: false

        updateState {
            copy(
                selectedCategoryId = savedStateHandle.get(KEY_SELECTED_CATEGORY_ID),
                lastFocusedCategoryId = savedStateHandle.get(KEY_LAST_FOCUSED_CATEGORY_ID),
                lastFocusedCategoryPosition = savedStateHandle.get(KEY_LAST_FOCUSED_CATEGORY_POSITION),
                lastFocusedChannelId = savedStateHandle.get(KEY_LAST_FOCUSED_CHANNEL_ID),
                lastFocusedChannelPosition = savedStateHandle.get(KEY_LAST_FOCUSED_CHANNEL_POSITION),
                lastFocusedChannelCategoryId = savedStateHandle.get(KEY_LAST_FOCUSED_CHANNEL_CATEGORY_ID),
                lastPlayedChannelId = savedStateHandle.get(KEY_LAST_PLAYED_CHANNEL_ID),
                lastPlayedChannelName = savedStateHandle.get(KEY_LAST_PLAYED_CHANNEL_NAME),
                lastPlayedChannelLogo = savedStateHandle.get(KEY_LAST_PLAYED_CHANNEL_LOGO),
                lastPlayedEpgChannelId = savedStateHandle.get(KEY_LAST_PLAYED_EPG_CHANNEL_ID),
                lastFocusArea = focusArea,
                restoreFromFullscreenPending = restorePending
            )
        }
    }
    
    /**
     * Initialize repository with saved credentials
     * Required before loading data
     */
    private fun initializeRepository() {
        val credentialsPrefs = CredentialsPreferences(context)
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
        }
    }

    private fun buildFavoriteChannels(
        favorites: List<FavoriteEntity>,
        searchQuery: String
    ): List<XtreamStream> {
        val items = favorites.mapNotNull { favorite ->
            repository.getLiveStreamById(favorite.streamId) ?: favoriteFallbackChannel(favorite)
        }
        if (searchQuery.isBlank()) return items
        return items.filter { stream ->
            val query = searchQuery.trim()
            stream.name?.contains(query, ignoreCase = true) == true ||
                stream.num?.toString()?.contains(query) == true
        }
    }

    private fun favoriteFallbackChannel(favorite: FavoriteEntity): XtreamStream {
        return XtreamStream(
            num = null,
            name = favorite.name,
            stream_type = "live",
            stream_id = favorite.streamId,
            stream_icon = favorite.iconUrl,
            epg_channel_id = null,
            added = null,
            category_id = FAVORITES_CATEGORY_ID,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = null,
            tv_archive = null,
            tv_archive_duration = null
        )
    }

    override fun onEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.LoadCategories -> loadCategories()
            is LiveEvent.SelectCategory -> loadChannelsForCategory(event.categoryId)
            is LiveEvent.PlayChannel -> handlePlayChannel(event.stream)
            is LiveEvent.SearchChannels -> searchChannels(event.query)
            is LiveEvent.ClearSearch -> clearSearch()
            is LiveEvent.Retry -> retry()
            is LiveEvent.RememberCategoryFocus -> rememberCategoryFocus(event.categoryId, event.position)
            is LiveEvent.RememberChannelFocus -> rememberChannelFocus(event.streamId, event.position)
            is LiveEvent.RememberPreviewStream -> rememberPreviewStream(event.stream)
            is LiveEvent.RememberFullscreenLaunch -> rememberFullscreenLaunch(event.stream)
            is LiveEvent.ConsumeReturnFromFullscreen -> consumeReturnFromFullscreen()
        }
    }

    private fun rememberCategoryFocus(categoryId: String, position: Int) {
        if (position < 0) return
        savedStateHandle[KEY_LAST_FOCUSED_CATEGORY_ID] = categoryId
        savedStateHandle[KEY_LAST_FOCUSED_CATEGORY_POSITION] = position
        updateState {
            copy(
                lastFocusedCategoryId = categoryId,
                lastFocusedCategoryPosition = position,
                lastFocusArea = if (restoreFromFullscreenPending && lastFocusArea == LiveFocusArea.CHANNELS) {
                    LiveFocusArea.CHANNELS
                } else {
                    savedStateHandle[KEY_LAST_FOCUS_AREA] = LiveFocusArea.CATEGORIES.name
                    LiveFocusArea.CATEGORIES
                }
            )
        }
    }

    private fun rememberChannelFocus(streamId: String, position: Int) {
        if (position < 0) return
        val categoryId = uiState.value.selectedCategoryId
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_ID] = streamId
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_POSITION] = position
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_CATEGORY_ID] = categoryId
        savedStateHandle[KEY_LAST_FOCUS_AREA] = LiveFocusArea.CHANNELS.name
        updateState {
            copy(
                lastFocusedChannelId = streamId,
                lastFocusedChannelCategoryId = categoryId,
                lastFocusedChannelPosition = position,
                lastFocusArea = LiveFocusArea.CHANNELS
            )
        }
    }

    private fun rememberPreviewStream(stream: XtreamStream) {
        val streamId = stream.stream_id ?: return
        val categoryId = uiState.value.selectedCategoryId
        savedStateHandle[KEY_LAST_PLAYED_CHANNEL_ID] = streamId
        savedStateHandle[KEY_LAST_PLAYED_CHANNEL_NAME] = stream.name
        savedStateHandle[KEY_LAST_PLAYED_CHANNEL_LOGO] = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
        savedStateHandle[KEY_LAST_PLAYED_EPG_CHANNEL_ID] = stream.epg_channel_id
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_ID] = streamId
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_CATEGORY_ID] = categoryId
        savedStateHandle[KEY_LAST_FOCUS_AREA] = LiveFocusArea.CHANNELS.name
        savedStateHandle[KEY_RESTORE_FROM_FULLSCREEN_PENDING] = false
        updateState {
            copy(
                lastPlayedChannelId = streamId,
                lastPlayedChannelName = stream.name,
                lastPlayedChannelLogo = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
                lastPlayedEpgChannelId = stream.epg_channel_id,
                lastFocusedChannelId = streamId,
                lastFocusedChannelCategoryId = categoryId,
                lastFocusArea = LiveFocusArea.CHANNELS,
                restoreFromFullscreenPending = false
            )
        }
    }

    private fun rememberFullscreenLaunch(stream: XtreamStream) {
        val streamId = stream.stream_id ?: return
        val categoryId = uiState.value.selectedCategoryId
        savedStateHandle[KEY_LAST_PLAYED_CHANNEL_ID] = streamId
        savedStateHandle[KEY_LAST_PLAYED_CHANNEL_NAME] = stream.name
        savedStateHandle[KEY_LAST_PLAYED_CHANNEL_LOGO] = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
        savedStateHandle[KEY_LAST_PLAYED_EPG_CHANNEL_ID] = stream.epg_channel_id
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_ID] = streamId
        savedStateHandle[KEY_LAST_FOCUSED_CHANNEL_CATEGORY_ID] = categoryId
        savedStateHandle[KEY_LAST_FOCUS_AREA] = LiveFocusArea.CHANNELS.name
        savedStateHandle[KEY_RESTORE_FROM_FULLSCREEN_PENDING] = true
        updateState {
            copy(
                lastPlayedChannelId = streamId,
                lastPlayedChannelName = stream.name,
                lastPlayedChannelLogo = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
                lastPlayedEpgChannelId = stream.epg_channel_id,
                lastFocusedChannelId = streamId,
                lastFocusedChannelCategoryId = categoryId,
                lastFocusArea = LiveFocusArea.CHANNELS,
                restoreFromFullscreenPending = true
            )
        }
    }

    private fun consumeReturnFromFullscreen() {
        if (!uiState.value.restoreFromFullscreenPending) return
        savedStateHandle[KEY_RESTORE_FROM_FULLSCREEN_PENDING] = false
        updateState { copy(restoreFromFullscreenPending = false) }
    }
    
    /**
     * Search channels by name or number
     */
    private fun searchChannels(query: String) {
        viewModelScope.launch {
            updateState { copy(searchQuery = query, isSearching = query.isNotEmpty()) }
            _searchQueryFlow.value = query
        }
    }
    
    /**
     * Clear search and show all channels
     */
    private fun clearSearch() {
        viewModelScope.launch {
            updateState { copy(searchQuery = "", isSearching = false) }
            _searchQueryFlow.value = ""
        }
    }
    
    /**
     * Load Live TV categories from cache
     */
    private fun loadCategories() {
        viewModelScope.launch(exceptionHandler) {
            updateState { copy(isLoadingCategories = true, error = null) }
            
            val categories = withContext(ioDispatcher) {
                repository.ensureLiveCategories()
            }

            // Avoid misleading counts from partial caches (often only the first category is available).
            val channelCounts: Map<String, Int> = emptyMap()
            
            if (categories.isEmpty()) {
                updateState {
                    copy(
                        isLoadingCategories = false,
                        error = "No categories found. Please login and sync."
                    )
                }
            } else {
                val preferredSelectedId = uiState.value.selectedCategoryId
                val selectedCategoryId = preferredSelectedId
                    ?.takeIf { id -> categories.any { it.category_id == id } }
                    ?: categories.firstOrNull()?.category_id

                if (selectedCategoryId != null) {
                    savedStateHandle[KEY_SELECTED_CATEGORY_ID] = selectedCategoryId
                }

                updateState {
                    copy(
                        categories = categories,
                        categoryChannelCounts = channelCounts,
                        isLoadingCategories = false,
                        selectedCategoryId = selectedCategoryId
                    )
                }
                
                // Auto-load first category's channels
                selectedCategoryId?.let { categoryId ->
                    loadChannelsForCategory(categoryId)
                }
            }
        }
    }
    
    /**
     * Load channels for a specific category
     * Triggers paging for the selected category
     */
    private fun loadChannelsForCategory(categoryId: String) {
        viewModelScope.launch(exceptionHandler) {
            savedStateHandle[KEY_SELECTED_CATEGORY_ID] = categoryId
            updateState {
                copy(
                    selectedCategoryId = categoryId,
                    isLoadingChannels = true,
                    error = null
                )
            }
            
            // Trigger paging for this category
            _selectedCategoryFlow.value = categoryId

            // Avoid redundant full-category network fetch here; PagingSource will fetch/cache.
            val cachedCount = withContext(ioDispatcher) {
                repository.getCachedLiveChannelCount(categoryId)
            }

            updateState {
                copy(
                    categoryChannelCounts = if (cachedCount != null) {
                        categoryChannelCounts + (categoryId to cachedCount)
                    } else {
                        categoryChannelCounts
                    },
                    isLoadingChannels = false
                )
            }
        }
    }
    
    /**
     * Handle play channel event
     * Emits navigation event to Fragment
     */
    private fun handlePlayChannel(stream: XtreamStream) {
        viewModelScope.launch {
            _navigationEvent.emit(stream)
        }
    }
    
    /**
     * Retry loading data
     */
    private fun retry() {
        val currentState = uiState.value
        if (currentState.categories.isEmpty()) {
            onEvent(LiveEvent.LoadCategories)
        } else {
            currentState.selectedCategoryId?.let { categoryId ->
                onEvent(LiveEvent.SelectCategory(categoryId))
            }
        }
    }
    
    /**
     * Handle exceptions from coroutines
     * Updates UI state with error message
     */
    override fun handleException(exception: Throwable) {
        updateState {
            copy(
                isLoadingCategories = false,
                isLoadingChannels = false,
                error = exception.message ?: "Unknown error occurred"
            )
        }
    }
}
