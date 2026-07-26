package com.tvonnet.debridxtreamiptv.ui.live

import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B3 — the Live category chip strip (LF-4), extracted verbatim with no tests.
 *
 * Three rules a user notices the moment they break: Favourites must be first and must not appear
 * twice when the provider ships its own "favourites" category; searching turns the strip into a
 * name filter (and Favourites is not something you find by name); and coming back from fullscreen
 * must put focus on the category you left, not on whatever index happens to still exist.
 */
class LiveCategoryChipsTest {

    // ── which chips exist ────────────────────────────────────────────────────

    @Test
    fun `favourites is always the first chip`() {
        val chips = LiveCategoryChips.buildDisplayCategories("Favorites", listOf(cat("1", "Sports"), cat("2", "News")))

        assertEquals(FAVORITES_CATEGORY_ID, chips.first().category_id)
        assertEquals("Favorites", chips.first().category_name)
        assertEquals(listOf("Favorites", "Sports", "News"), chips.map { it.category_name })
    }

    @Test
    fun `a provider's own favourites category does not become a second chip`() {
        val chips = LiveCategoryChips.buildDisplayCategories(
            "Favorites",
            listOf(cat(FAVORITES_CATEGORY_ID, "My Favourites"), cat("1", "Sports"))
        )

        assertEquals(1, chips.count { it.category_id == FAVORITES_CATEGORY_ID })
        assertEquals(listOf("Favorites", "Sports"), chips.map { it.category_name })
    }

    @Test
    fun `with no provider categories there is still a favourites chip`() {
        assertEquals(1, LiveCategoryChips.buildDisplayCategories("Favorites", emptyList()).size)
    }

    // ── search filtering ─────────────────────────────────────────────────────

    @Test
    fun `an empty query leaves the strip alone`() {
        val all = LiveCategoryChips.buildDisplayCategories("Favorites", listOf(cat("1", "Sports")))

        assertEquals(all, LiveCategoryChips.applyChipFilter(all, ""))
        assertEquals(all, LiveCategoryChips.applyChipFilter(all, "   "))
    }

    @Test
    fun `a query keeps matching names, case-insensitively`() {
        val all = LiveCategoryChips.buildDisplayCategories(
            "Favorites",
            listOf(cat("1", "UK Sports"), cat("2", "DE Sport HD"), cat("3", "News"))
        )

        val filtered = LiveCategoryChips.applyChipFilter(all, "spor")

        assertEquals(listOf("UK Sports", "DE Sport HD"), filtered.map { it.category_name })
    }

    @Test
    fun `favourites drops out of a filtered strip`() {
        val all = LiveCategoryChips.buildDisplayCategories("Favorites", listOf(cat("1", "Sports")))

        val filtered = LiveCategoryChips.applyChipFilter(all, "favo")

        assertTrue(
            "Favourites is not a category you jump to by name — matching it would be a dead chip",
            filtered.none { it.category_id == FAVORITES_CATEGORY_ID }
        )
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `a category with no name never matches`() {
        val all = listOf(cat("1", null), cat("2", "Sports"))

        assertEquals(listOf("Sports"), LiveCategoryChips.applyChipFilter(all, "s").map { it.category_name })
    }

    // ── which chip takes focus ───────────────────────────────────────────────

    @Test
    fun `the selected category wins`() {
        val chips = listOf(cat("f", "Favorites"), cat("1", "Sports"), cat("2", "News"))

        assertEquals(2, LiveCategoryChips.focusPositionFor(chips, "2", "1", 0))
    }

    @Test
    fun `without a selection the last focused category is used`() {
        val chips = listOf(cat("f", "Favorites"), cat("1", "Sports"), cat("2", "News"))

        assertEquals(1, LiveCategoryChips.focusPositionFor(chips, null, "1", 0))
    }

    /** The stale-state case: the category is gone, but the remembered index still fits. */
    @Test
    fun `a vanished category falls back to the remembered position`() {
        val chips = listOf(cat("f", "Favorites"), cat("1", "Sports"))

        assertEquals(1, LiveCategoryChips.focusPositionFor(chips, "gone", "also-gone", 1))
    }

    @Test
    fun `a remembered position past the end falls back to the first chip`() {
        val chips = listOf(cat("f", "Favorites"), cat("1", "Sports"))

        assertEquals(
            "returning to a shrunken list must not focus off the end",
            0,
            LiveCategoryChips.focusPositionFor(chips, null, null, 99)
        )
    }

    @Test
    fun `an empty strip has nothing to focus`() {
        assertNull(LiveCategoryChips.focusPositionFor(emptyList(), "1", "1", 0))
    }

    @Test
    fun `with nothing remembered at all the first chip takes focus`() {
        val chips = listOf(cat("f", "Favorites"), cat("1", "Sports"))

        assertEquals(0, LiveCategoryChips.focusPositionFor(chips, null, null, null))
        assertFalse(chips.isEmpty())
    }

    private fun cat(id: String, name: String?) =
        XtreamCategory(category_id = id, category_name = name, parent_id = null)
}
