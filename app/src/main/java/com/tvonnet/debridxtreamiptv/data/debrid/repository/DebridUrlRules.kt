package com.tvonnet.debridxtreamiptv.data.debrid.repository

/**
 * C6: the pure vocabulary for reading a debrid / addon URL or magnet.
 *
 * Everything here answers a question about a *string* — is this a proxy that has to be probed
 * before playback, is this redirect target an error page dressed as a video, is this already a
 * direct stream, which magnet does this identify. None of it needs a network, so it is the half of
 * the playback path that can actually be pinned by tests.
 *
 * The error-target list is the load-bearing one: addon proxies answer "not ready yet" with a 302 to
 * a URL whose *path* says so, and the player would otherwise buffer forever on an error page. Every
 * entry in [isAddonProxyErrorTarget] came from an observed provider response — treat it as data,
 * and add to it rather than "simplifying" it.
 *
 * Moved verbatim from `DebridPlaybackRepository`.
 */
object DebridUrlRules {

    // ── addon proxies ──────────────────────────────────────────────────────────

    /** Hosts whose playback URLs are a proxy in front of a debrid account, not a file. */
    fun isAddonProxyPlaybackUrl(url: String): Boolean {
        val lowered = url.lowercase()
        return lowered.contains("mediafusion.elfhosted.com/playback") ||
            lowered.contains("aiostreams.elfhosted.com")
    }

    /** The same proxies identified by provider / source name instead of by URL. */
    fun isAddonProxySource(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.contains("mediafusion", ignoreCase = true) ||
            value.contains("aio streams", ignoreCase = true) ||
            value.contains("aiostreams", ignoreCase = true) ||
            value.equals("aio", ignoreCase = true)
    }

    /** Statuses that mean "this will never play", as opposed to "not yet". */
    fun isTerminalAddonProxyStatus(code: Int): Boolean {
        return code == 401 ||
            code == 403 ||
            code == 404 ||
            code == 410 ||
            code == 451
    }

    /**
     * A redirect target that is an error page, not the stream. Proxies signal "torrent not
     * downloaded", rate limits and provider errors this way — every spelling below was observed
     * in the wild, which is why the list looks redundant.
     */
    fun isAddonProxyErrorTarget(location: String?): Boolean {
        val value = location?.lowercase() ?: return false
        return ADDON_PROXY_ERROR_MARKERS.any { value.contains(it) }
    }

    private val ADDON_PROXY_ERROR_MARKERS = listOf(
        "torrent_not_downloaded", "not_downloaded", "not-downloaded",
        "not-ready", "not_ready", "notready",
        "notcached", "not_cached", "not-cached",
        "api_exception", "api-exception", "api exception", "exception",
        "rate_limit", "rate-limit", "rate limit",
        "limit_reached", "limit-reached", "limit reached",
        "quota",
        "provider_error", "provider-error", "provider error",
        "invalid_token", "invalid-token", "invalid token",
        "error=", "error.mp4", "/error",
    )

    /** A proxy answering with a document rather than media is answering with an error. */
    fun isAddonProxyErrorContentType(contentType: String): Boolean {
        return contentType.contains("application/json") ||
            contentType.contains("text/html") ||
            contentType.contains("text/plain")
    }

    // ── stream URLs and magnets ────────────────────────────────────────────────

    /** Already playable as-is: an addon stream endpoint, or a plain media file URL. */
    fun isDirectStreamUrl(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("http")) return false
        if (isAddonStreamPath(lowered)) return true
        val clean = lowered.substringBefore('?').substringBefore('#')
        return DIRECT_STREAM_EXTENSIONS.any { clean.endsWith(it) }
    }

    private fun isAddonStreamPath(lowered: String): Boolean =
        lowered.contains("mediafusion.elfhosted.com") ||
            lowered.contains("aiostreams.elfhosted.com") ||
            lowered.contains("/stremio/") ||
            lowered.contains("/stream/") ||
            lowered.contains("/play/") ||
            lowered.contains("/playback/")

    private val DIRECT_STREAM_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".m3u8", ".mpd", ".webm", ".m4v", ".mov", ".ts", ".m2ts",
    )

    /**
     * The identity a source is rate-limited and resolution-cached under.
     *
     * Deliberately the **full** string, never a truncated prefix: two different magnets sharing
     * their first N characters must not collapse onto one key.
     */
    fun normalizeSourceKey(infoHash: String?, magnetLink: String?): String? {
        val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: magnetLink?.let { extractHashFromMagnet(it) }
        return hash ?: magnetLink?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    fun extractHashFromMagnet(magnetLink: String): String? {
        val match = Regex("btih:([a-fA-F0-9]{32,40})").find(magnetLink)
        return match?.groupValues?.getOrNull(1)?.lowercase()
    }
}
