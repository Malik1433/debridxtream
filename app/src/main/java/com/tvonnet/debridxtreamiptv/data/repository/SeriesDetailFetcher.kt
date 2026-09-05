package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.entity.toEpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toSeasonEntity
import com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes
import com.tvonnet.debridxtreamiptv.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One series' seasons and episodes: the whole read path, lifted out of [SeriesRepository]
 * (2026-09-05).
 *
 * `SeriesRepository` was itself an extraction — the Series slice of the old XtreamRepository
 * god-class — and it came out at 817 lines, which the project's own notes call "moved the problem
 * instead of solving it". This is the split that was owed: the detail path has ONE reason to
 * change (how a provider answers `get_series_info`, and what we do when it does not), and it
 * shares nothing with category listing, the outage state machine or search except the session.
 *
 * The chain is unchanged and deliberately layered, because Xtream panels fail in all of these
 * ways: memory cache -> disk cache -> `get_series_info` (bounded by
 * [SERIES_DETAIL_REQUEST_TIMEOUT_MS]) -> a fallback that rebuilds the same answer out of
 * `get_series` + `get_series_episodes`. A timeout DEGRADES to the fallback; it never hangs and
 * never throws the good cached copy away.
 *
 * Bodies were moved verbatim and [SeriesRepository] still exposes the same three functions, so no
 * caller changed. Behaviour stays frozen by SeriesCachePersistenceTest.
 */
internal class SeriesDetailFetcher(
    private val session: XtreamSession,
    private val seriesDao: SeriesDao,
    private val catalogCache: CatalogCache,
    private val cacheHelper: CacheHelper
) {
    private val apiService get() = session.apiService
    private val username: String get() = session.username
    private val password: String get() = session.password
    private val seriesDetailCache get() = catalogCache.seriesDetailCache

    suspend fun fetchSeriesDetail(seriesId: String, forceRefresh: Boolean = false): Result<XtreamSeriesDetailResponse> {
        if (!forceRefresh) {
            // 1. Check Memory Cache
            seriesDetailCache[seriesId]?.let { cached ->
                if (!cached.episodes.isNullOrEmpty()) {
                    Log.d(TAG, "✅ Memory Cache HIT: Series detail $seriesId")
                    return Result.Success(cached)
                }
            }
            
            // 2. Check Disk Cache (File) - Week 14 Optimization
            try {
                val diskCached = cacheHelper.readSeriesDetail(seriesId)
                if (diskCached != null && !diskCached.episodes.isNullOrEmpty()) {
                    Log.d(TAG, "✅ Disk Cache HIT: Series detail $seriesId")
                    // Update memory cache
                    seriesDetailCache[seriesId] = diskCached
                    return Result.Success(diskCached)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read series detail from disk cache", e)
            }
        } else {
            Log.d(TAG, "♻️ Forced refresh of series detail $seriesId requested")
        }
        return syncSeriesDetail(seriesId)
    }



    /**
     * Observe series details from database
     */
    fun getSeriesDetailFlow(seriesId: String): Flow<SeriesWithSeasonsAndEpisodes?> {
        return seriesDao?.getSeriesWithSeasonsAndEpisodesFlow(seriesId) ?: kotlinx.coroutines.flow.flowOf(null)
    }

    /**
     * Syncs series details from network to local database
     * Uses the deep fallback logic of fetchSeriesDetailFromNetwork
     */


    suspend fun syncSeriesDetail(seriesId: String): Result<XtreamSeriesDetailResponse> {
        val start = System.currentTimeMillis()
        Log.d("XtreamDebug", "syncSeriesDetail: Starting for $seriesId")
        val result = fetchSeriesDetailFromNetwork(seriesId)
        if (result is Result.Success) {
            persistSeriesDetailToDb(seriesId, result.data, start)
        } else {
             Log.e("XtreamDebug", "syncSeriesDetail: Fetch failed")
        }
        return result
    }

    private suspend fun persistSeriesDetailToDb(
        seriesId: String,
        detail: XtreamSeriesDetailResponse,
        start: Long
    ) {
        if (seriesDao == null) return
        try {
            Log.d("XtreamDebug", "syncSeriesDetail: Saving to DB...")
            val seasonEntities = detail.seasons?.map { it.toSeasonEntity(seriesId) } ?: emptyList()
            val episodeEntities = buildEpisodeEntities(seriesId, detail)

            seriesDao.saveSeriesDetails(seriesId, seasonEntities, episodeEntities)
            Log.d("XtreamDebug", "✅ Series $seriesId details synced to database in ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            Log.e("XtreamDebug", "❌ Failed to save series details to database. Data will still show in UI.", e)
        }
    }

    private suspend fun fetchSeriesDetailFromNetwork(seriesId: String): Result<XtreamSeriesDetailResponse> {
        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }
            Log.d("XtreamDebug", "⬇️ Network fetch: Series detail $seriesId")
            val start = System.currentTimeMillis()
            val response = withTimeoutOrNull(SERIES_DETAIL_REQUEST_TIMEOUT_MS) {
                apiService!!.getSeriesInfo(username, password, seriesId = seriesId)
            } ?: run {
                Log.w("XtreamDebug", "Series detail $seriesId timed out after ${SERIES_DETAIL_REQUEST_TIMEOUT_MS}ms; trying fallback")
                return fetchFallbackSeriesDetail(seriesId)
            }
            Log.d("XtreamDebug", "Response received in ${System.currentTimeMillis() - start}ms. Code: ${response.code()}")
            
            if (response.isSuccessful && response.body() != null) {
                val detail = response.body()!!
                Log.d("XtreamDebug", "Parsed Body. Episodes: ${detail.episodes?.size ?: 0} seasons, Info: ${detail.info?.name}")
                if (!detail.episodes.isNullOrEmpty()) {
                    seriesDetailCache[seriesId] = detail
                } else {
                    Log.w(TAG, "Series detail $seriesId returned without episodes")
                }
                Result.Success(detail)
            } else {
                Log.e("XtreamDebug", "❌ Failed to fetch series detail: ${response.code()}. Trying fallback...")
                // Fallback Strategy
                return fetchFallbackSeriesDetail(seriesId)
            }
        } catch (e: Exception) {
            Log.e("XtreamDebug", "❌ Error fetching series detail $seriesId: ${e.message}", e)
            Result.Error(e)
        }
    }

    private suspend fun fetchFallbackSeriesDetail(seriesId: String): Result<XtreamSeriesDetailResponse> {
        return try {
            Log.d("XtreamDebug", "⚠️ Starting Fallback for Series $seriesId")
            
            // 1. Fetch info using simple get_series (often works when get_series_info fails)
            val infoResponse = apiService!!.getSeries(username, password, seriesId = seriesId)
            val infoList = infoResponse.body()
            
            var info: XtreamSeriesDetailInfo? = null
            
            if (!infoResponse.isSuccessful || infoList.isNullOrEmpty()) {
                Log.w("XtreamDebug", "Fallback 1 (Info) failed/empty. Continuing to fetch episodes...")
            } else {
                 info = infoList.first().let {
                     XtreamSeriesDetailInfo(
                         name = it.name,
                         series_id = it.series_id,
                         cover = it.cover,
                         plot = it.plot,
                         cast = it.cast,
                         director = it.director,
                         genre = it.genre,
                         releaseDate = it.releaseDate,
                         rating = it.rating,
                         rating_5based = it.rating_5based,
                         backdrop_path = it.backdropPathList.firstOrNull(),
                         youtube_trailer = it.youtube_trailer
                     )
                }
            }
            
            // 2. Fetch episodes using get_series_episodes (get_show_episodes)
            val episodesResponse = apiService!!.getSeriesEpisodes(username, password, seriesId = seriesId)
             var episodesMap: Map<String, List<XtreamEpisodeInfo>>? = null

            if (episodesResponse.isSuccessful && episodesResponse.body() != null) {
                 episodesMap = parseFallbackEpisodesJson(episodesResponse.body()!!)
            }
            
            val combined = XtreamSeriesDetailResponse(
                info = info,
                episodes = episodesMap,
                seasons = null // We can infer seasons from keys if needed
            )
            
            Log.d("XtreamDebug", "✅ Fallback Successful. Info: ${info?.name ?: "Unknown"}, Episodes: ${episodesMap?.size}")
            Result.Success(combined)
            
        } catch (e: Exception) {
            Log.e("XtreamDebug", "Fallback failed critically", e)
             Result.Error(e)
        }
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val SERIES_DETAIL_REQUEST_TIMEOUT_MS = 15_000L
    }
}

