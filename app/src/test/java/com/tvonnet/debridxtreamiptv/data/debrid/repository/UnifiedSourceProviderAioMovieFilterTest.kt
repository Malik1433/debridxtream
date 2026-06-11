package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedSourceProviderAioMovieFilterTest {

    @Test
    fun `filters clearly different AIO movie with same year`() {
        val sources = listOf(aioSource("The Rip (2026) 1080p WEBRip"))

        val filtered = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(
            sources,
            requestedTitle = "Over Your Dead Body",
            requestedYear = "2026"
        )

        assertEquals(emptyList<AddonStream>(), filtered)
    }

    @Test
    fun `filters same title AIO movie with wrong explicit year`() {
        val sources = listOf(aioSource("Masters of the Universe (1987) 720p BrRip"))

        val filtered = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(
            sources,
            requestedTitle = "Masters of the Universe",
            requestedYear = "2026"
        )

        assertEquals(emptyList<AddonStream>(), filtered)
    }

    @Test
    fun `keeps matching AIO movie`() {
        val source = aioSource("Over.Your.Dead.Body.2026.1080p.WEB-DL")

        val filtered = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(
            listOf(source),
            requestedTitle = "Over Your Dead Body",
            requestedYear = "2026"
        )

        assertEquals(listOf(source), filtered)
    }

    @Test
    fun `keeps AIO movie when candidate metadata has no explicit year`() {
        val source = aioSource("Playable direct stream")

        val filtered = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(
            listOf(source),
            requestedTitle = "Over Your Dead Body",
            requestedYear = "2026"
        )

        assertEquals(listOf(source), filtered)
    }

    @Test
    fun `filters mismatched configured StreamThru row`() {
        val source = stremioSource(
            title = "The Rip (2026) 1080p WEBRip",
            providerName = "StreamThru"
        )

        val filtered = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(
            listOf(source),
            requestedTitle = "Over Your Dead Body",
            requestedYear = "2026"
        )

        assertEquals(emptyList<AddonStream>(), filtered)
    }

    @Test
    fun `filters mismatched MediaFusion row`() {
        val source = addonSource(
            title = "The Rip (2026) 1080p WEBRip",
            sourceType = AddonSourceType.MEDIA_FUSION,
            providerName = "MediaFusion"
        )

        val filtered = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(
            listOf(source),
            requestedTitle = "Over Your Dead Body",
            requestedYear = "2026"
        )

        assertEquals(emptyList<AddonStream>(), filtered)
    }

    private fun aioSource(title: String): AddonStream {
        return stremioSource(title = title, providerName = "AIOStreams | ElfHosted")
    }

    private fun stremioSource(title: String, providerName: String): AddonStream {
        return addonSource(title, AddonSourceType.STREMIO, providerName)
    }

    private fun addonSource(
        title: String,
        sourceType: AddonSourceType,
        providerName: String
    ): AddonStream {
        return AddonStream(
            source = sourceType,
            title = title,
            url = "https://addon.example.com/playback",
            infoHash = null,
            magnet = null,
            sizeBytes = null,
            seeders = null,
            peers = null,
            quality = "1080p",
            languages = listOf("en"),
            subtitles = emptyList(),
            extras = mapOf(
                "providerName" to providerName,
                "stremioAddonId" to providerName
            )
        )
    }
}
