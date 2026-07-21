package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7 freeze tests for XtreamSeriesInfo.matchesCategory — the relaxed series↔category predicate
 * consolidated out of five identical inline filters in XtreamRepository. Pure logic, no Android deps.
 */
class SeriesCategoryMatchingTest {

    private fun series(categoryId: String?, categoryIds: List<Int>? = null): XtreamSeriesInfo =
        XtreamSeriesInfo(
            num = 1,
            name = "Show",
            series_id = "s1",
            cover = null,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            rating = null,
            rating_5based = null,
            category_id = categoryId,
            category_ids = categoryIds,
            backdrop_path = null,
            youtube_trailer = null,
            episodes = null
        )

    @Test
    fun `exact string match wins`() {
        assertTrue(series(categoryId = "42").matchesCategory("42"))
    }

    @Test
    fun `trims whitespace on both sides`() {
        assertTrue(series(categoryId = " 42 ").matchesCategory("  42  "))
    }

    @Test
    fun `matches via category_ids list membership`() {
        assertTrue(series(categoryId = "99", categoryIds = listOf(1, 42, 7)).matchesCategory("42"))
    }

    @Test
    fun `loose integer match across differing string forms`() {
        // "042" != "42" as strings, but both parse to 42 -> intMatch.
        assertTrue(series(categoryId = "042").matchesCategory("42"))
    }

    @Test
    fun `no match when ids differ and not in list`() {
        assertFalse(series(categoryId = "10", categoryIds = listOf(1, 2)).matchesCategory("42"))
    }

    @Test
    fun `non-numeric target only matches by exact string, not loose int`() {
        // targetIdStr.toIntOrNull() is null, so the intMatch branch is gated off.
        assertTrue(series(categoryId = "kids").matchesCategory("kids"))
        assertFalse(series(categoryId = "7").matchesCategory("kids"))
    }

    @Test
    fun `null category_id with no list does not match`() {
        assertFalse(series(categoryId = null).matchesCategory("42"))
    }
}
