package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Freezes the EPG-strip state machine and the zap debounce extracted in P18.
 * The debounce is the LP-B-1 zap-storm fix: holding the D-pad must open ONE
 * connection, not one per step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerEpgOverlayTest {

    private fun overlay(isLive: Boolean = true, visible: Boolean = true) = PlayerEpgOverlay(
        handler = Handler(Looper.getMainLooper()),
        timeoutMs = 6000L,
        overlay = { null },
        isLive = { isLive },
        shouldBeVisible = { visible }
    )

    @Test
    fun `an unpinned show auto-hides after the timeout`() {
        val ui = overlay()
        ui.show(EpgOverlayMode.COMPACT, pinned = false)
        assertEquals(EpgOverlayMode.COMPACT, ui.mode)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(6001))

        assertEquals(EpgOverlayMode.HIDDEN, ui.mode)
    }

    @Test
    fun `a pinned live overlay survives the auto-hide`() {
        val ui = overlay(isLive = true)
        ui.show(EpgOverlayMode.COMPACT, pinned = true)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(6001))

        assertEquals(EpgOverlayMode.COMPACT, ui.mode)
        assertTrue(ui.isPinnedUp())
    }

    @Test
    fun `EXPANDED is normalised to COMPACT, HIDDEN stays hidden`() {
        val ui = overlay()
        ui.show(EpgOverlayMode.EXPANDED, pinned = true)
        assertEquals(EpgOverlayMode.COMPACT, ui.mode)

        ui.show(EpgOverlayMode.HIDDEN, pinned = true)
        assertEquals(EpgOverlayMode.HIDDEN, ui.mode)
    }

    @Test
    fun `toggle pins first, then hides`() {
        val ui = overlay()

        ui.togglePinned()
        assertTrue(ui.isPinnedUp())
        assertEquals(EpgOverlayMode.COMPACT, ui.mode)

        ui.togglePinned()
        assertFalse(ui.isPinnedUp())
        assertEquals(EpgOverlayMode.HIDDEN, ui.mode)
    }

    @Test
    fun `hide always clears the pin`() {
        val ui = overlay()
        ui.show(EpgOverlayMode.COMPACT, pinned = true)

        ui.hide()

        assertFalse(ui.isPinned)
        assertEquals(EpgOverlayMode.HIDDEN, ui.mode)
    }

    @Test
    fun `pinned-up needs both the pin and a visible mode`() {
        assertTrue(isEpgOverlayPinnedUp(pinned = true, mode = EpgOverlayMode.COMPACT))
        assertFalse(isEpgOverlayPinnedUp(pinned = true, mode = EpgOverlayMode.HIDDEN))
        assertFalse(isEpgOverlayPinnedUp(pinned = false, mode = EpgOverlayMode.COMPACT))
    }

    @Test
    fun `holding the dpad commits ONE tune, for the last channel`() {
        val committed = mutableListOf<String>()
        val debouncer = PlayerZapDebouncer(
            handler = Handler(Looper.getMainLooper()),
            debounceMs = 350L,
            onCommit = { committed += it.streamId }
        )

        debouncer.schedule(zapChannel("ch-1"))
        debouncer.schedule(zapChannel("ch-2"))
        debouncer.schedule(zapChannel("ch-3"))
        assertTrue(debouncer.hasPending())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(351))

        assertEquals(listOf("ch-3"), committed)
        assertFalse(debouncer.hasPending())
    }

    @Test
    fun `a real tune cancels a pending zap so it cannot fire on top`() {
        val committed = mutableListOf<String>()
        val debouncer = PlayerZapDebouncer(
            handler = Handler(Looper.getMainLooper()),
            debounceMs = 350L,
            onCommit = { committed += it.streamId }
        )

        debouncer.schedule(zapChannel("ch-1"))
        debouncer.cancel()

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(351))

        assertTrue("no late commit", committed.isEmpty())
    }

    private fun zapChannel(id: String) = ZapChannel(
        streamId = id,
        name = "Channel $id",
        streamUrl = "http://host/$id.ts",
        logoUrl = null,
        epgChannelId = null
    )
}
