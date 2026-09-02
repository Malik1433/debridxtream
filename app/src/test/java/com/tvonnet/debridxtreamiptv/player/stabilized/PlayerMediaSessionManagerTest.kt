package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two contracts, frozen because a media session that is *slightly* wrong is worse than none:
 *
 * 1. **Lifetime.** The session exists exactly while a player is bound; a rebind to another
 *    instance replaces (never stacks); unbind is idempotent because eight teardown paths call
 *    it and none knows whether another already did; and the callback is detached before
 *    release so a late media key can never reach a player this screen no longer owns.
 * 2. **State mapping.** What the system is told — PLAYING / PAUSED / BUFFERING / STOPPED, and
 *    which actions are on offer — is a pure function of the player's condition. A live
 *    stream must not advertise seeking; a paused player must not report speed 1.0.
 *
 * The platform session is faked: the framework's MediaSession needs a system service the
 * unit JVM does not have, and every call we make on it is recorded instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerMediaSessionManagerTest {

    private class FakeSession : PlayerMediaSessionManager.PlatformSession {
        // Field names differ from the setters on purpose: a Kotlin `var active` generates
        // setActive(Boolean), which clashes with the interface method of the same signature.
        var isActiveNow = false
        var released = false
        var boundCallback: MediaSession.Callback? = null
        var lastState: PlaybackState? = null
        var lastMetadata: MediaMetadata? = null
        override fun setActive(active: Boolean) { isActiveNow = active }
        override fun setPlaybackState(state: PlaybackState) { lastState = state }
        override fun setMetadata(metadata: MediaMetadata) { lastMetadata = metadata }
        override fun setCallback(callback: MediaSession.Callback?) { boundCallback = callback }
        override fun setSessionActivity(intent: PendingIntent?) {}
        override fun release() { released = true }
    }

    /** The smallest Player media3 will accept: idle, no items, main looper. */
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.EMPTY)
            .setPlaybackState(Player.STATE_IDLE)
            .build()
    }

    private val created = mutableListOf<FakeSession>()
    private lateinit var manager: PlayerMediaSessionManager

    @Before
    fun setUp() {
        manager = PlayerMediaSessionManager(ApplicationProvider.getApplicationContext<Context>()) {
            FakeSession().also { created += it }
        }
    }

    // ── lifetime ────────────────────────────────────────────────────────────────

    @Test
    fun `bind creates one active session with a transport callback`() {
        assertFalse(manager.isBound())
        manager.bind(FakePlayer())
        assertTrue(manager.isBound())
        assertEquals(1, created.size)
        assertTrue(created[0].isActiveNow)
        assertNotEquals(null, created[0].boundCallback)
    }

    @Test
    fun `unbind detaches the callback, deactivates and releases - and is idempotent`() {
        manager.unbind() // nothing bound yet: no-op, no crash
        manager.bind(FakePlayer())
        manager.unbind()
        val s = created.single()
        assertNull("a late media key must find no callback", s.boundCallback)
        assertFalse(s.isActiveNow)
        assertTrue(s.released)
        assertFalse(manager.isBound())
        manager.unbind() // already released
        assertEquals(1, created.size)
    }

    @Test
    fun `rebinding to another player releases the old session and creates a new one`() {
        manager.bind(FakePlayer())
        manager.bind(FakePlayer())
        assertEquals(2, created.size)
        assertTrue("old session released", created[0].released)
        assertFalse("old session inactive", created[0].isActiveNow)
        assertTrue("new session active", created[1].isActiveNow)
        assertFalse(created[1].released)
    }

    @Test
    fun `binding the same player again is a no-op`() {
        val player = FakePlayer()
        manager.bind(player)
        manager.bind(player)
        assertEquals(1, created.size)
        assertTrue(manager.isBound())
    }

    @Test
    fun `a fresh bind after unbind works`() {
        manager.bind(FakePlayer())
        manager.unbind()
        manager.bind(FakePlayer())
        assertTrue(manager.isBound())
        assertEquals(2, created.size)
        assertTrue(created[1].isActiveNow)
    }

    @Test
    fun `bind publishes an initial playback state`() {
        manager.bind(FakePlayer())
        assertNotEquals(null, created[0].lastState)
    }

    @Test
    fun `the launch title reaches Now Playing, and a re-bind with a new title updates it`() {
        val player = FakePlayer()
        manager.bind(player, "BBC One")
        assertEquals("BBC One", created[0].lastMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
        manager.bind(player, "ITV") // same player, new channel
        assertEquals(1, created.size)
        assertEquals("ITV", created[0].lastMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    // ── state mapping (pure) ────────────────────────────────────────────────────

    private fun state(playbackState: Int, playWhenReady: Boolean, seekable: Boolean = true, speed: Float = 1f) =
        PlayerMediaSessionManager.playbackStateFor(playbackState, playWhenReady, positionMs = 1234L, seekable = seekable, speed = speed)

    @Test
    fun `ready and playWhenReady is PLAYING at the player's speed`() {
        val s = state(Player.STATE_READY, playWhenReady = true, speed = 1.5f)
        assertEquals(PlaybackState.STATE_PLAYING, s.state)
        assertEquals(1.5f, s.playbackSpeed)
        assertEquals(1234L, s.position)
    }

    @Test
    fun `ready without playWhenReady is PAUSED and reports speed 0`() {
        val s = state(Player.STATE_READY, playWhenReady = false)
        assertEquals(PlaybackState.STATE_PAUSED, s.state)
        assertEquals(0f, s.playbackSpeed)
    }

    @Test
    fun `buffering, ended and idle map to BUFFERING, STOPPED and NONE`() {
        assertEquals(PlaybackState.STATE_BUFFERING, state(Player.STATE_BUFFERING, true).state)
        assertEquals(PlaybackState.STATE_STOPPED, state(Player.STATE_ENDED, true).state)
        assertEquals(PlaybackState.STATE_NONE, state(Player.STATE_IDLE, true).state)
    }

    @Test
    fun `a live stream advertises transport but never seeking`() {
        val live = state(Player.STATE_READY, true, seekable = false).actions
        assertTrue(live and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertEquals(0L, live and PlaybackState.ACTION_SEEK_TO)
        assertEquals(0L, live and PlaybackState.ACTION_FAST_FORWARD)
        assertEquals(0L, live and PlaybackState.ACTION_REWIND)
    }

    @Test
    fun `a seekable item advertises seek, fast-forward and rewind`() {
        val vod = state(Player.STATE_READY, true, seekable = true).actions
        assertTrue(vod and PlaybackState.ACTION_SEEK_TO != 0L)
        assertTrue(vod and PlaybackState.ACTION_FAST_FORWARD != 0L)
        assertTrue(vod and PlaybackState.ACTION_REWIND != 0L)
    }
}
