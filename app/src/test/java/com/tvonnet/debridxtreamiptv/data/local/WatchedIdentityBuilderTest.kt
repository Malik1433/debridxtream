package com.tvonnet.debridxtreamiptv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B3 — the watched-state identity key.
 *
 * This string is the primary key of `watched_state`, so it has to satisfy two things that pull in
 * opposite directions: it must be **stable** (the same episode watched twice is one row, and a key
 * that shifts orphans the user's history), and it must be **safe** — the ids handed to it include
 * stream URLs, magnets and Debrid tokens, and none of those may ever be written into the database
 * or into a log line. `isUnsafeIdentity` is that filter, and it is the part most worth pinning.
 */
class WatchedIdentityBuilderTest {

    // ── stability ────────────────────────────────────────────────────────────

    @Test
    fun `an IPTV movie keys off its stream id`() {
        assertEquals(
            "movie:xtream:stream:12345",
            WatchedIdentityBuilder.iptvMovie(streamId = "12345", title = "Inception", year = "2010")
        )
    }

    @Test
    fun `without a stream id an IPTV movie falls back to title and year`() {
        assertEquals(
            "movie:xtream:title:inception:year:2010",
            WatchedIdentityBuilder.iptvMovie(streamId = null, title = "Inception", year = "2010")
        )
    }

    @Test
    fun `a debrid movie prefers tmdb, then imdb, then title`() {
        assertEquals(
            "movie:debrid:tmdb:27205",
            WatchedIdentityBuilder.debridMovie(tmdbId = "27205", imdbId = "tt1375666", title = "Inception")
        )
        assertEquals(
            "movie:debrid:imdb:tt1375666",
            WatchedIdentityBuilder.debridMovie(tmdbId = null, imdbId = "tt1375666", title = "Inception")
        )
        assertEquals(
            "movie:debrid:title:inception:year:2010",
            WatchedIdentityBuilder.debridMovie(title = "Inception", year = "2010")
        )
    }

    @Test
    fun `an episode keys off its episode id when it has one`() {
        assertEquals(
            "episode:xtream:episode:998877",
            WatchedIdentityBuilder.iptvEpisode(episodeId = "998877", seriesId = "9911", seasonNumber = 1, episodeNumber = 2)
        )
    }

    @Test
    fun `without an episode id the season and episode numbers keep episodes apart`() {
        val first = WatchedIdentityBuilder.iptvEpisode(null, seriesId = "9911", seasonNumber = 1, episodeNumber = 1)
        val second = WatchedIdentityBuilder.iptvEpisode(null, seriesId = "9911", seasonNumber = 1, episodeNumber = 2)

        assertEquals("episode:xtream:series:9911:s:1:e:1", first)
        assertNotEquals("two episodes of one series must not share a row", first, second)
    }

    /** Missing numbers would collapse a whole season into one row, so a discriminator is added. */
    @Test
    fun `an episode with no numbering falls back to a discriminator`() {
        val key = WatchedIdentityBuilder.debridEpisode(
            seriesIdentity = "tt0903747",
            seasonNumber = null,
            episodeNumber = null,
            fallbackDiscriminator = "Cat's in the Bag"
        )

        assertEquals("episode:debrid:series:tt0903747:s:unknown:e:unknown:fallback:cat-s-in-the-bag", key)
    }

    @Test
    fun `normalization makes provider spelling irrelevant`() {
        assertEquals("the-lord-of-the-rings", WatchedIdentityBuilder.normalizeKeyPart("  The Lord of the Rings!  "))
        assertEquals("unknown", WatchedIdentityBuilder.normalizeKeyPart(null))
        assertEquals("unknown", WatchedIdentityBuilder.normalizeKeyPart("  ---  "))
    }

    // ── safety: secrets must never become a database key ─────────────────────

    @Test
    fun `a stream url is never used as an identity`() {
        val key = WatchedIdentityBuilder.iptvMovie(
            streamId = "http://provider.test:8080/movie/malik/hunter22/1.mp4",
            title = "Inception",
            year = "2010"
        )

        assertFalse("credentials must never reach the database key", key.contains("hunter22"))
        assertEquals("movie:xtream:title:inception:year:2010", key)
    }

    @Test
    fun `magnets, tokens and credentials are all rejected`() {
        listOf(
            "magnet:?xt=urn:btih:abc",
            "https://api.test/x?access_token=SEKRET",
            "https://api.test/x?username=malik&password=hunter22",
            "https://api.test/x?api_key=SEKRET"
        ).forEach { unsafe ->
            assertNull("'$unsafe' must not survive as an id part", WatchedIdentityBuilder.stableIdPart(unsafe))
        }
    }

    /** An info-hash identifies a specific torrent, not a film — using it would fragment history. */
    @Test
    fun `an info hash is rejected as an identity`() {
        assertNull(WatchedIdentityBuilder.stableIdPart("0123456789abcdef0123456789abcdef01234567"))
        assertNull(WatchedIdentityBuilder.stableIdPart("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"))
    }

    @Test
    fun `ordinary ids pass through untouched`() {
        assertEquals("12345", WatchedIdentityBuilder.stableIdPart("12345"))
        assertEquals("tt1375666", WatchedIdentityBuilder.stableIdPart("  tt1375666  "))
        assertNull(WatchedIdentityBuilder.stableIdPart(""))
        assertNull(WatchedIdentityBuilder.stableIdPart(null))
    }

    @Test
    fun `keys never leak anything that looks like a secret`() {
        val keys = listOf(
            WatchedIdentityBuilder.iptvMovie("http://u:p@host/x.mp4", "Film", "2020"),
            WatchedIdentityBuilder.debridMovie(tmdbId = "magnet:?xt=urn:btih:abc", title = "Film", year = "2020"),
            WatchedIdentityBuilder.debridEpisode("https://api.test/?token=SEKRET", 1, 2, "Show", "2020")
        )

        keys.forEach { key ->
            assertFalse(key, key.contains("://"))
            assertFalse(key, key.contains("magnet"))
            assertFalse(key, key.contains("SEKRET", ignoreCase = true))
        }
        assertTrue(keys.all { it.isNotBlank() })
    }
}
