package com.tvonnet.debridxtreamiptv.data.repository

import android.content.SharedPreferences
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
import com.tvonnet.debridxtreamiptv.data.local.entity.toSeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toVodEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Phase 7B-1: the background search-index sync lifted out of the XtreamRepository god-class. It fills
 * Room with the FULL VOD/series catalog (and indexes the live catalog via CacheManager) so search
 * covers categories the user never browsed. Self-contained: it reads the live session for the service
 * + credentials, stamps its per-stage freshness in the shared sync prefs, and owns its own background
 * scope + mutex. Bodies moved verbatim — behaviour is byte-for-byte identical.
 */
internal class SearchIndexManager(
    private val session: XtreamSession,
    private val syncPrefs: SharedPreferences,
    private val vodDao: VodDao,
    private val seriesDao: SeriesDao,
    private val cacheManager: CacheManager
) {
    private val searchIndexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchIndexMutex = Mutex()

    private val username: String get() = session.username
    private val password: String get() = session.password

    fun scheduleSearchIndexSyncIfStale() {
        val anyStale = isIndexStageStale(KEY_SEARCH_INDEX_VOD_TIME) ||
            isIndexStageStale(KEY_SEARCH_INDEX_SERIES_TIME) ||
            isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME)
        if (anyStale) {
            scheduleSearchIndexSync()
        }
    }

    fun scheduleSearchIndexSync() {
        searchIndexScope.launch {
            try {
                syncSearchIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index sync failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Live-only index refresh for the Live TV guide. Unlike the full search index it fetches ONLY the
     * live catalog (no VOD/series) so it stays light and never competes with channel playback for the
     * server's connections.
     */
    fun scheduleLiveIndexSyncIfStale() {
        if (!isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME)) return
        searchIndexScope.launch {
            val service = session.apiService ?: return@launch
            if (!searchIndexMutex.tryLock()) return@launch
            try {
                if (isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME) && indexLiveCatalog(service)) {
                    markIndexStageFresh(KEY_SEARCH_INDEX_LIVE_TIME)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live index sync failed: ${e.javaClass.simpleName}")
            } finally {
                searchIndexMutex.unlock()
            }
        }
    }

    private fun isIndexStageStale(stageKey: String): Boolean {
        return System.currentTimeMillis() - syncPrefs.getLong(stageKey, 0L) >= SEARCH_INDEX_REFRESH_INTERVAL_MS
    }

    private fun markIndexStageFresh(stageKey: String) {
        syncPrefs.edit().putLong(stageKey, System.currentTimeMillis()).apply()
    }

    private suspend fun syncSearchIndex() {
        val service = session.apiService ?: return
        if (!searchIndexMutex.tryLock()) return
        try {
            // Log.i so it is visible on devices that suppress Log.d. Each stage
            // is gated separately: a stage whose fetch failed (or returned
            // empty on a panel that rejects uncategorized calls) retries on the
            // next app open without re-running the stages that succeeded.
            Log.i(TAG, "Search index sync started")
            if (isIndexStageStale(KEY_SEARCH_INDEX_VOD_TIME) && indexVodCatalog(service)) {
                markIndexStageFresh(KEY_SEARCH_INDEX_VOD_TIME)
            }
            if (isIndexStageStale(KEY_SEARCH_INDEX_SERIES_TIME) && indexSeriesCatalog(service)) {
                markIndexStageFresh(KEY_SEARCH_INDEX_SERIES_TIME)
            }
            if (isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME) && indexLiveCatalog(service)) {
                markIndexStageFresh(KEY_SEARCH_INDEX_LIVE_TIME)
            }
            Log.i(TAG, "Search index sync finished")
        } finally {
            searchIndexMutex.unlock()
        }
    }

    private suspend fun indexVodCatalog(service: XtreamApiService): Boolean {
        val dao = vodDao ?: return false
        suspend fun insertAll(streams: List<XtreamVodInfo>, categoryIdOf: (XtreamVodInfo) -> String) {
            streams.chunked(SEARCH_INDEX_CHUNK).forEach { chunk ->
                dao.insertMovies(chunk.map { it.toVodEntity(categoryIdOf(it)) })
            }
        }
        return try {
            val full = try {
                service.getVodStreams(username, password, categoryId = null)
                    .takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index: catalog fetch failed (${e.javaClass.simpleName}) — falling back", e)
                emptyList()
            }
            if (full.isNotEmpty()) {
                insertAll(full) { it.category_id ?: "" }
                Log.i(TAG, "Search index: vod full-catalog upserted ${full.size} movies")
                return true
            }
            // Some panels reject the uncategorized call — iterate categories.
            Log.i(TAG, "Search index: vod full fetch empty — per-category fallback")
            val categories = try {
                service.getVodCategories(username, password).takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index: catalog fetch failed (${e.javaClass.simpleName}) — falling back", e)
                emptyList()
            }
            if (categories.isEmpty()) return false
            var indexedAny = false
            var failures = 0
            for (category in categories) {
                val catId = category.category_id ?: continue
                val streams = try {
                    service.getVodStreams(username, password, categoryId = catId)
                        .takeIf { it.isSuccessful }?.body().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "Search index: category $catId fetch failed (${e.javaClass.simpleName})", e)
                    emptyList()
                }
                if (streams.isNotEmpty()) {
                    insertAll(streams) { catId }
                    indexedAny = true
                }
                delay(SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS)
            }
            Log.i(TAG, "Search index: vod per-category done (categories=${categories.size}, failures=$failures)")
            indexedAny && failures < categories.size / 2
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search index: vod stage error: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun indexSeriesCatalog(service: XtreamApiService): Boolean {
        val dao = seriesDao ?: return false
        suspend fun insertAll(streams: List<XtreamSeriesInfo>, categoryIdOf: (XtreamSeriesInfo) -> String) {
            streams.chunked(SEARCH_INDEX_CHUNK).forEach { chunk ->
                dao.insertSeries(chunk.map { it.toSeriesEntity(categoryIdOf(it)) })
            }
        }
        return try {
            val full = try {
                service.getSeries(username, password, categoryId = null)
                    .takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index: catalog fetch failed (${e.javaClass.simpleName}) — falling back", e)
                emptyList()
            }
            if (full.isNotEmpty()) {
                insertAll(full) { it.category_id ?: "" }
                Log.i(TAG, "Search index: series full-catalog upserted ${full.size} series")
                return true
            }
            Log.i(TAG, "Search index: series full fetch empty — per-category fallback")
            val categories = try {
                service.getSeriesCategories(username, password).takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index: catalog fetch failed (${e.javaClass.simpleName}) — falling back", e)
                emptyList()
            }
            if (categories.isEmpty()) return false
            var indexedAny = false
            var failures = 0
            for (category in categories) {
                val catId = category.category_id ?: continue
                val streams = try {
                    service.getSeries(username, password, categoryId = catId)
                        .takeIf { it.isSuccessful }?.body().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "Search index: category $catId fetch failed (${e.javaClass.simpleName})", e)
                    emptyList()
                }
                if (streams.isNotEmpty()) {
                    insertAll(streams) { catId }
                    indexedAny = true
                }
                delay(SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS)
            }
            Log.i(TAG, "Search index: series per-category done (categories=${categories.size}, failures=$failures)")
            indexedAny && failures < categories.size / 2
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search index: series stage error: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun indexLiveCatalog(service: XtreamApiService): Boolean {
        val cm = cacheManager ?: return false
        return try {
            val full = try {
                service.getLiveStreams(username, password, categoryId = null)
                    .takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index: catalog fetch failed (${e.javaClass.simpleName}) — falling back", e)
                emptyList()
            }
            if (full.isNotEmpty()) {
                // Insert-only-new under a synthetic category id — never touches
                // the lazy per-category rows (see CacheManager).
                cm.indexChannelsForSearch(full, "live")
                Log.i(TAG, "Search index: live full-catalog ${full.size} channels")
                return true
            }
            Log.i(TAG, "Search index: live full fetch empty — per-category fallback")
            val categories = try {
                service.getLiveCategories(username, password).takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index: catalog fetch failed (${e.javaClass.simpleName}) — falling back", e)
                emptyList()
            }
            if (categories.isEmpty()) return false
            var indexedAny = false
            var failures = 0
            for (category in categories) {
                val catId = category.category_id ?: continue
                val streams = try {
                    service.getLiveStreams(username, password, categoryId = catId)
                        .takeIf { it.isSuccessful }?.body().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "Search index: category $catId fetch failed (${e.javaClass.simpleName})", e)
                    emptyList()
                }
                if (streams.isNotEmpty()) {
                    cm.indexChannelsForSearch(streams, "live")
                    indexedAny = true
                }
                delay(SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS)
            }
            Log.i(TAG, "Search index: live per-category done (categories=${categories.size}, failures=$failures)")
            indexedAny && failures < categories.size / 2
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search index: live stage error: ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val SEARCH_INDEX_CHUNK = 2000
        private const val KEY_SEARCH_INDEX_VOD_TIME = "search_index_vod_time"
        private const val KEY_SEARCH_INDEX_SERIES_TIME = "search_index_series_time"
        private const val KEY_SEARCH_INDEX_LIVE_TIME = "search_index_live_time"
        private const val SEARCH_INDEX_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS = 150L
    }
}
