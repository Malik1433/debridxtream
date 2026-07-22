package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the in-player episode-browsing rules extracted in P13 — above all the
 * "a passive playlist load must not hijack the playing stream" guard, which is the
 * fix for "select NL/DE -> same file".
 */
class PlayerSeriesBrowsingTest {

    private fun episode(id: String, number: Int = 1) = EpisodeEntityV2(
        episodeId = id,
        seriesId = "series-1",
        seasonNumber = 1,
        episodeNumber = number,
        title = "Episode $number",
        containerExtension = "mkv",
        streamType = "series",
        durationSecs = null,
        added = null,
        customSid = null,
        directSource = null,
        thumbnail = null,
        plot = null,
        cast = null,
        director = null,
        genre = null,
        releaseDate = null,
        rating = null
    )

    @Test
    fun `browser shows loading, then a message when empty, then the list`() {
        assertTrue(episodeBrowserViewFor(true, emptyList(), null) is EpisodeBrowserView.Loading)
        assertTrue(episodeBrowserViewFor(true, listOf(episode("a")), null) is EpisodeBrowserView.Loading)

        val empty = episodeBrowserViewFor(false, emptyList(), null)
        assertEquals("No episodes available", (empty as EpisodeBrowserView.Message).text)

        val failed = episodeBrowserViewFor(false, emptyList(), "Provider timeout")
        assertEquals("Provider timeout", (failed as EpisodeBrowserView.Message).text)

        val loaded = episodeBrowserViewFor(false, listOf(episode("a"), episode("b", 2)), null)
        assertEquals(2, (loaded as EpisodeBrowserView.Episodes).episodes.size)
    }

    @Test
    fun `season title falls back to the season number`() {
        assertEquals("Breaking Bad", episodeBrowserSeasonTitle("Breaking Bad", 2))
        assertEquals("Season 2", episodeBrowserSeasonTitle(null, 2))
        assertEquals("Season ", episodeBrowserSeasonTitle(null, null))
    }

    @Test
    fun `playlist may drive playback only when nothing is playing yet`() {
        assertTrue(
            shouldPlaylistDrivePlayback(
                isLoading = false, playlistEpisodeId = "ep-2", currentEpisodeId = "ep-1",
                currentUrl = null, playbackSource = PlaybackSource.IPTV
            )
        )

        // A stream is already playing (sibling-category pick) — must NOT be hijacked.
        assertFalse(
            shouldPlaylistDrivePlayback(
                isLoading = false, playlistEpisodeId = "ep-2", currentEpisodeId = "ep-1",
                currentUrl = "http://host/stream.ts", playbackSource = PlaybackSource.IPTV
            )
        )
    }

    @Test
    fun `playlist never drives debrid playback, and not while loading or already on the episode`() {
        assertFalse(
            shouldPlaylistDrivePlayback(
                isLoading = false, playlistEpisodeId = "ep-2", currentEpisodeId = "ep-1",
                currentUrl = null, playbackSource = PlaybackSource.DEBRID
            )
        )
        assertFalse(
            shouldPlaylistDrivePlayback(
                isLoading = true, playlistEpisodeId = "ep-2", currentEpisodeId = "ep-1",
                currentUrl = null, playbackSource = PlaybackSource.IPTV
            )
        )
        assertFalse(
            shouldPlaylistDrivePlayback(
                isLoading = false, playlistEpisodeId = "ep-1", currentEpisodeId = "ep-1",
                currentUrl = null, playbackSource = PlaybackSource.IPTV
            )
        )
        assertFalse(
            shouldPlaylistDrivePlayback(
                isLoading = false, playlistEpisodeId = null, currentEpisodeId = "ep-1",
                currentUrl = null, playbackSource = PlaybackSource.IPTV
            )
        )
    }

    @Test
    fun `an id that is really the stream url is not an episode id`() {
        assertEquals("ep-1", iptvPlaylistEpisodeId("ep-1", "http://host/stream.ts"))
        assertNull(iptvPlaylistEpisodeId("http://host/stream.ts", "http://host/stream.ts"))
        assertNull(iptvPlaylistEpisodeId(null, null))
        assertNull(iptvPlaylistEpisodeId("  ", null))
    }

    @Test
    fun `fallback image prefers the poster and rejects the literal null`() {
        assertEquals("poster", episodeBrowserFallbackImageUrl("poster", "backdrop"))
        assertEquals("backdrop", episodeBrowserFallbackImageUrl(null, "backdrop"))
        assertEquals("backdrop", episodeBrowserFallbackImageUrl("null", "backdrop"))
        assertEquals("backdrop", episodeBrowserFallbackImageUrl("  ", "backdrop"))
        assertNull(episodeBrowserFallbackImageUrl("NULL", "null"))
    }
}
