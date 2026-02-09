package com.tvonnet.debridxtreamiptv.features.seriesv2.data.model

import androidx.room.ColumnInfo

/**
 * Lightweight projection of SeriesEntityV2 for cache management.
 * Avoids loading full objects (descriptions, casts, etc.) into memory.
 */
data class SeriesMeta(
    @ColumnInfo(name = "series_id")
    val seriesId: String,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long
)
