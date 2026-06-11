package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.google.gson.JsonObject
import com.tvonnet.debridxtreamiptv.data.debrid.api.TorBoxApiService
import com.tvonnet.debridxtreamiptv.data.prefs.TorBoxPreferences
import com.tvonnet.debridxtreamiptv.data.repository.AddableTorrentIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class TorBoxSourceActionStatus {
    QUEUED,
    CACHING,
    READY,
    FAILED
}

data class TorBoxSourceActionResult(
    val status: TorBoxSourceActionStatus,
    val torrentId: Long? = null
)

@Singleton
class TorBoxSourceActionRepository internal constructor(
    private val service: TorBoxApiService,
    private val preferences: TorBoxPreferences,
    private val requestPacer: TorBoxRequestPacer
) {
    @Inject
    constructor(
        service: TorBoxApiService,
        preferences: TorBoxPreferences
    ) : this(service, preferences, TorBoxRequestPacer())

    private val sessionResults = ConcurrentHashMap<String, SessionEntry>()
    private val enqueueMutex = Mutex()

    suspend fun enqueue(identity: AddableTorrentIdentity): TorBoxSourceActionResult {
        return enqueueMutex.withLock {
            var context = identityContext(identity) ?: return@withLock failed()
            sessionResults[context.sessionKey]
                ?.result
                ?.takeIf { it.torrentId != null }
                ?.let { return@withLock it }

            requestPacer.awaitEnqueueSlot()
            context = identityContext(identity) ?: return@withLock failed()
            sessionResults[context.sessionKey]
                ?.result
                ?.takeIf { it.torrentId != null }
                ?.let { return@withLock it }

            val result = runCatching {
                val response = service.createTorrent(context.magnet.toRequestBody(TEXT_PLAIN))
                val body = response.body()
                val torrentId = body
                    ?.takeIf { response.isSuccessful && it.booleanOrFalse("success") }
                    ?.objectOrNull("data")
                    ?.longOrNull("torrent_id")
                    ?: return@runCatching failed()
                TorBoxSourceActionResult(TorBoxSourceActionStatus.QUEUED, torrentId)
            }.getOrElse { failed() }

            sessionResults[context.sessionKey] = SessionEntry(context.tokenFingerprint, result)
            result
        }
    }

    suspend fun enqueueOrRefresh(identity: AddableTorrentIdentity): TorBoxSourceActionResult {
        val existing = peek(identity)
        return when {
            existing?.status == TorBoxSourceActionStatus.READY -> existing
            existing?.torrentId != null -> refreshStatus(existing.torrentId)
            else -> enqueue(identity)
        }
    }

    suspend fun refresh(identity: AddableTorrentIdentity): TorBoxSourceActionResult {
        val existing = peek(identity) ?: return failed()
        val torrentId = existing.torrentId ?: return existing
        return refreshStatus(torrentId)
    }

    fun peek(identity: AddableTorrentIdentity): TorBoxSourceActionResult? {
        val context = identityContext(identity) ?: return null
        return sessionResults[context.sessionKey]?.result
    }

    suspend fun refreshStatus(torrentId: Long): TorBoxSourceActionResult {
        requestPacer.awaitRefreshSlot()
        val tokenFingerprint = preferences.getToken()
            ?.takeIf { it.isNotBlank() }
            ?.let(::sha256)
            ?: return failed(torrentId)

        val result = runCatching {
            val response = service.getTorrentStatus(torrentId)
            val data = response.body()
                ?.takeIf { response.isSuccessful && it.booleanOrFalse("success") }
                ?.objectOrNull("data")
                ?: return@runCatching failed(torrentId)

            TorBoxSourceActionResult(mapStatus(data), torrentId)
        }.getOrElse { failed(torrentId) }

        sessionResults.replaceAll { _, existing ->
            if (existing.tokenFingerprint == tokenFingerprint && existing.result.torrentId == torrentId) {
                existing.copy(result = result)
            } else {
                existing
            }
        }
        return result
    }

    private fun identityContext(identity: AddableTorrentIdentity): IdentityContext? {
        val token = preferences.getToken() ?: return null
        val normalizedHash = normalizeInfoHash(identity.infoHash)
            ?: extractInfoHash(identity.magnet)
            ?: return null
        val magnet = validatedMagnet(identity.magnet, normalizedHash)
            ?: canonicalMagnet(normalizedHash)
        val tokenFingerprint = sha256(token)
        val sessionKey = sha256("$tokenFingerprint|$normalizedHash")
        return IdentityContext(sessionKey, tokenFingerprint, magnet)
    }

    private fun mapStatus(data: JsonObject): TorBoxSourceActionStatus {
        val downloadState = data.stringOrNull("download_state")?.lowercase() ?: return TorBoxSourceActionStatus.FAILED
        val isReady = downloadState == "cached" &&
            data.booleanOrFalse("cached") &&
            data.booleanOrFalse("download_present") &&
            data.booleanOrFalse("download_finished") &&
            data.doubleOrNull("progress") == 1.0

        if (isReady) return TorBoxSourceActionStatus.READY

        return when (downloadState) {
            "downloading",
            "uploading",
            "metadl",
            "checkingresumedata",
            "stalled (no seeds)",
            "paused",
            "completed",
            "cached" -> TorBoxSourceActionStatus.CACHING
            else -> TorBoxSourceActionStatus.FAILED
        }
    }

    private fun validatedMagnet(magnet: String?, normalizedHash: String): String? {
        val value = magnet?.trim()?.takeIf { it.startsWith("magnet:?", ignoreCase = true) } ?: return null
        return value.takeIf { extractInfoHash(it) == normalizedHash }
    }

    private fun canonicalMagnet(normalizedHash: String): String {
        return "magnet:?xt=urn:btih:$normalizedHash"
    }

    private fun normalizeInfoHash(infoHash: String?): String? {
        val normalized = infoHash?.trim() ?: return null
        return normalized.takeIf { INFO_HASH.matches(it) }?.lowercase()
    }

    private fun extractInfoHash(magnet: String?): String? {
        val value = magnet?.trim()?.takeIf { it.startsWith("magnet:?", ignoreCase = true) } ?: return null
        return MAGNET_INFO_HASH.find(value)?.groupValues?.getOrNull(1)?.lowercase()
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun failed(torrentId: Long? = null) = TorBoxSourceActionResult(TorBoxSourceActionStatus.FAILED, torrentId)

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        return get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.booleanOrFalse(name: String): Boolean {
        return runCatching { get(name)?.asBoolean }.getOrNull() == true
    }

    private fun JsonObject.longOrNull(name: String): Long? {
        return runCatching { get(name)?.asLong }.getOrNull()
    }

    private fun JsonObject.doubleOrNull(name: String): Double? {
        return runCatching { get(name)?.asDouble }.getOrNull()
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        return runCatching { get(name)?.asString }.getOrNull()
    }

    private data class IdentityContext(
        val sessionKey: String,
        val tokenFingerprint: String,
        val magnet: String
    )

    private data class SessionEntry(
        val tokenFingerprint: String,
        val result: TorBoxSourceActionResult
    )

    private companion object {
        val TEXT_PLAIN = "text/plain".toMediaType()
        val INFO_HASH = Regex("^(?:[a-fA-F0-9]{40}|[a-zA-Z2-7]{32})$")
        val MAGNET_INFO_HASH = Regex("(?i)(?:^|[?&])xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})(?:&|$)")
    }
}

internal class TorBoxRequestPacer(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { waitMs -> delay(waitMs) },
    private val enqueueIntervalMs: Long = 6_500L,
    private val refreshIntervalMs: Long = 250L
) {
    private val enqueueMutex = Mutex()
    private val refreshMutex = Mutex()
    private var lastEnqueueAtMs: Long? = null
    private var lastRefreshAtMs: Long? = null

    suspend fun awaitEnqueueSlot() {
        enqueueMutex.withLock {
            lastEnqueueAtMs = awaitSlot(lastEnqueueAtMs, enqueueIntervalMs)
        }
    }

    suspend fun awaitRefreshSlot() {
        refreshMutex.withLock {
            lastRefreshAtMs = awaitSlot(lastRefreshAtMs, refreshIntervalMs)
        }
    }

    private suspend fun awaitSlot(previousAtMs: Long?, intervalMs: Long): Long {
        if (previousAtMs != null) {
            val waitMs = (intervalMs - (nowMs() - previousAtMs)).coerceAtLeast(0L)
            if (waitMs > 0L) sleep(waitMs)
        }
        return nowMs()
    }
}
