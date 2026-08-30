package com.tvonnet.debridxtreamiptv.ui

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * Moving this device to another of the account's providers (Option A).
 *
 * Under Option B a switch meant throwing the previous provider's library away and downloading the
 * new one — minutes of waiting, and that server's resume list and favourites gone for good. With
 * each provider owning its own database file and its own preference files, none of that is
 * necessary: the other library is already on the device, so switching is a matter of opening it.
 *
 * **Why a restart.** Hilt binds the database — and every DAO with it — once per process, and the
 * file is chosen from the active provider at that moment. Swapping the instance underneath live
 * DAOs would leave half the app reading a database the other half has stopped writing to. A
 * restart is the honest way to rebind everything at once, and it is quick because there is nothing
 * to fetch: the app comes back on the other provider's Home with its rows already there.
 */
object ServerSwitch {

    private const val TAG = "ServerSwitch"

    /** Set by the restart so the screen that comes up knows to say what happened. */
    const val EXTRA_SWITCHED = "SERVER_SWITCHED"

    /**
     * Restarts into the provider the credentials now point at.
     *
     * The fingerprints are deliberately left DISAGREEING across the restart. That disagreement is
     * what the new process reads to know a switch just happened — it is what sends it to the sync
     * screen, which then either opens a library that is already there or builds one for a provider
     * this device has never run. Marking it here would erase the only record that anything changed.
     */
    fun restartInto(activity: Activity) {
        Log.i(TAG, "Restarting into the provider this device now points at")

        // Every credentials/choice setter persists via apply(), and Runtime.exit(0) below does
        // NOT wait for apply()'s background queue. Without this synchronous flush the switch the
        // customer just made could die with the process, and the restart came back on the SAME
        // server (owner hit it live, 2026-08-30).
        com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(activity).flushBeforeRestart()

        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_SWITCHED, true)
        activity.startActivity(intent)
        activity.finish()
        // The process itself has to go: the point of the restart is that Hilt builds a NEW database
        // singleton, and it will not do that while this process is alive holding the old one.
        Runtime.getRuntime().exit(0)
    }

}
