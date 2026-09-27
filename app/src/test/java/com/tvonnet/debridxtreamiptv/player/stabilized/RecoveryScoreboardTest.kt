package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.player.stabilized.RecoveryScoreboard.Kind
import com.tvonnet.debridxtreamiptv.player.stabilized.RecoveryScoreboard.Tally
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recovery scoreboard's outcome rules. The event names are the ones the recovery code already
 * emits through PlaybackDiagnosticsRecorder - a renamed event would silently stop being counted,
 * so the names are part of what this freezes.
 */
class RecoveryScoreboardTest {

    private var now = 0L
    private val board = RecoveryScoreboard { now }

    private fun tally(kind: Kind) = board.tallies()[kind]

    @Test
    fun `a recovery followed by a rendered frame counts as ok`() {
        board.onEvent("stall_triggered")
        board.onEvent("retry_triggered")
        now += 4_000
        board.onEvent("first_frame_rendered")
        assertEquals(Tally(tries = 1, ok = 1), tally(Kind.STALL))
        assertEquals(Tally(tries = 1, ok = 1), tally(Kind.RETRY))
    }

    @Test
    fun `a terminal failure fails what was pending`() {
        board.onEvent("audio_sink_recovery")
        board.onEvent("audio_wedge_escape")
        board.onEvent("audio_wedge_both_routes")
        assertEquals(Tally(tries = 1, failed = 1), tally(Kind.AUDIO_SINK))
        assertEquals(Tally(tries = 1, failed = 1), tally(Kind.AUDIO_ESCAPE))
    }

    @Test
    fun `silence past the window is a failure, not a later success`() {
        board.onEvent("video_freeze_detected")
        now += RecoveryScoreboard.RESOLVE_WINDOW_MS + 1
        board.onEvent("first_frame_rendered")
        assertEquals(Tally(tries = 1, failed = 1), tally(Kind.VIDEO_FREEZE))
    }

    @Test
    fun `returning to the source list is a hand-off, leaving is abandoned`() {
        board.onEvent("direct_debrid_refresh_started")
        board.onEvent("return_to_sources")
        board.onEvent("black_video_tunneling_retry")
        board.close()
        assertEquals(Tally(tries = 1, handedOff = 1), tally(Kind.DEBRID_REFRESH))
        assertEquals(Tally(tries = 1, abandoned = 1), tally(Kind.BLACK_VIDEO))
    }

    @Test
    fun `an unexpected-pause resume resolves only its own kind`() {
        board.onEvent("retry_triggered")
        board.onEvent("unexpected_pause_resume")
        board.onEvent("unexpected_pause_resume_ok")
        board.close()
        assertEquals(Tally(tries = 1, ok = 1), tally(Kind.UNEXPECTED_PAUSE))
        assertEquals(Tally(tries = 1, abandoned = 1), tally(Kind.RETRY))
    }

    @Test
    fun `unrelated events and a first frame with nothing pending change nothing`() {
        board.onEvent("player_state_changed")
        board.onEvent("first_frame_rendered")
        assertEquals("recovery scoreboard mode=vod none", board.logLine("vod"))
    }

    @Test
    fun `the log line lists every kind that ran`() {
        board.onEvent("stall_triggered")
        board.onEvent("first_frame_rendered")
        board.onEvent("retry_triggered")
        board.close()
        assertEquals(
            "recovery scoreboard mode=debrid stall=1/1/0/0/0 retry=1/0/0/0/1 (tries/ok/failed/handed_off/abandoned)",
            board.logLine("debrid")
        )
    }

    @Test
    fun `rebuffer ratio is per mille of viewing time`() {
        assertEquals(0, rebufferPermille(0L, 0L))
        assertEquals(0, rebufferPermille(0L, 60_000L))
        assertEquals(50, rebufferPermille(3_000L, 57_000L))
        assertEquals(1000, rebufferPermille(5_000L, 0L))
    }
}
