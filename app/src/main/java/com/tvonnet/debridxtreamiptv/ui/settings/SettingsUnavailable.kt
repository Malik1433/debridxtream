package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
import com.tvonnet.debridxtreamiptv.R

/**
 * Which categories cannot do their job yet, and what to say about it (G4).
 *
 * Settings has always been willing to offer a choice that changes nothing. "Movie Rows" with no
 * catalogue on the device opened a loading dialog, found no categories and left a three-second
 * Toast; "Update TV Guide now" with no provider fetched a guide for no channels. Both look like
 * bugs, and neither says what would make them work.
 *
 * So a blocked category now carries an amber strip — the cause, then the fix — and its rows stay
 * VISIBLE but inert, except the ones that genuinely still work. Hiding them would answer the wrong
 * question: the customer is asking why the setting they remember is not there.
 *
 * No I/O of its own on purpose. The two facts it decides from are passed in, so the caller reads
 * them once per category change rather than this class reading them per row — and so the rules can
 * be tested without a device.
 */
object SettingsUnavailable {

    /**
     * @param liveKeys the rows that still act despite the block. Everything else in the category is
     *   dimmed and unclickable.
     * @param actionLabel non-null only when the fix is something Settings itself can do.
     */
    data class Blocked(
        val cause: String,
        val fix: String,
        val liveKeys: Set<String>,
        val actionLabel: String? = null,
    )

    /**
     * @param hasServer a provider is signed in on this device.
     * @param hasCatalogue that provider's catalogue has been downloaded at least once.
     */
    fun of(
        context: Context,
        category: SettingCategory,
        hasServer: Boolean,
        hasCatalogue: Boolean,
    ): Blocked? = when {
        !hasServer -> withoutServer(context, category)
        !hasCatalogue -> withoutCatalogue(context, category)
        else -> null
    }

    /**
     * Nothing on this device belongs to a provider yet.
     *
     * Live TV, the Home rows and the catalogue are all made of the provider's data, so every one of
     * their settings is a preference about something that does not exist. App Language and App
     * Layout are not — they are about this app on this device, and they keep working.
     */
    private fun withoutServer(context: Context, category: SettingCategory): Blocked? = when (category) {
        SettingCategory.LIVE_TV -> Blocked(
            cause = context.getString(R.string.s_blocked_no_server_cause),
            fix = context.getString(R.string.s_blocked_no_server_live_fix),
            liveKeys = emptySet(),
        )
        SettingCategory.HOME -> Blocked(
            cause = context.getString(R.string.s_blocked_no_server_cause),
            fix = context.getString(R.string.s_blocked_no_server_home_fix),
            liveKeys = setOf("app_language", "ui_mode"),
        )
        SettingCategory.DATA -> Blocked(
            cause = context.getString(R.string.s_blocked_no_server_cause),
            fix = context.getString(R.string.s_blocked_no_server_data_fix),
            // Clearing a cache with nothing in it is harmless and occasionally the thing that
            // unsticks a bad state, so it stays live. Only the refresh has nowhere to go.
            liveKeys = setOf("clear_cache"),
        )
        else -> null
    }

    /**
     * A provider is signed in, but its catalogue has never landed — the first sync has not run, or
     * it was cleared. Only the three row choosers depend on it, and the fix is one tap away, so
     * that one carries a button.
     */
    private fun withoutCatalogue(context: Context, category: SettingCategory): Blocked? =
        if (category != SettingCategory.HOME) {
            null
        } else {
            Blocked(
                cause = context.getString(R.string.s_blocked_no_catalogue_cause),
                fix = context.getString(R.string.s_blocked_no_catalogue_fix),
                liveKeys = setOf("app_language", "ui_mode"),
                actionLabel = context.getString(R.string.s_blocked_refresh_now),
            )
        }
}
