package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.epg.EpgParser
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.utils.PerformanceMonitor
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import kotlin.coroutines.coroutineContext

/**
 * Phase 7B-2: the network EPG sync lifted out of the XtreamRepository god-class. It owns the full
 * XMLTV fetch + generational-replace save (fetchAndSaveEpg), the freshness-gated ensureEpgData, and the
 * lightweight per-channel get_short_epg now/next paths. It reads the live XtreamSession for the service
 * + credentials and owns its own epg prefs + sync mutex. Bodies moved verbatim (ensureEpgData's default
 * for maxAgeMs stays on the repository delegator). Behaviour is byte-for-byte identical and frozen by
 * EpgGenerationalReplaceTest.
 */
internal class EpgSyncManager(
    private val session: XtreamSession,
    private val epgDao: EpgDao,
    private val memoryManager: MemoryManager,
    context: Context
) {
    private val epgSyncMutex = Mutex()
    private val epgPrefs = context.getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
    private val apiService get() = session.apiService
    private val username: String get() = session.username
    private val password: String get() = session.password

    suspend fun fetchAndSaveEpg(): Result<Int> {
        return epgSyncMutex.withLock {
            withContext(Dispatchers.IO) {
            try {
                val memoryPressure = preFetchMemoryPressure()

                if (apiService == null) {
                    return@withContext Result.Error(Exception("API service not initialized"))
                }

                Log.d(TAG, "Fetching EPG data from Xtream API... Memory pressure: $memoryPressure")

                // Track memory usage before operation
                val initialMemory = memoryManager.getCurrentMemoryUsage()
                memoryManager.trackMemoryUsage("epg_fetch_start", initialMemory.usedMemoryMB)

                val response = try {
                    apiService!!.getEpg(username, password)
                } catch (e: Exception) {
                    Log.e(TAG, "Network error during EPG fetch", e)
                    return@withContext Result.Error(e)
                }

                if (!response.isSuccessful || response.body() == null) {
                    Log.e(TAG, "EPG fetch failed: ${response.code()}")
                    return@withContext Result.Error(Exception("EPG fetch failed: ${response.code()}"))
                }

                if (epgDao == null) {
                    Log.w(TAG, "EpgDao not available, skipping database save")
                    return@withContext Result.Success(0)
                }

                // Generational replace: DO NOT wipe the table up-front. Stamp every
                // row of this sync with one timestamp, keep the previous generation
                // readable while we stream in the new one, and delete the old
                // generation only after a fully successful, non-empty parse (below).
                // This is why "EPG sometimes doesn't show" — the old code cleared
                // first, so any mid-parse failure or a guide opened mid-sync saw an
                // empty table. See deletePreviousGenerations().
                val syncStamp = System.currentTimeMillis()
                val totalPrograms = parseAndInsertGeneration(
                    response.body()!!, batchSizeFor(memoryPressure), syncStamp
                )
                retireOldGenerationAndCleanup(totalPrograms, syncStamp)

                Result.Success(totalPrograms)
            } catch (e: CancellationException) {
                Log.i(TAG, "EPG fetch cancelled: ${e.message}")
                memoryManager.requestGarbageCollection()
                Result.Error(e)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "EPG fetch failed due to memory constraints", e)
                memoryManager.requestGarbageCollection()
                Result.Error(Exception("Insufficient memory for EPG processing"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/save EPG", e)
                Result.Error(e)
            }
        }
        }
    }

    // System memory pressure (checkMemoryPressure) reflects the DEVICE's
    // memory, which on Android TV sits at ~90% as a normal steady state.
    // It must NOT gate the EPG fetch — doing so silently skipped syncs and,
    // mid-parse, truncated the guide so channels late in the feed had no EPG
    // ("EPG sometimes doesn't show"). Only react to true APP-HEAP pressure,
    // and even then just nudge GC and proceed rather than abandoning the sync.
    private fun preFetchMemoryPressure(): MemoryManager.MemoryPressure {
        val memoryPressure = memoryManager.checkMemoryPressure()
        if (memoryManager.appHeapPressure() == MemoryManager.MemoryPressure.CRITICAL) {
            Log.w(TAG, "App-heap high before EPG fetch; requesting GC and continuing")
            memoryManager.requestGarbageCollection()
        }
        return memoryPressure
    }

    private fun batchSizeFor(memoryPressure: MemoryManager.MemoryPressure): Int = when (memoryPressure) {
        MemoryManager.MemoryPressure.CRITICAL -> EPG_BATCH_SIZE / 4
        MemoryManager.MemoryPressure.WARNING -> EPG_BATCH_SIZE / 2
        MemoryManager.MemoryPressure.LOW -> EPG_BATCH_SIZE
        MemoryManager.MemoryPressure.NONE -> EPG_BATCH_SIZE
    }

    // The streaming parse + batched insert, verbatim: every row stamped with syncStamp;
    // APP-HEAP pressure only ever nudges GC and continues — aborting mid-parse is what
    // used to truncate the guide.
    private suspend fun parseAndInsertGeneration(
        responseBody: okhttp3.ResponseBody,
        batchSize: Int,
        syncStamp: Long
    ): Int {
        val batch = ArrayList<EpgEntity>(batchSize)
        var totalPrograms = 0
        var lastMemoryCheck = System.currentTimeMillis()

        responseBody.use { body ->
            body.charStream().buffered(memoryManager.getOptimalBufferSize()).use { reader ->
                totalPrograms = EpgParser.parseStream(reader) { program ->
                    // Periodic APP-HEAP check (not system memory — see note above).
                    // On real heap pressure, nudge GC and keep going; never abort
                    // the parse, or the guide loses every channel after this point.
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastMemoryCheck > 2000) { // Check every 2 seconds
                        if (memoryManager.appHeapPressure() == MemoryManager.MemoryPressure.CRITICAL) {
                            Log.w(TAG, "App-heap high during EPG parse; requesting GC and continuing")
                            memoryManager.requestGarbageCollection()
                        }
                        lastMemoryCheck = currentTime
                    }

                    batch.add(
                        EpgEntity(
                            channelId = program.channelId,
                            start = program.start,
                            stop = program.stop,
                            title = program.title,
                            description = program.desc,
                            category = program.category,
                            cachedAt = syncStamp
                        )
                    )

                    if (batch.size >= batchSize) {
                        epgDao.insertAll(batch)
                        batch.clear()

                        // APP-HEAP check after batch insert. The batch is already
                        // cleared, so heap should be low; on real pressure GC and
                        // continue. This is the check that (on system memory) used
                        // to abort at a batch boundary and truncate the guide.
                        if (memoryManager.appHeapPressure() == MemoryManager.MemoryPressure.CRITICAL) {
                            Log.w(TAG, "App-heap high after EPG batch insert; requesting GC and continuing")
                            memoryManager.requestGarbageCollection()
                        }
                    }

                    // Ensure coroutine is still active
                    coroutineContext[kotlinx.coroutines.Job]?.ensureActive()
                }
            }
        }

        if (batch.isNotEmpty()) {
            epgDao.insertAll(batch)
        }
        return totalPrograms
    }

    private suspend fun retireOldGenerationAndCleanup(totalPrograms: Int, syncStamp: Long) {
        // Atomic-from-a-reader's-view swap: the full new generation is now
        // in place, so retire the previous one. Guarded on totalPrograms > 0
        // so a provider returning "200 with an empty body" (or a parse that
        // yielded nothing) never wipes the existing, still-valid EPG — better
        // to keep slightly stale data than to show none.
        if (totalPrograms > 0) {
            epgDao.deletePreviousGenerations(syncStamp)
            Log.i(TAG, "EPG sync complete (generational replace): parsed=$totalPrograms programs")
        } else {
            Log.w(TAG, "EPG parse produced 0 programs; keeping previous generation")
        }

        // Clean up old expired programs (older than 24 hours)
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        epgDao.clearOldPrograms(cutoffTime)

        // Week 14: Track memory usage after EPG save
        PerformanceMonitor.trackMemory("afterEpgSave")
        val finalMemory = memoryManager.getCurrentMemoryUsage()
        memoryManager.trackMemoryUsage("epg_fetch_complete", finalMemory.usedMemoryMB)

        saveEpgLastSync()
    }

    suspend fun ensureEpgData(
        force: Boolean = false,
        maxAgeMs: Long
    ): Boolean {
        val dao = epgDao ?: return false
        val lastSync = epgPrefs.getLong("last_sync_time", 0L)
        val now = System.currentTimeMillis()
        val totalPrograms = dao.getTotalProgramCount()
        val shouldRefresh = force || totalPrograms == 0 || now - lastSync > maxAgeMs

        if (!shouldRefresh) return false

        return when (val result = fetchAndSaveEpg()) {
            is Result.Success -> {
                if (result.data > 0) {
                    saveEpgLastSync()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    suspend fun fetchShortEpgPrograms(
        streamId: String,
        channelKey: String,
        limit: Int = 12
    ): Result<List<EpgEntity>> {
        return try {
            val service = apiService ?: return Result.Error(Exception("API service not initialized"))

            val response = service.getShortEpg(username, password, streamId = streamId, limit = limit)
            if (!response.isSuccessful) {
                return Result.Error(HttpException(response))
            }

            val listings = response.body()?.listings.orEmpty()
            if (listings.isEmpty()) return Result.Success(emptyList())

            val mapped = listings.mapNotNull { listing -> listing.toEpgEntityOrNull(channelKey) }
                .sortedBy { it.start }
            Result.Success(mapped)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun fetchShortEpgNowNext(
        streamId: String,
        channelKey: String,
        limit: Int = 2
    ): Result<Pair<EpgEntity?, EpgEntity?>> {
        return when (val result = fetchShortEpgPrograms(streamId = streamId, channelKey = channelKey, limit = limit.coerceAtLeast(2))) {
            is Result.Success -> {
                val list = result.data
                if (list.isEmpty()) return Result.Success(null to null)
                val nowMs = System.currentTimeMillis()
                val now = list.firstOrNull { nowMs in it.start..it.stop } ?: list.firstOrNull()
                val next = list.firstOrNull { it.start > nowMs }
                Result.Success(now to next)
            }
            is Result.Error -> result
            else -> Result.Error(Exception("Unknown EPG result"))
        }
    }

    private fun saveEpgLastSync(timestamp: Long = System.currentTimeMillis()) {
        epgPrefs.edit().putLong("last_sync_time", timestamp).apply()
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val EPG_BATCH_SIZE = 500
    }
}
