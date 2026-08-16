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
     * Always shown — owner's decision, 2026-08-16.
     *
     * It used to appear only when the account held more than one server, on the reasoning that a
     * picker with a single entry does nothing. That reasoning was wrong in the case that actually
     * matters: on a device whose servers were typed in by hand, the account holds none, the row
     * vanished, and the customer went looking for a control that was not there. A row that says
     * "nothing here yet, and here is where they come from" is worth more than a hidden one.
     */
    fun show(activity: Activity) {
        val servers = AccountPlaylistSync.servers()
        if (servers.isEmpty()) {
            showNoServersYet(activity)
            return
        }

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

    /**
     * The account has no servers on it — which is the normal state for a device whose provider was
     * typed in by hand, since manual entry deliberately stays on the device and is never written to
     * the account.
     *
     * Says where servers come from instead of showing an empty list, because "empty" on its own
     * reads as a fault. The provider this device IS running is named on the row above.
     */
    private fun showNoServersYet(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.s_your_servers)
            .setMessage(activity.getString(R.string.s_no_servers_on_account, accountHome(activity)))
            .setPositiveButton(R.string.phone_close, null)
            .show()
    }

    /** The address the customer manages their account at, from the one string that owns it. */
    private fun accountHome(activity: Activity): String =
        activity.getString(R.string.companion_setup_url).trimEnd('/').removeSuffix("/link")
            .removePrefix("https://").removePrefix("http://")

    private fun hostOf(url: String?): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
}
