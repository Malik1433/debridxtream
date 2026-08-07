package com.tvonnet.debridxtreamiptv.util

import android.app.Activity
import android.content.pm.ActivityInfo
import com.tvonnet.debridxtreamiptv.R

/**
 * M11 — keep the browse screens portrait on a handset.
 *
 * The phone UI lives in `layout-port/`, and that qualifier means PORTRAIT: rotate the device and
 * Android loads `layout/` instead, which is the 10-foot TV design. The Live screen was the visible
 * casualty — turn the phone sideways and the channel list simply vanished, because the TV layout
 * puts it in a fixed-width side column sized for a 960dp screen.
 *
 * The obvious alternative — author `layout-land/` phone variants — does not work: **orientation
 * outranks UI mode in the qualifier table**, so a TV (which is landscape) would pick `layout-land`
 * over `layout-television` and every screen would regress there. Duplicating ~150 TV layouts into
 * `-television` to free up the default bucket is a migration, not a fix.
 *
 * So the browse screens do what every handset media app does: they stay portrait, and only
 * FULLSCREEN PLAYBACK rotates. Call this from the activities that actually have a portrait design;
 * anything else keeps whatever the manifest says, so nothing is locked into a layout that does not
 * exist for it.
 *
 * On TV this is a no-op — those activities are landscape by nature and `ui_uses_dpad_focus` is
 * true there.
 */
fun Activity.lockPortraitOnTouchDevices() {
    if (resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
