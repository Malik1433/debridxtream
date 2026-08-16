package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.ui.companion.AccountPlaylistSync

/**
 * What each category's row says it holds, on the phone's Settings list (G1).
 *
 * The design asks the list to answer the question before anything is opened: "4 Stremio add-ons ·
 * Real-Debrid on" beats "STREMIO · DEBRID", because the first tells the customer whether they need
 * to go in at all.
 *
 * Cheap on purpose — these run while a list binds. Every value here is a preference read or an
 * in-memory list; nothing touches the database or the network. A summary that cannot be produced
 * returns null and the row falls back to its fixed caption.
 */
class SettingsCategorySummaries(private val context: Context) {

    private val settings by lazy { SettingsPreferences(context) }
    private val debrid by lazy { DebridPreferences(context) }
    private val credentials by lazy { CredentialsPreferences(context) }

    fun of(category: SettingCategory): String? = runCatching {
        when (category) {
            SettingCategory.PLAYBACK -> playback()
            SettingCategory.LIVE_TV -> liveTv()
            SettingCategory.ADDONS -> addons()
            SettingCategory.ACCOUNT -> account()
            // Home Screen and Data & Storage keep their captions: the first summarises three
            // independent row choices (a sentence nobody reads), and the second would have to
            // measure the cache, which is disk work in a list binder.
            SettingCategory.HOME, SettingCategory.DATA, SettingCategory.ABOUT -> null
        }
    }.getOrNull()

    /** The languages that actually decide which audio track plays. */
    private fun playback(): String {
        val primary = SettingsOptionLabels.audioLangName(credentials.preferredAudioLang)
        val secondary = credentials.preferredAudioLang2
        return if (secondary.equals("NONE", ignoreCase = true)) {
            primary
        } else {
            context.getString(
                R.string.s_summary_pair,
                primary,
                SettingsOptionLabels.audioLang2Name(secondary),
            )
        }
    }

    /** Whether Live TV reopens where the customer left it, and whether the guide keeps itself fresh. */
    private fun liveTv(): String {
        val resume = settings.isResumeLastLiveEnabled()
        return context.getString(
            if (resume) R.string.s_summary_live_resumes else R.string.s_summary_live_from_first
        )
    }

    /** How many sources this device can actually search, which is the whole of what Add-ons is for. */
    private fun addons(): String {
        val count = debrid.getStremioAddonUrls().size + debrid.getAddonRegistryUrls().size
        val realDebrid = !debrid.getRealDebridToken().isNullOrBlank()
        val addonsText = context.resources.getQuantityString(R.plurals.s_summary_addons, count, count)
        return if (realDebrid) {
            context.getString(R.string.s_summary_pair, addonsText, context.getString(R.string.s_summary_rd_on))
        } else {
            addonsText
        }
    }

    /** Who is signed in, and how many servers they could switch between. */
    private fun account(): String? {
        val user = credentials.getUsername()?.takeIf { it.isNotBlank() } ?: return null
        val servers = AccountPlaylistSync.servers().size
        if (servers < 2) return user
        return context.getString(
            R.string.s_summary_pair,
            user,
            context.resources.getQuantityString(R.plurals.s_summary_servers, servers, servers),
        )
    }
}
