package com.tvonnet.debridxtreamiptv.ui.series

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity

data class SeriesUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val series: List<XtreamSeriesInfo> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val isLoadingSeries: Boolean = false,
    val isSwitchingCategory: Boolean = false, // New: track transition period
    val error: String? = null,
    val categoryStatus: SeriesCategoryStatus? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    /** category_id -> number of synced series, for the sidebar count badges. */
    val categoryCounts: Map<String, Int> = emptyMap()
)

sealed class SeriesEvent {
    object LoadCategories : SeriesEvent()
    data class SelectCategory(val categoryId: String) : SeriesEvent()
    data class PlaySeries(val series: XtreamSeriesInfo) : SeriesEvent()
    data class SearchSeries(val query: String) : SeriesEvent()
    object ClearSearch : SeriesEvent()
    object Retry : SeriesEvent()
}

/** Sort order for the series grid. Mirrors the sort chips in the Series header. */
enum class SeriesSortMode { RECENTLY_ADDED, TOP_RATED, A_TO_Z, MOST_EPISODES }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val seriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao,
    private val favoritesCache: com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache,
    private val repositoryV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2,
    private val episodeDaoV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2,
    @ApplicationContext private val context: Context,
    val savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    companion object {
        private val CATEGORY_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(15)

        /** Synthetic sidebar rows (above Favorites). Sentinel ids can't collide with real Xtream ids. */
        const val ALL_SERIES_CATEGORY_ID = "__all_series__"
        const val RECENTLY_ADDED_CATEGORY_ID = "__recently_added__"

        /** "Recently Added" = the newest-cached N series (fixed cap, distinct from All Series). */
        const val RECENT_LIMIT = 100

        @VisibleForTesting
        var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }

    private fun isVirtualCategory(categoryId: String?): Boolean =
        categoryId == ALL_SERIES_CATEGORY_ID || categoryId == RECENTLY_ADDED_CATEGORY_ID

    /** Prepend the two synthetic categories above the real Xtream ones. */
    private fun withVirtualCategories(real: List<XtreamCategory>): List<XtreamCategory> {
        val virtual = listOf(
            XtreamCategory(ALL_SERIES_CATEGORY_ID, "All Series", null),
            XtreamCategory(RECENTLY_ADDED_CATEGORY_ID, "Recently Added", null)
        )
        return virtual + real.filterNot { isVirtualCategory(it.category_id) }
    }

    private fun refreshCategoryCounts() {
        viewModelScope.launch {
            val counts = withContext(ioDispatcher) {
                try {
                    val perCat = seriesDao.getSeriesCountsByCategory().associate { it.categoryId to it.count }
                    val total = seriesDao.getTotalSeriesCount()
                    perCat + mapOf(
                        ALL_SERIES_CATEGORY_ID to total,
                        // Capped list → count is the cap (or fewer if the catalog is small).
                        RECENTLY_ADDED_CATEGORY_ID to minOf(RECENT_LIMIT, total)
                    )
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            _uiState.update { it.copy(categoryCounts = counts) }
        }
    }
    
    private val _uiState = MutableStateFlow(SeriesUiState())
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()

    // ── Focus prefetch ──────────────────────────────────────────────────────────
    // Warm get_series_info for the focused card so the detail page opens with the
    // episodes already in Room. One fetch at a time, latest-focused wins the queue,
    // and ids aren't retried within this session (dead listings would loop forever).
    private val prefetchAttempted =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var prefetchJob: kotlinx.coroutines.Job? = null
    private var pendingPrefetchId: String? = null

    fun prefetchSeriesDetail(seriesId: String) {
        if (seriesId.isBlank() || prefetchAttempted.contains(seriesId)) return
        pendingPrefetchId = seriesId
        drainPrefetchQueue()
    }

    private fun drainPrefetchQueue() {
        if (prefetchJob?.isActive == true) return
        val id = pendingPrefetchId ?: return
        pendingPrefetchId = null
        prefetchAttempted.add(id)
        prefetchJob = viewModelScope.launch(ioDispatcher) {
            try {
                if (episodeDaoV2.getCountForSeries(id) > 0) return@launch
                // Collecting the flow runs the whole fetch-and-persist pipeline;
                // emissions themselves are irrelevant here.
                repositoryV2.getSeriesById(id).collect {}
            } catch (_: Exception) {
                // Prefetch is best-effort — the detail page does its own fetch anyway.
            }
        }.also { job ->
            job.invokeOnCompletion { viewModelScope.launch { drainPrefetchQueue() } }
        }
    }
    
    /**
     * Paged series flow for current selected category
     * Using Paging3 for efficient memory usage and smooth scrolling
     */
    private val _selectedCategoryFlow = MutableStateFlow<String?>(null)
    private val _searchQueryFlow = MutableStateFlow("")
    private val _sortModeFlow = MutableStateFlow(SeriesSortMode.RECENTLY_ADDED)
    private val _genreFlow = MutableStateFlow<String?>(null)

    /**
     * Title filters from the phone's filter sheet: OR inside a group, AND between groups. Empty
     * by default, and the television never sets it, so that screen's SQL is unchanged.
     */
    private val _titleFiltersFlow = MutableStateFlow<List<List<String>>>(emptyList())

    data class SeriesQuery(
        val categoryId: String,
        val searchQuery: String,
        val sortMode: SeriesSortMode,
        val genre: String?,
        val titleFilters: List<List<String>> = emptyList(),
    )

    val pagedSeries: Flow<PagingData<XtreamSeriesInfo>> = combine(
        _selectedCategoryFlow.filterNotNull(),
        _searchQueryFlow,
        _sortModeFlow,
        _genreFlow,
        _titleFiltersFlow,
    ) { categoryId, searchQuery, sortMode, genre, titleFilters ->
        SeriesQuery(categoryId, searchQuery, sortMode, genre, titleFilters)
    }.flatMapLatest { q ->
        if (q.categoryId == FAVORITES_CATEGORY_ID) {
            repository.getFavoritesByType("series")
                // IPTV favourites only — see the same guard in VodViewModel.
                .map { all -> all.filter { it.source == WatchedStateEntity.SOURCE_XTREAM } }
                .map { favorites ->
                    val items = sortSeries(
                        filterByGenre(buildFavoriteSeries(favorites, q.searchQuery), q.genre),
                        q.sortMode
                    )
                    PagingData.from(items)
                }
        } else {
            val pagerFlow: Flow<PagingData<SeriesEntity>> = Pager(
                config = PagingConfig(
                    pageSize = 60,
                    enablePlaceholders = false,
                    prefetchDistance = 20
                ),
                pagingSourceFactory = { seriesDao.getSeriesByCategoryRaw(buildSeriesQuery(q)) }
            ).flow

            pagerFlow.map { pagingData ->
                // Clear error when data successfully emits from the PagingSource
                _uiState.update { it.copy(error = null) }
                pagingData.map { entity -> entity.toXtreamSeriesInfo() }
            }
        }
    }.cachedIn(viewModelScope)

    private fun buildSeriesQuery(q: SeriesQuery): androidx.sqlite.db.SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        // "All Series" / "Recently Added" span every category (no category filter).
        // Real categories keep their per-category filter.
        if (!isVirtualCategory(q.categoryId)) {
            conditions += "categoryId = ?"
            args += q.categoryId
        }
        if (q.searchQuery.isNotBlank()) {
            conditions += "name LIKE '%' || ? || '%'"
            args += q.searchQuery
        }
        if (!q.genre.isNullOrBlank()) {
            conditions += "genre LIKE '%' || ? || '%'"
            args += q.genre
        }
        q.titleFilters.filter { it.isNotEmpty() }.forEach { group ->
            conditions += group.joinToString(" OR ", prefix = "(", postfix = ")") {
                "name LIKE '%' || ? || '%'"
            }
            args.addAll(group)
        }

        val whereClause =
            if (conditions.isNotEmpty()) " WHERE " + conditions.joinToString(" AND ") else ""

        val sql = if (q.categoryId == RECENTLY_ADDED_CATEGORY_ID) {
            // "Recently Added" = the RECENT_LIMIT most-recently-cached series, newest first.
            // Cap in an INNER subquery so the Room PagingSource's appended LIMIT/OFFSET pages
            // WITHIN the capped set instead of overriding the cap. Genre/search predicates
            // apply inside the inner WHERE so filtering still works.
            "SELECT * FROM (SELECT * FROM series_v2$whereClause ORDER BY cachedAt DESC LIMIT $RECENT_LIMIT)"
        } else {
            val order = when (q.sortMode) {
                // series_v2 has no 'added' timestamp column; provider order (num) is the
                // closest proxy — newest additions arrive with the highest num.
                SeriesSortMode.RECENTLY_ADDED -> " ORDER BY num DESC"
                SeriesSortMode.TOP_RATED -> " ORDER BY rating5based DESC, name COLLATE NOCASE ASC"
                SeriesSortMode.A_TO_Z -> " ORDER BY name COLLATE NOCASE ASC"
                // Episode counts only exist for series whose details were cached locally
                // (legacy `episodes` or v2 `episodes_v2_core`); everything else sorts
                // to the tail alphabetically.
                // Handled below — it needs joins, not just an ORDER BY.
                SeriesSortMode.MOST_EPISODES -> ""
            }
            if (q.sortMode == SeriesSortMode.MOST_EPISODES) {
                mostEpisodesSql(whereClause)
            } else {
                "SELECT * FROM series_v2$whereClause$order"
            }
        }
        return androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun mostEpisodesSql(whereClause: String): String = buildMostEpisodesSql(whereClause)

    /**
     * B-3: "Most Episodes", with each episode table counted ONCE.
     *
     * It used to order by two **correlated** subqueries — a COUNT over `episodes` and a COUNT over
     * `episodes_v2_core`, re-run for every series row. Because the counts sit in the ORDER BY, SQLite
     * has to evaluate them for the whole table before it can hand back even the first page, so paging
     * bought nothing and every page re-did all of it: 2×N index lookups plus a full sort, per page.
     *
     * Grouping each table up front turns that into one grouped scan each, joined by series id — the
     * same numbers, computed once. Deliberately NOT a denormalised count column on `series_v2`: that
     * would mean keeping a mirror in step with two tables written by different subsystems, and a
     * stale mirror shows the wrong order with nothing to hint why.
     *
     * The subqueries expose only `sid`/`c`, so the caller's WHERE (categoryId / name / genre) stays
     * unambiguous against `series_v2`.
     *
     * Unchanged, and worth knowing before trusting this chip: the counts only cover series whose
     * episodes were cached locally. Series nobody has opened all count 0 and tie, so for most of the
     * list this still sorts alphabetically. It ranks what has been fetched, not what exists.
     */
    private fun filterByGenre(series: List<XtreamSeriesInfo>, genre: String?): List<XtreamSeriesInfo> {
        if (genre.isNullOrBlank()) return series
        return series.filter { it.genre?.contains(genre, ignoreCase = true) == true }
    }

    private fun sortSeries(series: List<XtreamSeriesInfo>, sortMode: SeriesSortMode): List<XtreamSeriesInfo> {
        return when (sortMode) {
            SeriesSortMode.RECENTLY_ADDED -> series.sortedByDescending { it.num ?: 0 }
            SeriesSortMode.TOP_RATED -> series.sortedByDescending { it.rating_5based ?: 0.0 }
            SeriesSortMode.A_TO_Z -> series.sortedBy { it.name?.lowercase() ?: "" }
            SeriesSortMode.MOST_EPISODES -> series.sortedByDescending { s ->
                s.episodes?.values?.sumOf { it.size } ?: 0
            }
        }
    }

    /** Applied by the phone Browse filter sheet; empty restores the unfiltered catalogue. */
    fun setTitleFilters(groups: List<List<String>>) {
        _titleFiltersFlow.value = groups
    }

    /**
     * How many series the current category holds under a set of filters, or null when the answer
     * is not worth the wait — the virtual "All" categories. Zero is a real answer.
     */
    suspend fun countWithFilters(groups: List<List<String>>): Int? {
        val category = _selectedCategoryFlow.value ?: return null
        if (isVirtualCategory(category)) return null
        val query = buildSeriesQuery(
            SeriesQuery(
                category,
                _searchQueryFlow.value,
                _sortModeFlow.value,
                _genreFlow.value,
                groups,
            )
        )
        val counted = androidx.sqlite.db.SimpleSQLiteQuery(
            query.sql.replace("SELECT * FROM", "SELECT COUNT(*) FROM").substringBefore(" ORDER BY"),
            (0 until query.argCount).map { index -> queryArg(query, index) }.toTypedArray(),
        )
        return runCatching { seriesDao.countSeriesRaw(counted) }.getOrNull()
    }

    /** SimpleSQLiteQuery does not expose its bindings, so they are re-read through the binder. */
    private fun queryArg(query: androidx.sqlite.db.SimpleSQLiteQuery, index: Int): Any? {
        var value: Any? = null
        query.bindTo(object : androidx.sqlite.db.SupportSQLiteProgram {
            override fun bindNull(i: Int) { if (i == index + 1) value = null }
            override fun bindLong(i: Int, v: Long) { if (i == index + 1) value = v }
            override fun bindDouble(i: Int, v: Double) { if (i == index + 1) value = v }
            override fun bindString(i: Int, v: String) { if (i == index + 1) value = v }
            override fun bindBlob(i: Int, v: ByteArray) { if (i == index + 1) value = v }
            override fun clearBindings() = Unit
            override fun close() = Unit
        })
        return value
    }

    fun setSortMode(mode: SeriesSortMode) {
        _sortModeFlow.value = mode
    }

    /** Pass null or "All" to clear the genre filter. */
    fun setGenre(genre: String?) {
        _genreFlow.value = genre?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
    }

    /**
     * Season/episode counts per series from the local episodes cache.
     * Series without cached details are absent from the map.
     */
    suspend fun getEpisodeCounts(): Map<String, Pair<Int, Int>> = withContext(ioDispatcher) {
        try {
            seriesDao.getSeriesEpisodeCounts().associate { it.seriesId to (it.seasonCount to it.episodeCount) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    init {
        // Initialize repository with credentials before loading data
        initializeRepository()
        // Auto-load categories on initialization
        onEvent(SeriesEvent.LoadCategories)
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
    
    fun onEvent(event: SeriesEvent) {
        when (event) {
            is SeriesEvent.LoadCategories -> loadCategories()
            is SeriesEvent.SelectCategory -> {
                // Clear error immediately on new selection to prevent stale messages
                _uiState.update { it.copy(error = null) }
                loadSeriesForCategory(event.categoryId)
            }
            is SeriesEvent.PlaySeries -> handlePlaySeries(event.series)
            is SeriesEvent.SearchSeries -> searchSeries(event.query)
            is SeriesEvent.ClearSearch -> clearSearch()
            is SeriesEvent.Retry -> retry()
        }
    }
    
    private fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCategories = true, error = null) }

            val realCategories = withContext(ioDispatcher) {
                repository.ensureSeriesCategories()
            }

            // Always show the two virtual categories (All Series / Recently Added) at the
            // top, even when the provider returns no real categories.
            updateCategories(withVirtualCategories(realCategories), finalizeLoading = true)
            refreshCategoryCounts()
        }
    }
    
    private fun loadSeriesForCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        selectedCategoryId = categoryId,
                        isLoadingSeries = true,
                        isSwitchingCategory = true, // Track transition start
                        error = null
                    )
                }
                
                // Trigger paging for this category
                _selectedCategoryFlow.value = categoryId

                if (categoryId == FAVORITES_CATEGORY_ID || isVirtualCategory(categoryId)) {
                    // Favorites + virtual (All Series / Recently Added) read the local DB;
                    // there is no per-category server fetch for them.
                    _uiState.update {
                        it.copy(
                            isLoadingSeries = false,
                            isSwitchingCategory = false
                        )
                    }
                } else {
                    // Fetch series from repository (lazy loading / sync)
                    val result = withContext(ioDispatcher) {
                        repository.fetchSeriesForCategory(categoryId)
                    }

                    result.onSuccess {
                        _uiState.update {
                            it.copy(
                                isLoadingSeries = false,
                                isSwitchingCategory = false
                            )
                        }
                        // New rows may have been synced — refresh the sidebar counts.
                        refreshCategoryCounts()
                    }
                    
                    result.onFailure { error ->
                        _uiState.update {
                            it.copy(
                                isLoadingSeries = false,
                                isSwitchingCategory = false,
                                error = error.message ?: "Failed to load series"
                            )
                        }
                    }

                    publishCategoryStatus(categoryId)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingSeries = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
                publishCategoryStatus(categoryId)
            }
        }
    }
    
    private fun updateCategories(categories: List<XtreamCategory>, finalizeLoading: Boolean) {
        val previousState = _uiState.value
        val previousSelected = previousState.selectedCategoryId
        val newSelected = when {
            previousSelected != null && categories.any { it.category_id == previousSelected } -> previousSelected
            else -> categories.firstOrNull()?.category_id
        }
        val shouldLoadSeries = newSelected != null && (
            previousSelected == null ||
            previousSelected != newSelected ||
            finalizeLoading
        )
        
        _uiState.update {
            it.copy(
                categories = categories,
                selectedCategoryId = newSelected,
                isLoadingCategories = !finalizeLoading,
                error = null
            )
        }
        
        if (shouldLoadSeries && newSelected != null) {
            loadSeriesForCategory(newSelected)
        }
        
        if (categories.isEmpty()) {
            _uiState.update { it.copy(series = emptyList()) }
        }
    }
    
    private fun handleEmptyCategories(message: String) {
        _uiState.update {
            it.copy(
                categories = emptyList(),
                series = emptyList(),
                selectedCategoryId = null,
                isLoadingCategories = false,
                error = message
            )
        }
    }
    
    private fun handlePlaySeries(series: XtreamSeriesInfo) {
        // Emit navigation event (handle in Fragment)
        savedStateHandle["play_series"] = series
    }

    private fun searchSeries(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = trimmed, isSearching = trimmed.isNotEmpty()) }
        _searchQueryFlow.value = trimmed
    }

    private fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", isSearching = false) }
        _searchQueryFlow.value = ""
    }
    
    private fun retry() {
        val currentState = _uiState.value
        if (currentState.categories.isEmpty()) {
            onEvent(SeriesEvent.LoadCategories)
        } else {
            currentState.selectedCategoryId?.let { categoryId ->
                onEvent(SeriesEvent.SelectCategory(categoryId))
            }
        }
    }
    
    /**
     * Week 13: Check if series is favorited (O(1) cache lookup)
     */
    fun isFavorite(streamId: String): Boolean {
        return favoritesCache.isFavorite(streamId)
    }
    
    /**
     * Week 13: Get repository for favorites operations
     */
    fun getRepository(): XtreamRepository = repository

    private fun publishCategoryStatus(categoryId: String) {
        val status = repository.getSeriesCategoryStatus(categoryId)
        _uiState.update { current ->
            current.copy(categoryStatus = status?.takeIf { it.isActionable })
        }
    }

    private suspend fun buildFavoriteSeries(
        favorites: List<FavoriteEntity>,
        searchQuery: String
    ): List<XtreamSeriesInfo> {
        val items = favorites.mapNotNull { favorite ->
            repository.getSeriesById(favorite.streamId) ?: favoriteFallbackSeries(favorite)
        }
        if (searchQuery.isBlank()) return items
        val query = searchQuery.trim()
        return items.filter { series ->
            series.name?.contains(query, ignoreCase = true) == true
        }
    }

    private fun favoriteFallbackSeries(favorite: FavoriteEntity): XtreamSeriesInfo {
        return XtreamSeriesInfo(
            num = null,
            name = favorite.name,
            series_id = favorite.streamId,
            cover = favorite.iconUrl,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            rating = null,
            rating_5based = null,
            category_id = FAVORITES_CATEGORY_ID,
            category_ids = null,
            backdrop_path = null,
            youtube_trailer = null,
            episodes = null
        )
    }
}

