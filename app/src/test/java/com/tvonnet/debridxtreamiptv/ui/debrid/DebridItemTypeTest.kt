package com.tvonnet.debridxtreamiptv.ui.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type vocabulary that decides which detail screen a Debrid card opens.
 *
 * This is pinned because getting it wrong is invisible in code review and total for the customer:
 * a favourited movie is stored as `vod`, every routing site compared against `"movie"`, and so
 * every favourite opened a series detail that could never list an episode. The word `vod` is the
 * one this test exists for.
 */
class DebridItemTypeTest {

    @Test
    fun `the favourites table's word for a movie is a movie`() {
        assertTrue(DebridItemType.isMovie("vod"))
        assertFalse(DebridItemType.isSeries("vod"))
        assertEquals(DebridItemType.MOVIE, DebridItemType.canonical("vod"))
    }

    @Test
    fun `every dialect for a series is a series`() {
        listOf("series", "show", "tv", "TVShow", " tv_show ").forEach {
            assertTrue("'$it' should read as a series", DebridItemType.isSeries(it))
            assertEquals(DebridItemType.SERIES, DebridItemType.canonical(it))
        }
    }

    @Test
    fun `every dialect for a movie is a movie`() {
        listOf("movie", "vod", "film", "MOVIE", " Vod ").forEach {
            assertTrue("'$it' should read as a movie", DebridItemType.isMovie(it))
            assertEquals(DebridItemType.MOVIE, DebridItemType.canonical(it))
        }
    }

    @Test
    fun `an unknown or missing type falls back to a movie, not a season list`() {
        // A movie detail with nothing to play says so; a series detail with no episodes is a
        // dead end, which is exactly the state this whole class was written to end.
        assertEquals(DebridItemType.MOVIE, DebridItemType.canonical(null))
        assertEquals(DebridItemType.MOVIE, DebridItemType.canonical(""))
        assertEquals(DebridItemType.MOVIE, DebridItemType.canonical("see_all"))
        assertFalse(DebridItemType.isSeries("anything else"))
    }
}
