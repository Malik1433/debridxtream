package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the direct-debrid bookkeeping extracted in P14: what counts as "the same
 * source failing again", when a fresh resolve is allowed, and which id identifies a
 * series for an episode lookup.
 */
class PlayerDebridSourceStateTest {

    @Test
    fun `context key is stable per url plus headers and ignores header order and case`() {
        val a = addonProxyContextKey("http://host/a", mapOf("Authorization" to "x", "Accept" to "y"))
        val b = addonProxyContextKey("http://host/a", mapOf("accept" to "y", "authorization" to "x"))
        assertEquals(a, b)
    }

    @Test
    fun `a new url or new headers is a different context`() {
        val base = addonProxyContextKey("http://host/a", mapOf("Authorization" to "x"))
        assertNotEquals(base, addonProxyContextKey("http://host/b", mapOf("Authorization" to "x")))
        assertNotEquals(base, addonProxyContextKey("http://host/a", mapOf("Authorization" to "y")))
        assertNotEquals(base, addonProxyContextKey("http://host/a", null))
    }

    @Test
    fun `fresh resolve needs direct debrid plus at least one id`() {
        val ids = DebridLookupIds(tmdbId = "1234")

        assertTrue(canFreshResolveDirectDebrid(PlaybackSource.DEBRID, true, ids))
        assertFalse(canFreshResolveDirectDebrid(PlaybackSource.IPTV, true, ids))
        assertFalse(canFreshResolveDirectDebrid(PlaybackSource.DEBRID, false, ids))
        assertFalse(canFreshResolveDirectDebrid(PlaybackSource.DEBRID, true, DebridLookupIds()))
        assertFalse(
            canFreshResolveDirectDebrid(
                PlaybackSource.DEBRID, true, DebridLookupIds(tmdbId = "  ", imdbId = "")
            )
        )
        assertTrue(
            canFreshResolveDirectDebrid(
                PlaybackSource.DEBRID, true, DebridLookupIds(title = "The Odyssey")
            )
        )
    }

    @Test
    fun `series lookup id falls back through tmdb, intent, imdb, then the episode id prefix`() {
        assertEquals("tmdb", debridSeriesLookupId("tmdb", "intent", "imdb", "series:1"))
        assertEquals("intent", debridSeriesLookupId(null, "intent", "imdb", "series:1"))
        assertEquals("imdb", debridSeriesLookupId(null, null, "imdb", "series:1"))
        assertEquals("series", debridSeriesLookupId(null, null, null, "series:1"))
        assertEquals("", debridSeriesLookupId(null, null, null, null))
    }

    @Test
    fun `a timeout only counts as repeated the second time the same context fails`() {
        val tracker = AddonProxyFailureTracker()

        assertFalse(tracker.hasRepeatedTimeout("ctx-1"))
        assertTrue(tracker.hasRepeatedTimeout("ctx-1"))
        // A re-resolve produced a new url/headers — not the same failure any more.
        assertFalse(tracker.hasRepeatedTimeout("ctx-2"))
        assertTrue(tracker.hasRepeatedTimeout("ctx-2"))
    }

    @Test
    fun `server errors are tracked separately from timeouts`() {
        val tracker = AddonProxyFailureTracker()

        assertFalse(tracker.hasRepeatedTimeout("ctx-1"))
        assertFalse(tracker.hasRepeatedServerError("ctx-1"))
        assertTrue(tracker.hasRepeatedServerError("ctx-1"))
        assertTrue(tracker.hasRepeatedTimeout("ctx-1"))
    }

    @Test
    fun `a clean READY forgets past failures but not past successes`() {
        val tracker = AddonProxyFailureTracker()
        tracker.hasRepeatedTimeout("ctx-1")
        tracker.hasRepeatedServerError("ctx-1")
        assertTrue(tracker.shouldRecordSuccess("ctx-1"))

        tracker.clearFailures()

        assertFalse("failure memory cleared", tracker.hasRepeatedTimeout("ctx-1"))
        assertFalse("failure memory cleared", tracker.hasRepeatedServerError("ctx-1"))
        assertFalse("success is still only credited once", tracker.shouldRecordSuccess("ctx-1"))
    }

    @Test
    fun `each source is credited with a success once`() {
        val tracker = AddonProxyFailureTracker()

        assertTrue(tracker.shouldRecordSuccess("ctx-1"))
        assertFalse(tracker.shouldRecordSuccess("ctx-1"))
        assertTrue(tracker.shouldRecordSuccess("ctx-2"))
        assertFalse(tracker.shouldRecordSuccess("ctx-2"))
    }
}
