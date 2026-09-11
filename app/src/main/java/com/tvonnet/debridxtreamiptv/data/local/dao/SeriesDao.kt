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
 * The part of an `episodes` row this device owns: what the player wrote, not what the provider
 * sent. Read before a series re-sync so [SeriesDao.saveSeriesDetails] can put it back.
 */
data class EpisodePlaybackState(
    val episodeId: String,
    val is_watched: Boolean,
    val resume_position: Long,
    val duration: Long
)

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

    /** How many rows the same WHERE clause matches — the filter sheet's count. */
    @RawQuery
    suspend fun countSeriesRaw(query: SupportSQLiteQuery): Int

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
    
    /** B-8 freshness gate: is this category's synced copy recent enough to skip the refetch? */
    @Query("SELECT COUNT(*) AS rowCount, MAX(cachedAt) AS newestCachedAt FROM series_v2 WHERE categoryId = :categoryId")
    suspend fun getCategoryFreshness(categoryId: String): CategoryCacheFreshness

    /** Full category list (non-paging), in the synced order — the fresh-path read for B-8. */
    @Query("SELECT * FROM series_v2 WHERE categoryId = :categoryId ORDER BY num ASC")
    suspend fun getSeriesByCategorySync(categoryId: String): List<SeriesEntity>

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

    // S2: whole-table wipes, for the one caller that must leave nothing behind — a change of
    // provider. Seasons and episodes were only ever deletable per series, so a switch left the
    // episode rows of every series the new provider does not carry sitting in the table for good.
    @Query("DELETE FROM seasons")
    suspend fun deleteAllSeasons()

    @Query("DELETE FROM episodes")
    suspend fun deleteAllEpisodes()

    @Transaction
    @Query("SELECT * FROM series_v2 WHERE seriesId = :seriesId")
    suspend fun getSeriesWithSeasonsAndEpisodes(seriesId: String): com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes?

    @Transaction
    @Query("SELECT * FROM series_v2 WHERE seriesId = :seriesId")
    fun getSeriesWithSeasonsAndEpisodesFlow(seriesId: String): kotlinx.coroutines.flow.Flow<com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes?>

    /**
     * The three columns on `episodes` that belong to THIS DEVICE rather than to the provider.
     * Everything else in the row is re-fetchable; these are not.
     */
    @Query("SELECT episodeId, is_watched, resume_position, duration FROM episodes WHERE seriesId = :seriesId")
    suspend fun getEpisodePlaybackState(seriesId: String): List<EpisodePlaybackState>

    /**
     * Replaces a series' seasons and episodes with what the provider just returned — **without
     * throwing away what this device learned about them** (2026-09-10).
     *
     * The delete-then-insert is deliberate: a provider can renumber or drop episodes, so the
     * remote listing has to win on structure. But `is_watched`, `resume_position` and `duration`
     * are written HERE, by the player (see [updatePlaybackStatus]), and the provider's payload has
     * no idea they exist. Re-inserting the fresh rows therefore reset every one of them to its
     * default, and because this sync runs whenever a series detail is opened, the customer's
     * watched ticks came back cleared after all but the current app session — the exact bug this
     * carry-over fixes.
     *
     * Carried over by `episodeId`, which is the primary key: an episode the provider no longer
     * lists simply has nothing to carry, and a genuinely new episode starts unwatched.
     */
    @Transaction
    suspend fun saveSeriesDetails(
        seriesId: String,
        seasons: List<com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity>,
        episodes: List<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity>
    ) {
        val previous = getEpisodePlaybackState(seriesId).associateBy { it.episodeId }

        // "Never let a failed refresh destroy good data." A partial or failed detail fetch arrives
        // here as an empty episode list, and without this the series' episodes — and everything
        // this device recorded about them — were deleted and nothing put back. The V2 sync has
        // guarded this since it was written (SeriesEpisodeSync.replaceEpisodes); this path never did.
        if (episodes.isEmpty() && previous.isNotEmpty()) return

        deleteSeasonsBySeries(seriesId)
        deleteEpisodesBySeries(seriesId)
        insertSeasons(seasons)
        insertEpisodes(
            episodes.map { episode ->
                val kept = previous[episode.episodeId] ?: return@map episode
                episode.copy(
                    isWatched = kept.is_watched,
                    resumePosition = kept.resume_position,
                    duration = kept.duration
                )
            }
        )
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
