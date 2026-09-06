package com.tvonnet.debridxtreamiptv.ui.settings

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.data.parental.ParentalPolicy

/**
 * The D-pad PIN wheel's state machine, with no views in it.
 *
 * ⚠️ **Rewritten 2026-09-06 after a real defect.** The first version pre-filled every box with `0`
 * and treated OK as "advance, and on the last box COMPLETE" — the old test asserted exactly that.
 * So the wheel could finish with digits the user never chose: OK alone yields `0000`, and any
 * stray UP/DOWN from someone still navigating yields something else. [ParentalPinDialogs.askNewPin]
 * chains two of these, so a short burst of remote presses — and the very act of turning the toggle
 * on is an OK press, which a TV remote then repeats — **set and confirmed a PIN the owner never
 * chose**, switched parental controls on, and then demanded that PIN to switch them off. The owner
 * was locked out of their own setting.
 *
 * (Confirmed on the owner's Fire TV afterwards: the PIN that got stored was NOT `0000`, so stray
 * digit keys were in the burst too. The mechanism is the point, not the value.)
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

    /**
     * What a key did.
     *
     * [IGNORED] is load-bearing: the dialog must NOT consume it, so the framework moves focus out
     * of the digit row and on to Cancel / Confirm / Forgot PIN. Without that the row is a trap on
     * a remote - every arrow key means "turn this digit", so there is no way out but BACK.
     */
    enum class Outcome { IGNORED, CHANGED, GO_CONFIRM }

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
        // Deliberately NOT GO_CONFIRM: stealing focus the instant the last digit is set would
        // make that digit the one you cannot correct.
        return Outcome.CHANGED
    }

    private fun move(delta: Int): Outcome {
        if (delta < 0 && cursor == 0) return Outcome.IGNORED          // leftwards, out to the buttons
        if (delta > 0 && cursor == digits.lastIndex) {
            return if (isComplete) Outcome.GO_CONFIRM else Outcome.IGNORED
        }
        // Never skip forward over a box that has not been filled: the PIN is entered in order.
        if (delta > 0 && !entered[cursor]) return Outcome.CHANGED
        cursor += delta
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
        return if (isComplete) Outcome.GO_CONFIRM else Outcome.CHANGED
    }

    private fun type(digit: Int): Outcome {
        digits[cursor] = digit
        entered[cursor] = true
        // Same rule as turn(): filling the last box does not grab focus.
        if (cursor < digits.lastIndex) cursor++
        return Outcome.CHANGED
    }
}
