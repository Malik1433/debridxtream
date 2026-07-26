package com.tvonnet.debridxtreamiptv.player

import android.content.ComponentCallbacks2
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerRetentionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B2.5 — the retained-player rules.
 *
 * The roadmap listed the "OOM soak" as the last genuinely device-only item. Most of it is not: the
 * three collaborating call sites (`onStop`'s retained branch, `onHostTrimMemory`, and the
 * `!isResolvingDebrid` play-restore) are *decisions*, and decisions can be frozen. What remains
 * device-only is the duration question — does the heap actually stay flat over hours of
 * background/foreground churn — which the diagnostics memory samples answer, not a unit test.
 *
 * Same template as `PlayerEndedActionTest`: pull the decision into a pure function, then pin it.
 */
class PlayerRetentionPolicyTest {

    // ── onStop ───────────────────────────────────────────────────────────────

    /**
     * The `max_connections=1` rule. A retained LIVE connection holds the account's only slot while
     * the app is in the background, locking the user out of their own service.
     */
    @Test
    fun `live always releases on stop`() {
        assertTrue(PlayerRetentionPolicy.shouldReleaseOnStop(ContentType.LIVE_TV, isFinishing = false))
        assertTrue(PlayerRetentionPolicy.shouldReleaseOnStop(ContentType.LIVE_TV, isFinishing = true))
    }

    @Test
    fun `vod and episodes are retained across a short stop`() {
        assertFalse(
            "retention is what makes Home→return instant instead of a 1-3s cold reconnect",
            PlayerRetentionPolicy.shouldReleaseOnStop(ContentType.MOVIE, isFinishing = false)
        )
        assertFalse(PlayerRetentionPolicy.shouldReleaseOnStop(ContentType.EPISODE, isFinishing = false))
    }

    @Test
    fun `a finishing session releases — there is nothing to come back to`() {
        assertTrue(PlayerRetentionPolicy.shouldReleaseOnStop(ContentType.MOVIE, isFinishing = true))
        assertTrue(PlayerRetentionPolicy.shouldReleaseOnStop(ContentType.EPISODE, isFinishing = true))
    }

    @Test
    fun `an unknown content type is retained like VOD, not treated as live`() {
        assertFalse(PlayerRetentionPolicy.shouldReleaseOnStop(null, isFinishing = false))
        assertTrue(PlayerRetentionPolicy.shouldReleaseOnStop(null, isFinishing = true))
    }

    // ── onTrimMemory: the bound on retention ─────────────────────────────────

    @Test
    fun `background level pressure ends the retention`() {
        assertTrue(
            "retention is otherwise unbounded — release before the OS reclaims the codec mid-hold",
            PlayerRetentionPolicy.shouldReleaseOnTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                hasPlayer = true,
                contentType = ContentType.MOVIE
            )
        )
        assertTrue(
            PlayerRetentionPolicy.shouldReleaseOnTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                hasPlayer = true,
                contentType = ContentType.MOVIE
            )
        )
    }

    @Test
    fun `pressure below the background threshold does not throw the session away`() {
        assertFalse(
            "UI-hidden and running-* levels are routine; dropping the player there would make " +
                "every Home→return a cold reconnect",
            PlayerRetentionPolicy.shouldReleaseOnTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                hasPlayer = true,
                contentType = ContentType.MOVIE
            )
        )
        assertFalse(
            PlayerRetentionPolicy.shouldReleaseOnTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                hasPlayer = true,
                contentType = ContentType.MOVIE
            )
        )
    }

    @Test
    fun `trim is a no-op when there is nothing retained`() {
        assertFalse(
            PlayerRetentionPolicy.shouldReleaseOnTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                hasPlayer = false,
                contentType = ContentType.MOVIE
            )
        )
    }

    @Test
    fun `trim never touches live — it was already released at onStop`() {
        assertFalse(
            PlayerRetentionPolicy.shouldReleaseOnTrimMemory(
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                hasPlayer = true,
                contentType = ContentType.LIVE_TV
            )
        )
    }

    // ── onResume ─────────────────────────────────────────────────────────────

    @Test
    fun `no player means a cold start`() {
        assertTrue(PlayerRetentionPolicy.shouldColdStartOnResume(hasPlayer = false, isResolvingDebrid = false))
    }

    @Test
    fun `a debrid re-resolve in flight owns playback — resume must not race it`() {
        assertFalse(
            "the resolver's Success switch starts playback; starting here too double-starts it",
            PlayerRetentionPolicy.shouldColdStartOnResume(hasPlayer = false, isResolvingDebrid = true)
        )
        assertFalse(
            "and a retained stale stream must not become audible under the resolve",
            PlayerRetentionPolicy.shouldResumePlaybackOnResume(
                hasPlayer = true,
                wasPlayingBeforePause = true,
                isResolvingDebrid = true
            )
        )
    }

    @Test
    fun `a retained player resumes only what was actually playing`() {
        assertTrue(
            PlayerRetentionPolicy.shouldResumePlaybackOnResume(
                hasPlayer = true,
                wasPlayingBeforePause = true,
                isResolvingDebrid = false
            )
        )
        assertFalse(
            "a session the user had paused must stay paused on return",
            PlayerRetentionPolicy.shouldResumePlaybackOnResume(
                hasPlayer = true,
                wasPlayingBeforePause = false,
                isResolvingDebrid = false
            )
        )
    }

    @Test
    fun `the two resume paths are mutually exclusive`() {
        // Whatever the flags, we never both cold-start and resume-in-place.
        listOf(true, false).forEach { wasPlaying ->
            listOf(true, false).forEach { resolving ->
                listOf(true, false).forEach { hasPlayer ->
                    val coldStart = PlayerRetentionPolicy.shouldColdStartOnResume(hasPlayer, resolving)
                    val resumeInPlace =
                        PlayerRetentionPolicy.shouldResumePlaybackOnResume(hasPlayer, wasPlaying, resolving)
                    assertFalse(
                        "hasPlayer=$hasPlayer wasPlaying=$wasPlaying resolving=$resolving",
                        coldStart && resumeInPlace
                    )
                }
            }
        }
    }
}
