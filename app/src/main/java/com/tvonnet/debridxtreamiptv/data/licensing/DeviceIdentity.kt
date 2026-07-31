package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tvonnet.debridxtreamiptv.BuildConfig

/**
 * Gives this TV an identity of its own (§7 D2 of docs/reports/ANTI_PIRACY_DECISION.md).
 *
 * Everything the app reads from Firestore today is addressed by an id that acts as a capability —
 * know the id, get the data. That is exactly why `device_codes` can hand a stranger the customer's
 * IPTV password. Rules can only express *ownership* if the caller has an identity, so the device
 * signs in anonymously and records "this uid is me" against its installId.
 *
 * Registering an identity grants nothing. `claimDevice` (U4) is what decides which account a uid
 * belongs to, and it runs while the customer is confirming at the television.
 *
 * **This must never affect playback or the licence gate.** It is fire-and-forget: anonymous sign-in
 * is not enabled in the Firebase console yet, so today every call fails with ADMIN_ONLY_OPERATION —
 * and that has to be a no-op, not a crash and not a delay. Nothing waits on it and nothing reads its
 * result.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"
    const val COLLECTION = "device_identity"

    @Volatile
    private var started = false

    /** Idempotent; safe to call from `MainActivity.onCreate`. Returns immediately. */
    fun start(context: Context) {
        if (started) return
        started = true
        val installId = LicenseManager.getInstance(context.applicationContext).installId

        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            // A misconfigured/absent Auth component must not take the app down on launch.
            Log.w(TAG, "auth unavailable", e)
            return
        }

        val existing = auth.currentUser
        if (existing != null) {
            publish(installId, existing.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                result.user?.uid?.let { publish(installId, it) }
            }
            .addOnFailureListener { e ->
                // Expected until the owner enables Anonymous sign-in. Deliberately quiet: this is a
                // capability the app does not have yet, not an error the user can act on.
                Log.i(TAG, "anonymous sign-in unavailable (${e.message})")
            }
    }

    /**
     * Records the identity. `merge` so this never clobbers fields a Function may have added, and the
     * write is allowed only because the rule checks `authUid == request.auth.uid` — a device can
     * register itself and nobody else.
     */
    private fun publish(installId: String, authUid: String) {
        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(installId)
            .set(
                mapOf(
                    "authUid" to authUid,
                    "appVersionCode" to BuildConfig.VERSION_CODE,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .addOnFailureListener { e -> Log.w(TAG, "identity publish failed", e) }
    }
}
