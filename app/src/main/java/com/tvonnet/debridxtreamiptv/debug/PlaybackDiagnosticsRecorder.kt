package com.tvonnet.debridxtreamiptv.debug

import android.content.Context
import android.net.Uri
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object PlaybackDiagnosticsRecorder {
    private const val DIRECTORY_NAME = "playback-diagnostics"
    private const val ENABLE_MARKER = ".enabled"
    private const val SCHEMA_VERSION = 1

    private val lock = Any()
    private var sessionId: String? = null
    private var sessionFile: File? = null
    private var sessionSalt: ByteArray? = null
    private var sessionStartMs: Long = 0L
    private var eventSeq: Long = 0L

    fun record(context: Context, eventType: String, fields: Map<String, Any?> = emptyMap()) {
        if (!isEnabled(context)) return
        synchronized(lock) {
            val file = ensureSessionFile(context) ?: return
            writeEvent(file, eventType, fields)
        }
    }

    fun finishSession(context: Context, reason: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            val file = sessionFile ?: return
            writeEvent(file, "session_finished", mapOf("reasonCode" to sanitizeReason(reason)))
            sessionId = null
            sessionFile = null
            sessionSalt = null
            sessionStartMs = 0L
            eventSeq = 0L
        }
    }

    fun sourceListFields(context: Context, sources: List<MovieSource>): Map<String, Any?> {
        if (!BuildConfig.DEBUG) return emptyMap()
        return mapOf(
            "sourceCount" to sources.size,
            "cacheStatusCounts" to sources.groupingBy { it.cacheStatus.name }.eachCount(),
            "providerCounts" to sources.groupingBy { it.provider ?: "unknown" }.eachCount(),
            "languageCounts" to sources
                .flatMap { it.languages.orEmpty().ifEmpty { listOf("unknown") } }
                .groupingBy { it.lowercase(Locale.US) }
                .eachCount(),
            "streamIdFingerprints" to sources.mapNotNull { fingerprint(it.stream.stream_id) }.take(50)
        )
    }

    fun sourceFields(context: Context, source: MovieSource?): Map<String, Any?> {
        if (!BuildConfig.DEBUG || source == null) return emptyMap()
        return mapOf(
            "provider" to source.provider,
            "sourceType" to source.sourceType,
            "sourceName" to source.sourceName,
            "cacheStatus" to source.cacheStatus.name,
            "languages" to source.languages,
            "quality" to source.quality,
            "hasBingeGroup" to !source.bingeGroup.isNullOrBlank(),
            "bingeGroupFingerprint" to fingerprint(source.bingeGroup),
            "fileIdx" to source.fileIdx,
            "streamIdFingerprint" to fingerprint(source.stream.stream_id)
        ) + urlFields(context, source.stream.direct_source) + headerFields(source.headers)
    }

    fun contentFields(
        kind: String,
        tmdbId: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): Map<String, Any?> {
        if (!BuildConfig.DEBUG) return emptyMap()
        return mapOf(
            "contentKind" to kind,
            "tmdbId" to tmdbId,
            "imdbIdFingerprint" to fingerprint(imdbId),
            "season" to season,
            "episode" to episode
        )
    }

    fun playbackFields(
        context: Context,
        url: String?,
        headers: Map<String, String>?,
        playbackSource: String?,
        directDebridPlayback: Boolean,
        retryCount: Int? = null,
        timeoutMs: Long? = null,
        mimeHint: String? = null
    ): Map<String, Any?> {
        if (!BuildConfig.DEBUG) return emptyMap()
        return urlFields(context, url) + headerFields(headers) + mapOf(
            "playbackSource" to playbackSource,
            "directDebridPlayback" to directDebridPlayback,
            "retryCount" to retryCount,
            "timeoutMs" to timeoutMs,
            "mimeHint" to mimeHint
        )
    }

    fun urlFields(context: Context, url: String?): Map<String, Any?> {
        if (!BuildConfig.DEBUG || url.isNullOrBlank()) return emptyMap()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        return mapOf(
            "urlHost" to uri?.host,
            "urlPathShape" to pathShape(uri),
            "urlFingerprint" to fingerprint(url)
        )
    }

    fun headerFields(headers: Map<String, String>?): Map<String, Any?> {
        if (!BuildConfig.DEBUG) return emptyMap()
        val keys = headers.orEmpty().keys
        val userAgent = headers.orEmpty().entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        return mapOf(
            "hasUserAgent" to keys.any { it.equals("User-Agent", ignoreCase = true) },
            "userAgentProfile" to userAgentProfile(userAgent),
            "hasAccept" to keys.any { it.equals("Accept", ignoreCase = true) },
            "hasReferer" to keys.any { it.equals("Referer", ignoreCase = true) },
            "hasOrigin" to keys.any { it.equals("Origin", ignoreCase = true) },
            "hasAuthorization" to keys.any { it.equals("Authorization", ignoreCase = true) },
            "hasCookie" to keys.any { it.equals("Cookie", ignoreCase = true) },
            "hasRange" to keys.any { it.equals("Range", ignoreCase = true) }
        )
    }

    fun sanitizeReason(reason: String?): String {
        val normalized = reason
            ?.lowercase(Locale.US)
            ?.replace(Regex("https?://\\S+"), "url")
            ?.replace(Regex("magnet:\\S+"), "magnet")
            ?.replace(Regex("[a-f0-9]{32,}", RegexOption.IGNORE_CASE), "hash")
            ?: return "unknown"
        return when {
            "timeout" in normalized -> "TIMEOUT"
            "http 401" in normalized -> "HTTP_401"
            "http 403" in normalized -> "HTTP_403"
            "http 404" in normalized -> "HTTP_404"
            "http 410" in normalized -> "HTTP_410"
            "http 429" in normalized -> "HTTP_429"
            "http 451" in normalized -> "HTTP_451"
            "network" in normalized -> "NETWORK"
            "init" in normalized -> "INITIALIZATION"
            "failed" in normalized -> "FAILED"
            else -> "UNKNOWN"
        }
    }

    fun httpStatusCode(reason: String?): Int? {
        return Regex("""\bHTTP\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(reason.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun identityFingerprint(value: String?): String? = fingerprint(value)

    fun playerStateName(state: Int): String {
        return when (state) {
            1 -> "IDLE"
            2 -> "BUFFERING"
            3 -> "READY"
            4 -> "ENDED"
            else -> "UNKNOWN"
        }
    }

    private fun isEnabled(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val dir = context.getExternalFilesDir(DIRECTORY_NAME) ?: return false
        return File(dir, ENABLE_MARKER).exists()
    }

    private fun ensureSessionFile(context: Context): File? {
        val existing = sessionFile
        if (existing != null) return existing

        val dir = context.getExternalFilesDir(DIRECTORY_NAME) ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val id = "pbdiag-$timestamp-$shortId"
        val file = File(dir, "session-$timestamp-$shortId.jsonl")

        sessionId = id
        sessionFile = file
        sessionSalt = sessionSalt ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        sessionStartMs = System.currentTimeMillis()
        eventSeq = 0L
        writeEvent(file, "session_started", mapOf("directory" to DIRECTORY_NAME))
        return file
    }

    private fun writeEvent(file: File, eventType: String, fields: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionId", sessionId)
            .put("eventSeq", eventSeq++)
            .put("tsEpochMs", now)
            .put("elapsedMs", if (sessionStartMs > 0L) now - sessionStartMs else 0L)
            .put("eventType", eventType)
            .put("fields", JSONObject(sanitizeMap(fields)))
        runCatching {
            file.appendText(json.toString() + "\n")
        }
    }

    private fun sanitizeMap(fields: Map<String, Any?>): Map<String, Any?> {
        return fields.mapValues { (_, value) -> sanitizeValue(value) }
            .filterValues { it != null }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> value.take(160)
            is Number, is Boolean -> value
            is Map<*, *> -> JSONObject(value.entries.associate { (k, v) -> k.toString() to sanitizeValue(v) })
            is Iterable<*> -> JSONArray(value.mapNotNull { sanitizeValue(it) })
            is Array<*> -> JSONArray(value.mapNotNull { sanitizeValue(it) })
            else -> value.toString().take(160)
        }
    }

    private fun fingerprint(value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        val salt = sessionSalt ?: ByteArray(16).also {
            SecureRandom().nextBytes(it)
            sessionSalt = it
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(raw.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun pathShape(uri: Uri?): String? {
        val segments = uri?.pathSegments ?: return null
        if (segments.isEmpty()) return "/"
        return segments.joinToString(prefix = "/", separator = "/") { segment ->
            when {
                segment.length > 12 -> "{id}"
                segment.matches(Regex("tt\\d+", RegexOption.IGNORE_CASE)) -> "{imdb}"
                segment.matches(Regex("[a-fA-F0-9]{8,}")) -> "{hex}"
                segment.matches(Regex("\\d+")) -> "{num}"
                segment.contains(".") -> segment.substringAfterLast('.', missingDelimiterValue = "")
                    .takeIf { it.length in 2..5 }
                    ?.let { "{file}.$it" }
                    ?: "{file}"
                else -> segment
            }
        }
    }

    private fun userAgentProfile(userAgent: String?): String {
        val normalized = userAgent?.lowercase(Locale.US) ?: return "absent"
        return when {
            "stremio" in normalized -> "stremio"
            "debridxtream" in normalized -> "debridxtream"
            "mozilla" in normalized || "chrome" in normalized || "safari" in normalized -> "browser"
            else -> "other"
        }
    }
}
