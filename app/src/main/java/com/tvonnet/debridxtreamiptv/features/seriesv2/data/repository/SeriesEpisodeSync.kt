package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import androidx.room.withTransaction
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.remote.XtreamResponseParser
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [SeriesEpisodeSync.syncSeries] concluded.
 *
 * The distinction matters: [Resolved] means the provider answered and the caller must NOT fall
 * through to its "no data available" error, even when [Resolved.series] is null. That mirrors the
 * original flow, where the primary-success branch always returned.
 */
internal sealed interface SeriesSyncOutcome {
    data class Resolved(val series: SeriesEntityV2?) : SeriesSyncOutcome
    object Unresolved : SeriesSyncOutcome
}

/**
 * C5: the network → database pipeline for one series' detail and episodes.
 *
 * This is a **ladder of fallbacks**, and the order is the whole point — real providers fail in
 * every one of these ways:
 *  1. `get_series_info` — the primary, carries info + episodes;
 *  2. [fetchEpisodesFallback] — `get_series` then `get_show_episodes`, for panels that 404 the
 *     primary or return it without episodes;
 *  3. [adoptSiblingEpisodes] — the listing is genuinely dead (regional dub, array-shaped JSON), so
 *     borrow the episode list from a healthy sibling of the same show.
 *
 * Two invariants that were real incidents:
 *  - **every call is bounded** ([SERIES_INFO_TIMEOUT_MS] / [FALLBACK_TIMEOUT_MS] / 6s for
 *    siblings) so a hung provider degrades instead of parking the detail page;
 *  - **an empty fetch never overwrites populated episodes** (`shouldOverwriteEpisodes`), and the
 *    parent series row is always written *before* its episodes — episodes_v2_core has a foreign
 *    key, and inserting the stub afterwards failed the whole transaction and silently dropped
 *    every fetched episode.
 *
 * Bodies moved verbatim from `XtreamSeriesRepositoryV2`.
 */
