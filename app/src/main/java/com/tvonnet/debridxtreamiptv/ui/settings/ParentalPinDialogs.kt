package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
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
 * PIN entry, one per rulebook (2026-09-03):
 *
 * - **TV** (`ui_uses_dpad_focus`): a four-digit wheel driven by the D-pad alone — UP/DOWN change
 *   the digit, LEFT/RIGHT move, OK confirms. No soft keyboard (the TV rule), and a Fire TV
 *   remote has no number keys anyway. Number keys from a keyboard-remote are accepted too.
 * - **Phone**: an `EditText` with the native numeric-password keyboard (the phone rule).
 *
 * Both call back with a four-digit string; [askNewPin] asks twice and rejects a mismatch.
 */
object ParentalPinDialogs {

    fun askPin(context: Context, titleRes: Int, onEntered: (String) -> Unit) {
        if (context.resources.getBoolean(R.bool.ui_uses_dpad_focus)) wheel(context, titleRes, onEntered)
        else keyboard(context, titleRes, onEntered)
    }

    /** Set a PIN: asked twice; a mismatch is reported and nothing is stored. */
    fun askNewPin(context: Context, onSet: (String) -> Unit, onMismatch: () -> Unit) {
        askPin(context, R.string.s_pin_set_title) { first ->
            askPin(context, R.string.s_pin_confirm_title) { second ->
                if (first == second) onSet(first) else onMismatch()
            }
        }
    }

    // ── phone ────────────────────────────────────────────────────────────────

    private fun keyboard(context: Context, titleRes: Int, onEntered: (String) -> Unit) {
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
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString().orEmpty()
                if (ParentalPolicy.isValidPin(pin)) { dialog.dismiss(); onEntered(pin) } else input.error = context.getString(R.string.s_pin_hint)
            }
            input.requestFocus()
        }
        dialog.show()
    }

    // ── TV ───────────────────────────────────────────────────────────────────

    private fun wheel(context: Context, titleRes: Int, onEntered: (String) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin_wheel, null)
        val boxes = listOf(R.id.pin_d1, R.id.pin_d2, R.id.pin_d3, R.id.pin_d4).map { view.findViewById<TextView>(it) }
        val state = PinWheelState()
        fun render() {
            boxes.forEachIndexed { i, tv ->
                tv.text = if (i <= state.cursor) state.digits[i].toString() else "•"
                tv.setTextColor(if (i == state.cursor) 0xFF22D3EE.toInt() else 0xFFE2E8F0.toInt())
                tv.setBackgroundColor(if (i == state.cursor) 0xFF0F3B45.toInt() else 0xFF1E293B.toInt())
                tv.contentDescription = context.getString(R.string.a11y_pin_digit, i + 1)
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val row = view.findViewById<View>(R.id.pin_digits)
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (state.onKey(keyCode)) {
                PinWheelState.Outcome.IGNORED -> false
                PinWheelState.Outcome.CHANGED -> { render(); true }
                PinWheelState.Outcome.COMPLETE -> { dialog.dismiss(); onEntered(state.pin); true }
            }
        }
        dialog.setOnShowListener { render(); row.requestFocus() }
        dialog.show()
    }
}
