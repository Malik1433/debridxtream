package com.tvonnet.debridxtreamiptv.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
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

/**
 * M13 (owner option B) — scale the type up on a handset.
 *
 * The phone now wears the TV layout, and that layout is sized to read from three metres: the design
 * uses a "px÷2" rule, so titles land at 7sp and metadata at 5.5sp. In the hand that is unreadable.
 *
 * `fontScale` is the only lever that reaches it. A dimens override would not: those sizes are
 * hard-coded inline in ~150 layouts (`android:textSize="7sp"`), not read from resources. fontScale
 * multiplies every `sp` the process resolves, inline values included.
 *
 * It multiplies the USER's accessibility setting rather than replacing it, so someone who has
 * already enlarged their system font still gets more, not less.
 *
 * TV is untouched — the guard is the same device question as everywhere else.
 */
fun phoneScaledContext(base: Context): Context {
    if (base.resources.getBoolean(R.bool.ui_uses_dpad_focus)) return base
    val scaled = Configuration(base.resources.configuration)
    scaled.fontScale = scaled.fontScale * PHONE_FONT_SCALE
    return base.createConfigurationContext(scaled)
}

/** Enough to bring a 7sp TV title to ~11sp in the hand without reflowing every fixed-height row. */
private const val PHONE_FONT_SCALE = 1.6f
