package com.tvonnet.debridxtreamiptv.ui.consent

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.util.DeliberateDialog
import com.tvonnet.debridxtreamiptv.util.DiagnosticsConsent

/**
 * The one-time question: may this app send crash reports and anonymous playback summaries?
 *
 * G3 shipped the switch (Settings › Data & Storage) defaulted ON, and that was the honest half of
 * the job: it made the collection visible and stoppable. What it was not is **consent** — nobody
 * had been asked. This asks, once, and until it is answered [DiagnosticsConsent.isEnabled] is
 * false, so nothing leaves the device from anyone who has not said yes.
 *
 * That deliberately includes devices that UPDATED into this build. A consent screen only new
 * installs ever see would leave the whole existing base unasked, which is the situation it exists
 * to end. The cost is one dialog, once, and the answer is one press away either way.
 *
 * Three details that matter more than the dialog itself:
 *
 * - **It is not cancellable, and it is not a trap.** Both answers are a single press, and neither
 *   is hidden. A dialog you can dismiss without answering would either nag on every launch or
 *   silently record a decision nobody made.
 * - **It is [DeliberateDialog]-guarded**, so the keypress that opened the screen behind it cannot
 *   answer it. An accidental "Allow" is consent nobody gave — the same failure the guard was
 *   written for, with nothing destroyed and the principle unchanged.
 * - **One question per launch**, decided by the CALLER. `MainActivity` skips this when the
 *   TV-or-phone chooser actually put a dialog up; two modal dialogs stacked on a television is
 *   nobody's idea of a first run. The first cut asked the chooser's `isUiModeChooserPending()`
 *   flag instead, which is true forever on any device the chooser does not apply to — so this
 *   prompt was silently never shown. Caught on the Fire TV, not in review.
 */
object DiagnosticsConsentPrompt {

    /** Safe to call on every start; does nothing once the question has been answered. */
    fun showIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = SettingsPreferences(activity)
        if (prefs.hasAnsweredDiagnostics()) return

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.s_consent_title)
            .setMessage(R.string.s_consent_message)
            .setPositiveButton(R.string.s_consent_allow) { _, _ ->
                DiagnosticsConsent.set(activity, true)
            }
            .setNegativeButton(R.string.s_consent_deny) { _, _ ->
                DiagnosticsConsent.set(activity, false)
            }
            .setCancelable(false)
            .create()

        // Focus starts on "No thanks" and "Allow" is briefly disabled: the safe answer is the one
        // a stray press lands on, and consent has to be chosen rather than collected.
        DeliberateDialog.showProtected(dialog)
    }
}
