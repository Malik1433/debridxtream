package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Search History operations
 * Week 10: Favorites System - Search History Persistence
 */
@Dao
interface SearchHistoryDao {
    
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun getRecentSearches(limit: Int = 10): Flow<List<SearchHistoryEntity>>
    
    @Query("SELECT * FROM search_history WHERE query = :query")
    suspend fun getSearchByQuery(query: String): SearchHistoryEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity)
    
    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearchByQuery(query: String)
    
    @Query("DELETE FROM search_history")
    suspend fun deleteAllSearches()
    
    /**
     * Clean old searches (keep only last 50)
     * This prevents unlimited growth of search history
     */
    @Query("""
        DELETE FROM search_history 
        WHERE id NOT IN (
            SELECT id FROM search_history 
            ORDER BY searchedAt DESC 
            LIMIT 50
        )
    """)
    suspend fun cleanOldSearches()
}

