package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-session-only ordering hints for volatile direct addon/proxy sources.
 * Keys intentionally retain no raw URLs, headers, magnets, hashes, or credentials.
 */
object SessionSourcePreference {
    private const val SUCCESS_EXACT_BOOST = 100
    private const val SUCCESS_PATTERN_BOOST = 20
    private const val FAILURE_EXACT_DEMOTION = -100
    private const val FAILURE_PATTERN_DEMOTION = -20
    private const val MAX_ABSOLUTE_SCORE = 300
    private val SAFE_PATH_LABELS = setOf("api", "download", "file", "files", "hls", "play", "playback", "proxy", "stream", "video")
    private val SAFE_MEDIA_EXTENSIONS = setOf("m3u8", "mkv", "mp4", "mpd", "ts", "webm")

    private val exactScores = ConcurrentHashMap<SourcePreferenceKey, Int>()
    private val patternScores = ConcurrentHashMap<SourcePatternKey, Int>()

    fun score(source: MovieSource): Int {
        val key = buildKey(
            provider = source.provider ?: source.sourceName,
            sourceType = source.sourceType,
            languages = source.languages,
            quality = source.quality,
            url = source.stream.direct_source
        ) ?: return 0
        return exactScores[key].orZero() + patternScores[key.toPatternKey()].orZero()
    }

    fun recordFirstFrame(
        provider: String?,
        sourceType: String?,
        languages: List<String>?,
        quality: String?,
        url: String?
    ) {
        record(
            buildKey(provider, sourceType, languages, quality, url),
            SUCCESS_EXACT_BOOST,
            SUCCESS_PATTERN_BOOST
        )
    }

    fun recordFailure(
        provider: String?,
        sourceType: String?,
        languages: List<String>?,
        quality: String?,
        url: String?
    ) {
        record(
            buildKey(provider, sourceType, languages, quality, url),
            FAILURE_EXACT_DEMOTION,
            FAILURE_PATTERN_DEMOTION
        )
    }

    private fun record(key: SourcePreferenceKey?, exactDelta: Int, patternDelta: Int) {
        if (key == null) return
        exactScores.merge(key, exactDelta, ::boundedSum)
        patternScores.merge(key.toPatternKey(), patternDelta, ::boundedSum)
    }

    private fun boundedSum(current: Int, delta: Int): Int {
        return (current + delta).coerceIn(-MAX_ABSOLUTE_SCORE, MAX_ABSOLUTE_SCORE)
    }

    private fun buildKey(
        provider: String?,
        sourceType: String?,
        languages: List<String>?,
        quality: String?,
        url: String?
    ): SourcePreferenceKey? {
        val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        return SourcePreferenceKey(
            provider = normalize(provider),
            sourceType = normalize(sourceType),
            languages = languages.orEmpty().map(::normalize).filter { it.isNotEmpty() }.distinct().sorted(),
            quality = normalize(quality),
            host = host,
            pathShape = normalizePathShape(uri.path),
            urlFingerprint = shortDigest(normalizedUrl)
        )
    }

    private fun normalize(value: String?): String {
        return value?.trim()?.lowercase(Locale.US).orEmpty()
    }

    private fun normalizePathShape(path: String?): String {
        val segments = path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .map { segment ->
                val normalized = segment.lowercase(Locale.US)
                val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
                when {
                    segment.all(Char::isDigit) -> "{n}"
                    normalized in SAFE_PATH_LABELS -> normalized
                    extension in SAFE_MEDIA_EXTENSIONS -> "{file}.$extension"
                    else -> "{segment}"
                }
            }
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }

    private fun shortDigest(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private data class SourcePreferenceKey(
        val provider: String,
        val sourceType: String,
        val languages: List<String>,
        val quality: String,
        val host: String,
        val pathShape: String,
        val urlFingerprint: String
    ) {
        fun toPatternKey(): SourcePatternKey {
            return SourcePatternKey(provider, sourceType, languages, quality, host, pathShape)
        }
    }

    private data class SourcePatternKey(
        val provider: String,
        val sourceType: String,
        val languages: List<String>,
        val quality: String,
        val host: String,
        val pathShape: String
    )
}
