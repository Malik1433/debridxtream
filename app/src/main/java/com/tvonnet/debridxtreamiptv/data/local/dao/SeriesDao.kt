package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity

/**
 * DAO for Series
 * Phase 1: Database Infrastructure
 */
@Dao
interface SeriesDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: List<SeriesEntity>)
    
    @Query("DELETE FROM series_v2 WHERE categoryId = :categoryId")
    suspend fun deleteSeriesByCategory(categoryId: String)
    
    @Transaction
    suspend fun replaceSeriesForCategory(categoryId: String, series: List<SeriesEntity>) {
        deleteSeriesByCategory(categoryId)
        insertSeries(series)
    }
    
    /**
     * PagingSource for series in a category
     * Supports search query filtering
     */
    @Query("""
        SELECT * FROM series_v2 
        WHERE categoryId = :categoryId 
        AND (:searchQuery = '' OR name LIKE '%' || :searchQuery || '%')
        ORDER BY num ASC
    """)
    fun getSeriesByCategory(categoryId: String, searchQuery: String = ""): PagingSource<Int, SeriesEntity>
    
    @Query("SELECT COUNT(*) FROM series_v2 WHERE categoryId = :categoryId")
    suspend fun getSeriesCountByCategory(categoryId: String): Int
    
    /**
     * Get all series for global search
     */
    @Query("""
        SELECT * FROM series_v2 
        WHERE name LIKE '%' || :query || '%'
        LIMIT 50
    """)
    suspend fun searchSeries(query: String): List<SeriesEntity>
    
    @Query("DELETE FROM series_v2")
    suspend fun deleteAllSeries()

    // --- Phase 2: Seasons & Episodes ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeasons(seasons: List<com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity>)

    @Query("DELETE FROM seasons WHERE seriesId = :seriesId")
    suspend fun deleteSeasonsBySeries(seriesId: String)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun deleteEpisodesBySeries(seriesId: String)

    @Transaction
    @Query("SELECT * FROM series_v2 WHERE seriesId = :seriesId")
    suspend fun getSeriesWithSeasonsAndEpisodes(seriesId: String): com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes?

    @Transaction
    @Query("SELECT * FROM series_v2 WHERE seriesId = :seriesId")
    fun getSeriesWithSeasonsAndEpisodesFlow(seriesId: String): kotlinx.coroutines.flow.Flow<com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes?>

    @Transaction
    suspend fun saveSeriesDetails(
        seriesId: String,
        seasons: List<com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity>,
        episodes: List<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity>
    ) {
        deleteSeasonsBySeries(seriesId)
        deleteEpisodesBySeries(seriesId)
        insertSeasons(seasons)
        insertEpisodes(episodes)
    }

    @Query("UPDATE episodes SET is_watched = :isWatched, resume_position = :resumePosition, duration = :duration WHERE episodeId = :episodeId")
    suspend fun updatePlaybackStatus(episodeId: String, isWatched: Boolean, resumePosition: Long, duration: Long)

    @Query("SELECT * FROM series_v2 WHERE seriesId = :seriesId")
    suspend fun getSeriesById(seriesId: String): SeriesEntity?
}
