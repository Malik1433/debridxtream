package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder

/**
 * Diagnostics field builders lifted out of [PlayerActivity] (Phase P7).
 *
 * Each one takes the state it needs as parameters instead of reaching into Activity
 * fields, so they are state-free and unit-testable. `diagnosticsPlaybackFields` stays
 * on the Activity — it needs the Context for [PlaybackDiagnosticsRecorder.playbackFields]
 * and the live url/retry/timeout state — but it is now a thin composition of these.
 */

/** Reads the optional per-request HTTP headers the caller put on the launch intent. */
internal fun readStreamHeaders(intent: Intent, extraKey: String): Map<String, String>? {
    @Suppress("UNCHECKED_CAST")
    val raw = intent.getSerializableExtra(extraKey) as? HashMap<String, String>
    return raw?.takeIf { it.isNotEmpty() }
}

/** What is being played: kind + the content ids, never the raw title. */
internal fun diagnosticsContentFields(
    kind: String,
    tmdbId: String?,
    imdbId: String?,
    season: Int?,
    episode: Int?
): Map<String, Any?> {
    return PlaybackDiagnosticsRecorder.contentFields(
        kind = kind,
        tmdbId = tmdbId,
        imdbId = imdbId,
        season = season,
        episode = episode
    )
}

/** Where the stream came from: provider/source metadata, identities only as fingerprints. */
internal fun diagnosticsSourceFields(
    provider: String?,
    sourceType: String?,
    sourceName: String?,
    languages: List<String>?,
    quality: String?,
    bingeGroup: String?,
    fileIdx: Int?,
    streamId: String?,
    infoHash: String?
): Map<String, Any?> {
    return mapOf(
        "provider" to provider,
        "sourceType" to sourceType,
        "sourceName" to sourceName,
        "languages" to languages,
        "quality" to quality,
        "hasBingeGroup" to !bingeGroup.isNullOrBlank(),
        "bingeGroupFingerprint" to PlaybackDiagnosticsRecorder.identityFingerprint(bingeGroup),
        "fileIdx" to fileIdx,
        "streamIdFingerprint" to PlaybackDiagnosticsRecorder.identityFingerprint(streamId ?: infoHash)
    )
}

/** Running tally of one track type: how many are supported, which of them are selected. */
private class TrackTally {
    var supportedCount = 0
    val selectedLanguages = mutableListOf<String>()

    fun countSupported(group: Tracks.Group, index: Int) {
        supportedCount++
        if (group.isTrackSelected(index)) {
            selectedLanguages += group.getTrackFormat(index).language ?: "unknown"
        }
    }
}

/** Supported/selected audio and text tracks of the current selection. */
internal fun trackDiagnosticsFields(tracks: Tracks): Map<String, Any?> {
    val audio = TrackTally()
    val text = TrackTally()

    tracks.groups.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> audio.countSupported(group, index)
                C.TRACK_TYPE_TEXT -> text.countSupported(group, index)
            }
        }
    }

    return mapOf(
        "supportedAudioTrackCount" to audio.supportedCount,
        "supportedTextTrackCount" to text.supportedCount,
        "selectedAudioLanguages" to audio.selectedLanguages.distinct(),
        "selectedTextLanguages" to text.selectedLanguages.distinct()
    )
}
