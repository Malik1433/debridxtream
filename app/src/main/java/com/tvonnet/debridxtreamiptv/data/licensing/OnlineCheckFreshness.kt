package com.tvonnet.debridxtreamiptv.data.licensing

/**
 * How long a device may go without reaching the licence server before it stops counting as licensed.
 *
 * The owner's choice: online-only, no offline token. That makes this the whole enforcement mechanism,
 * so what it does when it is *uncertain* matters more than what it does when it is sure.
 *
 * Two things it deliberately does NOT try to be:
 *
 * 1. **Instant.** A grace window is not a weakness here. Someone cracking the app removes the check
 *    entirely — the window costs them nothing and they never see it. It exists for the paying user
 *    whose Wi-Fi drops, or whose evening coincides with a Firebase outage. Without it, one bad hour
 *    on our side locks out everyone at once, which is a far larger failure than a few days of grace
 *    for a pirate who was never going to be stopped by this.
 *
 * 2. **Clever about a device that has never checked in.** A first launch with no network is a normal
 *    thing that happens to real people. It gets its own, shorter window measured from install rather
 *    than being treated as a failure — but it is NOT unlimited, because "never synced" is also
 *    exactly what a firewalled install looks like.
 */
internal object OnlineCheckFreshness {

    /**
     * Once a device has synced at least once, this is how long it may stay unreachable and still be
     * treated as licensed. Days rather than hours: a TV can sit unplugged over a holiday.
     */
    const val VERIFIED_GRACE_MS = 3L * 24 * 60 * 60 * 1000   // 3 days

    /**
     * A device that has NEVER reached the server. Shorter, because this is also the shape of a
     * deliberately firewalled install — but long enough that a genuinely offline first evening works.
     */
    const val FIRST_RUN_GRACE_MS = 48L * 60 * 60 * 1000      // 48 hours

    /**
     * @param lastVerifiedAt when the server was last actually reached — 0 if never. Must come from a
     *   real server round-trip; a Firestore snapshot served from its own local cache does not count,
     *   or this never expires and the whole gate is decorative.
     * @param firstSeenAt when this install was first recorded, for the never-synced case.
     * @return true while the device may still be treated as licensed.
     */
    fun isFresh(
        nowMs: Long,
        lastVerifiedAt: Long,
        firstSeenAt: Long,
        verifiedGraceMs: Long = VERIFIED_GRACE_MS,
        firstRunGraceMs: Long = FIRST_RUN_GRACE_MS
    ): Boolean {
        if (lastVerifiedAt > 0L) {
            // A clock rolled backwards (or a device with no RTC booting at epoch) must not read as
            // "checked in far in the future" and never expire. Treat the future as now.
            if (lastVerifiedAt >= nowMs) return true
            return nowMs - lastVerifiedAt <= verifiedGraceMs
        }
        // Never reached the server. Unknown install time is treated as "just installed" rather than
        // "installed long ago" — being wrong in the strict direction here would lock out a user whose
        // prefs simply predate this field.
        if (firstSeenAt <= 0L || firstSeenAt >= nowMs) return true
        return nowMs - firstSeenAt <= firstRunGraceMs
    }
}
