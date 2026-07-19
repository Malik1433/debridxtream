package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.remote.XtreamResponseParser
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.StreamLanguage
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.StreamQuality
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamSeriesRepositoryV2 @Inject constructor(
    private val legacyRepository: XtreamRepository,
    private val seriesDao: SeriesDaoV2,
    private val episodeDao: EpisodeDaoV2,
    private val categoryDao: com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao,
    private val legacySeriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao,
    private val database: com.tvonnet.debridxtreamiptv.data.local.AppDatabase
) {

    companion object {
        // Per-attempt bounds for the episode-detail fetch so a slow/hung provider fails
        // over to lighter endpoints fast instead of blocking the UI for the full 30s
        // socket timeout. Primary get_series_info carries all episode metadata (bigger),
        // so it gets a little more room than the lighter fallback endpoints.
        private const val SERIES_INFO_TIMEOUT_MS = 15_000L
        private const val FALLBACK_TIMEOUT_MS = 10_000L
    }

    // Helper to get service safely from legacy repo
    private val apiService: XtreamApiService
        get() = legacyRepository.getApiService() ?: throw IllegalStateException("API Not Initialized. Please login first.")

    fun getStreamUrl(episodeId: String, extension: String?): String {
        val server = legacyRepository.getServerUrl().trimEnd('/')
        val user = legacyRepository.getUsername()
        val pass = legacyRepository.getPassword()
        val ext = extension ?: "mp4" // Default to mp4 if null
        return "$server/series/$user/$pass/$episodeId.$ext"
    }

    fun getSeriesById(seriesId: String): Flow<Result<SeriesEntityV2>> = flow {
         val username = legacyRepository.getUsername()
         val password = legacyRepository.getPassword()

         android.util.Log.d("SeriesRepoV2", "getSeriesById: Fetching for ID: $seriesId")

         // 1. Try Local Cache (Offline First)
         val localSeries = seriesDao.getSeriesById(seriesId)
         if (localSeries != null) {
             android.util.Log.d("SeriesRepoV2", "getSeriesById: Cache HIT. Episodes: ${episodeDao.getCountForSeries(seriesId)}")
             emit(Result.Success(localSeries))
         } else {
             android.util.Log.d("SeriesRepoV2", "getSeriesById: Cache MISS")
         }

         // 2. Network Fetch Attempt.
         // The catalog-index warm is deferred to a `finally` below so this page's own
         // episode fetch grabs the (often single) provider connection FIRST — otherwise
         // a stale-index warm on open steals the connection and stalls episode loading.
        try {
            android.util.Log.d("SeriesRepoV2", "getSeriesById: Starting Network Request...")
            // Bound the call: a slow/hung provider must fail over to the lighter fallbacks
            // fast instead of blocking the UI for the full 30s socket timeout.
            val response = withTimeoutOrNull(SERIES_INFO_TIMEOUT_MS) {
                apiService.getSeriesInfo(username, password, seriesId = seriesId)
            }
            if (response == null) {
                android.util.Log.w("SeriesRepoV2", "getSeriesById: get_series_info timed out after ${SERIES_INFO_TIMEOUT_MS}ms — trying fallbacks")
                fetchEpisodesFallback(seriesId, username, password)
                if (episodeDao.getCountForSeries(seriesId) == 0) {
                    adoptSiblingEpisodes(seriesId, username, password)
                }
                val updatedSeries = seriesDao.getSeriesById(seriesId)
                if (updatedSeries != null) { emit(Result.Success(updatedSeries)); return@flow }
            } else {
            android.util.Log.d("SeriesRepoV2", "getSeriesById: Network Response Code: ${response.code()}")

            if (response.isSuccessful) {
                val body = response.body()
                val info = body?.info
                val episodesMap = body?.episodes

                android.util.Log.d("SeriesRepoV2", "getSeriesById: Body Info: ${if(info!=null) "Found" else "NULL"}")
                android.util.Log.d("SeriesRepoV2", "getSeriesById: Episodes Map Size: ${episodesMap?.size ?: 0}")

                if (info != null) {
                    processSeriesInfoResponse(seriesId, info, episodesMap)

                    if (episodesMap.isNullOrEmpty()) {
                        android.util.Log.w("SeriesRepoV2", "getSeriesById: Primary info missing episodes. Attempting fallback fetch...")
                        fetchEpisodesFallback(seriesId, username, password)
                        // Still empty (dead listing) → borrow episodes from a healthy sibling.
                        if (episodeDao.getCountForSeries(seriesId) == 0) {
                            adoptSiblingEpisodes(seriesId, username, password)
                        }
                    }

                    // Re-fetch updated local series to emit standard result
                    val updatedSeries = seriesDao.getSeriesById(seriesId)
                    if (updatedSeries != null) emit(Result.Success(updatedSeries))
                    return@flow
                }
            } else if (response.code() == 404) {
                 android.util.Log.w("SeriesRepoV2", "getSeriesById: 404 on get_series_info. Attempting fallback to get_show_episodes...")
                 // Fallback: Fetch Episodes Only
                 fetchEpisodesFallback(seriesId, username, password)
                 if (episodeDao.getCountForSeries(seriesId) == 0) {
                     adoptSiblingEpisodes(seriesId, username, password)
                 }

                 // Return local data if we have it (now updated with episodes)
                 val updatedSeries = seriesDao.getSeriesById(seriesId)
                 if (updatedSeries != null) {
                     emit(Result.Success(updatedSeries))
                     return@flow
                 }
            } else {
                 android.util.Log.e("SeriesRepoV2", "getSeriesById: Request Failed: ${response.message()}")
            }
            }
        } catch (e: Exception) {
            android.util.Log.e("SeriesRepoV2", "getSeriesById: Exception: ${e.message}", e)
            // Providers often return array-shaped JSON that breaks get_series_info parsing
            // (dead regional dubs like SOM-/PH-). Try the episodes-only fallbacks, then
            // adopt episodes from a healthy sibling of the same show. Do NOT early-return
            // on a cached local series — that left cached dead listings showing "No episodes".
            android.util.Log.w("SeriesRepoV2", "getSeriesById: Network exception, attempting episodes fallback + sibling adoption...")
            try { fetchEpisodesFallback(seriesId, username, password) } catch (_: Exception) {}
            if (episodeDao.getCountForSeries(seriesId) == 0) {
                adoptSiblingEpisodes(seriesId, username, password)
            }
            val updatedSeries = seriesDao.getSeriesById(seriesId)
            if (updatedSeries != null) {
                emit(Result.Success(updatedSeries))
                return@flow
            }
        } finally {
            // Warm the full series catalog (legacy series_v2 table) so cross-category
            // stream aggregation can find sibling listings. Idempotent, 24h-gated.
            // Runs AFTER the episode fetch so it never contends for the connection on
            // the critical path. `return@flow` above still triggers this.
            try { legacyRepository.scheduleSearchIndexSyncIfStale() } catch (_: Exception) {}
        }

        // 3. Final Fallback
        if (localSeries == null) {
             android.util.Log.e("SeriesRepoV2", "getSeriesById: Failure - No data available.")
             emit(Result.Error(Exception("Failed to fetch series details and no local data available")))
        }
    }.flowOn(Dispatchers.IO)
    
    private suspend fun processSeriesInfoResponse(seriesId: String, info: com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo, episodesMap: Map<String, List<com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo>>?) {
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
            rating5based = try { info.rating_5based?.toDouble() } catch(e: Exception) { null },
            backdropPath = backdropPathList,
            youtubeTrailer = info.youtube_trailer,
            episodeRunTime = null,
            categoryId = existingCategoryId,
            lastModified = null
        )
        
        // Map Episodes
        val episodeEntities = mapEpisodes(seriesId, episodesMap)
        
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Mapped ${episodeEntities.size} episodes.")
        // #region agent log
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: EpisodesMap size=${episodesMap?.size ?: 0}, EpisodeEntities size=${episodeEntities.size}")
        if (episodeEntities.isEmpty() && !episodesMap.isNullOrEmpty()) {
            android.util.Log.w("SeriesRepoV2", "processSeriesInfoResponse: WARNING - EpisodesMap not empty but episodeEntities is empty!")
        }
        // #endregion

        // Save to DB (Atomic Transaction)
        database.withTransaction {
            seriesDao.insertAll(listOf(seriesEntity))

            val existingCount = episodeDao.getCountForSeries(seriesId)
            val shouldOverwriteEpisodes = episodeEntities.isNotEmpty() || existingCount == 0

            if (shouldOverwriteEpisodes) {
                episodeDao.clearForSeries(seriesId)
                if (episodeEntities.isNotEmpty()) {
                    // #region agent log
                    android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Inserting ${episodeEntities.size} episodes to DB")
                    // #endregion
                    episodeDao.insertAll(episodeEntities)
                    // #region agent log
                    val verifyCount = episodeDao.getCountForSeries(seriesId)
                    android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Verified DB count after insert=$verifyCount")
                    // #endregion
                } else {
                    // Keep existing episodes; do not insert empties.
                    // #region agent log
                    android.util.Log.w("SeriesRepoV2", "processSeriesInfoResponse: EpisodeEntities empty, not inserting")
                    // #endregion
                }
            } else {
                android.util.Log.w(
                    "SeriesRepoV2",
                    "processSeriesInfoResponse: Skipping episode overwrite (new=0, existing=$existingCount)"
                )
            }
        }
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Saved to DB.")
    }
    
    private suspend fun fetchEpisodesFallback(seriesId: String, username: String, password: String) {
        // Strategy 1: Try get_series with series_id (Some providers return header + episodes here)
        try {
            android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Attempting Strategy 1 (get_series)...")
            val response = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                apiService.getSeries(username, password, seriesId = seriesId)
            }
            if (response == null) {
                android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 timed out after ${FALLBACK_TIMEOUT_MS}ms")
            } else if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val seriesInfo = response.body()!!.first()
                val episodesMap = seriesInfo.episodes
                
                if (!episodesMap.isNullOrEmpty()) {
                    android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Success! Found ${episodesMap.size} seasons.")
                    val episodeEntities = mapEpisodes(seriesId, episodesMap)
                    saveToDb(seriesId, episodeEntities)
                    return
                } else {
                    android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 returned Series info but NO episodes.")
                }
            } else {
                 android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Failed (Code ${response.code()})")
            }
        } catch(e: Exception) {
             android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Error", e)
        }
        
        // Strategy 2: Try get_show_episodes (Standard episode endpoint)
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
                // Prefer real metadata from the legacy catalog row when available.
                val legacy = legacySeriesDao.getSeriesById(seriesId)
                val stub = SeriesEntityV2(
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
                    lastModified = (System.currentTimeMillis()/1000).toString()
                )
                seriesDao.insertAll(listOf(stub))
            }

            // DO NOT clear series info, just update episodes
            val existingCount = episodeDao.getCountForSeries(seriesId)
            val shouldOverwriteEpisodes = episodeEntities.isNotEmpty() || existingCount == 0

            if (shouldOverwriteEpisodes) {
                episodeDao.clearForSeries(seriesId)
                if (episodeEntities.isNotEmpty()) {
                    episodeDao.insertAll(episodeEntities)
                }
            } else {
                android.util.Log.w(
                    "SeriesRepoV2",
                    "saveToDb: Skipping episode overwrite (new=0, existing=$existingCount)"
                )
            }
            Unit
        }
    }

    private fun mapEpisodes(seriesId: String, episodesMap: Map<String, List<com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo>>?): List<EpisodeEntityV2> {
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


    fun getPagedEpisodes(seriesId: String): Flow<PagingData<EpisodeEntityV2>> {
        // B-4: removed a runBlocking { episodeDao.getCountForSeries } that ran a
        // synchronous SQLite query on the UI thread on every detail open — it only
        // fed a debug Log.d. The Pager below reads the DB off-main anyway.
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = true
            ),
            pagingSourceFactory = {
                episodeDao.pagingSource(seriesId)
            }
        ).flow
    }

    fun getPagedEpisodesForSeason(seriesId: String, seasonNum: Int): Flow<PagingData<EpisodeEntityV2>> {
        // B-4: same main-thread runBlocking DB count removed (was debug-log only).
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = true
            ),
            pagingSourceFactory = { 
                // #region agent log
                android.util.Log.d("SeriesRepoV2", "getPagedEpisodesForSeason: Creating PagingSource for seriesId=$seriesId, seasonNum=$seasonNum")
                // #endregion
                episodeDao.pagingSourceForSeason(seriesId, seasonNum) 
            }
        ).flow
    }

    fun getSeasons(seriesId: String): Flow<List<Int>> {
        return episodeDao.getSeasonsForSeries(seriesId)
    }

    suspend fun getSeasonEpisodes(seriesId: String, seasonNum: Int): List<EpisodeEntityV2> {
        return episodeDao.getEpisodesForSeasonList(seriesId, seasonNum)
    }

    // ── Multi-category stream aggregation ──────────────────────────────────────
    // The same show is frequently listed across several Xtream playlist categories:
    // either as the SAME series_id belonging to multiple categories (category_ids),
    // or as SEPARATE series_ids with decorated name variants ("EN| Title", "FR| Title
    // 4K"…). Siblings are sourced from the legacy `series_v2` table — that is the
    // table category browsing and the global search index actually populate (the V2
    // table only ever holds the series the user opened). Matching uses a normalized
    // core title so decorated variants group together.

    private data class SiblingListing(
        val seriesId: String,
        val name: String?,
        val categoryIds: List<String?>
    )

    /** Find every listing of the same show across the legacy catalog. */
    private suspend fun findSiblingListings(primarySeriesId: String): List<SiblingListing> {
        val legacyPrimary = legacySeriesDao.getSeriesById(primarySeriesId)
        val primaryName = legacyPrimary?.name?.trim()
            ?: seriesDao.getSeriesById(primarySeriesId)?.name?.trim()
        val core = normalizeCoreTitle(primaryName.orEmpty())
        val likeToken = likeSearchToken(primaryName.orEmpty())

        val primaryYear = yearOf(primaryName.orEmpty())
        val candidates = if (core.length >= 4 && likeToken.length >= 4) {
            // The LIKE pool casts a wide net with a punctuation-free fragment of the
            // title (raw names contain it verbatim across variants); the normalized
            // equality check then drops lookalikes ("Title: Origins" ≠ "Title"), and
            // the year check keeps "Nemesis (2024)" from merging with "Nemesis (2026)".
            legacySeriesDao.findSeriesByNameLike(likeToken)
                .filter { normalizeCoreTitle(it.name.orEmpty()) == core }
                .filter { yearsCompatible(primaryYear, yearOf(it.name.orEmpty())) }
        } else emptyList()

        val all = (candidates + listOfNotNull(legacyPrimary))
            .distinctBy { it.seriesId }
            .map { row ->
                val cats: List<String?> = row.category_ids
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(row.categoryId) }
                SiblingListing(row.seriesId, row.name, cats)
            }

        return all.ifEmpty {
            // Legacy table empty (e.g. fresh install, detail opened via deep link):
            // fall back to the primary series alone from the V2 table.
            val v2 = seriesDao.getSeriesById(primarySeriesId) ?: return@ifEmpty emptyList()
            listOf(SiblingListing(v2.seriesId, v2.name, listOf(v2.categoryId)))
        }
    }

    /**
     * Number of distinct category listings this show appears in (for the hero summary).
     */
    suspend fun countCategoryListings(seriesId: String): Int = withContext(Dispatchers.IO) {
        val distinct = findSiblingListings(seriesId)
            .flatMap { it.categoryIds }
            .filterNotNull()
            .toSet().size
        maxOf(1, distinct)
    }

    /**
     * Build the grouped stream list for a specific episode across every category the
     * show is listed under. DB-only and effectively instant: siblings whose episodes
     * are cached get a ready playUrl; the rest stay unresolved (playUrl empty) and are
     * resolved with a single network fetch when the user actually picks them — see
     * [resolveStreamOption]. Never fetches all siblings up front: 13+ parallel
     * get_series_info calls froze the panel for ~30s and OOM-killed the app.
     */
    suspend fun aggregateEpisodeStreams(
        primarySeriesId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<IptvStreamGroup> = withContext(Dispatchers.IO) {
        val siblings = findSiblingListings(primarySeriesId)
        android.util.Log.i(
            "SeriesRepoV2",
            "aggregateEpisodeStreams: primary=$primarySeriesId S$seasonNumber E$episodeNumber " +
                "siblings=${siblings.map { "${it.seriesId}:${it.name}~cats=${it.categoryIds}" }}"
        )
        val primaryCats = siblings.firstOrNull { it.seriesId == primarySeriesId }
            ?.categoryIds?.filterNotNull()?.toSet().orEmpty()
        buildStreamGroups(siblings, seasonNumber, episodeNumber, primaryCats)
    }

    /**
     * Same aggregation keyed on a plain display title (e.g. the TMDB name used by the
     * Debrid detail screen) instead of an Xtream series id. DB-only and instant.
     */
    suspend fun aggregateStreamsForTitle(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        yearHint: String? = null
    ): List<IptvStreamGroup> = withContext(Dispatchers.IO) {
        val core = normalizeCoreTitle(title)
        val likeToken = likeSearchToken(title)
        if (core.length < 4 || likeToken.length < 4) return@withContext emptyList()
        val primaryYear = yearOf(title) ?: yearHint?.take(4)?.toIntOrNull()
        val nameMatched = legacySeriesDao.findSeriesByNameLike(likeToken)
            .filter { normalizeCoreTitle(it.name.orEmpty()) == core }
        val siblings = nameMatched
            .filter { yearsCompatible(primaryYear, yearOf(it.name.orEmpty())) }
            .map { row ->
                val cats: List<String?> = row.category_ids
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(row.categoryId) }
                SiblingListing(row.seriesId, row.name, cats)
            }
        android.util.Log.i(
            "SeriesRepoV2",
            "aggregateStreamsForTitle: title=$title year=$primaryYear S$seasonNumber E$episodeNumber " +
                "nameMatched=${nameMatched.size} afterYearGuard=${siblings.size}"
        )
        buildStreamGroups(siblings, seasonNumber, episodeNumber, primaryCats = emptySet())
    }

    private suspend fun buildStreamGroups(
        siblings: List<SiblingListing>,
        seasonNumber: Int,
        episodeNumber: Int,
        primaryCats: Set<String>
    ): List<IptvStreamGroup> {
        val order = mutableListOf<String>()
        val streamsByKey = linkedMapOf<String, MutableList<IptvStreamOption>>()
        val categoryByKey = mutableMapOf<String, Pair<String?, String>>()

        for (sib in siblings) {
            // Local lookup only — null just means "not cached yet", not "unavailable".
            val ep = episodeDao.getEpisode(sib.seriesId, seasonNumber, episodeNumber)

            for (catId in sib.categoryIds.distinct()) {
                val categoryName = catId
                    ?.let { categoryDao.getCategoryById(it)?.categoryName }
                    ?.takeIf { it.isNotBlank() }
                    ?: catId?.let { "Category $it" }
                    ?: "All Series"
                val key = catId ?: "other"
                val tag = shortCategoryTag(categoryName, sib.name)

                val option = IptvStreamOption(
                    streamId = ep?.episodeId ?: "pending:${sib.seriesId}:$key",
                    playUrl = ep?.let { getStreamUrl(it.episodeId, it.containerExtension) } ?: "",
                    containerExtension = ep?.containerExtension,
                    sourceSeriesId = sib.seriesId,
                    name = (sib.name ?: ep?.title ?: "Stream"),
                    quality = StreamQuality.fromName(sib.name ?: ep?.title),
                    container = ep?.let { (it.containerExtension ?: "mp4").uppercase() } ?: "",
                    languages = StreamLanguage.inferFrom(sib.name ?: ep?.title, tag),
                    categoryTag = tag
                )

                val bucket = streamsByKey.getOrPut(key) {
                    order.add(key)
                    categoryByKey[key] = catId to categoryName
                    mutableListOf()
                }
                if (bucket.none { it.sourceSeriesId == option.sourceSeriesId }) bucket.add(option)
            }
        }

        // Primary listing's categories lead the list.
        val sortedKeys = order.sortedByDescending { key -> if (key in primaryCats) 1 else 0 }

        return sortedKeys.mapNotNull { key ->
            val streams = streamsByKey[key]?.sortedByDescending { it.quality.rank }.orEmpty()
            if (streams.isEmpty()) return@mapNotNull null
            val (catId, catName) = categoryByKey[key] ?: (null to "All Series")
            IptvStreamGroup(
                categoryId = catId,
                categoryName = catName,
                categoryTag = shortCategoryTag(catName, streams.first().name),
                streams = streams
            )
        }
    }

    /**
     * Normalize a series name down to its core title so decorated variants match.
     * Real provider examples that must all normalize to "the walking dead":
     *   "EN - The Walking Dead (2010)", "NL - THE WALKING DEAD (2010)",
     *   "FR - The Walking Dead", "EN-  The Walking Dead", "[SE] The Walking Dead"
     */
    private fun normalizeCoreTitle(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.lowercase()
        // Drop bracketed decorations: years "(2010)", quality "(4K)", region "(US)", "[SE]"…
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
        // "EN| Title" / "4K | Title" — keep the longest pipe-separated segment
        if (s.contains('|')) {
            s = s.split('|').maxByOrNull { it.trim().length }?.trim() ?: s
        }
        // Strip leading short tag prefixes: "en - ", "nl- ", "nf -", "lat - "…
        // Dash separators only (a colon would eat real titles like "FBI: Most Wanted"),
        // and whitespace is required after the dash so "9-1-1" survives. Applied twice
        // for stacked tags.
        repeat(2) {
            s = s.trim().replace(Regex("^[a-z0-9+]{1,4}\\s*[-–]\\s+"), "")
        }
        // Strip quality / language / codec tokens appearing as standalone words
        val noise = setOf(
            "4k", "uhd", "fhd", "hd", "sd", "2160p", "1080p", "720p", "480p",
            "hevc", "x264", "x265", "h264", "h265", "10bit",
            "multi", "vostfr", "vosta", "vf", "vo", "dual", "dubbed", "sub", "subs"
        )
        s = s.replace(Regex("[^a-z0-9]+"), " ")
        val words = s.split(" ").filter { it.isNotBlank() && it !in noise }
        return words.joinToString(" ").trim()
    }

    /**
     * A punctuation-free fragment of the title used for the SQL LIKE candidate pool.
     * Raw sibling names must contain it verbatim, so it comes from the cleaned title's
     * longest punctuation-free run: "NL - The Walking Dead: Dead City (2023)" →
     * "the walking dead" (matches every variant regardless of its own decorations).
     */
    private fun likeSearchToken(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.lowercase()
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
        if (s.contains('|')) {
            s = s.split('|').maxByOrNull { it.trim().length }?.trim() ?: s
        }
        repeat(2) {
            s = s.trim().replace(Regex("^[a-z0-9+]{1,4}\\s*[-–]\\s+"), "")
        }
        // Split on anything that isn't a letter/digit/space; keep the longest run.
        val segment = s.split(Regex("[^a-z0-9 ]+"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .maxByOrNull { it.length }
            ?.trim().orEmpty()
        return segment
    }

    /** Release year embedded in a listing name, e.g. "Nemesis (2024)" → 2024. */
    private fun yearOf(raw: String): Int? =
        Regex("\\((19|20)\\d{2}\\)").find(raw)?.value?.trim('(', ')')?.toIntOrNull()

    /**
     * Different explicit years = different shows; a missing year matches anything.
     * ±1 year is tolerated — regional first-air years routinely differ by one
     * (strict equality wiped every listing for shows where the TMDB year and the
     * provider's label year disagree).
     */
    private fun yearsCompatible(a: Int?, b: Int?): Boolean =
        a == null || b == null || kotlin.math.abs(a - b) <= 1

    /**
     * Background availability sweep: resolve every unresolved option (throttled to 2
     * concurrent fetches — 13+ parallel fetches once OOM-killed the app) and drop the
     * listings that genuinely lack this episode. Returns the cleaned groups; results
     * persist in the DB so later panels are fully resolved instantly.
     */
    suspend fun verifyStreamGroups(
        groups: List<IptvStreamGroup>,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<IptvStreamGroup> = withContext(Dispatchers.IO) {
        val pendingIds = groups.flatMap { it.streams }
            .filter { it.playUrl.isBlank() }
            .map { it.sourceSeriesId }
            .distinct()
        if (pendingIds.isEmpty()) return@withContext groups

        val gate = Semaphore(2)
        val sweepResults = coroutineScope {
            pendingIds.map { id ->
                async {
                    gate.withPermit {
                        val fetched = ensureEpisodesCached(id)
                        id to (fetched to episodeDao.getEpisode(id, seasonNumber, episodeNumber))
                    }
                }
            }.awaitAll().toMap()
        }

        groups.mapNotNull { group ->
            val streams = group.streams.mapNotNull { opt ->
                if (opt.playUrl.isNotBlank()) return@mapNotNull opt
                val (fetchOk, ep) = sweepResults[opt.sourceSeriesId] ?: return@mapNotNull opt
                when {
                    ep != null -> opt.copy(
                        streamId = ep.episodeId,
                        playUrl = getStreamUrl(ep.episodeId, ep.containerExtension),
                        containerExtension = ep.containerExtension,
                        container = (ep.containerExtension ?: "mp4").uppercase()
                    )
                    // Fetched fine, episode definitively absent → drop the dead row.
                    fetchOk -> null
                    // Fetch failed (throttling, network) — fail OPEN: keep the row
                    // unresolved so it can still resolve on click. Dropping here once
                    // emptied whole panels during provider throttling.
                    else -> opt
                }
            }
            if (streams.isEmpty()) null else group.copy(streams = streams)
        }
    }

    /**
     * Resolve a stream option to a playable URL. Already-resolved options pass
     * through; unresolved ones trigger a single episode fetch for that sibling only.
     * Returns null when the sibling listing genuinely lacks this episode.
     */
    suspend fun resolveStreamOption(
        option: IptvStreamOption,
        seasonNumber: Int,
        episodeNumber: Int
    ): IptvStreamOption? = withContext(Dispatchers.IO) {
        if (option.playUrl.isNotBlank()) return@withContext option
        ensureEpisodesCached(option.sourceSeriesId)
        val ep = episodeDao.getEpisode(option.sourceSeriesId, seasonNumber, episodeNumber)
            ?: return@withContext null
        option.copy(
            streamId = ep.episodeId,
            playUrl = getStreamUrl(ep.episodeId, ep.containerExtension),
            containerExtension = ep.containerExtension,
            container = (ep.containerExtension ?: "mp4").uppercase()
        )
    }

    /**
     * Resolve an IPTV listing's playable URL for a specific episode. Used by the
     * Debrid detail screen when an injected IPTV source is picked.
     * Returns (playUrl, containerExtension, episodeId) or null when unavailable.
     */
    suspend fun resolveIptvPlayUrl(
        sourceSeriesId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): Triple<String, String?, String>? = withContext(Dispatchers.IO) {
        ensureEpisodesCached(sourceSeriesId)
        val ep = episodeDao.getEpisode(sourceSeriesId, seasonNumber, episodeNumber)
            ?: return@withContext null
        Triple(getStreamUrl(ep.episodeId, ep.containerExtension), ep.containerExtension, ep.episodeId)
    }

    /**
     * Ensure episode rows exist for a sibling series, fetching from network if empty.
     * Returns true only when the series ends up with episodes in the DB — callers use
     * this to distinguish "fetch failed" (keep the row, retry later) from "fetched
     * fine but the episode genuinely doesn't exist" (safe to drop).
     */
    /**
     * When the opened listing has no episodes of its own (dead regional dub, or a
     * provider that returns array-shaped JSON), adopt the episode list from a HEALTHY
     * sibling of the same show and store it under THIS series id — so the detail page
     * shows episodes. The adopted episodes keep the sibling's playable stream ids, which
     * is exactly what the multi-source panel would resolve anyway. Best-effort, bounded.
     */
    private suspend fun adoptSiblingEpisodes(seriesId: String, username: String, password: String) {
        if (episodeDao.getCountForSeries(seriesId) > 0) return
        val siblings = runCatching { findSiblingListings(seriesId) }.getOrNull()
            ?.filter { it.seriesId != seriesId }
            ?: return
        if (siblings.isEmpty()) return
        // Try the variants most likely to carry real episodes FIRST — |MULTI| / |EN| /
        // international listings usually do, while regional dubs (SOM-/PH-/AR-) are
        // typically the same dead references. Bounded + short timeout so a wall of dead
        // siblings can't hang the page for minutes.
        val ordered = siblings.sortedByDescending { siblingEpisodeLikelihood(it.name) }.take(8)
        android.util.Log.i("SeriesRepoV2", "adoptSiblingEpisodes: $seriesId has no episodes; trying ${ordered.size}/${siblings.size} siblings")
        for (sib in ordered) {
            try {
                val resp = withTimeoutOrNull(6_000L) {
                    apiService.getSeriesInfo(username, password, seriesId = sib.seriesId)
                }
                val epMap = resp?.takeIf { it.isSuccessful }?.body()?.episodes
                if (!epMap.isNullOrEmpty()) {
                    val eps = mapEpisodes(seriesId, epMap)
                    if (eps.isNotEmpty()) {
                        saveToDb(seriesId, eps)
                        android.util.Log.i("SeriesRepoV2", "adoptSiblingEpisodes: adopted ${eps.size} episodes from sibling ${sib.seriesId} (${sib.name})")
                        return
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sibling also dead / parse error — try the next.
            }
        }
        android.util.Log.w("SeriesRepoV2", "adoptSiblingEpisodes: no sibling of $seriesId had episodes")
    }

    /** Heuristic: which variants tend to carry real episode data. Higher = try sooner. */
    private fun siblingEpisodeLikelihood(name: String?): Int {
        val n = name?.uppercase().orEmpty()
        var score = 0
        if (n.contains("MULTI")) score += 5
        if (Regex("\\|\\s*EN\\s*\\|").containsMatchIn(n) || n.startsWith("EN")) score += 4
        if (n.contains("NETFLIX") || n.contains("4K") || n.contains("UHD")) score += 2
        // Regional-dub prefixes are usually the dead references.
        if (Regex("^(SOM|PH|AR|EXYU|ALB|PL|NL|PT|DE|ES|FR)\\b").containsMatchIn(n) ||
            Regex("^\\|\\s*(SOM|PH|AR|EXYU|ALB|PL|NL|PT|DE|ES|FR)\\s*\\|").containsMatchIn(n)) score -= 2
        return score
    }

    private suspend fun ensureEpisodesCached(seriesId: String): Boolean {
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

    /** Derive a short category badge (EN, NL, MULTI…) from the category / stream name. */
    private fun shortCategoryTag(categoryName: String?, streamName: String?): String {
        // Providers commonly embed the tag in the category name: "|EN| TOP SERIES",
        // "[FR] ACTION", "MULTI| NETFLIX 4K"…
        categoryName?.let { cat ->
            Regex("[|\\[]\\s*([A-Za-z]{2,5})\\s*[|\\]]").find(cat)
                ?.groupValues?.get(1)?.uppercase()
                ?.let { return it }
        }
        // …or as a name prefix on the listing itself: "EN - Title", "NL- Title".
        streamName?.let { name ->
            Regex("^\\s*([A-Za-z]{2,4})\\s*[-–]\\s+").find(name)
                ?.groupValues?.get(1)?.uppercase()
                ?.let { return it }
        }
        val hay = (categoryName.orEmpty() + " " + streamName.orEmpty()).lowercase()
        return when {
            hay.contains("multi") -> "MULTI"
            hay.contains("vostfr") || hay.contains("french") || hay.contains("français") ||
                hay.contains("séries") -> "FR"
            hay.contains("arab") || hay.contains("ramadan") -> "AR"
            hay.contains("hindi") -> "HI"
            hay.contains("span") || hay.contains("espa") -> "ES"
            hay.contains("english") -> "EN"
            else -> "SRV"
        }
    }

    /**
     * Get Paged Series for a Category.
     */
    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    fun getPagedSeries(categoryId: String): Flow<PagingData<SeriesEntityV2>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = true
            ),
            remoteMediator = SeriesRemoteMediatorV2(
                categoryId = categoryId,
                username = legacyRepository.getUsername(),
                password = legacyRepository.getPassword(),
                apiService = apiService,
                database = database
            ),
            pagingSourceFactory = { seriesDao.pagingSource(categoryId) }
        ).flow
    }
}
