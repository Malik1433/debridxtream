package com.tvonnet.debridxtreamiptv.ui.settings

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.data.parental.ParentalPolicy

/**
 * The D-pad PIN wheel's state machine, with no views in it (2026-09-03): four digits and a
 * cursor. UP/DOWN turn the digit under the cursor, LEFT/RIGHT move it, OK advances — and on
 * the last box completes; a number key (keyboard remotes) types and advances. Pure, so the
 * dialog only renders — see [PinWheelStateTest].
 */
class PinWheelState(length: Int = ParentalPolicy.PIN_LENGTH) {

    val digits = IntArray(length)
    var cursor = 0
        private set

    val pin: String get() = digits.joinToString("")

    /** What a key did: nothing (not ours), changed the display, or completed the PIN. */
    enum class Outcome { IGNORED, CHANGED, COMPLETE }

    fun onKey(keyCode: Int): Outcome = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> turn(+1)
        KeyEvent.KEYCODE_DPAD_DOWN -> turn(-1)
        KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> move(+1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> advance()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> type(keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> type(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        else -> Outcome.IGNORED
    }

    private fun turn(delta: Int): Outcome {
        digits[cursor] = (digits[cursor] + delta + 10) % 10
        return Outcome.CHANGED
    }

    private fun move(delta: Int): Outcome {
        cursor = (cursor + delta).coerceIn(0, digits.lastIndex)
        return Outcome.CHANGED
    }

    private fun advance(): Outcome =
        if (cursor < digits.lastIndex) { cursor++; Outcome.CHANGED } else Outcome.COMPLETE

    private fun type(digit: Int): Outcome {
        digits[cursor] = digit
        return advance()
    }
}
