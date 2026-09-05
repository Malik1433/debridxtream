package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.parental.ParentalPolicy

/**
 * PIN entry, one per rulebook:
 *
 * - **TV** (`ui_uses_dpad_focus`): a four-digit wheel driven by the D-pad alone — UP/DOWN choose
 *   the digit, LEFT/RIGHT move, and a separate **Confirm** button finishes. No soft keyboard (the
 *   TV rule), and a Fire TV remote has no number keys. Number keys from a keyboard-remote work too.
 * - **Phone**: an `EditText` with the native numeric-password keyboard (the phone rule).
 *
 * ⚠️ **Three guards, all added 2026-09-06 after the owner was locked out of their own setting.**
 * The old wheel finished on the fourth OK press over pre-filled zeroes, so the OK that switched the
 * toggle on — plus a remote's own key-repeat — set and confirmed a PIN of `0000` that nobody chose:
 *
 *  1. OK never enters a digit ([PinWheelState]); only UP/DOWN or a number key does.
 *  2. Finishing is a **separate button**, and it stays disabled until all four digits are chosen.
 *  3. Auto-repeat is dropped, and keys arriving in the first [INPUT_GUARD_MS] are ignored — that is
 *     the window a press carried over from the control that opened this dialog lands in.
 *
 * [askPin] takes an optional `onForgot`: a PIN nobody can produce is a lock-out, so every prompt
 * that asks for the EXISTING PIN offers a way back. Setting a new PIN does not — there is nothing
 * to recover yet.
 */
object ParentalPinDialogs {

    /** A keypress in flight when the dialog opened belongs to the previous screen, not to this one. */
    private const val INPUT_GUARD_MS = 600L

    fun askPin(
        context: Context,
        titleRes: Int,
        onForgot: (() -> Unit)? = null,
        onEntered: (String) -> Unit
    ) {
        if (context.resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            wheel(context, titleRes, onForgot, onEntered)
        } else {
            keyboard(context, titleRes, onForgot, onEntered)
        }
    }

    /** Set a PIN: asked twice; a mismatch is reported and nothing is stored. */
    fun askNewPin(context: Context, onSet: (String) -> Unit, onMismatch: () -> Unit) {
        askPin(context, R.string.s_pin_set_title) { first ->
            askPin(context, R.string.s_pin_confirm_title) { second ->
                if (first == second) onSet(first) else onMismatch()
            }
        }
    }

    private fun AlertDialog.Builder.withForgot(onForgot: (() -> Unit)?): AlertDialog.Builder {
        if (onForgot != null) setNeutralButton(R.string.s_pin_forgot) { _, _ -> onForgot() }
        return this
    }

    // ── phone ────────────────────────────────────────────────────────────────

    private fun keyboard(
        context: Context,
        titleRes: Int,
        onForgot: (() -> Unit)?,
        onEntered: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = context.getString(R.string.s_pin_hint)
            filters = arrayOf(android.text.InputFilter.LengthFilter(ParentalPolicy.PIN_LENGTH))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.s_pin_confirm_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .withForgot(onForgot)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString().orEmpty()
                if (ParentalPolicy.isValidPin(pin)) {
                    dialog.dismiss()
                    onEntered(pin)
                } else {
                    input.error = context.getString(R.string.s_pin_hint)
                }
            }
            input.requestFocus()
        }
        dialog.show()
    }

    // ── TV ───────────────────────────────────────────────────────────────────

    private fun wheel(
        context: Context,
        titleRes: Int,
        onForgot: (() -> Unit)?,
        onEntered: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin_wheel, null)
        val boxes = listOf(R.id.pin_d1, R.id.pin_d2, R.id.pin_d3, R.id.pin_d4)
            .map { view.findViewById<TextView>(it) }
        val state = PinWheelState()

        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.s_pin_confirm_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .withForgot(onForgot)
            .create()

        val confirm = { if (state.isComplete) { dialog.dismiss(); onEntered(state.pin) } }
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            renderWheel(context, boxes, state)
            button.isEnabled = false
            button.setOnClickListener { confirm() }

            val row = view.findViewById<View>(R.id.pin_digits)
            val guardUntil = SystemClock.uptimeMillis() + INPUT_GUARD_MS
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                // Swallow, do not pass on: an auto-repeat or a carried-over press must not reach
                // the wheel OR the buttons behind it.
                if (event.repeatCount > 0 || SystemClock.uptimeMillis() < guardUntil) {
                    return@setOnKeyListener true
                }
                when (state.onKey(keyCode)) {
                    PinWheelState.Outcome.IGNORED -> false
                    PinWheelState.Outcome.CHANGED -> {
                        renderWheel(context, boxes, state)
                        button.isEnabled = state.isComplete
                        true
                    }
                    PinWheelState.Outcome.DONE_EDITING -> {
                        renderWheel(context, boxes, state)
                        button.isEnabled = true
                        // The last digit is chosen; hand the remote to the button so the next OK
                        // is a deliberate "yes", not the tail of entering digits.
                        button.requestFocus()
                        true
                    }
                }
            }
            row.requestFocus()
        }
        dialog.show()
    }

    /**
     * Entered digits are masked; only the one being turned is legible. A PIN typed on a television
     * is readable across the room, and the digit under the cursor is the only one the user needs
     * to see to choose it.
     */
    private fun renderWheel(context: Context, boxes: List<TextView>, state: PinWheelState) {
        boxes.forEachIndexed { i, tv ->
            val digit = state.digitAt(i)
            tv.text = when {
                i == state.cursor && digit != null -> digit.toString()
                digit != null -> MASK
                else -> EMPTY
            }
            tv.setTextColor(if (i == state.cursor) CURSOR_TEXT else IDLE_TEXT)
            tv.setBackgroundColor(if (i == state.cursor) CURSOR_BG else IDLE_BG)
            tv.contentDescription = context.getString(R.string.a11y_pin_digit, i + 1)
        }
    }

    private const val MASK = "•"
    private const val EMPTY = "–"
    private const val CURSOR_TEXT = 0xFF22D3EE.toInt()
    private const val IDLE_TEXT = 0xFFE2E8F0.toInt()
    private const val CURSOR_BG = 0xFF0F3B45.toInt()
    private const val IDLE_BG = 0xFF1E293B.toInt()
}
