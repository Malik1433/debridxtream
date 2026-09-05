package com.tvonnet.debridxtreamiptv.ui.settings

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.ui.settings.PinWheelState.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote-only PIN entry.
 *
 * The first test is the regression that matters: the owner turned parental controls on, was never
 * asked for a PIN, and then could not turn them off. The old wheel pre-filled `0000` and let OK
 * both advance and finish, so pressing OK a few times — which a TV remote does on its own by
 * repeating — created and confirmed a PIN nobody chose.
 */
class PinWheelStateTest {

    private fun ok(w: PinWheelState) = w.onKey(KeyEvent.KEYCODE_DPAD_CENTER)

    @Test
    fun `mashing OK can never produce a PIN - the 2026-09-06 lockout`() {
        val w = PinWheelState()
        repeat(20) { ok(w) }

        assertFalse("no digit was ever chosen, so there is no PIN", w.isComplete)
        assertEquals("", w.pin)
        assertEquals("the cursor cannot move past an empty box", 0, w.cursor)
        assertNull(w.digitAt(0))
    }

    @Test
    fun `boxes start empty - nothing is pre-filled`() {
        val w = PinWheelState()
        (0..3).forEach { assertNull("box $it must start empty", w.digitAt(it)) }
        assertFalse(w.isComplete)
        assertEquals("", w.pin)
    }

    @Test
    fun `only up-down or a number key brings a digit into existence`() {
        val w = PinWheelState()
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(1, w.digitAt(0))

        val w2 = PinWheelState()
        w2.onKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(9, w2.digitAt(0))
    }

    @Test
    fun `up and down turn the digit under the cursor and wrap`() {
        val w = PinWheelState()
        w.onKey(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, w.digitAt(0))
        w.onKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(0, w.digitAt(0))
        w.onKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(9, w.digitAt(0))
    }

    @Test
    fun `the cursor never runs ahead of what has been entered`() {
        val w = PinWheelState()
        w.onKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("right on an empty box stays put", 0, w.cursor)

        w.onKey(KeyEvent.KEYCODE_DPAD_UP)
        w.onKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, w.cursor)

        w.onKey(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("going back is always allowed - to correct a digit", 0, w.cursor)
    }

    @Test
    fun `four deliberate digits complete the PIN and signal DONE_EDITING`() {
        val w = PinWheelState()
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_4))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_NUMPAD_8))
        assertEquals(Outcome.CHANGED, w.onKey(KeyEvent.KEYCODE_2))
        assertEquals(Outcome.DONE_EDITING, w.onKey(KeyEvent.KEYCODE_1))

        assertTrue(w.isComplete)
        assertEquals("4821", w.pin)
    }

    @Test
    fun `0000 is still a legal PIN when it is actually chosen`() {
        val w = PinWheelState()
        repeat(4) { w.onKey(KeyEvent.KEYCODE_0) }
        assertTrue(w.isComplete)
        assertEquals("0000", w.pin)
    }

    @Test
    fun `a digit can be corrected before confirming`() {
        val w = PinWheelState()
        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4)
            .forEach { w.onKey(it) }
        assertEquals("1234", w.pin)

        repeat(3) { w.onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals(0, w.cursor)
        w.onKey(KeyEvent.KEYCODE_9)
        assertEquals("9234", w.pin)
    }

    @Test
    fun `other keys are not ours`() {
        val w = PinWheelState()
        assertEquals(Outcome.IGNORED, w.onKey(KeyEvent.KEYCODE_BACK))
        assertEquals(Outcome.IGNORED, w.onKey(KeyEvent.KEYCODE_MENU))
        assertFalse(w.isComplete)
    }
}
