package com.tvonnet.debridxtreamiptv.update

import android.app.Activity
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tvonnet.debridxtreamiptv.R

/**
 * The update download's progress window (2026-09-05), replacing `android.app.ProgressDialog`.
 *
 * ProgressDialog has been deprecated since API 26 — it is a modal that owns its own window and
 * outlives nothing gracefully. The replacement is a plain AlertDialog over
 * `dialog_update_progress`, which matters for two reasons beyond the deprecation:
 *
 *  - **[dismissQuietly] never throws.** The old code called `progress.dismiss()` from a coroutine
 *    that was not tied to any lifecycle, so a download finishing after the user had left the
 *    screen dismissed a dialog whose Activity was gone — `IllegalArgumentException: View not
 *    attached to window manager`. The download is lifecycle-scoped now, and this is the belt to
 *    that braces.
 *  - **[percent] only touches views when the number actually changes.** The stream loop reads
 *    64 KB at a time, so a 16 MB APK produced ~250 main-thread hops to set the same integer.
 */
class UpdateProgressDialog(activity: Activity, message: String) {

    private val view = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
    private val bar: ProgressBar = view.findViewById(R.id.pb_update_progress)
    private val percentLabel: TextView = view.findViewById(R.id.tv_update_progress_percent)

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setView(view)
        .setCancelable(false)
        .create()

    private var shownPercent = -1

    init {
        view.findViewById<TextView>(R.id.tv_update_progress_message).text = message
        indeterminate(true)
        dialog.show()
    }

    /** Before the first byte the content length may be unknown; say "working", not "0%". */
    fun indeterminate(on: Boolean) {
        bar.isIndeterminate = on
        percentLabel.text = if (on) "" else percentLabel.text
    }

    /** Main thread. Returns true when it actually redrew, so callers can skip the dispatch. */
    fun percent(value: Int): Boolean {
        val clamped = value.coerceIn(0, 100)
        if (clamped == shownPercent) return false
        shownPercent = clamped
        if (bar.isIndeterminate) bar.isIndeterminate = false
        bar.progress = clamped
        percentLabel.text = percentLabel.context.getString(R.string.f_percent, clamped)
        return true
    }

    /** Safe to call from anywhere, including after the Activity has gone. */
    fun dismissQuietly() {
        runCatching { dialog.dismiss() }
    }
}
