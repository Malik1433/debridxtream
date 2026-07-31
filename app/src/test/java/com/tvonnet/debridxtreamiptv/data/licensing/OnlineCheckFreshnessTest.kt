package com.tvonnet.debridxtreamiptv.data.licensing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The online licence gate's grace rules.
 *
 * This is the whole enforcement mechanism now that the owner chose online-only over an offline token,
 * and the expensive failure is not "a pirate got three extra days" — it is "a Firebase outage locked
 * out every paying customer at once". So the cases pinned here are mostly about NOT locking someone
 * out wrongly, including the clock cases that are easy to forget and impossible to debug from a
 * support message.
 */
class OnlineCheckFreshnessTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000

    // ── the ordinary case ──────────────────────────────────────────────────────

    @Test
    fun `a device that checked in recently is fresh`() {
        assertTrue(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = now - hour, firstSeenAt = now - 30 * day))
    }

    @Test
    fun `a device just inside the window is still fresh`() {
        val lastVerified = now - OnlineCheckFreshness.VERIFIED_GRACE_MS
        assertTrue(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = lastVerified, firstSeenAt = now - 30 * day))
    }

    @Test
    fun `a device past the window is not`() {
        val lastVerified = now - OnlineCheckFreshness.VERIFIED_GRACE_MS - 1
        assertFalse(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = lastVerified, firstSeenAt = now - 30 * day))
    }

    // ── never synced ───────────────────────────────────────────────────────────

    @Test
    fun `a first launch with no network still works`() {
        // A real person's first evening can be offline. Treating that as piracy is a support ticket.
        assertTrue(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = 0L, firstSeenAt = now - hour))
    }

    @Test
    fun `but never syncing is not allowed forever`() {
        // "Never synced" is also exactly what a firewalled install looks like, so the window ends.
        assertFalse(
            OnlineCheckFreshness.isFresh(
                now,
                lastVerifiedAt = 0L,
                firstSeenAt = now - OnlineCheckFreshness.FIRST_RUN_GRACE_MS - 1
            )
        )
    }

    @Test
    fun `an unknown install time is treated as new, not as ancient`() {
        // Prefs written before this field existed have firstSeenAt = 0. Reading that as "installed
        // long ago" would lock out existing users on the very first build that ships this.
        assertTrue(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = 0L, firstSeenAt = 0L))
    }

    // ── clocks, which are not to be trusted ────────────────────────────────────

    @Test
    fun `a timestamp in the future does not read as expired`() {
        // A TV that boots at epoch and then jumps forward on NTP leaves stamps "in the future"
        // relative to the earlier read. That is a broken clock, not a broken licence.
        assertTrue(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = now + 10 * day, firstSeenAt = now - 30 * day))
        assertTrue(OnlineCheckFreshness.isFresh(now, lastVerifiedAt = 0L, firstSeenAt = now + 10 * day))
    }

    @Test
    fun `the grace windows are days and hours, not seconds`() {
        // Guards against someone "tidying" the constants into something that locks a TV out over a
        // long weekend.
        assertTrue(OnlineCheckFreshness.VERIFIED_GRACE_MS >= 2 * day)
        assertTrue(OnlineCheckFreshness.FIRST_RUN_GRACE_MS >= 12 * hour)
    }
}
