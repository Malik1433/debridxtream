package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.SourceAlternate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H2 (SOURCE_LIST_QUALITY_PLAN P2): a dead link retries the SAME file via another
 * addon's copy before the file is blacklisted; the identity (stream_id) never changes.
 */
class SourceAlternatesTest {

    private fun vod(streamId: String, url: String?) = XtreamVodInfo(
        stream_id = streamId,
        name = "Movie 2026",
        direct_source = url,
        num = 1,
        stream_type = "movie",
        category_id = null,
        category_ids = null,
        container_extension = null,
        custom_sid = null,
        rating = null,
        rating_5based = null,
        added = null,
        cast = null,
        director = null,
        plot = null,
        releaseDate = null,
        duration = null,
        genre = null,
        youtube_trailer = null,
        cover = null,
        rating_imdb = null,
        stream_icon = null,
    )

    private fun source(streamId: String = "abc_0", alternates: List<SourceAlternate> = emptyList()) =
        MovieSource(
            stream = vod(streamId, "https://primary.example/$streamId"),
            category = null,
            label = "Primary",
            isPrimary = true,
            provider = "StremThru",
            sourceType = "STREMIO",
            alternates = alternates,
        )

    private fun alt(url: String?, provider: String = "MediaFusion") = SourceAlternate(
        provider = provider,
        sourceType = "MEDIA_FUSION",
        sourceName = "MF",
        directSource = url,
        headers = mapOf("X-Test" to "1"),
        cacheStatus = DebridCacheStatus.DIRECT_STREAM,
    )

    @Test
    fun `next swaps delivery but keeps the stream id`() {
        val s = source(alternates = listOf(alt("https://alt.example/a")))
        val next = SourceAlternates.next(s, 0)
        assertNotNull(next)
        assertEquals("abc_0", next!!.stream.stream_id)
        assertEquals("https://alt.example/a", next.stream.direct_source)
        assertEquals("MediaFusion", next.provider)
        assertEquals(mapOf("X-Test" to "1"), next.headers)
        assertEquals(DebridCacheStatus.DIRECT_STREAM, next.cacheStatus)
    }

    @Test
    fun `budget is MAX_ALTERNATE_ATTEMPTS`() {
        val s = source(alternates = listOf(alt("https://a/1"), alt("https://a/2"), alt("https://a/3")))
        assertNotNull(SourceAlternates.next(s, 0))
        assertNotNull(SourceAlternates.next(s, 1))
        // A third copy exists but the budget is spent — the file is declared dead.
        assertNull(SourceAlternates.next(s, SourceAlternates.MAX_ALTERNATE_ATTEMPTS))
    }

    @Test
    fun `unplayable alternates are skipped entirely`() {
        val s = source(alternates = listOf(alt(null), alt("  "), alt("https://a/only")))
        val next = SourceAlternates.next(s, 0)
        assertEquals("https://a/only", next?.stream?.direct_source)
        assertNull(SourceAlternates.next(s, 1))
    }

    @Test
    fun `failover walks copies then exhausts to null`() {
        val failover = AlternateFailover()
        val sources = listOf(source(alternates = listOf(alt("https://a/1"), alt("https://a/2"))))
        assertEquals("https://a/1", failover.nextFor("abc_0", sources)?.stream?.direct_source)
        assertEquals("https://a/2", failover.nextFor("abc_0", sources)?.stream?.direct_source)
        assertNull(failover.nextFor("abc_0", sources))
        // After exhaustion the state reset — a fresh failure of the same file starts over.
        assertEquals("https://a/1", failover.nextFor("abc_0", sources)?.stream?.direct_source)
    }

    @Test
    fun `a different failed file starts its own count`() {
        val failover = AlternateFailover()
        val sources = listOf(
            source(streamId = "abc_0", alternates = listOf(alt("https://a/1"), alt("https://a/2"))),
            source(streamId = "def_1", alternates = listOf(alt("https://d/1"))),
        )
        assertEquals("https://a/1", failover.nextFor("abc_0", sources)?.stream?.direct_source)
        assertEquals("https://d/1", failover.nextFor("def_1", sources)?.stream?.direct_source)
        // Back to the first file: its count restarts (state tracks one file at a time).
        assertEquals("https://a/1", failover.nextFor("abc_0", sources)?.stream?.direct_source)
    }

    @Test
    fun `unknown failed id yields null and resets`() {
        val failover = AlternateFailover()
        assertNull(failover.nextFor("missing", listOf(source())))
    }
}
