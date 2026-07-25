package com.tvonnet.debridxtreamiptv.ui.series

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * SD-4a: stateless Debrid series-detail data helpers lifted out of [SeriesDetailActivity]. Both are
 * pure given their inputs (no Activity/view/mutable-state reference), so they relocate byte-identical
 * from the old private methods. The Activity now passes its injected [TmdbRemoteDataSource] in.
 */

/**
 * Fetch a Debrid (TMDB-backed) series detail and map it into the Xtream detail shape. Season details
 * are fetched CONCURRENTLY (async+awaitAll preserves season order; only successful seasons kept,
 * season 0 / specials skipped) — identical result to the old sequential loop. Byte-identical to the
 * old `fetchDebridSeriesDetail`.
 */
internal suspend fun fetchDebridSeriesDetail(
    tmdbRemoteDataSource: TmdbRemoteDataSource,
    tmdbId: String,
): Result<XtreamSeriesDetailResponse> {
    return withContext(Dispatchers.IO) {
        try {
            val idInt = tmdbId.toIntOrNull() ?: return@withContext Result.Error(Exception("Invalid TMDB ID"))

            // 1. Fetch Show Details
            val showDetailsResult = tmdbRemoteDataSource.getSeriesDetails(idInt)
            if (showDetailsResult is Result.Error) return@withContext Result.Error(showDetailsResult.exception)
            val showDetails = (showDetailsResult as Result.Success).data

            // 2. Fetch season details CONCURRENTLY. This was an N+1 sequential TMDB call per
            //    season that blocked the debrid series-detail load (one slow season stalled the
            //    whole page). async+awaitAll preserves season order; only successful seasons are
            //    kept and season 0 (specials) is skipped — identical result to the old loop.
            //    Season count is naturally small, so no extra concurrency cap is needed.
            val seasonsDetails = coroutineScope {
                showDetails.seasons.orEmpty()
                    .mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }
                    .map { seasonNumber -> async { tmdbRemoteDataSource.getSeasonDetails(idInt, seasonNumber) } }
                    .awaitAll()
                    .mapNotNull { (it as? Result.Success)?.data }
            }

            // 3. Map to Xtream format
            val xtreamResponse = com.tvonnet.debridxtreamiptv.data.mapper.TmdbToXtreamMapper.mapToXtreamSeriesDetail(showDetails, seasonsDetails)
            Result.Success(xtreamResponse)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}

/**
 * Map the IPTV cross-category aggregation onto [com.tvonnet.debridxtreamiptv.data.repository.MovieSource]
 * rows so the Debrid source sheet can offer the same show's Xtream streams alongside debrid results.
 * Unresolved listings carry an empty direct_source and resolve on selection. Byte-identical to the old
 * `mapIptvGroupsToSources`.
 */
internal fun mapIptvGroupsToSources(
    groups: List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup>
): List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource> {
    return try {
        groups.flatMap { group ->
            group.streams.map { opt ->
                com.tvonnet.debridxtreamiptv.data.repository.MovieSource(
                    stream = com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo(
                        num = null,
                        name = "${group.categoryName} · ${opt.name}",
                        stream_type = "series",
                        stream_id = "iptv:${opt.sourceSeriesId}",
                        stream_icon = null,
                        rating = null,
                        rating_5based = null,
                        added = null,
                        category_id = group.categoryId,
                        category_ids = null,
                        container_extension = opt.containerExtension,
                        custom_sid = opt.streamId.takeUnless { it.startsWith("pending:") },
                        direct_source = opt.playUrl.ifBlank { null },
                        releaseDate = null,
                        duration = null,
                        plot = null,
                        cast = null,
                        director = null,
                        genre = null,
                        youtube_trailer = null,
                        cover = null,
                        rating_imdb = null
                    ),
                    category = null,
                    // The source row renders `label` as its title — lead with the
                    // playlist category so the user sees where each stream lives.
                    label = "${group.categoryName} · ${opt.name}",
                    isPrimary = false,
                    languages = opt.languages.map { it.code.lowercase() },
                    quality = opt.quality.label,
                    isCached = true,
                    cacheStatus = DebridCacheStatus.DIRECT_STREAM,
                    provider = "IPTV",
                    sourceType = "IPTV",
                    sourceName = group.categoryName
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("SeriesDetailActivity", "mapIptvGroupsToSources failed: ${e.message}")
        emptyList()
    }
}
