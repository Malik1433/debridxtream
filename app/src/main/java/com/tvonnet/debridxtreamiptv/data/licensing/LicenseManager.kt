package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.data.prefs.IdentityPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.LicensePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/** Coarse gate state consumed by the activation screen. */
sealed class LicenseState {
    object Loading : LicenseState()
    data class Locked(val activationCode: String, val reason: Reason) : LicenseState()
    data class Active(val tier: String, val isTrial: Boolean = false) : LicenseState()

    enum class Reason { PENDING, DEACTIVATED, EXPIRED, TRIAL_ENDED }
}

/**
 * Device licensing gate. Source of truth is Firestore `licenses/{installId}`; the
 * device only ever READS its status (never activates itself). Mirrors the proven
 * companion-pairing Firestore pattern (ui/companion/CompanionSetupActivity + a
 * document + addSnapshotListener), keyed by the stable install id from
 * [IdentityPreferences]. Results are mirrored into [LicensePreferences] so the
 * MainActivity gate can decide instantly and survive brief network outages.
 */
class LicenseManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val identity = IdentityPreferences(appContext)
    private val cache = LicensePreferences(appContext)
    private val db by lazy { FirebaseFirestore.getInstance() }

    /**
     * Firestore document id for this DEVICE; also the license key.
     *
     * Hardware-anchored: derived from ANDROID_ID, which is stable across app
     * restarts, app updates AND uninstall/reinstall (it only changes on a factory
     * reset). So the device key is PERMANENT for the physical device — reinstalling
     * cannot mint a fresh identity (which also closes the trial-reset loophole).
     * Falls back to the per-install UUID only when ANDROID_ID is unavailable.
     */
    val installId: String = run {
        val androidId = android.provider.Settings.Secure.getString(
            appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
        if (androidId.isNullOrBlank() || androidId == LEGACY_EMULATOR_ANDROID_ID) {
            identity.getOrCreateInstallInstanceId()
        } else {
            "hw-" + sha256("debridxtream-device-v1:$androidId").take(32)
        }
    }

    /** Human-readable code the user reads out to the admin (XXXX-XXXX). */
    val activationCode: String = deriveActivationCode(installId)

    private val _state = MutableStateFlow(cachedState())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    private var listener: ListenerRegistration? = null
    private var configListener: ListenerRegistration? = null

    /**
     * Instant, cache-based decision for the launch gate. Fail-open: when global
     * enforcement is off (the default) every device is allowed, so shipping the gate
     * cannot lock anyone out until the owner turns enforcement on. A pending device
     * inside its 7-day trial window also passes.
     */
    fun isEntitledCached(): Boolean =
        !cache.enforce || cache.isCurrentlyEntitled() || isTrialActive()

    /** Instant premium check (used by [Entitlements]). */
    fun isPremiumCached(): Boolean = cache.isPremium()

    /** Whether global licensing enforcement is currently on (cached). */
    fun isEnforced(): Boolean = cache.enforce

    /**
     * 7-day FULL-PREMIUM trial for freshly registered (still-pending) devices,
     * anchored to the license doc's server-side createdAt. Only meaningful while
     * enforcement is on — with enforcement off everything is open anyway.
     */
    fun isTrialActive(now: Long = System.currentTimeMillis()): Boolean {
        if (cache.status != LicensePreferences.STATUS_PENDING) return false
        val created = cache.createdAt
        return created > 0L && now < created + TRIAL_DURATION_MS
    }

    /** Whole days of trial remaining (>= 1 while the trial is active). */
    fun trialDaysLeft(now: Long = System.currentTimeMillis()): Int {
        if (!isTrialActive(now)) return 0
        val leftMs = (cache.createdAt + TRIAL_DURATION_MS) - now
        return ((leftMs + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
    }

    private fun cachedState(): LicenseState {
        if (!cache.enforce || cache.isCurrentlyEntitled()) return LicenseState.Active(cache.tier)
        if (isTrialActive()) return LicenseState.Active(LicensePreferences.TIER_PREMIUM, isTrial = true)
        val reason = when {
            cache.status == LicensePreferences.STATUS_ACTIVE -> LicenseState.Reason.EXPIRED // active but past expiresAt
            cache.status == LicensePreferences.STATUS_INACTIVE -> LicenseState.Reason.DEACTIVATED
            // Pending with a known registration time means the 7-day trial ran out.
            cache.status == LicensePreferences.STATUS_PENDING && cache.createdAt > 0L ->
                LicenseState.Reason.TRIAL_ENDED
            else -> LicenseState.Reason.PENDING
        }
        return LicenseState.Locked(activationCode, reason)
    }

    /** Attach the realtime listeners and ensure the pending doc exists. Idempotent. */
    fun start() {
        if (listener != null) return
        try { FirebaseFirestore.setLoggingEnabled(false) } catch (_: Exception) {}

        // Global enforcement switch (fail-open default). Kept separate so the owner can
        // roll the gate out safely: ship code with enforce=false, flip to true when ready.
        configListener = db.collection(CONFIG_COLLECTION).document(CONFIG_LICENSING)
            .addSnapshotListener { snap, e ->
                if (e == null && snap != null && snap.exists()) {
                    cache.enforce = snap.getBoolean("enforce") ?: false
                    _state.value = cachedState()
                }
            }

        val docRef = db.collection(COLLECTION).document(installId)
        val now = System.currentTimeMillis()

        // Create the doc as `pending` ONLY if it doesn't exist yet — never overwrite an
        // admin-set status. If it exists, only touch telemetry fields (merge).
        docRef.get().addOnSuccessListener { snap ->
            if (snap == null || !snap.exists()) {
                docRef.set(
                    mapOf(
                        "status" to LicensePreferences.STATUS_PENDING,
                        "tier" to LicensePreferences.TIER_NORMAL,
                        "activationCode" to activationCode,
                        "appVersionCode" to BuildConfig.VERSION_CODE,
                        "appVersionName" to BuildConfig.VERSION_NAME,
                        "createdAt" to now,
                        "lastSeenAt" to now
                    )
                ).addOnFailureListener { Log.w(TAG, "license doc create failed", it) }
            } else {
                docRef.set(
                    mapOf(
                        "lastSeenAt" to now,
                        "appVersionCode" to BuildConfig.VERSION_CODE,
                        "appVersionName" to BuildConfig.VERSION_NAME,
                        "activationCode" to activationCode
                    ),
                    SetOptions.merge()
                )
            }
            cache.docCreated = true
        }.addOnFailureListener { Log.w(TAG, "license doc get failed", it) }

        listener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) { Log.w(TAG, "license listen error", e); return@addSnapshotListener }
            if (snapshot != null && snapshot.exists()) {
                cache.docCreated = true
                cache.status = snapshot.getString("status") ?: cache.status
                cache.tier = snapshot.getString("tier") ?: LicensePreferences.TIER_NORMAL
                cache.expiresAt = snapshot.getLong("expiresAt") ?: 0L
                cache.createdAt = snapshot.getLong("createdAt") ?: cache.createdAt
                if (cache.isCurrentlyEntitled()) cache.lastActiveAt = System.currentTimeMillis()
            }
            _state.value = cachedState()
        }
    }

    fun stop() {
        listener?.remove()
        listener = null
        configListener?.remove()
        configListener = null
    }

    companion object {
        private const val TAG = "LicenseManager"
        private const val COLLECTION = "licenses"
        private const val CONFIG_COLLECTION = "app_config"
        private const val CONFIG_LICENSING = "licensing"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val TRIAL_DURATION_MS = 7 * DAY_MS
        // The well-known constant ANDROID_ID some old emulators/ROMs report.
        private const val LEGACY_EMULATOR_ANDROID_ID = "9774d56d682e549c"

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        @Volatile private var instance: LicenseManager? = null

        fun getInstance(context: Context): LicenseManager =
            instance ?: synchronized(this) {
                instance ?: LicenseManager(context).also { instance = it }
            }

        /**
         * Stable 8-char activation code (XXXX-XXXX) derived from the install id, using an
         * alphabet without visually ambiguous characters so it's easy to read out loud.
         */
        fun deriveActivationCode(installId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I
            val sb = StringBuilder(8)
            for (i in 0 until 8) sb.append(alphabet[(digest[i].toInt() and 0xFF) % alphabet.length])
            return "${sb.substring(0, 4)}-${sb.substring(4, 8)}"
        }
    }
}
