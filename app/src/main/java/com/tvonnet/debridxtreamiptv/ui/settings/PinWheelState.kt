package com.tvonnet.debridxtreamiptv.ui.settings

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.data.parental.ParentalPolicy

/**
 * The D-pad PIN wheel's state machine, with no views in it.
 *
 * ⚠️ **Rewritten 2026-09-06 after a real defect.** The first version pre-filled every box with `0`
 * and treated OK as "advance, and on the last box COMPLETE". A television remote repeats keys, and
 * the very act of turning the toggle on is an OK press — so four more OK presses, which is what an
 * impatient press or one auto-repeat produces, silently entered `0000`. [ParentalPinDialogs.askNewPin]
 * chains two of these, so eight presses **set and confirmed a PIN the owner never chose**, switched
 * parental controls on, and then asked for that PIN to switch them off. The owner was locked out of
 * their own setting by pressing OK.
 *
 * The rule that makes that impossible: **OK never enters a digit.** Boxes start EMPTY, and only
 * UP/DOWN or a number key fills one. Mashing OK now does nothing at all — the cursor cannot move
 * past a box the user has not deliberately set, and [isComplete] stays false, so the dialog's
 * confirm button stays disabled.
 *
 * See [PinWheelStateTest].
 */
class PinWheelState(length: Int = ParentalPolicy.PIN_LENGTH) {

    private val digits = IntArray(length)
    private val entered = BooleanArray(length)

    var cursor = 0
        private set

    /** The digit under the cursor, or null while that box is still empty. */
    fun digitAt(index: Int): Int? = if (entered[index]) digits[index] else null

    val isComplete: Boolean get() = entered.all { it }

    /** Only meaningful once [isComplete]; empty otherwise, so a partial PIN can never be stored. */
    val pin: String get() = if (isComplete) digits.joinToString("") else ""

    /** What a key did: nothing, changed the display, or finished the last digit. */
    enum class Outcome { IGNORED, CHANGED, DONE_EDITING }

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

    /** UP/DOWN is the only way a digit comes into existence on a remote. */
    private fun turn(delta: Int): Outcome {
        if (!entered[cursor]) {
            // First touch of an empty box starts at 0 going down, 1 going up — either way the
            // user chose it.
            digits[cursor] = if (delta > 0) 1 else 9
            entered[cursor] = true
        } else {
            digits[cursor] = (digits[cursor] + delta + 10) % 10
        }
        return if (isComplete && cursor == digits.lastIndex) Outcome.DONE_EDITING else Outcome.CHANGED
    }

    private fun move(delta: Int): Outcome {
        val target = (cursor + delta).coerceIn(0, digits.lastIndex)
        // Never skip forward over a box that has not been filled: the PIN is entered in order.
        if (delta > 0 && !entered[cursor]) return Outcome.CHANGED
        cursor = target
        return Outcome.CHANGED
    }

    /**
     * OK on a filled box moves on; OK on an EMPTY box does nothing. That single line is what makes
     * an accidental PIN impossible.
     */
    private fun advance(): Outcome {
        if (!entered[cursor]) return Outcome.CHANGED
        if (cursor < digits.lastIndex) {
            cursor++
            return Outcome.CHANGED
        }
        return if (isComplete) Outcome.DONE_EDITING else Outcome.CHANGED
    }

    private fun type(digit: Int): Outcome {
        digits[cursor] = digit
        entered[cursor] = true
        if (cursor < digits.lastIndex) {
            cursor++
            return Outcome.CHANGED
        }
        return if (isComplete) Outcome.DONE_EDITING else Outcome.CHANGED
    }
}
