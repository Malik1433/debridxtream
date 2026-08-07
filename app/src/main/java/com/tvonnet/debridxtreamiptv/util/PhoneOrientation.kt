package com.tvonnet.debridxtreamiptv.util

import android.app.Activity
import android.content.pm.ActivityInfo
import com.tvonnet.debridxtreamiptv.R

/**
 * M13 — the phone runs LANDSCAPE, like the TV. Owner decision, 2026-08-07:
 *
 *   "जैसे ही app खुले तो वह portrait ना हो, landscape ही हो … उसको हम configure करें ही नहीं portrait में."
 *
 * This replaces M11's portrait lock, which solved the same complaint the other way round (rotating
 * a handset lost the Live channel list because `layout-port` stopped applying). Locking the other
 * way settles it just as firmly: there is now exactly ONE orientation on a phone, so a layout can
 * never be swapped underneath the user.
 *
 * **Consequence, stated plainly:** every `layout-port/` file is now dead — those load only in
 * portrait. A phone renders the same `layout/` the TV does. They are left on disk rather than
 * deleted, so the decision stays reversible.
 *
 * SENSOR_LANDSCAPE, not LANDSCAPE, so the device works held either way round.
 *
 * What does NOT change: the UI-mode bools are keyed on `-television`, a DEVICE question, so a phone
 * keeps its touch behaviour (one tap acts, no focus-first, backdrop-tap dismiss) even while wearing
 * the TV's layout.
 *
 * On TV this is a no-op — it is landscape already, and `ui_uses_dpad_focus` is true there.
 */
fun Activity.lockLandscapeOnTouchDevices() {
    if (resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
