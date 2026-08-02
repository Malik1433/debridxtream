package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity

/** Number of synced movie rows per category id (for the sidebar count badges). */
data class CategoryMovieCount(
    val categoryId: String,
    val count: Int
)

/** COUNT + newest cachedAt for one category in one scan — the B-8 freshness gate's input. */
data class CategoryCacheFreshness(
    val rowCount: Int,
    val newestCachedAt: Long?
)

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
     * Dynamic paging query supporting genre filtering + sort ordering.
     * The SQL is assembled in the ViewModel (category + optional search/genre + ORDER BY).
     */
    @RawQuery(observedEntities = [VodEntity::class])
    fun getMoviesByCategoryRaw(query: SupportSQLiteQuery): PagingSource<Int, VodEntity>

    /**
     * Search movies by name, optionally scoped to a single category.
     * categoryId == null → search across all categories (global behavior).
     */
    @Query("""
        SELECT * FROM vod_v2
        WHERE name LIKE '%' || :query || '%'
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        LIMIT 50
    """)
    suspend fun searchMovies(query: String, categoryId: String?): List<VodEntity>

    /** Total distinct synced movies (used for the "All Movies" / "Recently Added" counts). */
    @Query("SELECT COUNT(DISTINCT streamId) FROM vod_v2")
    suspend fun getTotalMovieCount(): Int

    /** Per-category synced movie counts, in one pass, for the sidebar badges. */
    @Query("SELECT categoryId AS categoryId, COUNT(*) AS count FROM vod_v2 WHERE categoryId IS NOT NULL GROUP BY categoryId")
    suspend fun getMovieCountsByCategory(): List<CategoryMovieCount>

    /** B-8 freshness gate: is this category's synced copy recent enough to skip the refetch? */
    @Query("SELECT COUNT(*) AS rowCount, MAX(cachedAt) AS newestCachedAt FROM vod_v2 WHERE categoryId = :categoryId")
    suspend fun getCategoryFreshness(categoryId: String): CategoryCacheFreshness

    /** Full category list (non-paging), in the synced order — the fresh-path read for B-8. */
    @Query("SELECT * FROM vod_v2 WHERE categoryId = :categoryId ORDER BY num ASC")
    suspend fun getMoviesByCategorySync(categoryId: String): List<VodEntity>

    @Query("DELETE FROM vod_v2")
    suspend fun deleteAllMovies()

    @Query("SELECT * FROM vod_v2 WHERE streamId = :streamId")
    suspend fun getVodById(streamId: String): VodEntity?
}
