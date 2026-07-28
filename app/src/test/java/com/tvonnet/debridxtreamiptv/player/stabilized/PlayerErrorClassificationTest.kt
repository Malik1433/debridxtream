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

    // ── an aged-out debrid link is repairable, not terminal ────────────────────
    //
    // The rule that lets resume follow the standard shape: play the link you have, and mint a new
    // one only when it actually dies. While these codes were terminal a dead link was unrecoverable,
    // which is precisely why every resume re-resolved before the first frame instead.

    private fun repairable(
        code: Int?,
        source: PlaybackSource = PlaybackSource.DEBRID,
        identity: Boolean = true,
        direct: Boolean = false
    ) = isRepairableExpiredDebridLink(code, source, identity, direct)

    @Test
    fun `the codes that mean the link is no longer yours are repairable`() {
        listOf(401, 403, 410).forEach { code ->
            assertTrue("HTTP $code should be repairable", repairable(code))
        }
    }

    @Test
    fun `404, 429 and 451 stay terminal - a new link does not fix any of them`() {
        // 404: the addon proxy is saying the file is not there. 429: re-resolving into a rate limit
        // deepens it, and it has its own cool-off. 451: a legal block; no link will be minted.
        listOf(404, 429, 451, 500, 503, null).forEach { code ->
            assertFalse("HTTP $code must not be treated as an expired link", repairable(code))
        }
    }

    @Test
    fun `only debrid links are repairable this way`() {
        // An Xtream URL does not expire and there is no metadata to mint a new one from.
        assertFalse(repairable(403, source = PlaybackSource.IPTV))
    }

    @Test
    fun `without a route back to a fresh link there is nothing to repair`() {
        // No infoHash/magnet and no re-resolvable direct source: retrying the same dead URL would
        // only burn the reconnect budget, so failing fast is correct.
        assertFalse(repairable(403, identity = false, direct = false))
    }

    @Test
    fun `either route back is enough`() {
        assertTrue("a durable infoHash or magnet is a route", repairable(403, identity = true, direct = false))
        assertTrue("a re-resolvable direct source is a route", repairable(403, identity = false, direct = true))
    }
}
