package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the key-routing rules extracted in P17 — above all the zap repeat-filter,
 * which is a shipped fix: a held D-pad used to fire one tune per auto-repeat and
 * hammer the provider (zap storm).
 */
class PlayerKeyRoutingTest {

    @Test
    fun `zap keys map to a direction, everything else does not`() {
        assertEquals(1, zapDirectionFor(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(1, zapDirectionFor(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(1, zapDirectionFor(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(1, zapDirectionFor(KeyEvent.KEYCODE_DPAD_UP))

        assertEquals(-1, zapDirectionFor(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(-1, zapDirectionFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(-1, zapDirectionFor(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(-1, zapDirectionFor(KeyEvent.KEYCODE_DPAD_DOWN))

        assertNull(zapDirectionFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(zapDirectionFor(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `a held key only zaps once — auto-repeats are filtered`() {
        assertTrue(
            canZapNow(KeyEvent.ACTION_DOWN, 0, ContentType.LIVE_TV, OpenPlayerSurfaces())
        )
        assertFalse(
            "repeat 1 must not tune again",
            canZapNow(KeyEvent.ACTION_DOWN, 1, ContentType.LIVE_TV, OpenPlayerSurfaces())
        )
        assertFalse(
            "key-up must not tune",
            canZapNow(KeyEvent.ACTION_UP, 0, ContentType.LIVE_TV, OpenPlayerSurfaces())
        )
    }

    @Test
    fun `only live content zaps`() {
        assertFalse(canZapNow(KeyEvent.ACTION_DOWN, 0, ContentType.MOVIE, OpenPlayerSurfaces()))
        assertFalse(canZapNow(KeyEvent.ACTION_DOWN, 0, ContentType.EPISODE, OpenPlayerSurfaces()))
        assertFalse(canZapNow(KeyEvent.ACTION_DOWN, 0, null, OpenPlayerSurfaces()))
    }

    @Test
    fun `nothing zaps underneath an open surface`() {
        assertFalse(
            canZapNow(
                KeyEvent.ACTION_DOWN, 0, ContentType.LIVE_TV,
                OpenPlayerSurfaces(isBrowserVisible = true)
            )
        )
        assertFalse(
            canZapNow(
                KeyEvent.ACTION_DOWN, 0, ContentType.LIVE_TV,
                OpenPlayerSurfaces(isSurfDrawerOpen = true)
            )
        )
        assertFalse(
            canZapNow(
                KeyEvent.ACTION_DOWN, 0, ContentType.LIVE_TV,
                OpenPlayerSurfaces(isGuideOpen = true)
            )
        )
    }

    @Test
    fun `controller trigger keys include the dpad and the media keys`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MENU
        ).forEach { assertTrue("key $it should open the controller", isControllerTriggerKey(it)) }

        assertFalse(isControllerTriggerKey(KeyEvent.KEYCODE_BACK))
        assertFalse(isControllerTriggerKey(KeyEvent.KEYCODE_CAPTIONS))
        assertFalse(isControllerTriggerKey(KeyEvent.KEYCODE_GUIDE))
    }

    @Test
    fun `VOD back hides the controller first, then exits`() {
        assertEquals(
            VodBackAction.HIDE_CONTROLLER,
            vodBackAction(isInPictureInPictureMode = false, isControllerVisible = true, backHideArmed = false, consumeFirstBackToHideControls = true)
        )
        assertEquals(
            VodBackAction.EXIT,
            vodBackAction(isInPictureInPictureMode = false, isControllerVisible = true, backHideArmed = true, consumeFirstBackToHideControls = true)
        )
        assertEquals(
            VodBackAction.EXIT,
            vodBackAction(isInPictureInPictureMode = false, isControllerVisible = false, backHideArmed = false, consumeFirstBackToHideControls = true)
        )
    }

    @Test
    fun `PiP lets the system handle back`() {
        assertEquals(
            VodBackAction.PASS_THROUGH,
            vodBackAction(isInPictureInPictureMode = true, isControllerVisible = true, backHideArmed = false, consumeFirstBackToHideControls = true)
        )
    }


    @Test
    fun `on a phone BACK leaves straight away, controls or not`() {
        // M9: reported from a real handset as "the movie player doesn't go back" — the back
        // GESTURE spent itself hiding chrome, which looks like nothing happening at all.
        assertEquals(
            VodBackAction.EXIT,
            vodBackAction(
                isInPictureInPictureMode = false,
                isControllerVisible = true,
                backHideArmed = false,
                consumeFirstBackToHideControls = false,
            )
        )
        // PiP still wins, on either form factor.
        assertEquals(
            VodBackAction.PASS_THROUGH,
            vodBackAction(
                isInPictureInPictureMode = true,
                isControllerVisible = true,
                backHideArmed = false,
                consumeFirstBackToHideControls = false,
            )
        )
    }

    // ── LEFT in the Live OSD: drawer, or step back along the row? ──────────────

    @Test
    fun `LEFT from a control mid-row steps back instead of opening the drawer`() {
        // The reported bug: walk right to the audio or aspect button and the first LEFT threw the
        // channel drawer open, so there was no way back along the row.
        assertFalse(
            leftOpensChannelDrawer(
                isOsdVisible = true, isControlFocused = true, isFirstControlFocused = false
            )
        )
    }

    @Test
    fun `LEFT from the leftmost control opens the drawer`() {
        // Nothing further left to reach, so this is where the gesture belongs.
        assertTrue(
            leftOpensChannelDrawer(
                isOsdVisible = true, isControlFocused = true, isFirstControlFocused = true
            )
        )
    }

    @Test
    fun `LEFT from the video itself opens the drawer`() {
        // Both ways of "no control has focus": the OSD is down, or it is up but focus is elsewhere.
        assertTrue(
            leftOpensChannelDrawer(
                isOsdVisible = false, isControlFocused = false, isFirstControlFocused = false
            )
        )
        assertTrue(
            leftOpensChannelDrawer(
                isOsdVisible = true, isControlFocused = false, isFirstControlFocused = false
            )
        )
    }
}
