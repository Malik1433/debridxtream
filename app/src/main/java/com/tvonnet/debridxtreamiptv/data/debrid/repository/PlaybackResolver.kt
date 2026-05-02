package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the playback resolution process.
 */
sealed class ResolutionResult {
    data class Success(val url: String, val expiresAt: Long? = null) : ResolutionResult()
    object RefreshRequired : ResolutionResult()
    data class Error(val message: String) : ResolutionResult()
}

/**
 * The primary decision engine for all playback sources.
 *
 * Enforces production-grade rules:
 * 1. Debrid: ALWAYS resolve via hash/magnet, IGNORE saved streamUrls.
 * 2. IPTV: Instant playback via saved URL IF not expired.
 * 3. Resilience: Exactly 2 attempts for Debrid resolution before failing.
 */
@Singleton
class PlaybackResolver @Inject constructor(
    private val debridRepo: DebridPlaybackRepository
) {
    suspend fun resolve(
        source: String,
        streamUrl: String?,
        isExpired: Boolean,
        infoHash: String?,
        magnet: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        currentExpiresAt: Long? = null
    ): ResolutionResult {
        android.util.Log.d("PlaybackResolver", "Resolving - source: $source, hasStreamUrl: ${!streamUrl.isNullOrBlank()}, isExpired: $isExpired, infoHash: $infoHash")
        
        if (source == "debrid") {
            // SMART VALIDATION: If link exists and is not expired (with 5-min buffer), skip resolution
            val now = System.currentTimeMillis()
            
            if (!isExpired && !streamUrl.isNullOrBlank()) {
                android.util.Log.i("PlaybackResolver", "Debrid link is still valid (Smart Cache), skipping re-resolution")
                return ResolutionResult.Success(streamUrl, currentExpiresAt)
            }

            // RULE: Debrid items MUST resolve via hash/magnet if expired.
            // We ignore the stored streamUrl completely to avoid 403 Forbidden/link expiration.
            if (infoHash.isNullOrBlank() && magnet.isNullOrBlank()) {
                android.util.Log.w("PlaybackResolver", "Debrid item missing metadata, requiring refresh")
                return ResolutionResult.RefreshRequired
            }

            // RULE: Exactly 2 attempts for Debrid resolution.
            var lastError: String? = null
            for (attempt in 1..2) {
                android.util.Log.d("PlaybackResolver", "Debrid resolution attempt $attempt...")
                val result = debridRepo.resolveDebridUrl(
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle
                )

                when (result) {
                    is Result.Success -> {
                        val resolvedUrl = result.data
                        val isValidUrl = !resolvedUrl.isNullOrBlank() && 
                            (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))
                        
                        if (!isValidUrl) {
                            lastError = "Resolved URL is empty or invalid"
                            android.util.Log.w("PlaybackResolver", "Debrid resolution attempt $attempt failed: $lastError")
                            continue
                        }
                        
                        // Metadata Anchor: Generate new expiration (4 hours from now).
                        // NOTE: Real-Debrid links typically expire in 2-6 hours in practice.
                        // Using 23h caused the SmartCache to serve dead links until the
                        // next resume attempt hours later.
                        val newExpiresAt = now + (4 * 60 * 60 * 1000L)
                        android.util.Log.i("PlaybackResolver", "Debrid resolution SUCCESS. New expiry: $newExpiresAt")
                        return ResolutionResult.Success(resolvedUrl, newExpiresAt)
                    }
                    is Result.Error -> {
                        lastError = result.exception.message
                        android.util.Log.w("PlaybackResolver", "Debrid resolution attempt $attempt failed: $lastError")
                        // No delay needed as resolveDebridUrl has internal polling/backoff
                    }
                    else -> { /* Handle Loading or other states if necessary */ }
                }
            }
            android.util.Log.e("PlaybackResolver", "Debrid resolution FAILED after 2 attempts")
            return ResolutionResult.Error(lastError ?: "Resolution failed after 2 attempts")
        } else {
            // RULE: Non-Debrid (Xtream) items must remain instant. NEVER use expiry logic.
            if (streamUrl.isNullOrBlank()) {
                android.util.Log.w("PlaybackResolver", "Xtream item missing streamUrl")
                return ResolutionResult.RefreshRequired
            }
            android.util.Log.d("PlaybackResolver", "Xtream resolution SUCCESS (instant)")
            return ResolutionResult.Success(streamUrl)
        }
    }
}
