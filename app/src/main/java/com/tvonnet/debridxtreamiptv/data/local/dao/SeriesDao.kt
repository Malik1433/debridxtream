package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity

/**
 * Per-series season/episode counts aggregated from locally cached episode rows.
 * Only series whose details were fetched at least once have a row here.
 */
data class SeriesEpisodeCounts(
    val seriesId: String,
    val seasonCount: Int,
    val episodeCount: Int
)

/** Number of synced series rows per category id (for the sidebar count badges). */
data class CategorySeriesCount(
    val categoryId: String,
    val count: Int
)

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

    /**
     * Dynamic paging query supporting genre filtering + sort ordering.
     * The SQL is assembled in the ViewModel (category + optional search/genre + ORDER BY).
     * Mirrors VodDao.getMoviesByCategoryRaw.
     */
    @RawQuery(observedEntities = [SeriesEntity::class])
    fun getSeriesByCategoryRaw(query: SupportSQLiteQuery): PagingSource<Int, SeriesEntity>

    /**
     * Season/episode counts per series, derived from the locally cached episode rows.
     * Covers BOTH detail caches: legacy `episodes` (XtreamRepository.saveSeriesDetails)
     * and `episodes_v2_core` (SeriesDetailFragmentV2 / XtreamSeriesRepositoryV2 path).
     * UNION dedupes identical (series, season, episode) triples across the two tables.
     * Used by the Series grid for the "N S" / "N EP" badges and footer meta.
     * Note: the Xtream get_series LIST response carries no counts — these only exist
     * after a per-series get_series_info fetch, hence the graceful-hide fallback.
     */
    @Query(
        """
        SELECT seriesId, COUNT(DISTINCT seasonNumber) AS seasonCount, COUNT(*) AS episodeCount FROM (
            SELECT seriesId, seasonNumber, episodeNumber FROM episodes
            UNION
            SELECT series_id AS seriesId, season_number AS seasonNumber, episode_number AS episodeNumber FROM episodes_v2_core
        ) GROUP BY seriesId
        """
    )
    suspend fun getSeriesEpisodeCounts(): List<SeriesEpisodeCounts>

    @Query("SELECT COUNT(*) FROM series_v2 WHERE categoryId = :categoryId")
    suspend fun getSeriesCountByCategory(categoryId: String): Int

    /** Total distinct synced series (used for the "All Series" / "Recently Added" counts). */
    @Query("SELECT COUNT(DISTINCT seriesId) FROM series_v2")
    suspend fun getTotalSeriesCount(): Int

    /** Per-category synced series counts, in one pass, for the sidebar badges. */
    @Query("SELECT categoryId AS categoryId, COUNT(*) AS count FROM series_v2 WHERE categoryId IS NOT NULL GROUP BY categoryId")
    suspend fun getSeriesCountsByCategory(): List<CategorySeriesCount>
    
    /**
     * Search series by name, optionally scoped to a single category.
     * categoryId == null → search across all categories (global behavior).
     */
    @Query("""
        SELECT * FROM series_v2
        WHERE name LIKE '%' || :query || '%'
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        LIMIT 50
    """)
    suspend fun searchSeries(query: String, categoryId: String?): List<SeriesEntity>
    
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

    /**
     * Candidate rows for cross-category stream aggregation: any series whose name
     * contains the given core title (decorated variants like "EN| Title 4K" match).
     * Callers verify candidates with normalized-name equality.
     */
    @Query("SELECT * FROM series_v2 WHERE name LIKE '%' || :coreTitle || '%' LIMIT 200")
    suspend fun findSeriesByNameLike(coreTitle: String): List<SeriesEntity>
}
