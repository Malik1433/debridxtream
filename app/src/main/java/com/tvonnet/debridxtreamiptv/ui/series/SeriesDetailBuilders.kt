package com.tvonnet.debridxtreamiptv.ui.series

import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeasonInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.util.GlobalConfig

/**
 * SD-2: pure series-detail builders lifted out of [SeriesDetailActivity]. State-free — the one
 * resource dependency (the localized season name) is passed in as [seasonName] so the function
 * stays testable and free of any Activity/Context reference.
 *
 * Builds a synthetic [XtreamSeriesDetailResponse] from the episode map an [XtreamSeriesInfo]
 * already carries — the fallback used when a full series-detail fetch is unavailable. Groups the
 * raw episode lists by a normalized season key, sorts each season by episode number, and mints a
 * season list. Byte-identical to the old `buildFallbackDetail`.
 */
internal fun buildFallbackSeriesDetail(
    seriesInfo: XtreamSeriesInfo,
    seasonName: (Int) -> String,
): XtreamSeriesDetailResponse? {
    val originalEpisodes = seriesInfo.episodes ?: return null

    val grouped = mutableMapOf<String, MutableList<XtreamEpisodeInfo>>()
    originalEpisodes.forEach { (seasonKeyRaw, episodeList) ->
        val normalizedKey = seasonKeyRaw.takeIf { it.isNotBlank() }
            ?: episodeList.firstOrNull()?.season?.takeIf { it > 0 }?.toString()
            ?: "1"
        val bucket = grouped.getOrPut(normalizedKey) { mutableListOf() }
        bucket.addAll(episodeList)
    }

    if (grouped.isEmpty()) {
        return null
    }

    grouped.values.forEach { list ->
        list.sortWith(compareBy<XtreamEpisodeInfo>({ it.episode_num ?: Int.MAX_VALUE }, { it.title ?: "" }))
    }

    val seasonKeys = grouped.keys.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
    val seasons = seasonKeys.mapIndexed { index, key ->
        val seasonNumber = key.toIntOrNull() ?: (index + 1)
        XtreamSeasonInfo(
            season_number = key,
            name = seasonName(seasonNumber),
            overview = null,
            air_date = null,
            cover = null
        )
    }

    val normalizedEpisodes = seasonKeys.associateWith { seasonKey ->
        grouped[seasonKey]?.toList() ?: emptyList()
    }

    val detailInfo = XtreamSeriesDetailInfo(
        name = seriesInfo.name,
        series_id = seriesInfo.series_id,
        plot = seriesInfo.plot,
        cast = seriesInfo.cast,
        director = seriesInfo.director,
        genre = seriesInfo.genre,
        releaseDate = seriesInfo.releaseDate,
        rating = seriesInfo.rating,
        rating_5based = seriesInfo.rating_5based,
        cover = GlobalConfig.resolveIconUrl(seriesInfo.cover),
        backdrop_path = GlobalConfig.resolveIconUrl(seriesInfo.cover),
        youtube_trailer = null
    )

    return XtreamSeriesDetailResponse(
        info = detailInfo,
        episodes = normalizedEpisodes,
        seasons = seasons
    )
}
