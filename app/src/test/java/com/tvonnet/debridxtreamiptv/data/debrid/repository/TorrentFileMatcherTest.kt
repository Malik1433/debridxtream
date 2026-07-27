package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentFile
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap C6 — picking the right file out of a torrent.
 *
 * A season pack is forty files named however the release group felt like naming them, and this
 * decides both which file Real-Debrid is told to select and which resolved link is played. Every
 * failure mode is user-visible: the wrong episode, a sample clip, or a resolution that dies with
 * "episode not found" while a perfectly playable video sits in the torrent.
 *
 * The two shipped-bug behaviours are pinned deliberately, because both look like cruft:
 *  - **A3** — a hinted torrent with no `SxxEyy` filename falls back to the largest selected video
 *    instead of returning null;
 *  - **A2** — `.m4v` counts as video (it was accepted as a direct stream URL but rejected here, so
 *    a `.m4v`-only torrent sat in `waiting_files_selection` until it timed out).
 */
class TorrentFileMatcherTest {

    private val hint = EpisodeHint(season = 1, episode = 2, title = null)

    private fun file(id: Int, path: String, bytes: Long = 1_000L, selected: Int = 1) =
        RealDebridTorrentFile(id = id, path = path, bytes = bytes, selected = selected)

    private fun torrent(files: List<RealDebridTorrentFile>?, links: List<String>?) =
        RealDebridTorrentInfoResponse(
            id = "t1", filename = null, originalFilename = null, status = "downloaded",
            progress = 100.0, seeders = 1, speed = 0L, links = links, files = files
        )

    // ── scoring ────────────────────────────────────────────────────────────────

