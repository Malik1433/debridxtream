package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 7: the self-contained search-history sub-domain lifted out of the XtreamRepository god-class.
 * Owns only the SearchHistoryDao — no session/catalog coupling — so the bodies moved here verbatim and
 * XtreamRepository delegates its search-history API to an instance of this class. Content search
 * (searchSeries/searchVod/searchLive/searchEpg) stays on the repository: those are one-line DAO/cache
 * delegators spread across four different collaborators, so grouping them here would buy nothing.
 */
internal class SearchHistoryRepository(
    private val searchHistoryDao: SearchHistoryDao
) {
    private val tag = "XtreamRepository"

    /** Get recent search queries as Flow (reactive) */
    fun getRecentSearches(limit: Int = 10): Flow<List<SearchHistoryEntity>> {
        return searchHistoryDao?.getRecentSearches(limit)
            ?: throw IllegalStateException("SearchHistoryDao not initialized")
    }

    /** Save a search query to history */
    suspend fun saveSearchQuery(query: String) {
        if (query.isBlank()) return

        val search = SearchHistoryEntity(query = query.trim())
        searchHistoryDao?.insertSearch(search)

        // Clean old searches periodically
        try {
            searchHistoryDao?.cleanOldSearches()
        } catch (e: Exception) {
            Log.w(tag, "Failed to clean old searches", e)
        }

        Log.d(tag, "Saved search query: $query")
    }

    /** Remove a specific search query from history */
    suspend fun removeSearchQuery(query: String) {
        searchHistoryDao?.deleteSearchByQuery(query)
        Log.d(tag, "Removed search query: $query")
    }

    /** Clear all search history */
    suspend fun clearSearchHistory() {
        searchHistoryDao?.deleteAllSearches()
        Log.d(tag, "Cleared all search history")
    }
}
