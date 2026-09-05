package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryState
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cooldown window, tested directly (2026-09-05).
 *
 * `SeriesOutageStatusTest` already freezes the state machine end-to-end through XtreamRepository —
 * a failure records TEMPORARILY_UNAVAILABLE, a good fetch clears it. What nothing covered is the
 * part that decides **what the user sees while a category is parked**, and getting that wrong is
 * indistinguishable from data loss: hand back an empty list during a cooldown and the screen says
 * "this category has no content" about a category that was full a minute ago.
 */
class SeriesCategoryHealthTest {

    private lateinit var cache: CatalogCache
    private lateinit var health: SeriesCategoryHealth

    private fun series(name: String) = XtreamSeriesInfo(
        num = 0, name = name, series_id = name, cover = null, plot = null, cast = null,
        director = null, genre = null, releaseDate = null, rating = null, rating_5based = null,
        category_id = "42", category_ids = null, backdrop_path = null,
        youtube_trailer = null, episodes = null
    )

    private fun park(categoryId: String, ageMs: Long) {
        cache.seriesCategoryStatus[categoryId] = SeriesCategoryStatus(
            categoryId = categoryId,
            state = SeriesCategoryState.TEMPORARILY_UNAVAILABLE,
            message = "provider said 500",
            lastUpdatedMillis = System.currentTimeMillis() - ageMs
        )
    }

    @Before
    fun setUp() {
        cache = CatalogCache(mockk<CacheHelper>(relaxed = true), mockk<CacheManager>(relaxed = true))
        health = SeriesCategoryHealth(cache)
    }

    @Test
    fun `a healthy category is never parked, so the caller always fetches`() {
        assertNull(health.status("42"))
        assertNull("null means go and fetch", health.cachedDuringCooldown("42"))
    }

    @Test
    fun `a parked category hands back the last good list, not an empty one`() {
        cache.perCategorySeriesCache["42"] = listOf(series("Dark"), series("Fringe"))
        park("42", ageMs = 5_000)

        val held = health.cachedDuringCooldown("42")

        assertEquals(listOf("Dark", "Fringe"), held?.map { it.name })
        assertTrue("still parked - the entry must survive the read", cache.seriesCategoryStatus.containsKey("42"))
    }

    @Test
    fun `a parked category with nothing cached returns empty, which is not the same as null`() {
        park("42", ageMs = 5_000)

        val held = health.cachedDuringCooldown("42")

        assertEquals("empty list = do not fetch, show nothing", emptyList<XtreamSeriesInfo>(), held)
    }

    @Test
    fun `once the cooldown expires the entry is dropped and fetching is allowed again`() {
        cache.perCategorySeriesCache["42"] = listOf(series("Dark"))
        park("42", ageMs = 3 * 60 * 1000L)

        assertNull("expired means fetch", health.cachedDuringCooldown("42"))
        assertNull("and the parked entry is gone", health.status("42"))
    }

    @Test
    fun `only a temporary outage parks a category`() {
        cache.seriesCategoryStatus["42"] = SeriesCategoryStatus(
            categoryId = "42",
            state = SeriesCategoryState.EMPTY,
            message = null,
            lastUpdatedMillis = System.currentTimeMillis()
        )

        assertNull("an EMPTY category is answered, not cooled down", health.cachedDuringCooldown("42"))
    }

    @Test
    fun `update records a failure and HEALTHY clears it`() {
        health.update("42", SeriesCategoryState.TEMPORARILY_UNAVAILABLE, "timeout")
        assertEquals(SeriesCategoryState.TEMPORARILY_UNAVAILABLE, health.status("42")?.state)
        assertEquals("timeout", health.status("42")?.message)

        health.update("42", SeriesCategoryState.HEALTHY, null)
        assertNull("healthy is the ABSENCE of a status, never a stored one", health.status("42"))

        health.update("42", SeriesCategoryState.TEMPORARILY_UNAVAILABLE, "timeout")
        health.markHealthy("42")
        assertNull(health.status("42"))
    }
}
