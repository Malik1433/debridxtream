package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Freezes the **T1.5 episode-continuity** rules — the behaviour that previously could only be checked
 * by playing a real series to its end on the TV (the long-pending manual QA):
 *
 *  - `99d10b9` — at the END of the FINAL episode there must be NO "ghost" advance for an episode that
 *    does not exist. Auto-advance happens only when the playlist genuinely `hasNext`.
 *  - `a969022` — while a next-episode Debrid resolve is already in flight, STATE_ENDED on the OLD
 *    stream must neither advance again (double-advance = skipped episode) nor `finish()` the activity
 *    out from under the cinematic loader.
 *
 * These cases are exactly the ones that are painful to reproduce by hand: they need a series played to
 * its last frame, and a race between the countdown and the stream ending.
 */
class PlayerEndedActionTest {

    // ── The reported bug: end of the FINAL episode ──────────────────────────────────────────────

    @Test
    fun `final episode ends - completes and finishes, never advances`() {
        val action = endedActionFor(
            isSeriesLike = true,
            isResolvingDebrid = false,
            hasNext = false,
            isNextPromptVisible = false,
        )
        assertEquals(EndedAction.COMPLETE_AND_FINISH, action)
    }

    @Test
    fun `final episode ends while the prompt is up - completes but keeps the screen`() {
        val action = endedActionFor(
            isSeriesLike = true,
            isResolvingDebrid = false,
            hasNext = false,
            isNextPromptVisible = true,
        )
        assertEquals(EndedAction.COMPLETE_AND_KEEP_PROMPT, action)
    }

    // ── Normal continuity: a middle episode ─────────────────────────────────────────────────────

    @Test
    fun `middle episode ends - advances exactly once`() {
        val action = endedActionFor(
            isSeriesLike = true,
            isResolvingDebrid = false,
            hasNext = true,
            isNextPromptVisible = false,
        )
        assertEquals(EndedAction.ADVANCE_TO_NEXT, action)
    }

    @Test
    fun `a visible prompt does not stop a real advance`() {
        // The prompt being on screen must not suppress the advance when there IS a next episode —
        // otherwise the countdown would finish and nothing would play.
        val action = endedActionFor(
            isSeriesLike = true,
            isResolvingDebrid = false,
            hasNext = true,
            isNextPromptVisible = true,
        )
        assertEquals(EndedAction.ADVANCE_TO_NEXT, action)
    }

    // ── The race guard (a969022) ────────────────────────────────────────────────────────────────

    @Test
    fun `resolve in flight - never double-advances`() {
        val action = endedActionFor(
            isSeriesLike = true,
            isResolvingDebrid = true,
            hasNext = true,
            isNextPromptVisible = false,
        )
        assertEquals(EndedAction.WAIT_FOR_RESOLVE, action)
    }

    @Test
    fun `resolve in flight on the final episode - never finishes under the loader`() {
        // hasNext is false here (last episode) but a resolve is still running: finishing would kill
        // the activity while the cinematic loader is on screen.
        val action = endedActionFor(
            isSeriesLike = true,
            isResolvingDebrid = true,
            hasNext = false,
            isNextPromptVisible = false,
        )
        assertEquals(EndedAction.WAIT_FOR_RESOLVE, action)
    }

    // ── Non-series content is unaffected ────────────────────────────────────────────────────────

    @Test
    fun `a movie ends - finishes, and series flags cannot leak into it`() {
        assertEquals(
            EndedAction.COMPLETE_AND_FINISH,
            endedActionFor(
                isSeriesLike = false,
                isResolvingDebrid = false,
                hasNext = false,
                isNextPromptVisible = false,
            )
        )
        // Even if stale series state says "hasNext"/"resolving", a movie must still just finish.
        assertEquals(
            EndedAction.COMPLETE_AND_FINISH,
            endedActionFor(
                isSeriesLike = false,
                isResolvingDebrid = true,
                hasNext = true,
                isNextPromptVisible = false,
            )
        )
    }
}
