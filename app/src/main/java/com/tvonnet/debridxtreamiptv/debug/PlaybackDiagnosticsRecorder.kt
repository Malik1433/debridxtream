package com.tvonnet.debridxtreamiptv.debug

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Debug
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
    private const val SCHEMA_VERSION = 2

    // G1 (2026-09-03): bounded on disk. A 3-hour Live session used to grow one file without
    // limit; now a session file rotates at [MAX_SESSION_FILE_BYTES] (its summary goes into the
    // `session_rotated` event) and the directory is pruned oldest-first past [MAX_FILES] /
    // [MAX_DIRECTORY_BYTES] whenever a new session file is opened.
    internal const val MAX_SESSION_FILE_BYTES = 2L * 1024 * 1024
    internal const val MAX_FILES = 30
    internal const val MAX_DIRECTORY_BYTES = 24L * 1024 * 1024
    internal const val MEMORY_SAMPLE_INTERVAL_MS = 30_000L
    private const val BYTES_PER_MB = 1024L * 1024

    private val lock = Any()
    private var sessionId: String? = null
    private var sessionFile: File? = null
    private var sessionSalt: ByteArray? = null
    private var sessionStartMs: Long = 0L
    private var eventSeq: Long = 0L
    private val countsByType = HashMap<String, Int>()
    private var peakJavaHeapMb: Long = 0L
    private var lastMemorySampleMs: Long = 0L

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
            // The finish reason is a code-set lifecycle constant ("activity_destroyed"), never
            // provider data - sanitizeReason() would flatten every one of them to UNKNOWN.
            val reasonCode = reason.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_").take(40)
            writeEvent(file, "session_finished", summaryFields() + mapOf("reasonCode" to reasonCode))
            resetSession(keepSalt = false)
        }
    }

    /**
     * A memory data point into the same timeline, at most once per [MEMORY_SAMPLE_INTERVAL_MS].
     * Called from the stall monitor's 3-second tick, so a long session yields the growth curve
     * the retained-player OOM question (roadmap B2.5 / G1) was waiting for. No-op when the
     * recorder is off or no session is open - it never opens one by itself.
     */
    fun maybeRecordMemorySample(context: Context) {
        if (!isEnabled(context)) return
        synchronized(lock) {
            val file = sessionFile ?: return
            val now = System.currentTimeMillis()
            if (now - lastMemorySampleMs < MEMORY_SAMPLE_INTERVAL_MS) return
            lastMemorySampleMs = now
            val runtime = Runtime.getRuntime()
            val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
            if (javaUsedMb > peakJavaHeapMb) peakJavaHeapMb = javaUsedMb
            val info = ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
            }
            writeEvent(
                file,
                "memory_sample",
                mapOf(
                    "javaHeapUsedMb" to javaUsedMb,
                    "javaHeapMaxMb" to runtime.maxMemory() / BYTES_PER_MB,
                    "nativeHeapMb" to Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB,
                    "systemAvailMb" to info.availMem / BYTES_PER_MB,
                    "systemLowMemory" to info.lowMemory
                )
            )
        }
    }

    /** What a `session_finished` / `session_rotated` event carries, so no reader has to re-count. */
    private fun summaryFields(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        return mapOf(
            "durationMs" to if (sessionStartMs > 0L) now - sessionStartMs else 0L,
            "eventCount" to eventSeq,
            "stallWarnings" to (countsByType["stall_warning"] ?: 0),
            "stalls" to (countsByType["stall_triggered"] ?: 0),
            "retries" to (countsByType["retry_triggered"] ?: 0),
            "playerErrors" to (countsByType["player_error"] ?: 0),
            "firstFrames" to (countsByType["first_frame_rendered"] ?: 0),
            "releases" to (countsByType["release_player"] ?: 0),
            "memorySamples" to (countsByType["memory_sample"] ?: 0),
            "peakJavaHeapMb" to peakJavaHeapMb,
            "countsByType" to countsByType.toMap() // a Map: sanitizeValue turns it into a JSON object (a JSONObject would be stringified)
        )
    }

    private fun resetSession(keepSalt: Boolean) {
        sessionId = null
        sessionFile = null
        if (!keepSalt) sessionSalt = null
        sessionStartMs = 0L
        eventSeq = 0L
        countsByType.clear()
        peakJavaHeapMb = 0L
        lastMemorySampleMs = 0L
    }

    /**
     * Oldest-first, until the directory has room for the file about to be opened: after this
     * plus one new session file, the count is <= [MAX_FILES] and the bytes <= [MAX_DIRECTORY_BYTES].
     */
    private fun pruneDirectory(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("session-") && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (files.isNotEmpty() && (files.size >= MAX_FILES || total > MAX_DIRECTORY_BYTES)) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            runCatching { oldest.delete() }
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
        pruneDirectory(dir)

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
        countsByType.clear()
        peakJavaHeapMb = 0L
        lastMemorySampleMs = 0L
        writeEvent(file, "session_started", mapOf("directory" to DIRECTORY_NAME, "maxFileBytes" to MAX_SESSION_FILE_BYTES))
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
        countsByType[eventType] = (countsByType[eventType] ?: 0) + 1
        runCatching {
            file.appendText(json.toString() + "\n")
        }
        // Rotation: the cap is checked AFTER the write so the closing event always lands in
        // the file it summarises. The next record() opens a fresh file; the salt is kept so
        // identifier fingerprints stay comparable across the two halves of one viewing.
        if (eventType != "session_rotated" && file.length() >= MAX_SESSION_FILE_BYTES) {
            writeEvent(file, "session_rotated", summaryFields())
            resetSession(keepSalt = true)
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
                // QA fix: only echo KNOWN media/manifest extensions — a credential
                // segment containing a dot ("abc.tv") must not leak as "{file}.tv".
                segment.contains(".") -> segment.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase(Locale.US)
                    .takeIf { it in SAFE_FILE_EXTENSIONS }
                    ?.let { "{file}.$it" }
                    ?: "{seg}"
                // LP-D-4: short opaque segments in Xtream URLs are /user/pass/ —
                // only echo known structural path words, redact everything else.
                segment.lowercase(Locale.US) in SAFE_PATH_SEGMENTS -> segment
                else -> "{seg}"
            }
        }
    }

    private val SAFE_PATH_SEGMENTS = setOf(
        "live", "movie", "movies", "series", "vod", "stream", "streams", "hls",
        "dash", "playlist", "resolve", "proxy", "api", "watch", "play", "video"
    )

    private val SAFE_FILE_EXTENSIONS = setOf(
        "ts", "m3u8", "mpd", "mp4", "mkv", "avi", "mov", "m2ts", "wmv", "flv",
        "webm", "m4v", "php", "srt", "vtt", "ass"
    )

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
