package com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2

/**
 * DAO for Series V2 Core table.
 */
@Dao
interface SeriesDaoV2 {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntityV2>)

    @Query("SELECT * FROM series_v2_core WHERE category_id = :categoryId ORDER BY num ASC")
    fun pagingSource(categoryId: String): PagingSource<Int, SeriesEntityV2>

    @Query("DELETE FROM series_v2_core")
    suspend fun clearAll()

    @Query("DELETE FROM series_v2_core WHERE category_id = :categoryId")
    suspend fun clearByCategory(categoryId: String)

    @Query("DELETE FROM series_v2_core WHERE series_id IN (:seriesIds)")
    suspend fun deleteSeriesByIds(seriesIds: List<String>)

    @Query("SELECT series_id, cached_at FROM series_v2_core")
    suspend fun getAllSeriesMeta(): List<com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesMeta>

    @Query("SELECT COUNT(*) FROM series_v2_core")
    suspend fun getCount(): Int

    @Query("SELECT * FROM series_v2_core WHERE series_id = :seriesId LIMIT 1")
    suspend fun getSeriesById(seriesId: String): SeriesEntityV2?
}
