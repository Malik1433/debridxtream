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
            vodBackAction(isInPictureInPictureMode = false, isControllerVisible = true, backHideArmed = false)
        )
        assertEquals(
            VodBackAction.EXIT,
            vodBackAction(isInPictureInPictureMode = false, isControllerVisible = true, backHideArmed = true)
        )
        assertEquals(
            VodBackAction.EXIT,
            vodBackAction(isInPictureInPictureMode = false, isControllerVisible = false, backHideArmed = false)
        )
    }

    @Test
    fun `PiP lets the system handle back`() {
        assertEquals(
            VodBackAction.PASS_THROUGH,
            vodBackAction(isInPictureInPictureMode = true, isControllerVisible = true, backHideArmed = false)
        )
    }
}
