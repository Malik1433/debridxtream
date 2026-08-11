package com.tvonnet.debridxtreamiptv.ui.mode

import com.tvonnet.debridxtreamiptv.R

import android.app.Activity
import android.app.AlertDialog
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences

/**
 * M1: the Smarters-style "TV or phone?" question — but asked ONLY when the device
 * genuinely cannot answer for itself ([UiModeResolver.isAmbiguous]).
 *
 * A Fire TV, an Android TV box and an ordinary phone all detect cleanly, so none of
 * them ever sees this. Whatever the user picks is stored as the override and can be
 * changed later in Settings → Home Screen → App Layout.
 */
object UiModeChooser {

    /** Shows the one-time chooser if this device needs it. Safe to call on every start. */
    fun showIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = SettingsPreferences(activity)
        if (!prefs.isUiModeChooserPending()) return
        if (!UiModeResolver.isAmbiguous(activity)) return

        // Mark first: a dismissed dialog must not come back on every launch.
        prefs.markUiModeChooserShown()
        AlertDialog.Builder(activity)
            .setTitle(R.string.c_how_are_you_using_this)
            .setMessage(R.string.c_pick_the_layout_that_fits)
            .setPositiveButton("TV / remote") { d, _ ->
                prefs.setUiModeOverride(UiModeResolver.OVERRIDE_TV)
                d.dismiss()
            }
            .setNegativeButton("Phone / touch") { d, _ ->
                prefs.setUiModeOverride(UiModeResolver.OVERRIDE_MOBILE)
                d.dismiss()
            }
            .setCancelable(true)
            .show()
    }
}
