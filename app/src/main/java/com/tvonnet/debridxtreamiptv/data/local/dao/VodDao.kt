package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity

/**
 * DAO for VOD Movies
 * Phase 1: Database Infrastructure
 */
@Dao
interface VodDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<VodEntity>)
    
    @Query("DELETE FROM vod_v2 WHERE categoryId = :categoryId")
    suspend fun deleteMoviesByCategory(categoryId: String)
    
    @Transaction
    suspend fun replaceMoviesForCategory(categoryId: String, movies: List<VodEntity>) {
        deleteMoviesByCategory(categoryId)
        insertMovies(movies)
    }
    
    /**
     * PagingSource for movies in a category
     * Supports search query filtering
     */
    @Query("""
        SELECT * FROM vod_v2 
        WHERE categoryId = :categoryId 
        AND (:searchQuery = '' OR name LIKE '%' || :searchQuery || '%')
        ORDER BY num ASC
    """)
    fun getMoviesByCategory(categoryId: String, searchQuery: String = ""): PagingSource<Int, VodEntity>
    
    /**
     * Get all movies for global search
     */
    @Query("""
        SELECT * FROM vod_v2 
        WHERE name LIKE '%' || :query || '%'
        LIMIT 50
    """)
    suspend fun searchMovies(query: String): List<VodEntity>
    
    @Query("DELETE FROM vod_v2")
    suspend fun deleteAllMovies()
}
