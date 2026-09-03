package com.tvonnet.debridxtreamiptv.ui.settings

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.ui.settings.PinWheelState.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** The remote-only PIN entry: every key does exactly one thing, and OK on the last box completes. */
class PinWheelStateTest {

    @Test
    fun `up and down turn the digit under the cursor and wrap`() {
        val w = PinWheelState()
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("1000", w.pin)
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("9000", w.pin)
    }

    @Test
    fun `left and right move the cursor inside the four boxes`() {
        val w = PinWheelState()
        w.onKey(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(0, w.cursor)
        w.onKey(KeyEvent.KEYCODE_DPAD_RIGHT); w.onKey(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(2, w.cursor)
        repeat(5) { w.onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }; assertEquals(3, w.cursor)
        w.onKey(KeyEvent.KEYCODE_DPAD_UP); assertEquals("0001", w.pin)
    }

    @Test
    fun `OK advances, and completes only on the last box`() {
        val w = PinWheelState()
        w.onKey(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(Outcome.COMPLETE, w.onKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("1000", w.pin)
    }

    @Test
    fun `number keys type and advance, the fourth completes`() {
        val w = PinWheelState()
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_4))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_NUMPAD_8))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_2))
        assertEquals(Outcome.COMPLETE, w.onKey(KeyEvent.KEYCODE_1))
        assertEquals("4821", w.pin)
    }

    @Test
    fun `other keys are not ours`() {
        val w = PinWheelState()
        assertEquals(Outcome.IGNORED, w.onKey(KeyEvent.KEYCODE_BACK))
        assertEquals(Outcome.IGNORED, w.onKey(KeyEvent.KEYCODE_MENU))
        assertEquals("0000", w.pin)
    }
}
