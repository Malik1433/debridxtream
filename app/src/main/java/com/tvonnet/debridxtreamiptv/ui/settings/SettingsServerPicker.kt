package com.tvonnet.debridxtreamiptv.ui.settings

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.ServerIdentity
import com.tvonnet.debridxtreamiptv.ui.ServerSwitch
import com.tvonnet.debridxtreamiptv.ui.companion.AccountPlaylistSync

/**
 * Choosing which of the account's servers THIS device runs (A5).
 *
 * The account has always been able to hold several; until now the device silently ran whichever one
 * was addressed to it, and changing that meant opening the portal on a phone. It is offered here
 * because Option A made it cheap: each server owns its data, so picking another one opens a library
 * that is already on the device instead of downloading it again.
 *
 * A dialog rather than a bottom sheet, matching the eight selectors already in
 * [SettingsSelectorDialogs]. The phone rulebook does ask for sheets, and these predate it — but one
 * sheet among eight dialogs on the same screen is worse for the customer than the inconsistency it
 * would be fixing. Converting the set is its own change.
 */
object SettingsServerPicker {

    /** The name to show on the Settings row: what this device is running now. */
    fun currentLabel(activity: Activity): String {
        val prefs = CredentialsPreferences(activity)
        return prefs.serverLabel.ifBlank { hostOf(prefs.getServerUrl()) }
    }

    /**
     * True when there is something to choose between.
     *
     * False while the account sync has not resolved yet, when the device is not claimed to an
     * account, and when the account holds one server — a picker with a single entry is a row that
     * does nothing, and the row above it already names the provider.
     */
    fun hasChoice(): Boolean = AccountPlaylistSync.servers().size > 1

    fun show(activity: Activity) {
        val servers = AccountPlaylistSync.servers()
        if (servers.isEmpty()) return

        val prefs = CredentialsPreferences(activity)
        val activeFingerprint = prefs.activeServerFingerprint
        val names = servers.map { server ->
            val running = ServerIdentity.fingerprint(server.url, server.username) == activeFingerprint
            if (running) activity.getString(R.string.s_server_running_now, server.name) else server.name
        }.toTypedArray()
        val checked = servers.indexOfFirst {
            ServerIdentity.fingerprint(it.url, it.username) == activeFingerprint
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.s_your_servers)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                val picked = servers[which]
                if (ServerIdentity.fingerprint(picked.url, picked.username) == activeFingerprint) return@setSingleChoiceItems
                AccountPlaylistSync.choose(activity.applicationContext, picked.id)
                // Straight into it: the credentials now point somewhere else, and this process is
                // holding the previous server's database. ServerSwitch explains why that means a
                // restart rather than a navigation.
                ServerSwitch.restartInto(activity)
            }
            .setNegativeButton(R.string.phone_close, null)
            .show()
    }

    private fun hostOf(url: String?): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
}
