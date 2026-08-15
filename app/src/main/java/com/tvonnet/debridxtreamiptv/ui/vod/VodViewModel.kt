package com.tvonnet.debridxtreamiptv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamVodInfo
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID

data class VodUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val movies: List<XtreamVodInfo> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val isLoadingMovies: Boolean = false,
    val isSwitchingCategory: Boolean = false, // New: track transition period
    val error: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    /** category_id -> number of synced movies, for the sidebar count badges. */
    val categoryCounts: Map<String, Int> = emptyMap()
)

sealed class VodEvent {
    object LoadCategories : VodEvent()
    data class SelectCategory(val categoryId: String) : VodEvent()
    data class PlayMovie(val movie: XtreamVodInfo) : VodEvent()
    data class SearchMovies(val query: String) : VodEvent()
    object ClearSearch : VodEvent()
    object Retry : VodEvent()
}

/** Sort order for the movie grid. Mirrors the sort chips in the VOD header. */
enum class VodSortMode { RECENTLY_ADDED, TOP_RATED, A_TO_Z, NEWEST }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val vodDao: com.tvonnet.debridxtreamiptv.data.local.dao.VodDao,
    private val favoritesCache: com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache,
    val savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    companion object {
        /** Synthetic sidebar rows (above Favorites). Sentinel ids can't collide with real Xtream ids. */
        const val ALL_MOVIES_CATEGORY_ID = "__all_movies__"
        const val RECENTLY_ADDED_VOD_CATEGORY_ID = "__recently_added_vod__"

        /** "Recently Added" = the newest N movies by the provider `added` timestamp. */
        const val RECENT_LIMIT = 100
    }

    private fun isVirtualCategory(categoryId: String?): Boolean =
        categoryId == ALL_MOVIES_CATEGORY_ID || categoryId == RECENTLY_ADDED_VOD_CATEGORY_ID

    /** Prepend the two synthetic categories above the real Xtream ones. */
    private fun withVirtualCategories(real: List<XtreamCategory>): List<XtreamCategory> {
        val virtual = listOf(
            XtreamCategory(ALL_MOVIES_CATEGORY_ID, "All Movies", null),
            XtreamCategory(RECENTLY_ADDED_VOD_CATEGORY_ID, "Recently Added", null)
        )
        return virtual + real.filterNot { isVirtualCategory(it.category_id) }
    }

    private fun refreshCategoryCounts() {
        viewModelScope.launch {
            val counts = try {
                val perCat = vodDao.getMovieCountsByCategory().associate { it.categoryId to it.count }
                val total = vodDao.getTotalMovieCount()
                perCat + mapOf(
                    ALL_MOVIES_CATEGORY_ID to total,
                    // Capped list → count is the cap (or fewer if the catalog is small).
                    RECENTLY_ADDED_VOD_CATEGORY_ID to minOf(RECENT_LIMIT, total)
                )
            } catch (_: Exception) {
                emptyMap()
            }
            _uiState.update { it.copy(categoryCounts = counts) }
        }
    }

    private val _uiState = MutableStateFlow(VodUiState())
    val uiState: StateFlow<VodUiState> = _uiState.asStateFlow()
    
    /**
     * Paged movies flow for current selected category
     * Using Paging3 for efficient memory usage and smooth scrolling
     */
    private val _selectedCategoryFlow = MutableStateFlow<String?>(null)
    private val _searchQueryFlow = MutableStateFlow("")
    private val _sortModeFlow = MutableStateFlow(VodSortMode.RECENTLY_ADDED)

    /**
     * Title filters from the phone's filter sheet, as groups of alternatives: OR inside a group,
     * AND between groups, so "4K or 1080p" and "English" narrow together.
     *
     * It is EMPTY by default and the television never sets it, so the SQL that screen has always
     * run is unchanged byte for byte.
     */
    private val _titleFiltersFlow = MutableStateFlow<List<List<String>>>(emptyList())
    private val _genreFlow = MutableStateFlow<String?>(null)

    data class MovieQuery(
        val categoryId: String,
        val searchQuery: String,
        val sortMode: VodSortMode,
        val genre: String?,
        /** Phone filter sheet; empty everywhere else, including the whole television screen. */
        val titleFilters: List<List<String>> = emptyList(),
    )

    val pagedMovies: Flow<PagingData<XtreamVodInfo>> = combine(
        _selectedCategoryFlow.filterNotNull(),
        _searchQueryFlow,
        _sortModeFlow,
        _genreFlow,
        _titleFiltersFlow,
    ) { categoryId, searchQuery, sortMode, genre, titleFilters ->
        MovieQuery(categoryId, searchQuery, sortMode, genre, titleFilters)
    }.flatMapLatest { q ->
        if (q.categoryId == FAVORITES_CATEGORY_ID) {
            repository.getFavoritesByType("vod")
                .map { favorites ->
                    val items = sortMovies(
                        filterByGenre(buildFavoriteMovies(favorites, q.searchQuery), q.genre),
                        q.sortMode
                    )
                    PagingData.from(items)
                }
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = 60,
                    enablePlaceholders = true, // Placeholders work well with Room
                    prefetchDistance = 20
                ),
                pagingSourceFactory = {
                    vodDao.getMoviesByCategoryRaw(buildMovieQuery(q))
                }
            ).flow
                .map { pagingData ->
                    pagingData.map { entity -> entity.toXtreamVodInfo() }
                }
        }
    }.cachedIn(viewModelScope)

    private fun buildMovieQuery(q: MovieQuery): androidx.sqlite.db.SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        // "All Movies" / "Recently Added" span every category (no category filter).
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
        // Phone filter sheet: OR inside a group, AND between groups. Empty on the television.
        q.titleFilters.filter { it.isNotEmpty() }.forEach { group ->
            conditions += group.joinToString(" OR ", prefix = "(", postfix = ")") {
                "name LIKE '%' || ? || '%'"
            }
            args.addAll(group)
        }

        val whereClause =
            if (conditions.isNotEmpty()) " WHERE " + conditions.joinToString(" AND ") else ""

        val sql = if (q.categoryId == RECENTLY_ADDED_VOD_CATEGORY_ID) {
            // "Recently Added" = the RECENT_LIMIT newest movies by the provider `added`
            // timestamp (VOD has a real one, unlike series). Cap in an INNER subquery so
            // the Room PagingSource's appended LIMIT/OFFSET pages WITHIN the capped set.
            // B-2: sort on the indexed numeric mirror (added_ts) instead of CAST(added).
            "SELECT * FROM (SELECT * FROM vod_v2$whereClause ORDER BY added_ts DESC LIMIT $RECENT_LIMIT)"
        } else {
            val order = when (q.sortMode) {
                // B-2: added_ts is the indexable numeric mirror of the `added` timestamp string.
                VodSortMode.RECENTLY_ADDED -> " ORDER BY added_ts DESC, num DESC"
                VodSortMode.TOP_RATED -> " ORDER BY rating5based DESC, name COLLATE NOCASE ASC"
                VodSortMode.A_TO_Z -> " ORDER BY name COLLATE NOCASE ASC"
                VodSortMode.NEWEST -> " ORDER BY releaseDate DESC, added_ts DESC"
            }
            "SELECT * FROM vod_v2$whereClause$order"
        }
        return androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun filterByGenre(movies: List<XtreamVodInfo>, genre: String?): List<XtreamVodInfo> {
        if (genre.isNullOrBlank()) return movies
        return movies.filter { it.genre?.contains(genre, ignoreCase = true) == true }
    }

    private fun sortMovies(movies: List<XtreamVodInfo>, sortMode: VodSortMode): List<XtreamVodInfo> {
        return when (sortMode) {
            VodSortMode.RECENTLY_ADDED -> movies.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
            VodSortMode.TOP_RATED -> movies.sortedByDescending { it.rating_5based ?: 0.0 }
            VodSortMode.A_TO_Z -> movies.sortedBy { it.name?.lowercase() ?: "" }
            VodSortMode.NEWEST -> movies.sortedByDescending { it.releaseDate ?: "" }
        }
    }

    /** Applied by the phone Browse filter sheet; empty restores the unfiltered catalogue. */
    fun setTitleFilters(groups: List<List<String>>) {
        _titleFiltersFlow.value = groups
    }

    /**
     * How many titles the current category holds for a given set of filter groups.
     *
     * Scoped to the CURRENT category on purpose (owner, 2026-08-15): a count per option across
     * this playlist's 141,525-title "All Movies" is not a free number, and a wrong count is worse
     * than none.
     */
    suspend fun countWithFilters(groups: List<List<String>>): Int? {
        val category = _selectedCategoryFlow.value ?: return null
        // Null, not zero: the virtual "All" categories span 141,525 rows on this owner's
        // playlist, and a count nobody waits for is worse than no count. Zero stays a real
        // answer, which is what disables the apply button.
        if (isVirtualCategory(category)) return null
        val query = buildMovieQuery(
            MovieQuery(category, _searchQueryFlow.value, _sortModeFlow.value, _genreFlow.value, groups)
        )
        val counted = androidx.sqlite.db.SimpleSQLiteQuery(
            query.sql.replace("SELECT * FROM", "SELECT COUNT(*) FROM").substringBefore(" ORDER BY"),
            (0 until query.argCount).map { index -> queryArg(query, index) }.toTypedArray(),
        )
        return runCatching { vodDao.countMoviesRaw(counted) }.getOrNull()
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

    fun setSortMode(mode: VodSortMode) {
        _sortModeFlow.value = mode
    }

    /** Pass null or "All" to clear the genre filter. */
    fun setGenre(genre: String?) {
        _genreFlow.value = genre?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
    }
    
    init {
        // Auto-load categories on initialization
        onEvent(VodEvent.LoadCategories)
    }
    
    fun onEvent(event: VodEvent) {
        when (event) {
            is VodEvent.LoadCategories -> loadCategories()
            is VodEvent.SelectCategory -> loadMoviesForCategory(event.categoryId)
            is VodEvent.PlayMovie -> handlePlayMovie(event.movie)
            is VodEvent.SearchMovies -> searchMovies(event.query)
            is VodEvent.ClearSearch -> clearSearch()
            is VodEvent.Retry -> retry()
        }
    }
    
    private fun loadCategories() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingCategories = true, error = null) }
                
                val realCategories = repository.ensureVodCategories()

                // Always show the two virtual categories (All Movies / Recently Added) at
                // the top, even when the provider returns no real categories.
                val categories = withVirtualCategories(realCategories)
                _uiState.update {
                    it.copy(
                        categories = categories,
                        isLoadingCategories = false,
                        selectedCategoryId = categories.firstOrNull()?.category_id
                    )
                }
                refreshCategoryCounts()

                // Auto-load the default landing (All Movies).
                categories.firstOrNull()?.category_id?.let { categoryId ->
                    loadMoviesForCategory(categoryId)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCategories = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
            }
        }
    }
    
    private fun loadMoviesForCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        selectedCategoryId = categoryId,
                        isLoadingMovies = true,
                        isSwitchingCategory = true, // Track transition start
                        error = null
                    )
                }
                
                // Trigger paging for this category (DB observation)
                _selectedCategoryFlow.value = categoryId

                if (categoryId == FAVORITES_CATEGORY_ID || isVirtualCategory(categoryId)) {
                    // Favorites + virtual (All Movies / Recently Added) read the local DB;
                    // there is no per-category server fetch for them.
                    _uiState.update {
                        it.copy(
                            isLoadingMovies = false,
                            isSwitchingCategory = false
                        )
                    }
                } else {
                    // Trigger network sync (Hybrid Sync)
                    val result = repository.fetchVodStreamsForCategory(categoryId)

                    result.onSuccess {
                        _uiState.update {
                            it.copy(
                                isLoadingMovies = false,
                                isSwitchingCategory = false
                            )
                        }
                        // New rows may have been synced — refresh the sidebar counts.
                        refreshCategoryCounts()
                    }
                    
                    result.onFailure { error ->
                        _uiState.update { 
                            it.copy(
                                isLoadingMovies = false,
                                isSwitchingCategory = false,
                                error = error.message
                            ) 
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingMovies = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
            }
        }
    }

    private fun searchMovies(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = trimmed, isSearching = trimmed.isNotEmpty()) }
        _searchQueryFlow.value = trimmed
    }

    private fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", isSearching = false) }
        _searchQueryFlow.value = ""
    }
    
    private fun handlePlayMovie(movie: XtreamVodInfo) {
        // Emit navigation event (handle in Fragment)
        savedStateHandle["play_movie"] = movie
    }
    
    private fun retry() {
        val currentState = _uiState.value
        if (currentState.categories.isEmpty()) {
            onEvent(VodEvent.LoadCategories)
        } else {
            currentState.selectedCategoryId?.let { categoryId ->
                onEvent(VodEvent.SelectCategory(categoryId))
            }
        }
    }
    
    /**
     * Week 13: Check if movie is favorited (O(1) cache lookup)
     */
    fun isFavorite(streamId: String): Boolean {
        return favoritesCache.isFavorite(streamId)
    }

    private suspend fun buildFavoriteMovies(
        favorites: List<FavoriteEntity>,
        searchQuery: String
    ): List<XtreamVodInfo> {
        val items = favorites.mapNotNull { favorite ->
            repository.getVodById(favorite.streamId) ?: favoriteFallbackMovie(favorite)
        }
        if (searchQuery.isBlank()) return items
        val query = searchQuery.trim()
        return items.filter { movie ->
            movie.name?.contains(query, ignoreCase = true) == true
        }
    }

    private fun favoriteFallbackMovie(favorite: FavoriteEntity): XtreamVodInfo {
        return XtreamVodInfo(
            num = null,
            name = favorite.name,
            stream_type = "vod",
            stream_id = favorite.streamId,
            stream_icon = favorite.iconUrl,
            rating = null,
            rating_5based = null,
            added = null,
            category_id = FAVORITES_CATEGORY_ID,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = null,
            duration = null,
            cover = favorite.iconUrl,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            youtube_trailer = null,
            rating_imdb = null
        )
    }
    
    /**
     * Week 13: Get repository for favorites operations
     */
    fun getRepository(): XtreamRepository = repository
}
