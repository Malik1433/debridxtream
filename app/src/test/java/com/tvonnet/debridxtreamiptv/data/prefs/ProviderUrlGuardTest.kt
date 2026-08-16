package com.tvonnet.debridxtreamiptv.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard hides rows, so its failure modes point in two directions: letting a foreign URL through
 * means the device streams from the provider the customer left, and hiding a good one means their
 * Continue Watching looks emptied for no reason. Both are covered here.
 */
class ProviderUrlGuardTest {

    private val current = "http://tv.example/"

    @Test
    fun `an entry from the current provider is kept`() {
        assertTrue(
            ProviderUrlGuard.belongsToCurrentProvider(
                "http://tv.example/movie/user/pass/123.mp4", source = "xtream", currentBaseUrl = current
            )
        )
    }

    @Test
    fun `an entry from the previous provider is hidden`() {
        assertFalse(
            ProviderUrlGuard.belongsToCurrentProvider(
                "http://old.example/movie/user/pass/123.mp4", source = "xtream", currentBaseUrl = current
            )
        )
    }

    @Test
    fun `a debrid entry is kept whatever host it came from`() {
        assertTrue(
            ProviderUrlGuard.belongsToCurrentProvider(
                "https://cdn.debrid.example/x/file.mkv", source = "debrid", currentBaseUrl = current
            )
        )
    }

    @Test
    fun `nothing is hidden while the current provider is unknown`() {
        assertTrue(
            ProviderUrlGuard.belongsToCurrentProvider(
                "http://old.example/live/1.ts", source = "xtream", currentBaseUrl = ""
            )
        )
    }

    @Test
    fun `an entry with no url is left for the rest of the app to judge`() {
        assertTrue(ProviderUrlGuard.belongsToCurrentProvider(null, source = "xtream", currentBaseUrl = current))
        assertTrue(ProviderUrlGuard.belongsToCurrentProvider("", source = "xtream", currentBaseUrl = current))
    }

    @Test
    fun `scheme and case do not make one provider look like two`() {
        assertTrue(
            ProviderUrlGuard.belongsToCurrentProvider(
                "https://TV.Example/live/1.ts", source = null, currentBaseUrl = current
            )
        )
    }

    @Test
    fun `a different port is a different provider`() {
        assertFalse(
            ProviderUrlGuard.belongsToCurrentProvider(
                "http://tv.example:8080/live/1.ts", source = null, currentBaseUrl = current
            )
        )
    }
}
