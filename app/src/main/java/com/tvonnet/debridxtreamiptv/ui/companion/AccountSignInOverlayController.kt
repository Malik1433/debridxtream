package com.tvonnet.debridxtreamiptv.ui.companion

import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.tvonnet.debridxtreamiptv.R

/**
 * Signing in to a DebridXtream account from the TV (§9 W1).
 *
 * The **fallback** way in, not the main one: typing an email and password on a remote is the slowest
 * path available, so the phone/QR route stays first on the screen. This exists for the customer who
 * has no phone to hand, and for anyone whose device was never activated by a reseller.
 *
 * It only authenticates. What follows is already built: [AccountPlaylistSync] notices a real (non
 * anonymous) user and reads that account's playlists directly, then the login screen's existing
 * auto-login picks the credentials up. Nothing here writes credentials or entitlement.
 */
class AccountSignInOverlayController(
    private val overlay: View,
    private val onSignedIn: () -> Unit,
) {
    private companion object { const val TAG = "AccountSignIn" }

    private val email: EditText = overlay.findViewById(R.id.et_account_email)
    private val password: EditText = overlay.findViewById(R.id.et_account_password)
    private val submit: View = overlay.findViewById(R.id.btn_account_submit)
    private val submitLabel: TextView = overlay.findViewById(R.id.tv_account_submit)
    private val error: TextView = overlay.findViewById(R.id.tv_account_error)
    private val close: View = overlay.findViewById(R.id.btn_account_close)

    private var busy = false

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    init {
        submit.setOnClickListener { attemptSignIn() }
        close.setOnClickListener { hide() }
    }

    fun show() {
        error.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        email.post { email.requestFocus() }
    }

    fun hide() {
        overlay.visibility = View.GONE
    }

    private fun attemptSignIn() {
        if (busy) return
        val mail = email.text?.toString()?.trim().orEmpty()
        val pass = password.text?.toString().orEmpty()
        if (mail.isEmpty() || pass.isEmpty()) {
            showError("Enter your email and password.")
            return
        }

        setBusy(true)
        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            // Keep the cause: "sign-in is unavailable" is all the customer can act on, but a
            // misconfigured Firebase component is something only this log would ever reveal.
            Log.w(TAG, "Firebase auth unavailable", e)
            setBusy(false)
            showError("Sign-in is unavailable right now.")
            return
        }

        auth.signInWithEmailAndPassword(mail, pass)
            .addOnSuccessListener {
                setBusy(false)
                hide()
                onSignedIn()
            }
            .addOnFailureListener { e ->
                setBusy(false)
                showError(friendlyError(e))
            }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        submit.isEnabled = !value
        submitLabel.text = if (value) "Signing in…" else "Sign in"
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    /**
     * Firebase's own messages name internal things ("There is no user record corresponding to this
     * identifier") that mean nothing on a television.
     */
    private fun friendlyError(e: Exception): String {
        val text = (e.message ?: "").lowercase()
        return when {
            text.contains("password is invalid") || text.contains("no user record") ||
                text.contains("credential is incorrect") || text.contains("malformed") ->
                "Wrong email or password."
            text.contains("badly formatted") -> "That email address doesn't look right."
            text.contains("network") -> "No internet connection."
            text.contains("blocked") || text.contains("too many") ->
                "Too many attempts — try again in a few minutes."
            else -> "Could not sign in. Please try again."
        }
    }
}
