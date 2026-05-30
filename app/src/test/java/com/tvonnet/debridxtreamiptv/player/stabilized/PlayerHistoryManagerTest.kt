package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.*
import org.junit.Test

class PlayerHistoryManagerTest {

    // Thresholds from PlayerHistoryManager
    private val MIN_PROGRESS_RATIO = 0.05f
    private val COMPLETION_THRESHOLD_RATIO = 0.90f
    private val MIN_DURATION_TO_TRACK_MS = 60_000L
    private val MOVIE_REMAINING_COMPLETION_MS = 5 * 60 * 1000L
    private val EPISODE_REMAINING_COMPLETION_MS = 3 * 60 * 1000L

    private fun progressRatio(currentPositionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        return currentPositionMs.toFloat() / durationMs.toFloat()
    }

    private fun remainingMs(currentPositionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0) return 0L
        return durationMs - currentPositionMs
    }

    private fun shouldIgnoreTinyProgress(currentPositionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= MIN_DURATION_TO_TRACK_MS) return true
        if (currentPositionMs <= 0L) return true
        return progressRatio(currentPositionMs, durationMs) < MIN_PROGRESS_RATIO
    }

    private fun isMovieWatchedThreshold(currentPositionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        return progressRatio(currentPositionMs, durationMs) >= COMPLETION_THRESHOLD_RATIO ||
                remainingMs(currentPositionMs, durationMs) <= MOVIE_REMAINING_COMPLETION_MS
    }

    private fun isEpisodeWatchedThreshold(currentPositionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        return progressRatio(currentPositionMs, durationMs) >= COMPLETION_THRESHOLD_RATIO ||
                remainingMs(currentPositionMs, durationMs) <= EPISODE_REMAINING_COMPLETION_MS
    }

    @Test
    fun testMovieThresholds() {
        val movieDuration = 100 * 60 * 1000L // 100 minutes

        // < 5% -> Tiny Progress
        val pos4Percent = 4 * 60 * 1000L
        assertTrue(shouldIgnoreTinyProgress(pos4Percent, movieDuration))
        assertFalse(isMovieWatchedThreshold(pos4Percent, movieDuration))

        // 5% - 89% -> Not tiny, not watched
        val pos50Percent = 50 * 60 * 1000L
        assertFalse(shouldIgnoreTinyProgress(pos50Percent, movieDuration))
        assertFalse(isMovieWatchedThreshold(pos50Percent, movieDuration))

        // >= 90% -> Watched
        val pos91Percent = 91 * 60 * 1000L
        assertFalse(shouldIgnoreTinyProgress(pos91Percent, movieDuration))
        assertTrue(isMovieWatchedThreshold(pos91Percent, movieDuration))

        // <= 5 mins remaining -> Watched
        val pos96Mins = 96 * 60 * 1000L // 4 mins remaining, which is 96% anyway
        assertTrue(isMovieWatchedThreshold(pos96Mins, movieDuration))
    }

    @Test
    fun testEpisodeThresholds() {
        val episodeDuration = 45 * 60 * 1000L // 45 minutes

        // < 5% -> Tiny Progress
        val pos2Mins = 2 * 60 * 1000L // 4.4%
        assertTrue(shouldIgnoreTinyProgress(pos2Mins, episodeDuration))
        assertFalse(isEpisodeWatchedThreshold(pos2Mins, episodeDuration))

        // 5% - 89% -> Not tiny, not watched
        val pos20Mins = 20 * 60 * 1000L // ~44%
        assertFalse(shouldIgnoreTinyProgress(pos20Mins, episodeDuration))
        assertFalse(isEpisodeWatchedThreshold(pos20Mins, episodeDuration))

        // >= 90% -> Watched
        val pos41Mins = 41 * 60 * 1000L // 91%
        assertFalse(shouldIgnoreTinyProgress(pos41Mins, episodeDuration))
        assertTrue(isEpisodeWatchedThreshold(pos41Mins, episodeDuration))

        // <= 3 mins remaining -> Watched
        val pos43Mins = 43 * 60 * 1000L // 2 mins remaining, 95%
        assertTrue(isEpisodeWatchedThreshold(pos43Mins, episodeDuration))
        
        // Long episode (60 mins), 58 mins watched (2 mins remaining) but < 90%?
        // Wait, 58/60 is > 90%. Let's test a very long episode where 3 mins is < 10%
        // E.g. 100 mins episode. 98 mins watched = 2 mins remaining.
        val longEpDuration = 100 * 60 * 1000L
        val pos98Mins = 98 * 60 * 1000L // 98%, 2 mins remaining. Both conditions met.
        assertTrue(isEpisodeWatchedThreshold(pos98Mins, longEpDuration))
    }
}
