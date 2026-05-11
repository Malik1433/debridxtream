package com.tvonnet.debridxtreamiptv.util

import android.app.Activity
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences

/**
 * Utility class to apply the user's selected theme (Light/Dark) to activities.
 */
object ThemeHelper {

    fun applyTheme(activity: Activity) {
        // Light/Dark theme support was reverted. Default to dark.
        activity.setTheme(R.style.AppTheme)
    }
}
