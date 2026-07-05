package com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import kotlinx.coroutines.flow.Flow

data class SeasonEpisodeCount(val season_number: Int, val totalEpisodes: Int)

/**
 * DAO for Series V2 Episodes.
 */
@Dao
interface EpisodeDaoV2 {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntityV2>)

    @Query("SELECT * FROM episodes_v2_core WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    fun pagingSource(seriesId: String): PagingSource<Int, EpisodeEntityV2>

    @Query("SELECT * FROM episodes_v2_core WHERE series_id = :seriesId AND season_number = :seasonNum ORDER BY episode_number ASC")
    fun pagingSourceForSeason(seriesId: String, seasonNum: Int): PagingSource<Int, EpisodeEntityV2>

    @Query("DELETE FROM episodes_v2_core WHERE series_id = :seriesId")
    suspend fun clearForSeries(seriesId: String)
    
    @Query("SELECT COUNT(*) FROM episodes_v2_core WHERE series_id = :seriesId")
    suspend fun getCountForSeries(seriesId: String): Int

    @Query("SELECT DISTINCT season_number FROM episodes_v2_core WHERE series_id = :seriesId ORDER BY season_number ASC")
    fun getSeasonsForSeries(seriesId: String): Flow<List<Int>>

    @Query("SELECT season_number, COUNT(episode_id) as totalEpisodes FROM episodes_v2_core WHERE series_id = :seriesId GROUP BY season_number")
    fun observeSeasonEpisodeCounts(seriesId: String): Flow<List<SeasonEpisodeCount>>

    // Added for Playlist Logic (Plan C: Dynamic Queue)
    @Query("SELECT * FROM episodes_v2_core WHERE series_id = :seriesId AND season_number = :seasonNum ORDER BY episode_number ASC")
    suspend fun getEpisodesForSeasonList(seriesId: String, seasonNum: Int): List<EpisodeEntityV2>

    @Query("SELECT * FROM episodes_v2_core WHERE episode_id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: String): EpisodeEntityV2?

    /** Matching episode within a sibling series, used for cross-category aggregation. */
    @Query("SELECT * FROM episodes_v2_core WHERE series_id = :seriesId AND season_number = :seasonNum AND episode_number = :episodeNum LIMIT 1")
    suspend fun getEpisode(seriesId: String, seasonNum: Int, episodeNum: Int): EpisodeEntityV2?

    // Added for Playlist Logic (Get specific episode logic potentially needed later or just use list)
}
