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

// H4: media3 reports ISO 639-2 (3-letter) codes for embedded tracks; the source-list
// language chips speak ISO 639-1 (2-letter). Both bibliographic and terminological
// 3-letter variants map to the same 2-letter code.
private val ISO3_TO_ISO1 = mapOf(
    "eng" to "en", "hin" to "hi", "urd" to "ur", "tam" to "ta", "tel" to "te",
    "kan" to "kn", "mal" to "ml", "pan" to "pa", "ben" to "bn", "mar" to "mr",
    "guj" to "gu", "spa" to "es", "fra" to "fr", "fre" to "fr", "deu" to "de",
    "ger" to "de", "ita" to "it", "por" to "pt", "rus" to "ru", "jpn" to "ja",
    "kor" to "ko", "zho" to "zh", "chi" to "zh", "ara" to "ar", "tur" to "tr",
    "nld" to "nl", "dut" to "nl", "pol" to "pl", "tha" to "th", "vie" to "vi",
    "ind" to "id", "fas" to "fa", "per" to "fa", "ukr" to "uk", "ron" to "ro",
    "rum" to "ro", "hun" to "hu", "ces" to "cs", "cze" to "cs", "ell" to "el",
    "gre" to "el", "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi",
)

/**
 * H4: normalize a raw media3 track-language tag to the 2-letter code the source
 * list uses, or null for undetermined/absent tags ("und", blank, null).
 */
internal fun normalizeAudioLanguageCode(raw: String?): String? {
    val tag = raw?.trim()?.lowercase()?.substringBefore('-') ?: return null
    if (tag.isBlank() || tag == "und") return null
    return when (tag.length) {
        2 -> tag
        3 -> ISO3_TO_ISO1[tag]
        else -> null
    }
}

/**
 * H4: EVERY audio language the stream carries (not just the selected one) — this is
 * the ground truth that turns a "MULTI" claim into real language chips. Undetermined
 * tags are dropped, so a container with no language metadata yields an empty list
 * (and we record nothing rather than a lie).
 */
internal fun availableAudioLanguages(tracks: Tracks): List<String> {
    val codes = linkedSetOf<String>()
    tracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
        for (index in 0 until group.length) {
            normalizeAudioLanguageCode(group.getTrackFormat(index).language)?.let { codes += it }
        }
    }
    return codes.toList()
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
