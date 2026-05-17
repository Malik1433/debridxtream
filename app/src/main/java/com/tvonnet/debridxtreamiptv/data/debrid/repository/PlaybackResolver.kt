package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the playback resolution process.
 */
sealed class ResolutionResult {
    data class Success(val url: String) : ResolutionResult()
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
        episodeTitle: String? = null
    ): ResolutionResult {
        android.util.Log.d(
            "PlaybackResolver",
            "Resolving - source: $source, hasStreamUrl: ${!streamUrl.isNullOrBlank()}, isExpired: $isExpired, infoHash=${SensitiveLogRedactor.describeHash(infoHash)}, magnet=${SensitiveLogRedactor.describeUrl(magnet)}"
        )
        
        if (source == "debrid") {
            // RULE: Debrid items MUST ALWAYS resolve via hash/magnet.
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
                        android.util.Log.i("PlaybackResolver", "Debrid resolution SUCCESS")
                        return ResolutionResult.Success(resolvedUrl)
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
