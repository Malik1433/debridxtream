package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Torrent → Real-Debrid → stream resolution, plus the pre-playback readiness probe for addon
 * proxies. A facade over three collaborators (C6):
 *
 * - [DebridLinkResolver] — add-magnet → poll → select file → unrestrict, with the resolution cache,
 *   the rate limiter and the one-shot re-add.
 * - [AddonProxyReadinessProbe] — "will this proxy URL actually play right now", answered READY /
 *   UNCERTAIN / TERMINAL so the caller can advance past a dead source without dropping a merely
 *   unlucky one.
 * - [DebridUrlRules] + [TorrentFileMatcher] — the pure string/scoring decisions both of the above
 *   rest on, extracted so they can be unit-tested without a provider.
 *
 * The public API is unchanged; this class is the facade callers already use.
 */
@Singleton
class DebridPlaybackRepository @Inject constructor(
    realDebridRemote: RealDebridRemoteDataSource,
    debridAccountRepository: DebridAccountRepository,
    okHttpClient: OkHttpClient,
    realDebridRateLimiter: RealDebridRateLimiter
) {
    private val readinessProbe = AddonProxyReadinessProbe(okHttpClient)

    private val linkResolver = DebridLinkResolver(
        realDebridRemote = realDebridRemote,
        debridAccountRepository = debridAccountRepository,
        realDebridRateLimiter = realDebridRateLimiter,
    )

    // ── addon-proxy readiness ──────────────────────────────────────────────────

    fun requiresDirectProxyReadinessCheck(
        url: String?,
        provider: String? = null,
        sourceName: String? = null,
        sourceType: String? = null
    ): Boolean = readinessProbe.isRequired(url, provider, sourceName, sourceType)

    suspend fun getAddonProxyPlaybackReadiness(
        url: String,
        headers: Map<String, String>? = null,
        provider: String? = null,
        sourceName: String? = null,
        sourceType: String? = null,
        diagnosticsContext: Context? = null
    ): Result<AddonProxyReadiness> =
        readinessProbe.readiness(
            ProbeSubject(url, provider, sourceName, sourceType), headers, diagnosticsContext
        )

    /** The headers the probe used — playback must send the same ones or the proxy can disagree. */
    fun effectiveAddonProxyPlaybackHeaders(headers: Map<String, String>?): Map<String, String> =
        readinessProbe.effectiveHeaders(headers)

    // ── link resolution ────────────────────────────────────────────────────────

    /**
     * Resolve a magnet/infoHash to a streamable URL via Real-Debrid.
     * Pass [bypassCache]=true on a retry so a dead link is never served back from cache.
     */
    @Suppress("LongParameterList") // established public API; callers pass these by name
    suspend fun resolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        allowDirectStreamUrlPassthrough: Boolean = true,
        bypassCache: Boolean = false
    ): Result<String> = linkResolver.resolve(
        infoHash = infoHash,
        magnet = magnet,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        allowDirectStreamUrlPassthrough = allowDirectStreamUrlPassthrough,
        bypassCache = bypassCache,
    )
}
