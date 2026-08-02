package com.tvonnet.debridxtreamiptv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-8: the freshness decision that lets a category open skip the network refetch + delete/insert.
 * Wrong-to-fresh here means a user stops seeing provider updates; wrong-to-stale means the gate
 * is dead weight — both directions are pinned.
 */
class CategoryFetchFreshnessTest {

    private val now = 1_754_000_000_000L // an arbitrary real-world epoch instant
    private val ttl = CategoryFetchFreshness.CATEGORY_TTL_MS

    @Test
    fun `rows synced moments ago are fresh`() {
        assertTrue(CategoryFetchFreshness.isFresh(rowCount = 120, newestCachedAtMs = now - 5_000, nowMs = now))
    }

    @Test
    fun `rows just inside the ttl window are fresh`() {
        assertTrue(CategoryFetchFreshness.isFresh(rowCount = 1, newestCachedAtMs = now - ttl + 1, nowMs = now))
    }

    @Test
    fun `rows exactly at the ttl boundary are stale`() {
        assertFalse(CategoryFetchFreshness.isFresh(rowCount = 1, newestCachedAtMs = now - ttl, nowMs = now))
    }

    @Test
    fun `an empty category is never fresh - it must fall through to the network`() {
        assertFalse(CategoryFetchFreshness.isFresh(rowCount = 0, newestCachedAtMs = now - 1_000, nowMs = now))
    }

    @Test
    fun `a missing or unset timestamp is never fresh`() {
        assertFalse(CategoryFetchFreshness.isFresh(rowCount = 50, newestCachedAtMs = null, nowMs = now))
        assertFalse(CategoryFetchFreshness.isFresh(rowCount = 50, newestCachedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `a far-future stamp from a clock jump degrades to a refetch instead of fresh-forever`() {
        // Rows written with a sane clock, then the device clock reset far backwards: the stamp is
        // now far in the caller's future. abs() makes that stale once the gap exceeds the TTL.
        assertFalse(CategoryFetchFreshness.isFresh(rowCount = 50, newestCachedAtMs = now + ttl + 1, nowMs = now))
    }

    @Test
    fun `a custom ttl is respected`() {
        assertTrue(CategoryFetchFreshness.isFresh(1, now - 1_000, now, ttlMs = 2_000))
        assertFalse(CategoryFetchFreshness.isFresh(1, now - 3_000, now, ttlMs = 2_000))
    }
}