/**
 * B-3: the "Most Episodes" ordering, with each episode table counted ONCE.
 *
 * It used to order by two **correlated** subqueries — a COUNT over `episodes` and a COUNT over
 * `episodes_v2_core`, re-run for every series row. Because the counts sit in the ORDER BY, SQLite has
 * to evaluate them for the whole table before it can hand back even the first page, so paging bought
 * nothing and every page re-did all of it: 2×N index lookups plus a full sort, per page.
 *
 * Grouping each table up front turns that into one grouped scan each, joined by series id — the same
 * numbers, computed once. Deliberately NOT a denormalised count column on `series_v2`: that would
 * mean keeping a mirror in step with two tables written by different subsystems, and a stale mirror
 * shows the wrong order with nothing to hint why.
 *
 * The subqueries expose only `sid`/`c`, so the caller's WHERE (categoryId / name / genre) stays
 * unambiguous against `series_v2`.
 *
 * Top-level and `internal` so `MostEpisodesSortTest` executes THIS string rather than a copy of it —
 * raw SQL behind a `@RawQuery` is unchecked by the compiler, and a test holding its own duplicate
 * would keep passing while production drifted.
 *
 * Unchanged, and worth knowing before trusting this chip: the counts only cover series whose episodes
 * were cached locally. Series nobody has opened all count 0 and tie, so for most of the list this
 * still sorts alphabetically. It ranks what has been fetched, not what exists.
 */
internal fun buildMostEpisodesSql(whereClause: String): String =
    "SELECT series_v2.* FROM series_v2" +
        " LEFT JOIN (SELECT seriesId AS sid, COUNT(*) AS c FROM episodes GROUP BY seriesId)" +
        " legacy ON legacy.sid = series_v2.seriesId" +
        " LEFT JOIN (SELECT series_id AS sid, COUNT(*) AS c FROM episodes_v2_core GROUP BY series_id)" +
        " v2 ON v2.sid = series_v2.seriesId" +
        whereClause +
        " ORDER BY MAX(IFNULL(legacy.c, 0), IFNULL(v2.c, 0)) DESC, name COLLATE NOCASE ASC"
