package com.tvonnet.debridxtreamiptv.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 * PORTRAIT on a phone — the standard every media app follows.
 *
 * Owner decision, 2026-08-13, reversing M13's blanket landscape: browsing is portrait and only the
 * PLAYER is landscape. That is what Netflix, YouTube, Prime and Disney+ all do, and M13 locked
 * landscape not because it was better but because the portrait layouts were missing — rotating the
 * phone used to lose the Live channel list.
 *
 * So this is applied SCREEN BY SCREEN as each one gets a real portrait layout, never globally: a
 * screen still wearing the TV's wide layout must stay landscape until its own portrait design
 * lands, or it just gets squeezed.
 *
 * On TV it is a no-op, as everything here is.
 */
fun Activity.usePortraitOnTouchDevices() {
    if (resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

/**
 * The marker a screen wears once it HAS a portrait design.
 *
 * MainActivity hosts every browse screen in one window, so orientation cannot be decided once in
 * onCreate — it belongs to whichever fragment is on top. Home has its portrait layout today; Live,
 * Movies, Series, Search and Settings do not, and squeezing their wide TV layout into a 411dp
 * column would be worse than the landscape they have now. So each one turns portrait on the day it
 * gets its own design, by implementing this — and nothing else has to change.
 */
interface PortraitScreen

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

/**
 * Keep the UI out from under the status and navigation bars.
 *
 * `targetSdk 35` means Android 15 draws every app edge-to-edge whether it asked to or not, and this
 * app handled no insets at all — so on a phone the screen title sat UNDER the system clock, on
 * Settings and on Home alike. The phone rulebook is explicit: nothing under the bars, nothing behind
 * the gesture pill.
 *
 * Padding rather than `fitsSystemWindows`: the root here is a bare FrameLayout that fragments swap
 * content into, and padding survives that where the flag's one-shot consumption does not.
 *
 * A television reports zero system-bar insets, so this is a no-op there — no device branch needed,
 * and nothing on the TV moves.
 *
 * NOT for the player or the trailer: video is meant to fill the panel edge to edge.
 */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
