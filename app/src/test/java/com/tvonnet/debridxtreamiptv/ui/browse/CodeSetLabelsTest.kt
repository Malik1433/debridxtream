package com.tvonnet.debridxtreamiptv.ui.browse

import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow
import com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioRowTitles
import com.tvonnet.debridxtreamiptv.ui.series.SeriesViewModel
import com.tvonnet.debridxtreamiptv.ui.vod.VodViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The labels the ViewModels mint in English must be resolved through string resources where
 * they are shown — and ONLY the virtual ones: a provider's own category name and an add-on's
 * own row title must come through untouched, whatever they happen to say.
 */
class CodeSetLabelsTest {

    private val resolve: (Int) -> String = { id ->
        when (id) {
            R.string.code_cat_all_movies -> "Alle Filme"
            R.string.code_cat_all_series -> "Alle Serien"
            R.string.code_cat_recently_added -> "Zuletzt hinzugefügt"
            R.string.code_row_my_library -> "Meine Bibliothek"
            R.string.code_row_trending_movies -> "Filme im Trend"
            R.string.code_row_trending_series -> "Serien im Trend"
            else -> error("unexpected resource $id")
        }
    }

    @Test
    fun `the four virtual categories resolve by id, not by their English name`() {
        assertEquals("Alle Filme", VirtualCategoryNames.displayName(XtreamCategory(VodViewModel.ALL_MOVIES_CATEGORY_ID, "All Movies", null), resolve))
        assertEquals("Zuletzt hinzugefügt", VirtualCategoryNames.displayName(XtreamCategory(VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID, "Recently Added", null), resolve))
        assertEquals("Alle Serien", VirtualCategoryNames.displayName(XtreamCategory(SeriesViewModel.ALL_SERIES_CATEGORY_ID, "All Series", null), resolve))
        assertEquals("Zuletzt hinzugefügt", VirtualCategoryNames.displayName(XtreamCategory(SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID, "Recently Added", null), resolve))
    }

    @Test
    fun `a provider category keeps its own name, even one that says All Movies`() {
        assertEquals("All Movies", VirtualCategoryNames.displayName(XtreamCategory("42", "All Movies", null), resolve))
        assertEquals("|AR| Cinema", VirtualCategoryNames.displayName(XtreamCategory("7", "|AR| Cinema", null), resolve))
        assertEquals("", VirtualCategoryNames.displayName(XtreamCategory("9", null, null), resolve))
    }

    @Test
    fun `the three home rows resolve by id and add-on rows keep their title`() {
        assertEquals("Meine Bibliothek", StremioRowTitles.displayTitle(DebridRow("my_library", "My Library", emptyList()), resolve))
        assertEquals("Filme im Trend", StremioRowTitles.displayTitle(DebridRow("trending_movies", "Trending Movies", emptyList()), resolve))
        assertEquals("Serien im Trend", StremioRowTitles.displayTitle(DebridRow("trending_series", "Trending Series", emptyList()), resolve))
        assertEquals("Torrentio: Popular", StremioRowTitles.displayTitle(DebridRow("catalog:torrentio:popular", "Torrentio: Popular", emptyList()), resolve))
    }
}
