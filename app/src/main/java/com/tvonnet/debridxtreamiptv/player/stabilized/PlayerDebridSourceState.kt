package com.tvonnet.debridxtreamiptv.player.stabilized

import java.util.Locale

/**
 * The stateful bookkeeping around a direct-debrid (addon-proxy) source (Phase P14).
 *
 * Scope note: resolution itself stays in [PlayerActivity] — it calls the ViewModel,
 * drives the source panel and re-initialises the player. What lives here is the part
 * that is pure decision + small state: is this the SAME source failing again, and may
 * we fresh-resolve at all.
 */

/**
 * Identity of a playback attempt: the url plus the headers actually sent with it.
 * Two attempts share a context only when both match, so a re-resolve that returns a
 * new url (or new headers) is never mistaken for "the same thing failed twice".
 */
internal fun addonProxyContextKey(url: String, headers: Map<String, String>?): String {
    val headersKey = headers.orEmpty()
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .entries
        .joinToString("|") { "${it.key.lowercase(Locale.US)}=${it.value}" }
    return "${url.hashCode()}:${headersKey.hashCode()}"
}

/**
 * A fresh direct-debrid resolve needs the source to BE direct debrid and at least one
 * id to look the content up by.
 */
internal fun canFreshResolveDirectDebrid(
    playbackSource: PlaybackSource,
    directDebridPlayback: Boolean,
    ids: DebridLookupIds
): Boolean =
    playbackSource == PlaybackSource.DEBRID &&
        directDebridPlayback &&
        ids.hasAny()

/** The ids the player can resolve a debrid source from, in preference order. */
internal data class DebridLookupIds(
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val contentId: String? = null,
    val title: String? = null
) {
    fun hasAny(): Boolean =
        tmdbId?.isNotBlank() == true ||
            imdbId?.isNotBlank() == true ||
            contentId?.isNotBlank() == true ||
            title?.isNotBlank() == true
}

/**
 * Which id identifies the SERIES for a debrid episode lookup: TMDB, else the launch
 * intent's series id, else IMDb, else the part of the content id before ':' (episode
 * ids are "<seriesId>:<episode>"), else empty.
 */
internal fun debridSeriesLookupId(
    tmdbId: String?,
    intentSeriesId: String?,
    imdbId: String?,
    contentId: String?
): String = tmdbId
    ?: intentSeriesId
    ?: imdbId
    ?: contentId?.substringBefore(':')
    ?: ""

/**
 * Remembers which addon-proxy context last failed, so the recovery paths can tell a
 * one-off from "this exact source is broken" — and so a source is only credited with
 * a success once per context.
 */
internal class AddonProxyFailureTracker {

    private var lastTimeoutContext: String? = null
    private var lastServerErrorContext: String? = null
    private var lastSuccessContext: String? = null

    /** True when [contextKey] timed out on the previous attempt too. */
    fun hasRepeatedTimeout(contextKey: String): Boolean {
        val repeated = lastTimeoutContext == contextKey
        lastTimeoutContext = contextKey
        return repeated
    }

    /** True when [contextKey] already returned a 5xx on the previous attempt. */
    fun hasRepeatedServerError(contextKey: String): Boolean {
        val repeated = lastServerErrorContext == contextKey
        lastServerErrorContext = contextKey
        return repeated
    }

    /** A clean READY clears the failure memory — the next failure is a fresh one. */
    fun clearFailures() {
        lastTimeoutContext = null
        lastServerErrorContext = null
    }

    /** True the first time [contextKey] plays successfully; false on every repeat. */
    fun shouldRecordSuccess(contextKey: String): Boolean {
        if (lastSuccessContext == contextKey) return false
        lastSuccessContext = contextKey
        return true
    }
}
