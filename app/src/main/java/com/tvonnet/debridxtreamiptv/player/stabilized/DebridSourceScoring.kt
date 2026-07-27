package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import java.util.Locale

/**
 * C10: picking the next episode's source so it matches the one already playing.
 *
 * This is what makes binge-watching not feel random. When episode 1 played from a particular
 * release in German at 1080p, episode 2 should come from the same release in the same language —
 * not from whatever happened to sort first. [continuityScore] ranks candidates against a profile of
 * what is currently playing.
 *
 * The weights encode that intent and are not arbitrary: **release identity outranks quality**.
 * Source type (40), provider (35) and binge group (30) dominate, because staying inside the same
 * release is what keeps audio tracks, subtitles and file naming consistent. Quality is only 10 —
 * dropping from 1080p to 720p within the same release is far less jarring than jumping to a
 * different release at the same resolution. Language overlap is worth 20 *per matched language*,
 * which is the one thing a viewer notices immediately.
 *
 * Moved verbatim from `PlayerViewModel`.
 */
object DebridSourceScoring {

    /**
     * Strip a trailing `_<n>` so the same release keeps one identity across episodes — providers
     * append a per-file index that would otherwise make every episode look like a new source.
     */
    fun stableIdentity(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return id.trim()
            .replace(Regex("_\\d+$"), "")
            .lowercase()
            .takeIf { it.isNotBlank() }
    }

    fun continuityScore(source: MovieSource, profile: DebridSourceProfile?): Int {
        if (profile == null) return 0
        return identityScore(source, profile) +
            languageScore(source, profile) +
            playabilityScore(source, profile)
    }

    /** Same release, same provider, same group — the part that keeps a binge consistent. */
    private fun identityScore(source: MovieSource, profile: DebridSourceProfile): Int {
        var score = 0
        score += matchBonus(source.sourceType, profile.sourceType, 40)
        score += matchBonus(source.provider, profile.provider, 35)
        score += matchBonus(source.bingeGroup, profile.bingeGroup, 30)
        score += matchBonus(source.sourceName, profile.sourceName, 15)
        score += matchBonus(source.quality, profile.quality, 10)
        return score
    }

    /** An unset field on the profile is "no preference", not a mismatch — it scores nothing. */
    private fun matchBonus(candidate: String?, wanted: String?, points: Int): Int =
        if (!wanted.isNullOrBlank() && candidate.equals(wanted, ignoreCase = true)) points else 0

    /** The difference a viewer hears immediately, so it is weighted per matched language. */
    private fun languageScore(source: MovieSource, profile: DebridSourceProfile): Int {
        val requested = normalizeLanguageSet(profile.languages)
        if (requested.isEmpty()) return 0
        val candidate = normalizeLanguageSet(source.languages)
        var score = requested.intersect(candidate).size * 20
        if (candidate.contains("multi")) score += 8
        return score
    }

    /** Prefer a source that will actually start: already direct, and already cached. */
    private fun playabilityScore(source: MovieSource, profile: DebridSourceProfile): Int {
        var score = 0
        if (profile.directPlayback && isDirectPlayableUrl(source.stream.direct_source)) score += 20
        if (SourceFilterUtils.isPlaybackReady(source)) score += 12
        return score
    }

    /**
     * Providers write languages every way imaginable — `"German, English"`, `"ger/eng"`,
     * `"Dual Audio"`. This folds them onto comparable codes so an overlap check means something.
     */
    fun normalizeLanguageSet(languages: List<String>?): Set<String> {
        return languages.orEmpty()
            .flatMap { it.split(',', '/', '|', '+', '&') }
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .map {
                when (it) {
                    "hindi", "hin" -> "hi"
                    "german", "deu", "ger" -> "de"
                    "english", "eng" -> "en"
                    "multi", "dual audio", "dual" -> "multi"
                    else -> it
                }
            }
            .toSet()
    }

    fun qualityScore(quality: String?): Int {
        return when {
            quality?.contains("2160", ignoreCase = true) == true || quality?.contains("4K", ignoreCase = true) == true -> 4
            quality?.contains("1080", ignoreCase = true) == true -> 3
            quality?.contains("720", ignoreCase = true) == true -> 2
            quality?.contains("480", ignoreCase = true) == true -> 1
            else -> 0
        }
    }

    /** Playable as-is: an http(s) file rather than a `.torrent` that still needs resolving. */
    fun isDirectPlayableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http", ignoreCase = true) && !url.endsWith(".torrent", ignoreCase = true)
    }

    /** Snapshot of what is playing now, used to keep the next episode on the same release. */
    fun profileOf(source: MovieSource, directPlayback: Boolean): DebridSourceProfile =
        DebridSourceProfile(
            provider = source.provider,
            sourceType = source.sourceType,
            sourceName = source.sourceName,
            languages = source.languages,
            quality = source.quality,
            streamId = source.stream.stream_id,
            bingeGroup = source.bingeGroup,
            fileIdx = source.fileIdx,
            directPlayback = directPlayback
        )
}
