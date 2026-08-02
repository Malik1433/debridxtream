package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridResolutionException
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the playback resolution process.
 */
sealed class ResolutionResult {
    data class Success(val url: String) : ResolutionResult()
    object RefreshRequired : ResolutionResult()
    data class Error(
        val message: String,
        val failureType: DebridFailureType? = null,
        val retryAfterSeconds: Long? = null
    ) : ResolutionResult()
}

/**
 * The primary decision engine for all playback sources.
 *
 * Enforces production-grade rules:
 * 1. Debrid: ALWAYS resolve via hash/magnet, IGNORE saved streamUrls.
 * 2. IPTV: Instant playback via saved URL IF not expired.
 * 3. Resilience: Retry transient failures, but do not retry terminal RD failures.
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
        allowDirectHttpPassthrough: Boolean = true
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

            // RULE: Up to 2 attempts for transient Debrid failures. Terminal RD failures stop immediately.
            var lastError: String? = null
            for (attempt in 1..2) {
                android.util.Log.d("PlaybackResolver", "Debrid resolution attempt $attempt...")
                val result = debridRepo.resolveDebridUrl(
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    allowDirectStreamUrlPassthrough = allowDirectHttpPassthrough,
                    // isExpired marks a forced re-resolution (retry after failure):
                    // never serve a possibly dead link back from the resolution cache.
                    bypassCache = isExpired || attempt > 1
                )

                val outcome = debridAttemptOutcome(result, attempt)
                if (outcome.final != null) return outcome.final
                if (outcome.lastError != null) lastError = outcome.lastError
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

    // One debrid attempt's verdict: `final` short-circuits the retry loop with that result;
    // `lastError` (when set) replaces the loop's running lastError. Bodies verbatim from resolve().
    private data class DebridAttemptOutcome(
        val final: ResolutionResult?,
        val lastError: String?
    )

    private fun debridAttemptOutcome(result: Result<String>, attempt: Int): DebridAttemptOutcome {
        return when (result) {
            is Result.Success -> {
                val resolvedUrl = result.data
                val isValidUrl = !resolvedUrl.isNullOrBlank() &&
                    (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))

                if (!isValidUrl) {
                    val lastError = "Resolved URL is empty or invalid"
                    android.util.Log.w("PlaybackResolver", "Debrid resolution attempt $attempt failed: $lastError")
                    DebridAttemptOutcome(final = null, lastError = lastError)
                } else {
                    android.util.Log.i("PlaybackResolver", "Debrid resolution SUCCESS")
                    DebridAttemptOutcome(final = ResolutionResult.Success(resolvedUrl), lastError = null)
                }
            }
            is Result.Error -> {
                // QA fix (B1 completion): the repository returns several failures as RAW
                // Exceptions ("Torrent not ready…", "No links available…", "No download
                // URL…"). A plain `as?` cast left them untyped → not terminal → a wasted
                // second ~63s poll cycle AND failureType=null → the picker never
                // auto-advanced. Classify everything so the NOT_CACHED mapping applies.
                val typedError = result.exception as? DebridResolutionException
                    ?: com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifier
                        .classify(result.exception)
                android.util.Log.w(
                    "PlaybackResolver",
                    "Debrid resolution attempt $attempt failed: ${typedError.type}"
                )
                if (typedError.terminal) {
                    DebridAttemptOutcome(
                        final = ResolutionResult.Error(
                            message = typedError.message,
                            failureType = typedError.type,
                            retryAfterSeconds = typedError.retryAfterSeconds
                        ),
                        lastError = typedError.message
                    )
                } else {
                    // No delay needed as resolveDebridUrl has internal polling/backoff
                    DebridAttemptOutcome(final = null, lastError = typedError.message)
                }
            }
            else -> { /* Handle Loading or other states if necessary */ DebridAttemptOutcome(null, null) }
        }
    }
}
