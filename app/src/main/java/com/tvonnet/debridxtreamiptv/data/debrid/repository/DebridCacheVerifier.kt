package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifier
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.Result as AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns Real-Debrid cache-status resolution for addon streams: the TTL'd
 * availability cache, the rate-limiter cooldown checks and the bounded
 * verification pass used before sources are shown or replayed.
 *
 * Extracted verbatim from [UnifiedSourceProvider] (USP-4) — the provider keeps
 * a single instance and delegates, so behaviour and the "UnifiedSourceProvider"
 * log tag are unchanged.
 */
internal class DebridCacheVerifier(
    private val realDebridRemote: RealDebridRemoteDataSource,
    private val realDebridRateLimiter: RealDebridRateLimiter
) {

    private val availabilityCache = ConcurrentHashMap<String, AvailabilityCacheEntry>()

    companion object {
        private const val TAG = "UnifiedSourceProvider"
        private const val MAX_CACHE_VERIFICATION_HASHES = 10
        private const val CACHED_STATUS_TTL_MS = 15L * 60L * 1000L
        private const val NOT_CACHED_STATUS_TTL_MS = 3L * 60L * 1000L
        private const val UNKNOWN_STATUS_TTL_MS = 60L * 1000L
    }

    suspend fun verifyRealDebridCacheStatuses(
        streams: List<AddonStream>,
        priorityInfoHash: String? = null
    ): Map<String, DebridCacheStatus> = coroutineScope {
        val allHashes = streams.mapNotNull { stream ->
            normalizeInfoHash(stream.infoHash) ?: extractInfoHashFromMagnet(stream.magnet)
        }.distinct()

        // Resume continuity path: the caller already knows which source it will
        // replay, so only that hash needs verified cache state before playback.
        // All other hashes stay UNKNOWN (Debrid Cache Confidence Rule). If the
        // priority hash is not among the fetched streams, fall back to the full
        // verification pass so source ranking behaves exactly as before.
        // Saved continuity ids carry a volatile "_<list index>" suffix
        // (see convertAddonStreamsToMovieSources streamId) — strip it so the
        // bare infoHash can match and the single-hash verification engages.
        val priorityHash = normalizeInfoHash(priorityInfoHash?.replace(Regex("_\\d+$"), ""))
        val hashes = if (priorityHash != null && allHashes.contains(priorityHash)) {
            listOf(priorityHash)
        } else {
            allHashes.take(MAX_CACHE_VERIFICATION_HASHES)
        }

        if (hashes.isEmpty()) return@coroutineScope emptyMap()

        val availabilitySemaphore = Semaphore(1)
        // Phase 2: accumulate each hash's verified status as it resolves, so a 20s timeout (or
        // error) KEEPS the already-verified results instead of discarding everything and marking
        // every source UNKNOWN. Semaphore(1) serialises the RD calls, so entries are written one
        // at a time; on timeout the partial map is returned and only the unresolved hashes stay
        // unknown.
        val verified = java.util.concurrent.ConcurrentHashMap<String, DebridCacheStatus>()
        try {
            withTimeout(20000L) {
                hashes.map { hash ->
                    async {
                        availabilitySemaphore.withPermit {
                            verified[hash] = resolveCachedAvailability(hash)
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "RD cache verification incomplete after timeout; keeping ${verified.size}/${hashes.size} resolved, rest stay unknown", e)
        }
        verified.toMap()
    }

    private suspend fun resolveCachedAvailability(hash: String): DebridCacheStatus {
        val now = System.currentTimeMillis()
        availabilityCache[hash]?.let { entry ->
            if (entry.expiresAtMillis > now) return entry.status
            availabilityCache.remove(hash)
        }

        realDebridRateLimiter.globalCooldown()?.let {
            val status = DebridCacheStatus.UNKNOWN
            availabilityCache[hash] = AvailabilityCacheEntry(status, now + UNKNOWN_STATUS_TTL_MS)
            return status
        }

        realDebridRateLimiter.sourceCooldown(hash)?.let {
            val status = DebridCacheStatus.UNKNOWN
            availabilityCache[hash] = AvailabilityCacheEntry(status, now + UNKNOWN_STATUS_TTL_MS)
            return status
        }

        realDebridRateLimiter.awaitPermit()
        val status = when (val result = realDebridRemote.getInstantAvailability(hash)) {
            is AppResult.Success -> if (result.data) {
                DebridCacheStatus.VERIFIED_CACHED
            } else {
                DebridCacheStatus.NOT_CACHED
            }
            is AppResult.Error -> {
                val failure = DebridFailureClassifier.classify(result.exception)
                realDebridRateLimiter.recordFailure(hash, failure)
                DebridCacheStatus.UNKNOWN
            }
            else -> DebridCacheStatus.UNKNOWN
        }

        val ttl = when (status) {
            DebridCacheStatus.VERIFIED_CACHED -> CACHED_STATUS_TTL_MS
            DebridCacheStatus.NOT_CACHED -> NOT_CACHED_STATUS_TTL_MS
            else -> UNKNOWN_STATUS_TTL_MS
        }
        availabilityCache[hash] = AvailabilityCacheEntry(status, now + ttl)
        return status
    }

    private data class AvailabilityCacheEntry(
        val status: DebridCacheStatus,
        val expiresAtMillis: Long
    )
}

/**
 * Pure per-stream cache-status decision: a direct/MediaFusion URL always wins,
 * then a verified RD status for the stream's infoHash, then the addon's own
 * "cached" hint (only a hard `false` downgrades to NOT_CACHED).
 */
internal fun resolveCacheStatus(
    addonStream: AddonStream,
    mediaFusionUrl: String?,
    directUrl: String?,
    infoHash: String?,
    cacheStatusByHash: Map<String, DebridCacheStatus>
): DebridCacheStatus {
    if (mediaFusionUrl != null || directUrl != null) {
        return DebridCacheStatus.DIRECT_STREAM
    }

    val verified = infoHash?.let { cacheStatusByHash[it] }
    if (verified != null) return verified

    return when (parseCachedValue(addonStream.extras["cached"])) {
        false -> DebridCacheStatus.NOT_CACHED
        else -> DebridCacheStatus.UNKNOWN
    }
}
