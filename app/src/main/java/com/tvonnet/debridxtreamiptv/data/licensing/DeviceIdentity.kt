package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tvonnet.debridxtreamiptv.BuildConfig
import java.security.MessageDigest
import java.util.UUID

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

    private const val PROOF_SALT = "debridxtream-identity-proof-v1:"
    private const val PREFS = "device_identity"
    private const val KEY_PROOF_SECRET = "proof_secret"

    @Volatile
    private var started = false

    /** Idempotent; safe to call from `MainActivity.onCreate`. Returns immediately. */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val installId = LicenseManager.getInstance(appContext).installId
        val proof = proofFor(
            androidId = android.provider.Settings.Secure.getString(
                appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ),
            installSecret = { installSecret(appContext) },
        )

        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            // A misconfigured/absent Auth component must not take the app down on launch.
            Log.w(TAG, "auth unavailable", e)
            return
        }

        val existing = auth.currentUser
        if (existing != null) {
            publish(installId, existing.uid, proof)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                result.user?.uid?.let { publish(installId, it, proof) }
            }
            .addOnFailureListener { e ->
                // Expected until the owner enables Anonymous sign-in. Deliberately quiet: this is a
                // capability the app does not have yet, not an error the user can act on.
                Log.i(TAG, "anonymous sign-in unavailable (${e.message})")
            }
    }

    /**
     * The secret that makes an identity write THIS device's (security audit 2026-09-26, H1).
     *
     * The installId is not a secret - the activation screen prints it - so a rule that only checked
     * `authUid == request.auth.uid` let anyone who had seen it register as this TV. The proof is a
     * hash of ANDROID_ID under a salt different from the installId's, so the installId does not give
     * it away, and it survives a clear-data (the new anonymous uid presents the same proof). The
     * first write pins it in `device_identity`; the rules refuse any later write that does not match.
     *
     * Without a usable ANDROID_ID the installId itself is a random per-install id, so the proof falls
     * back to a random secret kept beside it: both are lost together on a clear-data, which mints a
     * new installId anyway.
     */
    internal fun proofFor(androidId: String?, installSecret: () -> String): String {
        val source = if (androidId.isNullOrBlank() || androidId == LicenseManager.LEGACY_EMULATOR_ANDROID_ID) {
            installSecret()
        } else {
            androidId
        }
        return MessageDigest.getInstance("SHA-256")
            .digest((PROOF_SALT + source).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun installSecret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PROOF_SECRET, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val secret = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_PROOF_SECRET, secret).apply()
        return secret
    }

    /**
     * Records the identity. `merge` so this never clobbers fields a Function may have added. The rule
     * checks `authUid == request.auth.uid` AND that [proof] matches the one the first write pinned.
     */
    private fun publish(installId: String, authUid: String, proof: String) {
        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(installId)
            .set(
                mapOf(
                    "authUid" to authUid,
                    "proof" to proof,
                    "appVersionCode" to BuildConfig.VERSION_CODE,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .addOnFailureListener { e -> Log.w(TAG, "identity publish failed", e) }
    }
}