internal class SeriesEpisodeSync(
    private val legacyRepository: XtreamRepository,
    private val seriesDao: SeriesDaoV2,
    private val episodeDao: EpisodeDaoV2,
    private val legacySeriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao,
    private val database: com.tvonnet.debridxtreamiptv.data.local.AppDatabase,
    private val siblingFinder: SeriesSiblingFinder,
) : EpisodeCacheWarmer {

    companion object {
        // Per-attempt bounds for the episode-detail fetch so a slow/hung provider fails
        // over to lighter endpoints fast instead of blocking the UI for the full 30s
        // socket timeout. Primary get_series_info carries all episode metadata (bigger),
        // so it gets a little more room than the lighter fallback endpoints.
        private const val SERIES_INFO_TIMEOUT_MS = 15_000L
        private const val FALLBACK_TIMEOUT_MS = 10_000L
        private const val SIBLING_TIMEOUT_MS = 6_000L

        /** Dead siblings are cheap to try but not free — cap the walk. */
        private const val MAX_SIBLINGS_TRIED = 8
    }

    private val apiService: XtreamApiService
        get() = legacyRepository.getApiService()
            ?: throw IllegalStateException("API Not Initialized. Please login first.")

    /**
     * Fetch a series' detail + episodes, persisting whatever the ladder recovers.
     * @return [SeriesSyncOutcome.Resolved] when the provider answered usefully, otherwise
     *   [SeriesSyncOutcome.Unresolved] so the caller can fall back to its cached copy.
     */
    suspend fun syncSeries(seriesId: String, username: String, password: String): SeriesSyncOutcome {
        android.util.Log.d("SeriesRepoV2", "getSeriesById: Starting Network Request...")
        // Bound the call: a slow/hung provider must fail over to the lighter fallbacks
        // fast instead of blocking the UI for the full 30s socket timeout.
        val response = withTimeoutOrNull(SERIES_INFO_TIMEOUT_MS) {
            apiService.getSeriesInfo(username, password, seriesId = seriesId)
        }
        if (response == null) {
            android.util.Log.w(
                "SeriesRepoV2",
                "getSeriesById: get_series_info timed out after ${SERIES_INFO_TIMEOUT_MS}ms — trying fallbacks"
            )
            return resolvedIfRecovered(seriesId, username, password)
        }

        android.util.Log.d("SeriesRepoV2", "getSeriesById: Network Response Code: ${response.code()}")

        if (response.isSuccessful) return onPrimarySuccess(seriesId, username, password, response.body())

        if (response.code() == 404) {
            android.util.Log.w("SeriesRepoV2", "getSeriesById: 404 on get_series_info. Attempting fallback to get_show_episodes...")
            return resolvedIfRecovered(seriesId, username, password)
        }

        android.util.Log.e("SeriesRepoV2", "getSeriesById: Request Failed: ${response.message()}")
        return SeriesSyncOutcome.Unresolved
    }

    private suspend fun onPrimarySuccess(
        seriesId: String,
        username: String,
        password: String,
        body: com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse?,
    ): SeriesSyncOutcome {
        val info = body?.info
        val episodesMap = body?.episodes
        android.util.Log.d("SeriesRepoV2", "getSeriesById: Body Info: ${if (info != null) "Found" else "NULL"}")
        android.util.Log.d("SeriesRepoV2", "getSeriesById: Episodes Map Size: ${episodesMap?.size ?: 0}")
        if (info == null) return SeriesSyncOutcome.Unresolved

        processSeriesInfoResponse(seriesId, info, episodesMap)
        if (episodesMap.isNullOrEmpty()) {
            android.util.Log.w("SeriesRepoV2", "getSeriesById: Primary info missing episodes. Attempting fallback fetch...")
            return SeriesSyncOutcome.Resolved(recoverEpisodes(seriesId, username, password))
        }
        // Re-fetch updated local series to emit standard result
        return SeriesSyncOutcome.Resolved(seriesDao.getSeriesById(seriesId))
    }

    /** Timeout / 404 paths: only a recovered row counts as an answer. */
    private suspend fun resolvedIfRecovered(
        seriesId: String,
        username: String,
        password: String
    ): SeriesSyncOutcome = recoverEpisodes(seriesId, username, password)
        ?.let { SeriesSyncOutcome.Resolved(it) }
        ?: SeriesSyncOutcome.Unresolved

    /**
     * Episodes-only fallbacks, then sibling adoption if the listing still has none.
     * @return the stored series row afterwards, or null when it does not exist locally.
     */
    suspend fun recoverEpisodes(seriesId: String, username: String, password: String): SeriesEntityV2? {
        fetchEpisodesFallback(seriesId, username, password)
        // Still empty (dead listing) → borrow episodes from a healthy sibling.
        if (episodeDao.getCountForSeries(seriesId) == 0) {
            adoptSiblingEpisodes(seriesId, username, password)
        }
        return seriesDao.getSeriesById(seriesId)
    }

    private suspend fun processSeriesInfoResponse(
        seriesId: String,
        info: com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo,
        episodesMap: Map<String, List<com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo>>?
    ) {
        // Map API -> Entity
        // Handle backdrop_path which can be String or List<String>
        val backdropPathList = when (info.backdrop_path) {
            is List<*> -> info.backdrop_path.filterIsInstance<String>().filter { it.isNotEmpty() }
            is String -> if (info.backdrop_path.isNotEmpty()) listOf(info.backdrop_path) else emptyList()
            else -> emptyList()
        }

        // Preserve category membership captured during browse — the detail endpoint
        // doesn't return it, and stream aggregation groups siblings by category.
        val existingCategoryId = seriesDao.getSeriesById(seriesId)?.categoryId

        val seriesEntity = SeriesEntityV2(
            seriesId = seriesId,
            num = null,
            name = info.name,
            title = info.name,
            year = null,
            streamType = "series",
            cover = info.cover,
            plot = info.plot,
            cast = info.cast,
            director = info.director,
            genre = info.genre,
            releaseDate = info.releaseDate,
            rating = info.rating,
            // rating_5based is already Double? — the old try/catch could never fire.
            rating5based = info.rating_5based,
            backdropPath = backdropPathList,
            youtubeTrailer = info.youtube_trailer,
            episodeRunTime = null,
            categoryId = existingCategoryId,
            lastModified = null
        )

        val episodeEntities = mapEpisodes(seriesId, episodesMap)
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Mapped ${episodeEntities.size} episodes.")
        if (episodeEntities.isEmpty() && !episodesMap.isNullOrEmpty()) {
            android.util.Log.w("SeriesRepoV2", "processSeriesInfoResponse: WARNING - EpisodesMap not empty but episodeEntities is empty!")
        }

        // Save to DB (Atomic Transaction)
        database.withTransaction {
            seriesDao.insertAll(listOf(seriesEntity))
            replaceEpisodes(seriesId, episodeEntities, caller = "processSeriesInfoResponse")
        }
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Saved to DB.")
    }

    /**
     * Never let a failed/empty fetch destroy good data: episodes are replaced only when the new
     * list has rows, or when there is nothing cached to lose.
     * Caller must already be inside a transaction.
     */
    private suspend fun replaceEpisodes(
        seriesId: String,
        episodeEntities: List<EpisodeEntityV2>,
        caller: String,
    ) {
        val existingCount = episodeDao.getCountForSeries(seriesId)
        if (episodeEntities.isEmpty() && existingCount > 0) {
            android.util.Log.w(
                "SeriesRepoV2",
                "$caller: Skipping episode overwrite (new=0, existing=$existingCount)"
            )
            return
        }
        episodeDao.clearForSeries(seriesId)
        if (episodeEntities.isNotEmpty()) {
            episodeDao.insertAll(episodeEntities)
        } else {
            // Keep existing episodes; do not insert empties.
            android.util.Log.w("SeriesRepoV2", "$caller: EpisodeEntities empty, not inserting")
        }
    }

    private suspend fun fetchEpisodesFallback(seriesId: String, username: String, password: String) {
        // Strategy 1: Try get_series with series_id (Some providers return header + episodes here)
        if (fetchViaGetSeries(seriesId, username, password)) return
        // Strategy 2: Try get_show_episodes (Standard episode endpoint)
        fetchViaGetShowEpisodes(seriesId, username, password)
    }

    private suspend fun fetchViaGetSeries(seriesId: String, username: String, password: String): Boolean {
        try {
            android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Attempting Strategy 1 (get_series)...")
            val response = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                apiService.getSeries(username, password, seriesId = seriesId)
            }
            if (response == null) {
                android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 timed out after ${FALLBACK_TIMEOUT_MS}ms")
            } else if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val episodesMap = response.body()!!.first().episodes
                if (!episodesMap.isNullOrEmpty()) {
                    android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Success! Found ${episodesMap.size} seasons.")
                    saveToDb(seriesId, mapEpisodes(seriesId, episodesMap))
                    return true
                }
                android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 returned Series info but NO episodes.")
            } else {
                android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Failed (Code ${response.code()})")
            }
        } catch (e: Exception) {
            android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Error", e)
        }
        return false
    }

    private suspend fun fetchViaGetShowEpisodes(seriesId: String, username: String, password: String) {
        try {
            android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Attempting Strategy 2 (get_show_episodes)...")
            val response = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                apiService.getSeriesEpisodes(username, password, seriesId = seriesId)
            }
            if (response == null) {
                android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 2 timed out after ${FALLBACK_TIMEOUT_MS}ms")
            } else if (response.isSuccessful) {
                val episodesMap = XtreamResponseParser.parseEpisodes(response.body())
                if (episodesMap.isNotEmpty()) {
                    val episodeEntities = mapEpisodes(seriesId, episodesMap)
                    android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Strategy 2 Success! Recovered ${episodeEntities.size} episodes.")
                    saveToDb(seriesId, episodeEntities)
                } else {
                    android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 2 returned empty episodes.")
                }
            } else {
                android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Failed with code ${response.code()}")
            }
        } catch (e: Exception) {
            android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Error", e)
        }
    }

    private suspend fun saveToDb(seriesId: String, episodeEntities: List<EpisodeEntityV2>) {
        database.withTransaction {
            // The parent series row MUST exist before episodes: episodes_v2_core has a
            // FOREIGN KEY on series_id, and sibling listings resolved for stream
            // aggregation have never been opened, so no V2 row exists yet. Inserting
            // the stub after the episodes (as this used to) fails the whole
            // transaction with SQLITE_CONSTRAINT_FOREIGNKEY and silently drops every
            // fetched episode.
            if (seriesDao.getSeriesById(seriesId) == null) {
                seriesDao.insertAll(listOf(stubSeriesRow(seriesId)))
            }
            // DO NOT clear series info, just update episodes
            replaceEpisodes(seriesId, episodeEntities, caller = "saveToDb")
            Unit
        }
    }

    /** Prefer real metadata from the legacy catalog row when available. */
    private suspend fun stubSeriesRow(seriesId: String): SeriesEntityV2 {
        val legacy = legacySeriesDao.getSeriesById(seriesId)
        return SeriesEntityV2(
            seriesId = seriesId,
            num = legacy?.num,
            name = legacy?.name ?: "Series $seriesId",
            title = legacy?.name ?: "Series $seriesId",
            year = null,
            streamType = "series",
            cover = legacy?.cover,
            plot = legacy?.plot,
            cast = legacy?.cast,
            director = legacy?.director,
            genre = legacy?.genre,
            releaseDate = legacy?.releaseDate,
            rating = legacy?.rating,
            rating5based = legacy?.rating5based,
            youtubeTrailer = legacy?.youtube_trailer,
            episodeRunTime = null,
            categoryId = legacy?.categoryId,
            lastModified = (System.currentTimeMillis() / 1000).toString()
        )
    }

    private fun mapEpisodes(
        seriesId: String,
        episodesMap: Map<String, List<com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo>>?
    ): List<EpisodeEntityV2> {
        val episodeEntities = mutableListOf<EpisodeEntityV2>()
        episodesMap?.forEach { (seasonNumStr, episodesList) ->
            val seasonFromKey = seasonNumStr.toIntOrNull()
            episodesList.forEach { epInfo ->
                val episodeIdRaw = epInfo.id?.toString()?.trim()
                if (episodeIdRaw.isNullOrEmpty() || episodeIdRaw.equals("null", ignoreCase = true)) return@forEach

                val seasonNum = seasonFromKey
                    ?: epInfo.season?.takeIf { it > 0 }
                    ?: 0

                val durationSecsRaw = epInfo.duration_secs ?: epInfo.info?.duration_secs
                val durationLong = durationSecsRaw?.toLongOrNull() ?: 0L

                episodeEntities.add(
                    EpisodeEntityV2(
                        episodeId = episodeIdRaw,
                        seriesId = seriesId,
                        seasonNumber = seasonNum,
                        episodeNumber = epInfo.episode_num ?: 0,
                        title = epInfo.title,
                        containerExtension = epInfo.container_extension,
                        streamType = "series",
                        durationSecs = durationSecsRaw,
                        added = epInfo.added,
                        customSid = epInfo.custom_sid,
                        directSource = epInfo.direct_source,
                        thumbnail = epInfo.info?.cover ?: epInfo.thumbnail,
                        plot = epInfo.info?.plot,
                        cast = epInfo.info?.cast,
                        director = epInfo.info?.director,
                        genre = epInfo.info?.genre,
                        releaseDate = epInfo.info?.releaseDate,
                        rating = epInfo.info?.rating,
                        duration = durationLong
                    )
                )
            }
        }
        return episodeEntities
    }

    /**
     * When the opened listing has no episodes of its own (dead regional dub, or a
     * provider that returns array-shaped JSON), adopt the episode list from a HEALTHY
     * sibling of the same show and store it under THIS series id — so the detail page
     * shows episodes. The adopted episodes keep the sibling's playable stream ids, which
     * is exactly what the multi-source panel would resolve anyway. Best-effort, bounded.
     */
    private suspend fun adoptSiblingEpisodes(seriesId: String, username: String, password: String) {
        if (episodeDao.getCountForSeries(seriesId) > 0) return
        val siblings = runCatching { siblingFinder.find(seriesId) }.getOrNull()
            ?.filter { it.seriesId != seriesId }
            ?: return
        if (siblings.isEmpty()) return
        // Try the variants most likely to carry real episodes FIRST — |MULTI| / |EN| /
        // international listings usually do, while regional dubs (SOM-/PH-/AR-) are
        // typically the same dead references. Bounded + short timeout so a wall of dead
        // siblings can't hang the page for minutes.
        val ordered = siblings
            .sortedByDescending { SeriesTitleMatching.siblingEpisodeLikelihood(it.name) }
            .take(MAX_SIBLINGS_TRIED)
        android.util.Log.i(
            "SeriesRepoV2",
            "adoptSiblingEpisodes: $seriesId has no episodes; trying ${ordered.size}/${siblings.size} siblings"
        )
        for (sib in ordered) {
            if (adoptFrom(seriesId, sib, username, password)) return
        }
        android.util.Log.w("SeriesRepoV2", "adoptSiblingEpisodes: no sibling of $seriesId had episodes")
    }

    /** @return true when this sibling supplied episodes and they were stored. */
    private suspend fun adoptFrom(
        seriesId: String,
        sib: SiblingListing,
        username: String,
        password: String
    ): Boolean {
        try {
            val resp = withTimeoutOrNull(SIBLING_TIMEOUT_MS) {
                apiService.getSeriesInfo(username, password, seriesId = sib.seriesId)
            }
            val epMap = resp?.takeIf { it.isSuccessful }?.body()?.episodes
            if (epMap.isNullOrEmpty()) return false
            val eps = mapEpisodes(seriesId, epMap)
            if (eps.isEmpty()) return false
            saveToDb(seriesId, eps)
            android.util.Log.i(
                "SeriesRepoV2",
                "adoptSiblingEpisodes: adopted ${eps.size} episodes from sibling ${sib.seriesId} (${sib.name})"
            )
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sibling also dead / parse error — try the next, but record which one failed.
            android.util.Log.w("XtreamSeriesRepositoryV2", "Sibling series lookup failed", e)
            return false
        }
    }

    /**
     * Ensure episode rows exist for a sibling series, fetching from network if empty.
     * Returns true only when the series ends up with episodes in the DB — callers use
     * this to distinguish "fetch failed" (keep the row, retry later) from "fetched
     * fine but the episode genuinely doesn't exist" (safe to drop).
     */
    override suspend fun ensureEpisodesCached(seriesId: String): Boolean {
        if (episodeDao.getCountForSeries(seriesId) > 0) return true
        val username = legacyRepository.getUsername()
        val password = legacyRepository.getPassword()

        // Standard endpoint first: get_series_info is what actually returns episodes
        // on real panels (the fallback strategies came back empty for sibling
        // listings — see "Strategy 1 returned Series info but NO episodes").
        try {
            val response = apiService.getSeriesInfo(username, password, seriesId = seriesId)
            val episodesMap = response.takeIf { it.isSuccessful }?.body()?.episodes
            if (!episodesMap.isNullOrEmpty()) {
                val episodeEntities = mapEpisodes(seriesId, episodesMap)
                if (episodeEntities.isNotEmpty()) {
                    saveToDb(seriesId, episodeEntities)
                    return true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SeriesRepoV2", "ensureEpisodesCached: get_series_info failed for $seriesId: ${e.message}")
        }

        try {
            fetchEpisodesFallback(seriesId, username, password)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SeriesRepoV2", "ensureEpisodesCached failed for $seriesId: ${e.message}")
        }
        return episodeDao.getCountForSeries(seriesId) > 0
    }
}
