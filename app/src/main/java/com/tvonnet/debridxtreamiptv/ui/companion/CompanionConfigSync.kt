package com.tvonnet.debridxtreamiptv.ui.companion

import com.tvonnet.debridxtreamiptv.R

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager

/**
 * Always-on companion config channel.
 *
 * The device key (licensing activation code) is permanent, so the companion web page
 * can push updated config (Xtream login, debrid/addons) at ANY time — not just while
 * the QR pairing screen is open. This keeps a persistent listener on
 * `device_codes/{deviceKey}`; whenever the web pushes `status == "completed"` with a
 * newer `updatedAt` than the last applied one, the payload is applied via
 * [CompanionConfigApplier] and the user sees a toast.
 *
 * Also ensures the doc exists (`status: waiting`) so the web page's verify step
 * passes whenever the user visits it.
 */
object CompanionConfigSync {

    private const val TAG = "CompanionConfigSync"
    private const val PREFS = "companion_sync_prefs"
    private const val KEY_LAST_APPLIED = "last_applied_at"

    private var listener: ListenerRegistration? = null

    /** Idempotent; call from MainActivity.onCreate alongside LicenseManager.start(). */
    fun start(context: Context) {
        if (listener != null) return
        val appContext = context.applicationContext
        val deviceKey = LicenseManager.getInstance(appContext).activationCode
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("device_codes").document(deviceKey)

        // Make sure the doc exists so the companion page can always verify the key.
        docRef.get().addOnSuccessListener { snap ->
            if (snap == null || !snap.exists()) {
                docRef.set(
                    mapOf("status" to "waiting", "createdAt" to System.currentTimeMillis()),
                    SetOptions.merge()
                )
            }
        }

        listener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) { Log.w(TAG, "companion listen error", e); return@addSnapshotListener }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            onCompanionPush(appContext, snapshot)
        }
    }

    // One completed companion push, verbatim: de-duped by updatedAt so a Firestore replay of
    // an already-consumed push can't re-apply (and re-toast) old settings.
    private fun onCompanionPush(appContext: Context, snapshot: com.google.firebase.firestore.DocumentSnapshot) {
        val status = snapshot.getString("status")
        if (status != "completed" && status != "success") return

        // De-dupe: only apply pushes newer than the last one we consumed.
        val updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time
            ?: snapshot.getLong("updatedAt") ?: 0L
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastApplied = prefs.getLong(KEY_LAST_APPLIED, 0L)
        if (updatedAt in 1..lastApplied) return
        if (updatedAt == 0L && lastApplied != 0L) return

        val applied = try {
            CompanionConfigApplier.apply(appContext, snapshot.data)
        } catch (ex: Exception) {
            Log.e(TAG, "config apply failed", ex); false
        }
        prefs.edit().putLong(KEY_LAST_APPLIED, if (updatedAt > 0) updatedAt else System.currentTimeMillis()).apply()
        Log.i(TAG, "companion config applied (iptv=$applied)")
        try {
            Toast.makeText(appContext, appContext.getString(R.string.c_settings_updated_from_your_phone), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    fun stop() {
        listener?.remove()
        listener = null
    }
}
