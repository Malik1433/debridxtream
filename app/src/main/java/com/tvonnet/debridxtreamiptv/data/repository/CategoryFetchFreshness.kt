package com.tvonnet.debridxtreamiptv.data.repository

import kotlin.math.abs

/**
 * B-8: decides whether a category's Room rows are fresh enough to SKIP the per-category network
 * refetch (and its full delete+insert rewrite) when the user opens that category.
 *
 * The browse UI is DB-first, so skipping the refetch never blanks the screen — the gate only
 * removes the redundant network + write cost. A stale or empty category always falls through to
 * the existing network path, and a FAILED refresh path is untouched (it already never wipes).
 */
object CategoryFetchFreshness {

    /** How long a synced category is served from Room without a network refetch. */
    const val CATEGORY_TTL_MS: Long = 6 * 60 * 60 * 1000L

    /**
     * Fresh = the category has rows AND the newest row was written within [ttlMs] of [nowMs].
     * abs() so a device clock that jumped (TV boxes boot at epoch until NTP) degrades to a
     * refetch instead of pinning the category fresh forever.
     */
    fun isFresh(
        rowCount: Int,
        newestCachedAtMs: Long?,
        nowMs: Long,
        ttlMs: Long = CATEGORY_TTL_MS
    ): Boolean {
        if (rowCount <= 0) return false
        if (newestCachedAtMs == null || newestCachedAtMs <= 0L) return false
        return abs(nowMs - newestCachedAtMs) < ttlMs
    }
}
