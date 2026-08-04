package com.tvonnet.debridxtreamiptv.ui.mode

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * M0 (MOBILE_DUAL_MODE_PLAN): which UI this device gets — the TV experience that
 * exists today, or the touch-first phone one.
 *
 * Precedence, highest first:
 *  1. the user's explicit choice (Settings, or the one-time chooser)
 *  2. the system's own answer (`UiModeManager.currentModeType == TELEVISION`) — what
 *     Fire TV / Android TV / Google TV report, and what a phone never reports
 *  3. hardware heuristics for boxes that answer neither: a leanback-only device with
 *     no touchscreen is a TV; anything with a touchscreen is not
 *
 * [isAmbiguous] is what the Smarters-style first-run dialog hangs off: ask ONLY when
 * the device gives contradictory signals, never on a device that is obviously one or
 * the other.
 *
 * This class decides presentation only. It must never gate data, playback or licensing.
 */
enum class AppUiMode { TV, MOBILE }

object UiModeResolver {

    const val OVERRIDE_AUTO = "auto"
    const val OVERRIDE_TV = "tv"
    const val OVERRIDE_MOBILE = "mobile"

    /** The mode to render, honouring an explicit override. */
    fun resolve(context: Context, override: String?): AppUiMode =
        when (override?.trim()?.lowercase()) {
            OVERRIDE_TV -> AppUiMode.TV
            OVERRIDE_MOBILE -> AppUiMode.MOBILE
            else -> detect(context)
        }

    /** What the DEVICE says, ignoring any override. */
    fun detect(context: Context): AppUiMode {
        if (systemSaysTelevision(context)) return AppUiMode.TV
        return if (looksLikeTvHardware(context)) AppUiMode.TV else AppUiMode.MOBILE
    }

    /**
     * True when the signals disagree — e.g. a touchscreen device that still declares
     * leanback, or an emulator/box that reports neither. Only then is it worth asking
     * the user; everywhere else the answer is already unambiguous.
     */
    fun isAmbiguous(context: Context): Boolean {
        if (systemSaysTelevision(context)) return false
        val pm = context.packageManager
        val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return leanback == touch // both true (TV-ish tablet) or both false (unknown box)
    }

    private fun systemSaysTelevision(context: Context): Boolean {
        val fromManager = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType
        if (fromManager == Configuration.UI_MODE_TYPE_TELEVISION) return true
        // Some boxes leave the service default but still stamp the configuration.
        val fromConfig = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return fromConfig == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun looksLikeTvHardware(context: Context): Boolean {
        val pm = context.packageManager
        val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return leanback && !touch
    }
}
