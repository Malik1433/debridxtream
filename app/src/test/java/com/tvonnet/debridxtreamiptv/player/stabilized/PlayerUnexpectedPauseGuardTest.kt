package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.Player
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerUnexpectedPauseGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 Phase 2. The one regression that matters most: the viewer's OWN pause must never be undone.
 * Every IGNORE case below is a pause somebody asked for.
 */
class PlayerUnexpectedPauseGuardTest {

    private var now = 100_000L
    private val guard = PlayerUnexpectedPauseGuard { now }

    private fun pause(
        reason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        isTelevision: Boolean = true,
        isLive: Boolean = false,
        isScreenResumed: Boolean = true,
        lastKeyAgeMs: Long = -1L,
        systemCommandAgeMs: Long = -1L,
    ) = guard.onPause(
        PlayerUnexpectedPauseGuard.Pause(reason, isTelevision, isLive, isScreenResumed, lastKeyAgeMs, systemCommandAgeMs)
    )

    @Test
    fun `a pause nobody asked for on a TV VOD is resumed`() {
        assertEquals(Decision.RESUME, pause())
    }

    @Test
    fun `becoming-noisy and suppressed-too-long are unexpected too`() {
        assertEquals(Decision.RESUME, pause(reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
        now += PlayerUnexpectedPauseGuard.REPEAT_WINDOW_MS
        assertEquals(Decision.RESUME, pause(reason = 6))
    }

    @Test
    fun `the viewer's own key press is respected`() {
        assertEquals(Decision.IGNORE, pause(lastKeyAgeMs = 200L))
        assertEquals(Decision.IGNORE, pause(lastKeyAgeMs = PlayerUnexpectedPauseGuard.USER_KEY_WINDOW_MS))
    }

    @Test
    fun `an old key press does not make a later pause the viewer's`() {
        assertEquals(Decision.RESUME, pause(lastKeyAgeMs = 30_000L))
    }

    @Test
    fun `a MediaSession command (Alexa, CEC) is respected`() {
        assertEquals(Decision.IGNORE, pause(systemCommandAgeMs = 5L))
    }

    @Test
    fun `phone, Live, background and focus loss are all left alone`() {
        assertEquals(Decision.IGNORE, pause(isTelevision = false))
        assertEquals(Decision.IGNORE, pause(isLive = true))
        assertEquals(Decision.IGNORE, pause(isScreenResumed = false))
        assertEquals(Decision.IGNORE, pause(reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertEquals(Decision.IGNORE, pause(reason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE))
        assertEquals(Decision.IGNORE, pause(reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
    }

    @Test
    fun `resume once, then ask the viewer instead of fighting it`() {
        assertEquals(Decision.RESUME, pause())
        now += 20_000L
        assertEquals(Decision.SHOW_HINT, pause())
        now += PlayerUnexpectedPauseGuard.REPEAT_WINDOW_MS
        assertEquals(Decision.RESUME, pause())
    }

    @Test
    fun `ignored pauses do not use up the one resume`() {
        assertEquals(Decision.IGNORE, pause(lastKeyAgeMs = 100L))
        assertEquals(Decision.RESUME, pause())
    }

    @Test
    fun `television means the device, not the layout`() {
        val tv = android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val normal = android.content.res.Configuration.UI_MODE_TYPE_NORMAL
        assertTrue(isTelevisionDevice(tv, hasLeanback = false, hasTouchscreen = true))
        assertTrue(isTelevisionDevice(normal, hasLeanback = true, hasTouchscreen = false))
        assertFalse(isTelevisionDevice(normal, hasLeanback = true, hasTouchscreen = true))
    }
}
