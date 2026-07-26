package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import java.util.Locale

/**
 * Pure helpers lifted out of [PlayerActivity] (Phase P6 of the player decomposition).
 *
 * Everything here is state-free: it takes its inputs as parameters and touches no view,
 * no ExoPlayer instance and no Activity field, which is what makes it unit-testable.
 */

/** QoE bucket for analytics: the mode a session is reported under. */
internal fun qoeMode(contentType: ContentType?, playbackSource: PlaybackSource): String = when {
    contentType == ContentType.LIVE_TV -> "live"
    playbackSource == PlaybackSource.DEBRID -> "debrid"
    else -> "vod"
}

/**
 * Container hint for the MediaItem: URL extension first, then any provider/source/title
 * hints, then Accept / Content-Type response headers. Caller supplies the state.
 */
internal fun resolveMediaMimeType(
    url: String,
    sourceHints: List<String?>,
    streamHeaders: Map<String, String>?
): String? {
    val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
    val sourceText = sourceHints.filterNotNull().joinToString(" ").lowercase(Locale.US)
    val headerText = streamHeaders.orEmpty()
        .filterKeys { key ->
            key.equals("Accept", ignoreCase = true) ||
                key.equals("Content-Type", ignoreCase = true)
        }
        .values
        .joinToString(" ")
        .lowercase(Locale.US)

    return when {
        cleanUrl.endsWith(".m3u8") ||
            sourceText.contains(".m3u8") ||
            sourceText.contains(" hls") ||
            headerText.contains("mpegurl") ||
            headerText.contains("application/vnd.apple.mpegurl") -> MimeTypes.APPLICATION_M3U8
        cleanUrl.endsWith(".mpd") ||
            sourceText.contains(".mpd") ||
            sourceText.contains(" dash") ||
            headerText.contains("application/dash+xml") -> MimeTypes.APPLICATION_MPD
        cleanUrl.endsWith(".mp4") ||
            cleanUrl.endsWith(".m4v") ||
            sourceText.contains(".mp4") ||
            sourceText.contains(".m4v") ||
            headerText.contains("video/mp4") -> MimeTypes.VIDEO_MP4
        cleanUrl.endsWith(".mkv") ||
            sourceText.contains(".mkv") ||
            headerText.contains("matroska") -> "video/x-matroska"
        cleanUrl.endsWith(".webm") ||
            sourceText.contains(".webm") ||
            headerText.contains("video/webm") -> MimeTypes.VIDEO_WEBM
        cleanUrl.endsWith(".ts") ||
            cleanUrl.endsWith(".m2ts") ||
            sourceText.contains(".m2ts") ||
            sourceText.contains(".ts ") ||
            headerText.contains("video/mp2t") -> MimeTypes.VIDEO_MP2T
        else -> null
    }
}

internal fun cleanTitle(raw: String): String {
    var clean = raw.replace(".", " ").replace("_", " ")
    val patterns = listOf(
        "\\d{3,4}p", "2160p", "1080p", "720p", "480p",
        "WEB-DL", "WEBRip", "BluRay", "HDRip", "DV", "HDR",
        "H264", "H265", "x264", "x265", "HEVC",
        "DDP5.1", "DTS", "AAC", "AC3",
        "Hybrid", "MULTI", "BTM"
    )
    patterns.forEach { pattern ->
        val regex = Regex("(?i)\\b$pattern\\b")
        val match = regex.find(clean)
        if (match != null) {
            clean = clean.substring(0, match.range.first).trim()
        }
    }
    val yearRegex = Regex("(\\d{4})")
    val yearMatch = yearRegex.find(raw)
    if (yearMatch != null) {
        val year = yearMatch.groupValues[1]
        if (clean.contains(year)) {
            clean = clean.replace(year, "($year)").replace("  ", " ").trim()
        }
    }
    return clean.trim()
}

/**
 * Parse a Retry-After header (QA fix 4) off a 429 response into a cool-off delay
 * in ms. Supports the delta-seconds form (e.g. "Retry-After: 30"); the HTTP-date
 * form is uncommon from IPTV panels and falls back to the fixed cool-off. Clamped
 * so a hostile value can't park playback for minutes.
 */