    @Test
    fun `an explicit SxxEyy filename scores highest`() {
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("Show.S01E02.1080p.mkv", hint))
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("Show s1 e2 720p.mkv", hint))
        // A double episode counts for either of its numbers.
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("Show.S01E01E02.mkv", hint))
    }

    @Test
    fun `the 1x02 form scores just below SxxEyy`() {
        assertEquals(95, TorrentFileMatcher.scoreEpisodeMatch("Show 1x02 HDTV.mkv", hint))
        assertEquals(95, TorrentFileMatcher.scoreEpisodeMatch("Show 1x01-02.mkv", hint))
    }

    @Test
    fun `spelled-out season and episode score below the numeric forms`() {
        assertEquals(90, TorrentFileMatcher.scoreEpisodeMatch("Show - Season 1 Episode 2.mkv", hint))
        // Episode-only is much weaker evidence: it says nothing about the season.
        assertEquals(60, TorrentFileMatcher.scoreEpisodeMatch("Show - Ep 2.mkv", hint))
    }

    @Test
    fun `a wrong season or episode scores nothing`() {
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("Show.S02E02.mkv", hint))
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("Show.S01E03.mkv", hint))
    }

    @Test
    fun `samples trailers and extras can never win, however well named`() {
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("Show.S01E02.sample.mkv", hint))
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("Show.S01E02.trailer.mkv", hint))
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("Show/extras.S01E02.mkv", hint))
    }

    @Test
    fun `a bonus folder excludes its files even when they are named like episodes`() {
        // Was a real gap: the check used to run on substringAfterLast('/') only, so a featurette
        // in an `extras/` FOLDER scored a perfect 100 and could outrank the real episode.
        listOf(
            "extras/Show.S01E02.mkv",
            "Show.S01.1080p/Featurettes/Show.S01E02.mkv",
            "Show/Bonus/Show.S01E02.mkv",
            "Show/Deleted Scenes/Show.S01E02.mkv",
            "/Show/Samples/Show.S01E02.mkv",
        ).forEach {
            assertEquals("'$it' must be excluded", 0, TorrentFileMatcher.scoreEpisodeMatch(it, hint))
        }
    }

    @Test
    fun `a show whose own name contains a bonus word is not excluded`() {
        // The folder test matches whole path SEGMENTS, never substrings — "Trailer Park Boys" is a
        // real show, and a substring test would zero out every file in it.
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("Trailer Park Boys S01/TPB.S01E02.mkv", hint))
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("Extraordinary/Show.S01E02.mkv", hint))
        // Only the folder is checked this way; a legitimate season folder still passes.
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("Show.S01.COMPLETE/Show.S01E02.mkv", hint))
    }

    @Test
    fun `the real episode outranks a same-named file sitting in extras`() {
        val info = torrent(
            files = listOf(
                file(1, "Show/Extras/Show.S01E02.mkv", bytes = 9_000_000_000L),
                file(2, "Show/Show.S01E02.mkv", bytes = 1_000_000_000L),
            ),
            links = listOf("https://rd/extras", "https://rd/episode"),
        )
        // Even though the featurette is the larger file, only the real episode scores.
        assertEquals("https://rd/episode", TorrentFileMatcher.pickVideoLink(info, hint))
        assertEquals(listOf(2), TorrentFileMatcher.selectFileIds(info.files, hint))
    }

    @Test
    fun `the episode title is a last resort, scored by how much of it matches`() {
        val titled = EpisodeHint(season = 1, episode = 2, title = "The Long Night Falls")
        assertEquals(50, TorrentFileMatcher.scoreEpisodeMatch("show.the.long.night.falls.mkv", titled))
        assertEquals(40, TorrentFileMatcher.scoreEpisodeMatch("show.long.night.mkv", titled))
        assertEquals(30, TorrentFileMatcher.scoreEpisodeMatch("show.night.mkv", titled))
        // Short words are ignored, so a title of only short words gives no signal at all.
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("show.pilot.mkv", EpisodeHint(1, 2, "Pi Lo")))
    }

    @Test
    fun `a numeric match always beats a title match`() {
        val titled = EpisodeHint(season = 1, episode = 2, title = "The Long Night Falls")
        assertEquals(100, TorrentFileMatcher.scoreEpisodeMatch("show.s01e02.the.long.night.falls.mkv", titled))
    }

    @Test
    fun `a missing path scores nothing rather than throwing`() {
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch(null, hint))
        assertEquals(0, TorrentFileMatcher.scoreEpisodeMatch("   ", hint))
    }

    // ── file selection ─────────────────────────────────────────────────────────

    @Test
    fun `with a hint only the matching episode is selected`() {
        val files = listOf(
            file(1, "Show/Show.S01E01.mkv"),
            file(2, "Show/Show.S01E02.mkv"),
            file(3, "Show/Show.S01E03.mkv"),
        )
        assertEquals(listOf(2), TorrentFileMatcher.selectFileIds(files, hint))
    }

    @Test
    fun `without a hint every video file is selected`() {
        val files = listOf(
            file(1, "Show/Show.S01E01.mkv"),
            file(2, "Show/readme.txt"),
            file(3, "Show/Show.S01E02.mkv"),
        )
        assertEquals(listOf(1, 3), TorrentFileMatcher.selectFileIds(files, null))
    }

    @Test
    fun `when nothing matches the hint the largest video is selected anyway`() {
        // A single-episode torrent named without SxxEyy must still play.
        val files = listOf(
            file(1, "Show/featurette.mkv", bytes = 50_000L),
            file(2, "Show/the.main.feature.mkv", bytes = 4_000_000_000L),
        )
        assertEquals(listOf(2), TorrentFileMatcher.selectFileIds(files, hint))
    }

    @Test
    fun `among equally-scoring matches the largest file wins`() {
        val files = listOf(
            file(1, "Show/Show.S01E02.480p.mkv", bytes = 200_000_000L),
            file(2, "Show/Show.S01E02.1080p.mkv", bytes = 3_000_000_000L),
        )
        assertEquals(listOf(2), TorrentFileMatcher.selectFileIds(files, hint))
    }

    @Test
    fun `a torrent with no video files selects nothing`() {
        val files = listOf(file(1, "Show/readme.txt"), file(2, "Show/cover.jpg"))
        assertTrue(TorrentFileMatcher.selectFileIds(files, hint).isEmpty())
        assertTrue(TorrentFileMatcher.selectFileIds(null, hint).isEmpty())
    }

    @Test
    fun `m4v counts as video so an m4v-only torrent is not stranded`() {
        // A2: this disagreeing with isDirectStreamUrl left the torrent in
        // waiting_files_selection until it timed out.
        assertTrue(TorrentFileMatcher.isVideoFile("Show/Show.S01E02.m4v"))
        assertEquals(listOf(1), TorrentFileMatcher.selectFileIds(listOf(file(1, "Show/Show.S01E02.m4v")), hint))
        assertFalse(TorrentFileMatcher.isVideoFile("Show/notes.nfo"))
        assertFalse(TorrentFileMatcher.isVideoFile(null))
    }

    // ── link picking ───────────────────────────────────────────────────────────

    @Test
    fun `the link matching the wanted episode is picked by selected-file position`() {
        val info = torrent(
            files = listOf(
                file(1, "Show/Show.S01E01.mkv"),
                file(2, "Show/Show.S01E02.mkv"),
            ),
            links = listOf("https://rd/e01", "https://rd/e02"),
        )
        assertEquals("https://rd/e02", TorrentFileMatcher.pickVideoLink(info, hint))
    }

    @Test
    fun `unselected files do not shift the link index`() {
        // Only `selected == 1` files line up with the links array; counting the others
        // would return the neighbouring episode.
        val info = torrent(
            files = listOf(
                file(9, "Show/Show.S01E01.mkv", selected = 0),
                file(1, "Show/Show.S01E02.mkv", selected = 1),
            ),
            links = listOf("https://rd/e02"),
        )
        assertEquals("https://rd/e02", TorrentFileMatcher.pickVideoLink(info, hint))
    }

    @Test
    fun `A3 - an unmatched hinted torrent falls back to the largest video, not to failure`() {
        val info = torrent(
            files = listOf(
                file(1, "Show/part.one.mkv", bytes = 100L),
                file(2, "Show/part.two.mkv", bytes = 9_000L),
            ),
            links = listOf("https://rd/small", "https://rd/large"),
        )
        // Returning null here failed the whole resolution with "episode not found" even
        // though a playable video exists.
        assertEquals("https://rd/large", TorrentFileMatcher.pickVideoLink(info, hint))
    }

    @Test
    fun `without a hint or without file metadata the first link is used`() {
        val info = torrent(files = null, links = listOf("https://rd/only"))
        assertEquals("https://rd/only", TorrentFileMatcher.pickVideoLink(info, hint))
        assertEquals("https://rd/only", TorrentFileMatcher.pickVideoLink(info, null))
    }

    @Test
    fun `a torrent with no links yields null`() {
        assertNull(TorrentFileMatcher.pickVideoLink(torrent(files = null, links = null), hint))
    }
}
