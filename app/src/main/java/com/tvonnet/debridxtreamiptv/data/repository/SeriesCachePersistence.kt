package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.SeriesCacheData

/**
 * Phase 7: the "replace only the series slice, preserve live/vod/epg" IptvCache builder that was
 * duplicated verbatim in persistSeriesCategories and persistSeriesStreamsForCategory. Pure function:
 * copies an existing cache with the new series data (leaving the other three domains untouched), or
 * mints a fresh cache with only the series slice populated when there is no existing cache. Behaviour
 * is byte-for-byte identical to both former inline branches — this only removes duplication and makes
 * the cache-preservation invariant unit-testable.
 */
internal fun IptvCache?.withSeriesData(series: SeriesCacheData, timestamp: Long): IptvCache =
    this?.copy(timestamp = timestamp, series = series)
        ?: IptvCache(timestamp = timestamp, live = null, vod = null, series = series, epg = null)