internal fun parseRetryAfterMs(error: PlaybackException): Long? {
    val headers = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.headerFields
        ?: return null
    val value = headers.entries
        .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
        ?.value?.firstOrNull()
        ?.trim()
        ?: return null
    val seconds = value.toLongOrNull() ?: return null
    return (seconds * 1000L).coerceIn(1000L, 60_000L)
}

internal fun isAudioSinkInitFailure(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    var cause: Throwable? = error.cause
    while (cause != null) {
        if (cause is androidx.media3.exoplayer.audio.AudioSink.InitializationException) return true
        cause = cause.cause
    }
    return false
}

internal fun isTerminalDirectHttpPlaybackError(
    error: PlaybackException,
    directDebridPlayback: Boolean
): Boolean {
    if (!directDebridPlayback) return false
    val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        ?: return false
    return responseCode == 401 ||
        responseCode == 403 ||
        responseCode == 404 ||
        responseCode == 410 ||
        responseCode == 429 ||
        responseCode == 451
}

internal fun frameRateMismatch(frameRate: Float, refreshRate: Float): Float {
    if (refreshRate < frameRate - 0.1f) return Float.MAX_VALUE
    val ratio = refreshRate / frameRate
    return kotlin.math.abs(ratio - kotlin.math.round(ratio))
}

/**
 * A clean display title for the loader. Strips site/pipe tags (MediaTitleCleaner) then
 * turns a scene release filename ("Backrooms.2026.MULTi.720p.HDTS...") into the plain
 * movie/series name ("Backrooms") by cutting at the first year / quality / codec token.
 */
internal fun cleanLoaderTitle(raw: String?): String {
    val base = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(raw).ifBlank { raw ?: "" }
    if (base.isBlank()) return "Loading"
    var t = base.replace('.', ' ').replace('_', ' ').trim()
    val cut = Regex(
        "(?i)[\\s(\\[]+((19|20)\\d{2}|720p|1080p|2160p|480p|4k|web[- ]?dl|webrip|web|bluray|blu-ray|brrip|hdrip|hdts|hdcam|cam|dvdrip|multi|dual|x264|x265|h264|h265|hevc|aac|ac3|dd5|dts|remux|s\\d{1,2}e\\d{1,2})\\b"
    ).find(t)
    if (cut != null && cut.range.first >= 2) t = t.substring(0, cut.range.first).trim()
    return t.trim(' ', '-', '·', ':').ifBlank { base }
}

/**
 * What STATE_ENDED means for a VOD/series session, once the live-TV and mid-stream-drop cases have
 * already been handled by the caller.
 *
 * Extracted from [PlayerEventListener] so the T1.5 episode-continuity rules are provable in a unit
 * test instead of only on a TV:
 *  - `99d10b9` — advance ONLY when the playlist actually `hasNext`. The final episode of a series must
 *    never fire a "ghost" load for an episode that does not exist.
 *  - `a969022` — while a next-episode Debrid resolve is already in flight, do NOT advance again
 *    (double-advance skips an episode) and do NOT finish() (that killed the activity mid-resolve).
 */
internal enum class EndedAction {
    /** A next-episode resolve is in flight — leave it alone. */
    WAIT_FOR_RESOLVE,
    /** There is a next episode: auto-advance. */
    ADVANCE_TO_NEXT,
    /** Session is over, but the next-episode prompt is on screen — mark complete, keep the activity. */
    COMPLETE_AND_KEEP_PROMPT,
    /** Session is over and nothing is holding the screen — mark complete and finish. */
    COMPLETE_AND_FINISH,
}

internal fun endedActionFor(
    isSeriesLike: Boolean,
    isResolvingDebrid: Boolean,
    hasNext: Boolean,
    isNextPromptVisible: Boolean,
): EndedAction = when {
    isSeriesLike && isResolvingDebrid -> EndedAction.WAIT_FOR_RESOLVE
    isSeriesLike && hasNext -> EndedAction.ADVANCE_TO_NEXT
    isNextPromptVisible -> EndedAction.COMPLETE_AND_KEEP_PROMPT
    else -> EndedAction.COMPLETE_AND_FINISH
}
