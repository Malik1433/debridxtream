package com.tvonnet.debridxtreamiptv.util

import android.os.SystemClock
import androidx.appcompat.app.AlertDialog

/**
 * Stops a confirmation dialog being answered by the keypress that OPENED it (2026-09-06).
 *
 * The mechanism is a well-known Android one and it is invisible until it bites: the remote's
 * ACTION_DOWN activates the settings row, the dialog opens, and the matching **ACTION_UP lands on
 * the new dialog's focused button** and clicks it. On a television the focused button is usually
 * the affirmative one, so a single OK press can both raise "Sign out of this device?" and answer
 * it — and Sign Out clears the library, Continue Watching, favourites and watch history for every
 * server used on that box.
 *
 * This is not theoretical here. It happened while driving the Fire TV during the parental-PIN
 * work: one press meant to move focus opened the sign-out confirmation. It is the same family as
 * the defect that had just been fixed in [com.tvonnet.debridxtreamiptv.ui.settings.PinWheelState] —
 * a remote press doing more than the person holding it asked for.
 *
 * Two guards, and the first one alone is what a careful TV app does anyway:
 *
 *  1. **Focus starts on the safe button.** The destructive action is never one accidental press
 *     away, and the default answer to a question nobody meant to ask is "no".
 *  2. **The destructive button is disabled for [GUARD_MS]**, so even a press delivered straight
 *     into it does nothing. It enables itself a moment later, which is far below the time anyone
 *     needs to read a sentence about what they are about to lose.
 *
 * Deliberately NOT applied to every dialog — only to the ones that destroy something. A guard on
 * a harmless "OK" is friction with nothing on the other side of the scale.
 */
object DestructiveDialog {

    /** Comfortably longer than the gap between a key's DOWN and UP, far shorter than reading time. */
    const val GUARD_MS = 700L

    /**
     * The rule, pure so it can be tested: has the guard window elapsed?
     *
     * @param shownAtMs when the dialog appeared, on the same clock as [nowMs].
     */
    fun isTooSoon(shownAtMs: Long, nowMs: Long): Boolean = nowMs - shownAtMs < GUARD_MS

    /**
     * Apply both guards. Call AFTER `show()`, because the buttons do not exist until then.
     *
     * @param dialog a shown [AlertDialog] whose POSITIVE button is the destructive one.
     */
    fun protect(dialog: AlertDialog, focusSafeButton: Boolean = true) {
        val destructive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val safe = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        destructive.isEnabled = false
        // A dialog whose whole point is typing something keeps focus in the field; the disabled
        // button is guard enough there, and taking focus away would also close the keyboard.
        if (focusSafeButton) safe?.requestFocus()

        val shownAt = SystemClock.uptimeMillis()
        destructive.postDelayed({
            // Re-check rather than trusting the delay: a paused window can deliver this late, and
            // the dialog may already be gone.
            if (!isTooSoon(shownAt, SystemClock.uptimeMillis())) destructive.isEnabled = true
        }, GUARD_MS)
    }

    /** Show the dialog and protect it — the form every destructive call site should use. */
    fun showProtected(dialog: AlertDialog, focusSafeButton: Boolean = true) {
        dialog.show()
        protect(dialog, focusSafeButton)
    }
}
