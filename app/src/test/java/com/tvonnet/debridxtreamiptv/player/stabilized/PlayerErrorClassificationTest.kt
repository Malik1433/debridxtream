package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the playback-error taxonomy extracted in P16. The fail-fast rule is a
 * shipped bug fix: without it a dead Xtream listing looped the "RECONNECTING…"
 * banner until the whole retry budget drained.
 */
class PlayerErrorClassificationTest {

    @Test
    fun `definitive 4xx is terminal for VOD`() {
        listOf(400, 401, 403, 404, 405, 410, 451).forEach { code ->
            assertTrue(
                "HTTP $code should be terminal",
                isTerminalHttpForNonLive(code, ContentType.MOVIE, PlaybackSource.IPTV)
            )
        }
    }

    @Test
    fun `live TV never fails fast on HTTP — a blip there is transient`() {
        listOf(403, 404, 500, 503).forEach { code ->
            assertFalse(
                "live must keep retrying on HTTP $code",
                isTerminalHttpForNonLive(code, ContentType.LIVE_TV, PlaybackSource.IPTV)
            )
        }
    }

    @Test
    fun `a 5xx is a dead listing for IPTV but a retryable blip for debrid`() {
        assertTrue(isTerminalHttpForNonLive(500, ContentType.MOVIE, PlaybackSource.IPTV))
        assertTrue(isTerminalHttpForNonLive(503, ContentType.EPISODE, PlaybackSource.IPTV))

        assertFalse(isTerminalHttpForNonLive(500, ContentType.MOVIE, PlaybackSource.DEBRID))
        assertFalse(isTerminalHttpForNonLive(503, ContentType.EPISODE, PlaybackSource.DEBRID))
    }

    @Test
    fun `429 is never terminal here — it gets a cool-off instead`() {
        assertFalse(isTerminalHttpForNonLive(429, ContentType.MOVIE, PlaybackSource.IPTV))
        assertFalse(isTerminalHttpForNonLive(429, ContentType.MOVIE, PlaybackSource.DEBRID))
    }

    @Test
    fun `a non-HTTP error carries no response code and is not terminal`() {
        assertFalse(isTerminalHttpForNonLive(null, ContentType.MOVIE, PlaybackSource.IPTV))
        assertFalse(isTerminalHttpForNonLive(null, ContentType.LIVE_TV, PlaybackSource.DEBRID))
    }

    @Test
    fun `rate-limit cool-off is for plain IPTV only`() {
        assertTrue(isRateLimitCoolOff(429, directDebridPlayback = false))
        assertFalse("direct debrid treats 429 as terminal", isRateLimitCoolOff(429, directDebridPlayback = true))
        assertFalse(isRateLimitCoolOff(503, directDebridPlayback = false))
        assertFalse(isRateLimitCoolOff(null, directDebridPlayback = false))
    }

    @Test
    fun `live gets its own retry allowance`() {
        assertEquals(8, maxRetriesFor(ContentType.LIVE_TV, liveMaxRetries = 8, defaultMaxRetries = 5))
        assertEquals(5, maxRetriesFor(ContentType.MOVIE, liveMaxRetries = 8, defaultMaxRetries = 5))
        assertEquals(5, maxRetriesFor(ContentType.EPISODE, liveMaxRetries = 8, defaultMaxRetries = 5))
        assertEquals(5, maxRetriesFor(null, liveMaxRetries = 8, defaultMaxRetries = 5))
    }
}
