package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridResolutionException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridRateLimiter @Inject constructor() {
    private val mutex = Mutex()
    private val sourceCooldowns = ConcurrentHashMap<String, Cooldown>()
    private var nextAllowedAtMillis: Long = 0L
    private var globalCooldownUntilMillis: Long = 0L

    suspend fun awaitPermit() {
        val delayMs = mutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (nextAllowedAtMillis - now).coerceAtLeast(0L)
            if (waitMs == 0L) {
                nextAllowedAtMillis = now + MIN_REQUEST_INTERVAL_MS
            }
            waitMs
        }
        if (delayMs > 0L) {
            delay(delayMs)
            awaitPermit()
        }
    }

    fun globalCooldown(): DebridResolutionException? {
        val remainingMs = globalCooldownUntilMillis - System.currentTimeMillis()
        if (remainingMs <= 0L) return null
        val remainingSeconds = (remainingMs + 999L) / 1000L
        return DebridResolutionException(
            type = DebridFailureType.RATE_LIMITED,
            message = "Real-Debrid rate limit active. Wait about ${remainingSeconds}s before trying more sources.",
            retryAfterSeconds = remainingSeconds
        )
    }

    fun sourceCooldown(key: String?): DebridResolutionException? {
        val normalized = key?.takeIf { it.isNotBlank() } ?: return null
        val cooldown = sourceCooldowns[normalized] ?: return null
        if (cooldown.untilMillis <= System.currentTimeMillis()) {
            sourceCooldowns.remove(normalized)
            return null
        }
        return cooldown.error
    }

    fun recordFailure(key: String?, error: DebridResolutionException) {
        if (error.type == DebridFailureType.RATE_LIMITED) {
            val seconds = error.retryAfterSeconds?.coerceAtLeast(DEFAULT_RATE_LIMIT_SECONDS)
                ?: DEFAULT_RATE_LIMIT_SECONDS
            globalCooldownUntilMillis = System.currentTimeMillis() + seconds * 1000L
        }

        val normalized = key?.takeIf { it.isNotBlank() } ?: return
        val ttlMs = when (error.type) {
            DebridFailureType.RATE_LIMITED -> (error.retryAfterSeconds?.coerceAtLeast(DEFAULT_RATE_LIMIT_SECONDS)
                ?: DEFAULT_RATE_LIMIT_SECONDS) * 1000L
            DebridFailureType.LEGAL_RESTRICTION,
            DebridFailureType.COPYRIGHT_BLOCKED -> BLOCKED_SOURCE_TTL_MS
            DebridFailureType.NOT_CACHED,
            DebridFailureType.UNAVAILABLE -> UNAVAILABLE_SOURCE_TTL_MS
            DebridFailureType.AUTH_REQUIRED -> DEFAULT_RATE_LIMIT_SECONDS * 1000L
            DebridFailureType.NETWORK,
            DebridFailureType.UNKNOWN -> return
        }
        sourceCooldowns[normalized] = Cooldown(System.currentTimeMillis() + ttlMs, error)
    }

    private data class Cooldown(
        val untilMillis: Long,
        val error: DebridResolutionException
    )

    companion object {
        private const val MIN_REQUEST_INTERVAL_MS = 1200L
        private const val DEFAULT_RATE_LIMIT_SECONDS = 120L
        private const val BLOCKED_SOURCE_TTL_MS = 30L * 60L * 1000L
        private const val UNAVAILABLE_SOURCE_TTL_MS = 5L * 60L * 1000L
    }
}
