package com.tvonnet.debridxtreamiptv.data.debrid.source

import org.junit.Assert.assertEquals
import org.junit.Test

class StremioAddonFetcherTest {

    @Test
    fun `buildStreamUrl preserves configured path for movies`() {
        val manifestUrl = "https://aiostreams.elfhosted.com/private-config/manifest.json"

        val streamUrl = StremioAddonFetcher.buildStreamUrl(
            manifestUrl = manifestUrl,
            type = "movie",
            streamId = "tt1375666"
        )

        assertEquals(
            "https://aiostreams.elfhosted.com/private-config/stream/movie/tt1375666.json",
            streamUrl
        )
    }

    @Test
    fun `buildStreamUrl preserves configured path for series episodes`() {
        val manifestUrl = "https://addon.example.com/user/token/manifest.json/"

        val streamUrl = StremioAddonFetcher.buildStreamUrl(
            manifestUrl = manifestUrl,
            type = "series",
            streamId = "tt0944947:1:2"
        )

        assertEquals(
            "https://addon.example.com/user/token/stream/series/tt0944947:1:2.json",
            streamUrl
        )
    }

    @Test
    fun `normalizeManifestUrl converts stremio scheme to https`() {
        val normalized = StremioAddonFetcher.normalizeManifestUrl(
            "stremio://torrentio.strem.fun/manifest.json/"
        )

        assertEquals("https://torrentio.strem.fun/manifest.json", normalized)
    }

    @Test
    fun `buildStreamUrl preserves query token`() {
        val manifestUrl = "https://addon.example.com/Manifest.json?token=abc123"

        val streamUrl = StremioAddonFetcher.buildStreamUrl(
            manifestUrl = manifestUrl,
            type = "movie",
            streamId = "tt1375666"
        )

        assertEquals(
            "https://addon.example.com/stream/movie/tt1375666.json?token=abc123",
            streamUrl
        )
    }
}
