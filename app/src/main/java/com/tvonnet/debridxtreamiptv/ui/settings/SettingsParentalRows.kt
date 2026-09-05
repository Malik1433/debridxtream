package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.parental.ParentalControls
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingItem

/**
 * The parental-controls rows of the Account panel. Kept out of SettingsFragment so that file stays
 * under the size ceiling; the fragment only appends [items] and re-renders.
 *
 * Four rows, and they answer to state:
 * - **Parental controls** (toggle). Turning it on with no PIN asks for one (twice); turning it
 *   off asks for the PIN — otherwise the gate would be a decoration.
 * - **Change PIN** (only while on): current PIN, then the new one twice.
 * - **Show adult categories for this session** (only while on and locked) / **Hide … now**
 *   (while unlocked). The unlock lasts 30 minutes or until the app closes.
 *
 * ⚠️ **Every prompt that asks for the EXISTING PIN also offers [forgotPin] (2026-09-06).** A PIN
 * the owner cannot produce is a lock-out, and this app produced exactly that: the wheel used to
 * finish on four OK presses over pre-filled zeroes, so the owner switched the controls on, was
 * never asked anything, and was then asked for a PIN they had never chosen. [PinWheelState] makes
 * that impossible now — and this makes it recoverable even when the owner simply forgets, which is
 * what any parental control has to do.
 */
class SettingsParentalRows(
    private val context: Context,
    private val parental: ParentalControls,
    private val rerender: () -> Unit,
) {

    fun items(): List<SettingItem> {
        val on = parental.isEnabled
        Log.i(TAG, "items: enabled=$on hasPin=${parental.hasPin} unlocked=${parental.isUnlocked}")
        return listOfNotNull(
            SettingItem.Toggle(
                key = "parental_enabled",
                title = context.getString(R.string.s_parental_title),
                description = context.getString(R.string.s_parental_desc),
                isChecked = on,
                // A re-render sets the switch programmatically and that fires this listener too;
                // a value that already matches the stored state is that echo, not a request.
                onToggle = { wanted ->
                    Log.i(TAG, "toggle: wanted=$wanted enabled=${parental.isEnabled}")
                    if (wanted != parental.isEnabled) { if (wanted) turnOn() else turnOff() }
                }
            ),
            if (on) SettingItem.Action(
                key = "parental_change_pin",
                title = context.getString(R.string.s_parental_change_pin),
                description = context.getString(R.string.s_parental_change_pin_desc),
                onClick = { changePin() }
            ) else null,
            if (on && !parental.isUnlocked) SettingItem.Action(
                key = "parental_unlock",
                title = context.getString(R.string.s_parental_unlock),
                description = context.getString(R.string.s_parental_unlock_desc),
                onClick = { unlock() }
            ) else null,
            if (on && parental.isUnlocked) SettingItem.Action(
                key = "parental_lock",
                title = context.getString(R.string.s_parental_lock_now),
                description = context.getString(R.string.s_parental_lock_now_desc),
                onClick = { parental.lockNow(); rerender() }
            ) else null,
        )
    }

    private fun turnOn() {
        if (parental.hasPin) { parental.setEnabled(true); toast(R.string.s_pin_saved); rerender(); return }
        ParentalPinDialogs.askNewPin(
            context,
            onSet = { pin -> parental.setPin(pin); toast(R.string.s_pin_saved); rerender() },
            onMismatch = { toast(R.string.s_pin_mismatch); rerender() }
        )
        // The toggle already drew itself "on"; until a PIN exists it is not. Redraw from truth.
        rerender()
    }

    private fun turnOff() {
        ParentalPinDialogs.askPin(context, R.string.s_pin_current_title, onForgot = ::forgotPin) { pin ->
            if (parental.verifyPin(pin)) { parental.setEnabled(false); toast(R.string.s_pin_off) } else toast(R.string.s_pin_wrong)
            rerender()
        }
        rerender()
    }

    private fun changePin() {
        ParentalPinDialogs.askPin(context, R.string.s_pin_current_title, onForgot = ::forgotPin) { current ->
            if (!parental.verifyPin(current)) { toast(R.string.s_pin_wrong); return@askPin }
            ParentalPinDialogs.askNewPin(
                context,
                onSet = { pin -> parental.setPin(pin); toast(R.string.s_pin_saved); rerender() },
                onMismatch = { toast(R.string.s_pin_mismatch) }
            )
        }
    }

    private fun unlock() {
        ParentalPinDialogs.askPin(context, R.string.s_pin_enter_title, onForgot = ::forgotPin) { pin ->
            if (parental.unlockWithPin(pin)) toast(R.string.s_pin_unlocked) else toast(R.string.s_pin_wrong)
            rerender()
        }
    }

    /**
     * The way back in: the ACCOUNT PASSWORD clears the PIN and switches the controls off.
     *
     * It has to be something the account holder knows and a child in front of the television does
     * not — which rules out the device key, printed on the home screen. The provider password is
     * already stored on the device (encrypted) for the session, so this is verified locally and
     * needs no network: a lock-out must be recoverable with the internet down.
     */
    private fun forgotPin() {
        val stored = CredentialsPreferences(context.applicationContext).getPassword().orEmpty()
        if (stored.isBlank()) { toast(R.string.s_pin_reset_unavailable); return }

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = context.getString(R.string.s_pin_reset_hint)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.s_pin_reset_title)
            .setMessage(R.string.s_pin_reset_message)
            .setView(input)
            .setPositiveButton(R.string.s_pin_reset_confirm) { _, _ ->
                if (input.text?.toString() == stored) {
                    parental.clear()
                    toast(R.string.s_pin_reset_done)
                } else {
                    toast(R.string.s_pin_reset_wrong)
                }
                rerender()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    private companion object { const val TAG = "ParentalRows" }
}