// Pure helpers for the series-detail sync path, deliberately outside the class: they touch no
// repository state, and SeriesRepository sits right at the LargeClass ceiling.

private fun buildEpisodeEntities(
    seriesId: String,
    detail: XtreamSeriesDetailResponse
): List<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity> {
    val episodeEntities = mutableListOf<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity>()
    detail.episodes?.forEach { entry ->
         val seasonKey = entry.key
         val episodes = entry.value
         val seasonNum = seasonKey.filter { it.isDigit() }.toIntOrNull() ?: 0
         episodes.forEach { episode ->
             episodeEntities.add(episode.toEpisodeEntity(seriesId, seasonNum))
         }
    }
    return episodeEntities
}

// Handle dynamic JSON (Map vs List)
private fun parseFallbackEpisodesJson(json: JsonElement): Map<String, List<XtreamEpisodeInfo>>? {
    var episodesMap: Map<String, List<XtreamEpisodeInfo>>? = null
    if (json.isJsonObject) {
        val type = object : TypeToken<Map<String, List<XtreamEpisodeInfo>>>() {}.type
        // We need to parse manually or strict because Gson might fail on "info"
        try {
            episodesMap = Gson().fromJson(json, type)
        } catch(e: Exception) {
            Log.e("XtreamDebug", "Fallback 2 JSON Parse Error: ${e.message}")
        }
    } else if (json.isJsonArray) {
        // Sometimes it returns a list of episodes directly? Or list of objects?
        // Standard Xtream get_show_episodes returns Map "1": [episodes], "2": [episodes]
        // If it is array, likely empty []
        Log.w("XtreamDebug", "Fallback 2 returned Array, constructing empty map")
        episodesMap = emptyMap()
    }
    return episodesMap
}
