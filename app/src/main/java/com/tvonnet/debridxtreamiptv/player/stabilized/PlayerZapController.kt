package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * C10: everything about *which channel is next* — the zap list, the surf drawer's category picker,
 * live favourites, and the full-screen channel browser.
 *
 * One collaborator because they are all views of the same question. The drawer picks a category,
 * which rebuilds the zap list, which is what CH+/CH− walks and what the guide and ON AIR strip are
 * drawn from. Splitting them would mean four objects passing the same list around.
 *
 * Two rules here are load-bearing:
 *  - **Zapping never restarts playback by itself.** [moveZap] and [zapTo] only move the index and
 *    return the channel; the caller decides what to do with it. [switchZapCategory] likewise leaves
 *    the current channel playing and merely re-points the list.
 *  - **The current channel keeps its place across a category switch.** If it exists in the new
 *    category the index follows it, so CH+ from there goes to the real neighbour rather than
 *    jumping to whatever sits at position 0.
 *
 * Bodies moved verbatim from `PlayerViewModel`; the one change is that building a [ZapChannel] list
 * out of provider streams, which existed twice, is now [toZapChannels].
 */
internal class PlayerZapController(
    private val repository: XtreamRepository,
    private val cacheManager: CacheManager,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _zapState = MutableStateFlow<ZapState?>(null)
    val zapState: StateFlow<ZapState?> = _zapState.asStateFlow()

    private val _surfCategories = MutableStateFlow<List<XtreamCategory>>(emptyList())
    val surfCategories: StateFlow<List<XtreamCategory>> = _surfCategories.asStateFlow()

    private val _liveFavoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val liveFavoriteIds: StateFlow<Set<String>> = _liveFavoriteIds.asStateFlow()
    private var liveFavoritesJob: Job? = null

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    /** What the EPG surfaces are drawn from. */
    fun currentChannels(): List<ZapChannel>? = _zapState.value?.channels

    // ── the zap list ───────────────────────────────────────────────────────────

    fun initLiveZapping(
        categoryId: String?,
        currentStreamId: String?,
        baseServerUrl: String?,
        orderedStreamIds: List<String>?
    ) {
        if (categoryId.isNullOrBlank() || currentStreamId.isNullOrBlank() || baseServerUrl.isNullOrBlank()) return

        scope.launch(Dispatchers.IO) {
            ensureRepository(baseServerUrl)

            val streams = cacheManager.getChannels(categoryId, streamType = "live").orEmpty()
            if (streams.isEmpty()) return@launch

            // The caller's order wins when it supplies one: it is the order the user is actually
            // looking at, which need not match the provider's.
            val byId = streams.associateBy { it.stream_id }
            val ordered = orderedStreamIds
                ?.mapNotNull { id -> byId[id] }
                ?.takeIf { it.isNotEmpty() }

            val list = toZapChannels(ordered ?: streams, baseServerUrl)
            if (list.isEmpty()) return@launch

            val index = list.indexOfFirst { it.streamId == currentStreamId }.takeIf { it >= 0 } ?: 0
            _zapState.value = ZapState(
                categoryId = categoryId,
                categoryName = liveCategoryName(categoryId),
                channels = list,
                index = index
            )
        }
    }

    /**
     * Reload the zap channel list for a category picked in the surf drawer.
     * Playback stays on the current channel; index resets to 0 for the new list.
     */
    fun switchZapCategory(categoryId: String, baseServerUrl: String?) {
        if (categoryId.isBlank() || baseServerUrl.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            ensureRepository(baseServerUrl)

            val categoryName: String?
            val list: List<ZapChannel>
            if (categoryId == FAVORITES_CATEGORY_ID) {
                categoryName = context.getString(R.string.favorites)
                list = buildFavoriteZapChannels(baseServerUrl)
            } else {
                val streams = liveStreamsFor(categoryId)
                if (streams.isEmpty()) return@launch
                categoryName = liveCategoryName(categoryId)
                list = toZapChannels(streams, baseServerUrl)
            }
            if (list.isEmpty()) return@launch

            // Keep the currently-playing channel selected if it lives in this category.
            val currentId = _zapState.value?.let { it.channels.getOrNull(it.index)?.streamId }
            val index = list.indexOfFirst { it.streamId == currentId }.takeIf { it >= 0 } ?: 0
            _zapState.value = ZapState(
                categoryId = categoryId,
                categoryName = categoryName,
                channels = list,
                index = index
            )
        }
    }

    /** CH+ / CH−. Wraps around, and returns null when there is nothing to move to. */
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

    /** Direct tune from the surf drawer (Live Player OSD). */
    fun zapTo(index: Int): ZapChannel? {
        val state = _zapState.value ?: return null
        if (index !in state.channels.indices) return null
        val updated = state.copy(index = index)
        _zapState.value = updated
        return updated.channels[index]
    }

    private suspend fun ensureRepository(baseServerUrl: String) {
        val prefs = CredentialsPreferences(context)
        repository.ensureInitialized(baseServerUrl, prefs.getUsername(), prefs.getPassword())
    }

    private suspend fun liveStreamsFor(categoryId: String): List<XtreamStream> {
        cacheManager.getChannels(categoryId, streamType = "live")
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val result = repository.fetchLiveStreamsForCategory(categoryId)
        return if (result is Result.Success) result.data else emptyList()
    }

    private suspend fun liveCategoryName(categoryId: String): String? =
        cacheManager.getCategories("live")
            ?.firstOrNull { it.category_id == categoryId }
            ?.category_name

    private fun toZapChannels(streams: List<XtreamStream>, baseServerUrl: String): List<ZapChannel> =
        streams
            .filter { !it.stream_id.isNullOrBlank() }
            .map { stream ->
                ZapChannel(
                    streamId = stream.stream_id!!,
                    name = stream.name ?: context.getString(R.string.player_epg_channel_unknown),
                    logoUrl = GlobalConfig.resolveIconUrl(stream.stream_icon),
                    epgChannelId = stream.epg_channel_id,
                    streamUrl = repository.buildLiveStreamUrl(stream, baseServerUrl)
                )
            }

    // ── surf-drawer category picker (Live Player OSD, second-LEFT) ───────────

    /** Preload live categories so the drawer's category picker is instant. */
    fun loadSurfCategories() {
        if (_surfCategories.value.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val cats = cacheManager.getCategories("live")
                ?: runCatching { repository.ensureLiveCategories() }.getOrNull()
            if (!cats.isNullOrEmpty()) {
                // Prepend the synthetic Favorites category, same convention as the
                // non-fullscreen Live TV screen (LiveFragment.buildDisplayCategories):
                // same FAVORITES_CATEGORY_ID, same repository.getFavoritesByType("live")
                // source of truth — no separate favorites data source.
                val favorites = XtreamCategory(
                    category_id = FAVORITES_CATEGORY_ID,
                    category_name = context.getString(R.string.favorites),
                    parent_id = null
                )
                _surfCategories.value = listOf(favorites) + cats.filterNot { it.category_id == FAVORITES_CATEGORY_ID }
            }
        }
    }

    // ── live favourites ────────────────────────────────────────────────────────

    fun observeLiveFavorites() {
        if (liveFavoritesJob != null) return
        liveFavoritesJob = scope.launch(Dispatchers.IO) {
            repository.getFavoritesByType("live").collect { favs ->
                _liveFavoriteIds.value = favs.map { it.streamId }.toSet()
            }
        }
    }

    /** Toggles the live-channel favorite; returns the new state for toast copy. */
    suspend fun toggleLiveFavorite(streamId: String?, name: String?, iconUrl: String?): Boolean? {
        if (streamId.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            if (repository.isFavorite(streamId)) {
                repository.removeFavorite(streamId)
                false
            } else {
                repository.addFavorite(streamId, "live", name ?: "", iconUrl)
                true
            }
        }
    }

    private suspend fun buildFavoriteZapChannels(baseServerUrl: String): List<ZapChannel> {
        val favorites = repository.getFavoritesByType("live").firstOrNull().orEmpty()
        return favorites.mapNotNull { favorite ->
            val stream = repository.getLiveStreamById(favorite.streamId) ?: favoriteFallbackStream(favorite)
            val streamId = stream.stream_id ?: return@mapNotNull null
            ZapChannel(
                streamId = streamId,
                name = stream.name ?: favorite.name,
                logoUrl = GlobalConfig.resolveIconUrl(stream.stream_icon ?: favorite.iconUrl),
                epgChannelId = stream.epg_channel_id,
                streamUrl = repository.buildLiveStreamUrl(stream, baseServerUrl)
            )
        }
    }

    /**
     * Same fallback shape as LiveViewModel.favoriteFallbackChannel — used only when a favorited
     * stream_id is no longer resolvable via the cache/API (e.g. removed from the provider's
     * playlist since it was favorited). Without it the channel silently vanishes from Favourites.
     */
    private fun favoriteFallbackStream(favorite: FavoriteEntity): XtreamStream = XtreamStream(
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

    // ── full-screen channel browser ────────────────────────────────────────────

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
        scope.launch {
            _browserState.value = _browserState.value.copy(isLoading = true, error = null)
            try {
                val categories = cacheManager.getCategories("live")
                    ?: repository.ensureLiveCategories().takeIf { it.isNotEmpty() }

                if (categories.isNullOrEmpty()) {
                    _browserState.value = _browserState.value.copy(
                        isLoading = false,
                        error = context.getString(R.string.series_category_unavailable_generic)
                    )
                    return@launch
                }
                _browserState.value = _browserState.value.copy(categories = categories, isLoading = false)
                val targetId = initialCategoryId
                    ?: _browserState.value.selectedCategoryId
                    ?: categories.first().category_id
                if (targetId != null) selectBrowserCategory(targetId)
            } catch (e: Exception) {
                _browserState.value = _browserState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun selectBrowserCategory(categoryId: String) {
        if (_browserState.value.selectedCategoryId == categoryId && _browserState.value.channels.isNotEmpty()) return

        scope.launch {
            _browserState.value = _browserState.value.copy(
                selectedCategoryId = categoryId,
                isLoadingChannels = true
            )
            try {
                val channels = liveStreamsFor(categoryId)
                val enriched = if (channels.isNotEmpty()) {
                    withContext(Dispatchers.IO) { repository.enrichChannelsWithCurrentEpg(channels) }
                } else {
                    emptyList()
                }
                _browserState.value = _browserState.value.copy(
                    channels = enriched,
                    isLoadingChannels = false
                )
            } catch (e: Exception) {
                android.util.Log.w("PlayerViewModel", "Channel browser load failed", e)
                _browserState.value = _browserState.value.copy(
                    isLoadingChannels = false,
                    channels = emptyList()
                )
            }
        }
    }
}
