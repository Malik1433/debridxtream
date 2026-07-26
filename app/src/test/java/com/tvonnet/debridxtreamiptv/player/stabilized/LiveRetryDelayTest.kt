package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the load-error retry policy that protects a **max_connections=1** provider — the behaviour
 * the long-pending "429-storm soak" was meant to observe by hand.
 *
 * The soak existed because hammering a rate-limited provider is exactly how this app used to earn a
 * deeper ban: standard fast backoff retries a 429 within a second, and the WAF extends the block. The
 * two rules that prevent it are asserted here instead:
 *  - a permanently-broken source (401/403/404/410/416/451) must NOT be retried at all, so app-level
 *    fallback runs immediately;
 *  - a 429 must cool off hard, honouring `Retry-After` when the provider sends one.
 */
class LiveRetryDelayTest {

    // ── Fail fast: never retry a dead source ────────────────────────────────────────────────────

    @Test
    fun `permanently broken sources are never retried`() {
        listOf(401, 403, 404, 410, 416, 451).forEach { code ->
            assertEquals(
                "HTTP $code must fail fast, not retry",
                C.TIME_UNSET,
                liveRetryDelayMsFor(responseCode = code, retryAfterSec = null, errorCount = 1)
            )
        }
    }

    // ── 429: cool off hard, never hammer ────────────────────────────────────────────────────────

    @Test
    fun `429 without Retry-After cools off for 20 seconds`() {
        assertEquals(
            20_000L,
            liveRetryDelayMsFor(responseCode = 429, retryAfterSec = null, errorCount = 1)
        )
    }

    @Test
    fun `429 honours the provider's Retry-After`() {
        assertEquals(
            5_000L,
            liveRetryDelayMsFor(responseCode = 429, retryAfterSec = 5, errorCount = 1)
        )
    }

    @Test
    fun `Retry-After is clamped so it can neither hammer nor hang the session`() {
        // 0s would be an instant retry into the ban…
        assertEquals(
            1_000L,
            liveRetryDelayMsFor(responseCode = 429, retryAfterSec = 0, errorCount = 1)
        )
        // …and an hour would strand the viewer on a dead screen.
        assertEquals(
            60_000L,
            liveRetryDelayMsFor(responseCode = 429, retryAfterSec = 3_600, errorCount = 1)
        )
    }

    @Test
    fun `a 429 storm never backs off FASTER as errors pile up`() {
        // The regression that caused the ban: exponential backoff treating 429 like any other error.
        val delays = (1..6).map { liveRetryDelayMsFor(429, retryAfterSec = null, errorCount = it) }
        assertTrue("every 429 retry must wait at least 20s, got $delays", delays.all { it >= 20_000L })
    }

    // ── Everything else: capped exponential backoff ─────────────────────────────────────────────

    @Test
    fun `transient errors back off exponentially and then cap`() {
        assertEquals(1_000L, liveRetryDelayMsFor(500, null, errorCount = 1))
        assertEquals(2_000L, liveRetryDelayMsFor(500, null, errorCount = 2))
        assertEquals(4_000L, liveRetryDelayMsFor(500, null, errorCount = 3))
        assertEquals(8_000L, liveRetryDelayMsFor(500, null, errorCount = 4))
        // Capped — a live viewer must not wait minutes for a reconnect.
        assertEquals(8_000L, liveRetryDelayMsFor(500, null, errorCount = 9))
    }

    @Test
    fun `a non-HTTP failure still backs off instead of failing fast`() {
        // responseCode is null when the load failed without an HTTP status (socket/DNS/timeout).
        assertEquals(1_000L, liveRetryDelayMsFor(responseCode = null, retryAfterSec = null, errorCount = 1))
    }
}
