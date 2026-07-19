package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import kotlinx.coroutines.flow.Flow

data class SeasonWatchedCount(val season_number: Int, val watchedEpisodes: Int)

@Dao
interface WatchedStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: WatchedStateEntity)

    @Query("SELECT * FROM watched_state WHERE identity_key = :identityKey")
    suspend fun getState(identityKey: String): WatchedStateEntity?

    @Query("SELECT * FROM watched_state WHERE identity_key = :identityKey")
    fun observeState(identityKey: String): Flow<WatchedStateEntity?>

    @Query("SELECT * FROM watched_state WHERE identity_key IN (:identityKeys)")
    fun observeStates(identityKeys: List<String>): Flow<List<WatchedStateEntity>>

    @Query("SELECT identity_key FROM watched_state WHERE content_type = 'MOVIE' AND is_watched = 1")
    fun observeAllWatchedMovieKeys(): Flow<List<String>>

    /** In-progress (unwatched, position > 0) IPTV movies — drives the browse-card
     *  progress strip. Emits on every progress/watched change. */
    @Query(
        """
        SELECT * FROM watched_state
        WHERE content_type = 'MOVIE'
            AND source = 'xtream'
            AND is_watched = 0
            AND progress_ms > 0
            AND iptv_stream_id IS NOT NULL
        """
    )
    fun observeInProgressMovies(): Flow<List<WatchedStateEntity>>

    @Query("SELECT identity_key FROM watched_state WHERE content_type = 'EPISODE' AND is_watched = 1")
    fun observeAllWatchedEpisodeKeys(): Flow<List<String>>

    @Query(
        """
        UPDATE watched_state
        SET is_watched = :watched,
            watched_at = CASE WHEN :watched THEN :timestamp ELSE NULL END,
            updated_at = :timestamp,
            manual_state = CASE WHEN :watched THEN 'WATCHED' ELSE 'UNWATCHED' END,
            manual_updated_at = :timestamp
        WHERE identity_key = :identityKey
        """
    )
    suspend fun setManualWatched(
        identityKey: String,
        watched: Boolean,
        timestamp: Long
    ): Int

    @Query(
        """
        SELECT * FROM watched_state
        WHERE content_type = 'EPISODE'
            AND source = :source
            AND series_id = :seriesIdentity
            AND season_number = :seasonNumber
        ORDER BY episode_number ASC
        """
    )
    suspend fun getEpisodeStatesForSeason(
        seriesIdentity: String,
        source: String,
        seasonNumber: Int
    ): List<WatchedStateEntity>

    @Query(
        """
        SELECT * FROM watched_state
        WHERE content_type = 'EPISODE'
            AND source = :source
            AND series_id = :seriesIdentity
        ORDER BY season_number ASC, episode_number ASC
        """
    )
    suspend fun getEpisodeStatesForSeries(
        seriesIdentity: String,
        source: String
    ): List<WatchedStateEntity>

    @Query("SELECT season_number, COUNT(identity_key) as watchedEpisodes FROM watched_state WHERE content_type = 'EPISODE' AND series_id = :seriesIdentity AND is_watched = 1 GROUP BY season_number")
    fun observeWatchedCountsForSeries(seriesIdentity: String): Flow<List<SeasonWatchedCount>>

    /** In-progress (unwatched, position > 0) episode states for a series — drives the
     *  episode-strip resume bars. Emits on every progress/watched change. */
    @Query(
        """
        SELECT * FROM watched_state
        WHERE content_type = 'EPISODE'
            AND source = :source
            AND series_id = :seriesIdentity
            AND is_watched = 0
            AND progress_ms > 0
        """
    )
    fun observeInProgressEpisodesForSeries(
        seriesIdentity: String,
        source: String
    ): Flow<List<WatchedStateEntity>>
}
