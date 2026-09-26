package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the pure player helpers extracted in P6 — they were untestable while they
 * lived inside the Activity, so this is the first coverage they have ever had.
 */
class PlayerHelpersTest {

    @Test
    fun `cleanTitle cuts the release junk and parenthesises the year`() {
        assertEquals(
            "Disclosure Day (2026)",
            cleanTitle("Disclosure.Day.2026.1080p.WEBRip.x265")
        )
    }

    @Test
    fun `cleanTitle leaves an already clean title alone`() {
        assertEquals("The Odyssey", cleanTitle("The Odyssey"))
    }

    @Test
    fun `cleanLoaderTitle strips scene tokens and provider tags`() {
        assertEquals("Backrooms", cleanLoaderTitle("Backrooms.2026.MULTi.720p.HDTS"))
        assertEquals("The Odyssey", cleanLoaderTitle("|EN| The Odyssey 2026 1080p WEB-DL"))
    }

    @Test
    fun `cleanLoaderTitle survives a dotted scene filename`() {
        // The exact string a debrid source pick put on the detail page after a failed play
        // (2026-09-08, Fire TV). PlayerExitController.failureDetailTitle leans on this.
        assertEquals(
            "the runner",
            cleanLoaderTitle("the.runner.2026.german.dl.1080p.web.h264.proper-sauerkraut")
        )
    }

    @Test
    fun `cleanLoaderTitle falls back to Loading when there is nothing to show`() {
        assertEquals("Loading", cleanLoaderTitle(null))
        assertEquals("Loading", cleanLoaderTitle("   "))
    }

    @Test
    fun `resolveMediaMimeType prefers the url extension`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            resolveMediaMimeType("http://host/live/stream.m3u8?token=1", emptyList(), null)
        )
        assertEquals(
            MimeTypes.VIDEO_MP2T,
            resolveMediaMimeType("http://host/live/1234.ts", emptyList(), null)
        )
        assertEquals(
            "video/x-matroska",
            resolveMediaMimeType("http://host/file.mkv", emptyList(), null)
        )
    }

    @Test
    fun `resolveMediaMimeType falls back to source hints then headers`() {
        assertEquals(
            MimeTypes.VIDEO_MP4,
            resolveMediaMimeType("http://host/opaque/id", listOf(null, "Movie.2026.mp4"), null)
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            resolveMediaMimeType(
                "http://host/opaque/id",
                emptyList(),
                mapOf("Content-Type" to "application/dash+xml")
            )
        )
    }

    @Test
    fun `resolveMediaMimeType ignores headers other than Accept and Content-Type`() {
        assertNull(
            resolveMediaMimeType(
                "http://host/opaque/id",
                emptyList(),
                mapOf("User-Agent" to "video/mp4 player")
            )
        )
    }

    @Test
    fun `frameRateMismatch is zero on an exact multiple and huge below the frame rate`() {
        assertEquals(0f, frameRateMismatch(frameRate = 24f, refreshRate = 48f), 0.0001f)
        assertEquals(0f, frameRateMismatch(frameRate = 25f, refreshRate = 50f), 0.0001f)
        assertTrue(frameRateMismatch(frameRate = 60f, refreshRate = 50f) == Float.MAX_VALUE)
        // 60Hz against 24fps judders: 2.5 is half a frame away from a whole multiple.
        assertEquals(0.5f, frameRateMismatch(frameRate = 24f, refreshRate = 60f), 0.0001f)
    }

    @Test
    fun `qoeMode buckets live above debrid above vod`() {
        assertEquals("live", qoeMode(ContentType.LIVE_TV, PlaybackSource.DEBRID))
        assertEquals("debrid", qoeMode(ContentType.MOVIE, PlaybackSource.DEBRID))
        assertEquals("vod", qoeMode(ContentType.MOVIE, PlaybackSource.IPTV))
        assertEquals("vod", qoeMode(null, PlaybackSource.IPTV))
    }
    // F1 Phase 0: these names are what the on-device QA greps for. A renamed or shifted constant
    // would silently turn "AUDIO_BECOMING_NOISY" into "UNKNOWN(3)" in the one line that matters.
    @Test
    fun `playWhenReady reasons are named`() {
        assertEquals("USER_REQUEST", playWhenReadyReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertEquals("AUDIO_FOCUS_LOSS", playWhenReadyReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertEquals("AUDIO_BECOMING_NOISY", playWhenReadyReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
        assertEquals("REMOTE", playWhenReadyReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE))
        assertEquals("END_OF_MEDIA_ITEM", playWhenReadyReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        assertEquals("SUPPRESSED_TOO_LONG", playWhenReadyReasonName(6))
        assertEquals("UNKNOWN(99)", playWhenReadyReasonName(99))
    }

    @Test
    fun `playback suppression reasons are named`() {
        assertEquals("NONE", playbackSuppressionReasonName(Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertEquals(
            "TRANSIENT_AUDIO_FOCUS_LOSS",
            playbackSuppressionReasonName(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        )
        assertEquals("UNSUITABLE_AUDIO_OUTPUT", playbackSuppressionReasonName(3))
        assertEquals("UNKNOWN(42)", playbackSuppressionReasonName(42))
    }
}
