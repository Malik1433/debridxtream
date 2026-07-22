package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Freezes the debug-overlay text built by the delegate extracted in P8. */
class PlayerDebugOverlayTest {

    private fun snapshot(
        playbackState: Int = Player.STATE_READY,
        playWhenReady: Boolean = true,
        positionMs: Long = 0L,
        bufferedPositionMs: Long = 0L,
        switchCount: Int = 0,
        playbackSource: PlaybackSource = PlaybackSource.IPTV,
        url: String? = null
    ) = PlayerDebugSnapshot(
        playbackState, playWhenReady, positionMs, bufferedPositionMs,
        switchCount, playbackSource, url
    )

    @Test
    fun `renders the four lines with mm ss clocks`() {
        val text = buildDebugOverlayText(
            snapshot(
                positionMs = 65_000L,
                bufferedPositionMs = 125_000L,
                switchCount = 2,
                playbackSource = PlaybackSource.DEBRID,
                url = "http://host/file.mkv"
            )
        )

        assertEquals(
            "State: READY | Playing: true\n" +
                "Pos: 01:05 | Buf: 02:05\n" +
                "Switches: 2 | Src: DEBRID\n" +
                "URL: http://host/file.mkv",
            text
        )
    }

    @Test
    fun `maps every player state`() {
        assertTrue(buildDebugOverlayText(snapshot(playbackState = Player.STATE_IDLE)).startsWith("State: IDLE"))
        assertTrue(buildDebugOverlayText(snapshot(playbackState = Player.STATE_BUFFERING)).startsWith("State: BUFFER"))
        assertTrue(buildDebugOverlayText(snapshot(playbackState = Player.STATE_ENDED)).startsWith("State: ENDED"))
        assertTrue(buildDebugOverlayText(snapshot(playbackState = 99)).startsWith("State: UNKNOWN"))
    }

    @Test
    fun `truncates a long url and shows Null when there is none`() {
        val long = "http://host/" + "a".repeat(80)
        val line = buildDebugOverlayText(snapshot(url = long)).substringAfter("URL: ")

        assertEquals(55, line.length)
        assertTrue(line.endsWith("..."))
        assertEquals("Null", buildDebugOverlayText(snapshot(url = null)).substringAfter("URL: "))
    }
}
