package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences

/**
 * The one switch for everything the app sends home (roadmap G3, 2026-09-03).
 *
 * Until now Crashlytics crash reports, playback non-fatals and the Firebase Analytics QoE
 * events (`playback_ttff` / `playback_error` / `playback_session`) went out for every user with
 * no way to say no. They are anonymous — mode, error codes and numbers, never a title, a URL or
 * an account — but "anonymous" is not "consented". This applies the stored choice to both SDKs
 * at start-up and whenever the Settings row changes, and [PlaybackQoeTracker] asks [isEnabled]
 * before every emit, so turning it off is immediate, not next-launch.
 *
 * **2026-09-07: asked, not assumed.** This shipped defaulted ON, which made it a setting rather
 * than consent - the crash-free target (E13) needs the reports, but wanting the data is not the
 * same as being allowed it. Collection is now OFF until [DiagnosticsConsentPrompt] has been
 * answered, on every device including ones that updated into this build. Nothing here is stored
 * per user; the choice lives in SettingsPreferences.
 */
object DiagnosticsConsent {

    fun isEnabled(context: Context): Boolean = SettingsPreferences(context.applicationContext).isDiagnosticsEnabled()

    /**
     * True until the customer has answered the question either way. While it is true nothing is
     * collected - [isEnabled] is false - so the prompt can be shown at leisure without a race
     * against the first crash.
     */
    fun isPending(context: Context): Boolean =
        !SettingsPreferences(context.applicationContext).hasAnsweredDiagnostics()

    /** Apply the stored choice to the SDKs — call once at application start. */
    fun applyStored(context: Context) = applyTo(context, isEnabled(context))

    /** Persist a new choice and apply it immediately. */
    fun set(context: Context, enabled: Boolean) {
        SettingsPreferences(context.applicationContext).saveDiagnosticsEnabled(enabled)
        applyTo(context, enabled)
    }

    private fun applyTo(context: Context, enabled: Boolean) {
        runCatching { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled) }
        runCatching { FirebaseAnalytics.getInstance(context.applicationContext).setAnalyticsCollectionEnabled(enabled) }
    }
}
