package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.ui.sources.DebridAutoNextPicker.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner's rule for auto-next (2026-09-27): a viewer watching in German only wants German. A
 * failed source may be replaced by another source, never by another language - when none is left in
 * that language the viewer is asked (the list opens) instead.
 */
class DebridAutoNextPickerTest {

    private fun vod(streamId: String) = XtreamVodInfo(
        stream_id = streamId, name = "Movie 2026", direct_source = "https://x.example/$streamId", num = 1,
        stream_type = "movie", category_id = null, category_ids = null, container_extension = null,
        custom_sid = null, rating = null, rating_5based = null, added = null, cast = null, director = null,
        plot = null, releaseDate = null, duration = null, genre = null, youtube_trailer = null, cover = null,
        rating_imdb = null, stream_icon = null,
    )

    private fun src(id: String, vararg languages: String) = MovieSource(
        stream = vod(id), category = null, label = id, isPrimary = false, provider = "Test",
        sourceType = "STREMIO", languages = languages.toList().ifEmpty { null },
    )

    private fun pick(
        sorted: List<MovieSource>,
        failed: String,
        filter: String? = null,
        priority: String? = null,
        all: List<MovieSource> = sorted,
    ) = DebridAutoNextPicker.pick(sorted, all, failed, filter, priority)

    private fun nextId(result: Result) = (result as Result.Next).source.stream.stream_id

    @Test
    fun `a failed German source is followed by German, skipping English rows in between`() {
        // Cache status leads the sort when the language only comes from settings, so EN can sit
        // between two German rows - the old rule played it.
        val list = listOf(src("de1", "de"), src("en1", "en"), src("de2", "de"))
        assertEquals("de2", nextId(pick(list, failed = "de1", priority = "German")))
    }

    @Test
    fun `when German runs out the viewer is asked, English is never played`() {
        val list = listOf(src("de1", "de"), src("en1", "en"), src("en2", "en"))
        assertEquals(Result.NoneInLanguage(StreamLanguage.GERMAN), pick(list, failed = "de1", priority = "German"))
    }

    @Test
    fun `the language the viewer filtered by wins`() {
        val list = listOf(src("hi1", "hi"), src("de1", "de"), src("hi2", "hi"))
        assertEquals("hi2", nextId(pick(list, failed = "hi1", filter = "Hindi", priority = "German")))
    }

    @Test
    fun `without a priority the failed source's own language is kept`() {
        val list = listOf(src("hi1", "hindi"), src("en1", "english"), src("hi2", "hin"))
        assertEquals("hi2", nextId(pick(list, failed = "hi1")))
    }

    @Test
    fun `a priority the failed source did not carry does not hijack it`() {
        // Settings say German, but the viewer was watching an English source: keep English.
        val list = listOf(src("en1", "en"), src("de1", "de"), src("en2", "en"))
        assertEquals("en2", nextId(pick(list, failed = "en1", priority = "German")))
    }

    @Test
    fun `a dual-audio source needs both languages again`() {
        val list = listOf(src("dual1", "de", "en"), src("en1", "en"), src("dual2", "en", "de"))
        assertEquals("dual2", nextId(pick(list, failed = "dual1")))
    }

    @Test
    fun `Multi and unlabelled sources are never auto-picked for a language`() {
        val list = listOf(src("de1", "de"), src("multi", "multi"), src("none"))
        assertEquals(Result.NoneInLanguage(StreamLanguage.GERMAN), pick(list, failed = "de1", priority = "German"))
    }

    @Test
    fun `nothing to preserve keeps the old next-row rule`() {
        val list = listOf(src("u1"), src("en1", "en"))
        assertEquals("en1", nextId(pick(list, failed = "u1")))
    }

    @Test
    fun `the failed source is found even when a filter hid it`() {
        val all = listOf(src("de1", "de"), src("en1", "en"), src("de2", "de"))
        val sorted = listOf(src("en1", "en"), src("de2", "de"))
        assertEquals("de2", nextId(pick(sorted, failed = "de1", all = all)))
    }

    @Test
    fun `the last row failing, or an empty list, leaves nothing`() {
        val list = listOf(src("de1", "de"), src("de2", "de"))
        assertEquals(Result.NoneLeft, pick(list, failed = "de2"))
        assertEquals(Result.NoneLeft, pick(emptyList(), failed = "de1"))
    }
}
