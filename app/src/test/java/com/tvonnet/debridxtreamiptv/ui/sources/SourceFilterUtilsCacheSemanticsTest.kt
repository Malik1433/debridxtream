package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFilterUtilsCacheSemanticsTest {

    @Test
    fun `cached only keeps verified cached sources only`() {
        val sources = listOf(
            movieSource("verified", DebridCacheStatus.VERIFIED_CACHED),
            movieSource("direct", DebridCacheStatus.DIRECT_STREAM),
            movieSource("uncached", DebridCacheStatus.NOT_CACHED),
            movieSource("unknown", DebridCacheStatus.UNKNOWN)
        )

        val filtered = SourceFilterUtils.filter(
            sources = sources,
            state = SourceFilterState(cachedOnly = true)
        )

        assertEquals(listOf("verified"), filtered.map { it.stream.stream_id })
    }

    @Test
    fun `rd cached type keeps verified cached sources only`() {
        val sources = listOf(
            movieSource("verified", DebridCacheStatus.VERIFIED_CACHED),
            movieSource("direct", DebridCacheStatus.DIRECT_STREAM),
            movieSource("uncached", DebridCacheStatus.NOT_CACHED),
            movieSource("unknown", DebridCacheStatus.UNKNOWN)
        )

        val filtered = SourceFilterUtils.filter(
            sources = sources,
            state = SourceFilterState(preferredType = "RD Cached")
        )

        assertEquals(listOf("verified"), filtered.map { it.stream.stream_id })
    }

    private fun movieSource(streamId: String, cacheStatus: DebridCacheStatus): MovieSource {
        return MovieSource(
            stream = XtreamVodInfo(
                num = 1,
                name = "Test",
                stream_type = "movie",
                stream_id = streamId,
                stream_icon = null,
                rating = null,
                rating_5based = null,
                added = null,
                category_id = null,
                category_ids = null,
                container_extension = "mkv",
                custom_sid = null,
                direct_source = null,
                releaseDate = null,
                duration = null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                youtube_trailer = null,
                cover = null,
                rating_imdb = null
            ),
            category = XtreamCategory(category_id = "1", category_name = "Debrid", parent_id = null),
            label = "Test",
            isPrimary = true,
            cacheStatus = cacheStatus,
            provider = "realdebrid",
            sourceType = "addon",
            sourceName = "Test Source",
            headers = null
        )
    }
}
