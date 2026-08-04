package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3 (SOURCE_LIST_QUALITY_PLAN P3): language filters over honest data — MULTI must
 * match any specific language filter (it may well contain it), and honestly-unknown
 * rows are reachable via the Unknown option.
 */
class SourceFilterUtilsLanguageTest {

    private fun vod(id: String) = XtreamVodInfo(
        num = null, name = id, stream_type = null, stream_id = id, stream_icon = null,
        rating = null, rating_5based = null, added = null, category_id = null, category_ids = null,
        container_extension = null, custom_sid = null, direct_source = null, releaseDate = null,
        duration = null, plot = null, cast = null, director = null, genre = null,
        youtube_trailer = null, cover = null, rating_imdb = null
    )

    private fun source(id: String, languages: List<String>?) = MovieSource(
        stream = vod(id),
        category = null,
        label = id,
        isPrimary = false,
        languages = languages,
    )

    private val english = source("en-row", listOf("en"))
    private val hindi = source("hi-row", listOf("hi"))
    private val multi = source("multi-row", listOf("multi"))
    private val unknown = source("unknown-row", emptyList())

    private fun filteredIds(preferredLanguage: String): List<String?> =
        SourceFilterUtils.filter(
            listOf(english, hindi, multi, unknown),
            SourceFilterState(preferredLanguage = preferredLanguage)
        ).map { it.stream.stream_id }

    @Test
    fun `MULTI matches a specific language filter`() {
        val ids = filteredIds("Hindi")
        assertTrue("hi-row" in ids)
        assertTrue("multi-row" in ids)
        assertTrue("en-row" !in ids)
        assertTrue("unknown-row" !in ids)
    }

    @Test
    fun `English filter keeps explicit English and MULTI only`() {
        assertEquals(listOf("en-row", "multi-row"), filteredIds("English"))
    }

    @Test
    fun `Unknown filter reaches the honestly-unknown rows`() {
        assertEquals(listOf("unknown-row"), filteredIds("Unknown"))
    }

    @Test
    fun `Multi filter stays exact`() {
        assertEquals(listOf("multi-row"), filteredIds("Multi"))
    }
}
