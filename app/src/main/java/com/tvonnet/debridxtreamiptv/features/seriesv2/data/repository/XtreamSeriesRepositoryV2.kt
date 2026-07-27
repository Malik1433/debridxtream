package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Series-detail data for the IPTV (Xtream) path: paging queries plus the offline-first detail
 * fetch, delegating to four collaborators (C5).
 *
 * - [SeriesEpisodeSync] — the network → DB ladder (`get_series_info` → episodes-only fallbacks →
 *   sibling adoption), every stage bounded, an empty fetch never overwriting good rows.
 * - [SeriesSiblingFinder] — every listing of the same show across the catalog.
 * - [SeriesStreamAggregator] — the multi-category "SELECT STREAM" panel and its lazy resolution.
 * - [SeriesTitleMatching] — the pure name normalization all of the above match on.
 *
 * The public API is unchanged; this class is the facade callers already use.
 */
@Singleton
class XtreamSeriesRepositoryV2 @Inject constructor(
    private val legacyRepository: XtreamRepository,
    private val seriesDao: SeriesDaoV2,
    private val episodeDao: EpisodeDaoV2,
    categoryDao: com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao,
    legacySeriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao,
    private val database: com.tvonnet.debridxtreamiptv.data.local.AppDatabase
) {

    // Helper to get service safely from legacy repo
    private val apiService: XtreamApiService
        get() = legacyRepository.getApiService() ?: throw IllegalStateException("API Not Initialized. Please login first.")

    private val siblingFinder = SeriesSiblingFinder(legacySeriesDao, seriesDao)

    private val episodeSync = SeriesEpisodeSync(
        legacyRepository = legacyRepository,
        seriesDao = seriesDao,
        episodeDao = episodeDao,
        legacySeriesDao = legacySeriesDao,
        database = database,
        siblingFinder = siblingFinder,
    )

    private val aggregator = SeriesStreamAggregator(
        episodeDao = episodeDao,
        categoryDao = categoryDao,
        siblingFinder = siblingFinder,
        cacheWarmer = episodeSync,
        streamUrl = ::getStreamUrl,
    )

    fun getStreamUrl(episodeId: String, extension: String?): String {
        val server = legacyRepository.getServerUrl().trimEnd('/')
        val user = legacyRepository.getUsername()
        val pass = legacyRepository.getPassword()
        val ext = extension ?: "mp4" // Default to mp4 if null
        return "$server/series/$user/$pass/$episodeId.$ext"
    }

    /**
     * Offline-first series detail: emit the cached row immediately (if any), then let
     * [SeriesEpisodeSync] refresh from the network and emit again.
     */
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
            val outcome = episodeSync.syncSeries(seriesId, username, password)
            if (outcome is SeriesSyncOutcome.Resolved) {
                outcome.series?.let { emit(Result.Success(it)) }
                return@flow
            }
        } catch (e: Exception) {
            android.util.Log.e("SeriesRepoV2", "getSeriesById: Exception: ${e.message}", e)
            // Providers often return array-shaped JSON that breaks get_series_info parsing
            // (dead regional dubs like SOM-/PH-). Try the episodes-only fallbacks, then
            // adopt episodes from a healthy sibling of the same show. Do NOT early-return
            // on a cached local series — that left cached dead listings showing "No episodes".
            android.util.Log.w("SeriesRepoV2", "getSeriesById: Network exception, attempting episodes fallback + sibling adoption...")
            val recovered = try {
                episodeSync.recoverEpisodes(seriesId, username, password)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                seriesDao.getSeriesById(seriesId)
            }
            if (recovered != null) {
                emit(Result.Success(recovered))
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

    // ── Paging / queries ───────────────────────────────────────────────────────

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

    // ── Multi-category stream aggregation ──────────────────────────────────────
    // The same show is frequently listed across several Xtream playlist categories:
    // either as the SAME series_id belonging to multiple categories (category_ids),
    // or as SEPARATE series_ids with decorated name variants ("EN| Title", "FR| Title
    // 4K"…). See [SeriesSiblingFinder] and [SeriesStreamAggregator].

    /** Number of distinct category listings this show appears in (for the hero summary). */
    suspend fun countCategoryListings(seriesId: String): Int =
        siblingFinder.countCategoryListings(seriesId)

    suspend fun aggregateEpisodeStreams(
        primarySeriesId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<IptvStreamGroup> =
        aggregator.aggregateEpisodeStreams(primarySeriesId, seasonNumber, episodeNumber)

    suspend fun aggregateStreamsForTitle(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        yearHint: String? = null
    ): List<IptvStreamGroup> =
        aggregator.aggregateStreamsForTitle(title, seasonNumber, episodeNumber, yearHint)

    suspend fun verifyStreamGroups(
        groups: List<IptvStreamGroup>,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<IptvStreamGroup> = aggregator.verifyStreamGroups(groups, seasonNumber, episodeNumber)

    suspend fun resolveStreamOption(
        option: IptvStreamOption,
        seasonNumber: Int,
        episodeNumber: Int
    ): IptvStreamOption? = aggregator.resolveStreamOption(option, seasonNumber, episodeNumber)

    suspend fun resolveIptvPlayUrl(
        sourceSeriesId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): Triple<String, String?, String>? =
        aggregator.resolveIptvPlayUrl(sourceSeriesId, seasonNumber, episodeNumber)
}
