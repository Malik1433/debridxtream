package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.data.prefs.IdentityPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.LicensePreferences
import com.tvonnet.debridxtreamiptv.util.AppIntegrity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/** Coarse gate state consumed by the activation screen. */
sealed class LicenseState {
    object Loading : LicenseState()
    data class Locked(val activationCode: String, val reason: Reason) : LicenseState()
    data class Active(val tier: String, val isTrial: Boolean = false) : LicenseState()

    enum class Reason {
        PENDING, DEACTIVATED, EXPIRED, TRIAL_ENDED,

        /**
         * Entitled as far as the cached document knows, but the licence server has not been reached
         * for longer than the grace window. Distinct from EXPIRED on purpose: nothing is wrong with
         * the licence, the device just needs to get online — and telling someone their subscription
         * expired when it has not is the kind of message that generates a support ticket.
         */
        OFFLINE_TOO_LONG
    }
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

    /**
     * True once this device's licence doc is confirmed ON THE SERVER — i.e. the provider
     * can actually find it by [activationCode]. Until then the activation screen must not
     * tell the customer to go and get activated, because the lookup will fail.
     */
    val isRegisteredOnServer: Boolean get() = cache.docCreated

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
        !cache.enforce || (onlineCheckFresh() && (cache.isCurrentlyEntitled() || isTrialActive()))

    /**
     * Has this device reached the licence server recently enough to still count?
     *
     * The gap this closes: entitlement was decided purely from the cached document, so a device that
     * blocks Firebase after one successful sync stayed entitled forever. With the owner's choice of
     * online-only (no offline token), freshness is the enforcement.
     *
     * Generous on purpose — see [OnlineCheckFreshness]. It only applies under `enforce`, which
     * defaults false, so this cannot lock anyone out until enforcement is deliberately turned on.
     */
    private fun onlineCheckFresh(now: Long = System.currentTimeMillis()): Boolean =
        OnlineCheckFreshness.isFresh(
            nowMs = now,
            lastVerifiedAt = cache.lastVerifiedAt,
            firstSeenAt = cache.firstSeenAt
        )

    /** True when the app is running under the expected release certificate (see [AppIntegrity]). */
    fun appIntegrityOk(): Boolean = AppIntegrity.isTrustedSignature(appContext)

    /** The cached entitlement fields still match their tamper-evidence tag. */
    private fun cacheTrusted(): Boolean = LicenseIntegrity.verify(cache, installId)

    /**
     * Instant premium check (used by [Entitlements]). Premium (the paid Debrid tier) is
     * granted only when BOTH the app signature is trusted (not repacked/re-signed) AND
     * the cached state is untampered — a rooted prefs edit to tier=premium breaks the
     * tag and is rejected. Basic access stays fail-open elsewhere so this never bricks IPTV.
     */
    fun isPremiumCached(): Boolean =
        appIntegrityOk() && cacheTrusted() && cache.isPremium()

    /** Trial premium, gated by the same integrity checks (createdAt is covered by the tag). */
    fun isTrialActiveTrusted(now: Long = System.currentTimeMillis()): Boolean =
        appIntegrityOk() && cacheTrusted() && isTrialActive(now)

    /** Whether global licensing enforcement is currently on (cached). */
    fun isEnforced(): Boolean = cache.enforce

    /**
     * 7-day FULL-PREMIUM trial for freshly registered (still-pending) devices,
     * anchored to the license doc's server-side createdAt. Only meaningful while
     * enforcement is on — with enforcement off everything is open anyway.
     */
    fun isTrialActive(now: Long = System.currentTimeMillis()): Boolean {
        if (cache.status != LicensePreferences.STATUS_PENDING) return false
        // A device that was previously activated does NOT get a fresh trial when it
        // re-registers as pending (e.g. after an admin deletes it) — otherwise "remove
        // client" would just reset it to a 7-day trial.
        if (cache.everEntitled) return false
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
        if (!cache.enforce) return LicenseState.Active(cache.tier)
        // Stale beats entitled: without this the gate would refuse entry while the state still said
        // Active, and the two would disagree about the same device.
        if (!onlineCheckFresh()) return LicenseState.Locked(activationCode, LicenseState.Reason.OFFLINE_TOO_LONG)
        if (cache.isCurrentlyEntitled()) return LicenseState.Active(cache.tier)
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

        // Migration/backfill: a device that is entitled right now counts as
        // ever-entitled, so if the admin later removes it, re-registering as pending
        // can't hand it a fresh 7-day trial. (Captures devices that were active before
        // the everEntitled flag existed.)
        if (cache.isCurrentlyEntitled()) cache.everEntitled = true

        // Stamp the install's first sighting once, so a device that never reaches the server still
        // gets a bounded first-run window rather than an unbounded one. An install that predates this
        // field reads 0, which OnlineCheckFreshness treats as "new" — deliberately lenient, because
        // guessing "ancient" would lock out existing users on the very build that ships this.
        if (cache.firstSeenAt <= 0L) cache.firstSeenAt = System.currentTimeMillis()

        attachEnforceConfigListener()

        val docRef = db.collection(COLLECTION).document(installId)
        registerOrTouchLicenseDoc(docRef)

        listener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) { Log.w(TAG, "license listen error", e); return@addSnapshotListener }
            if (snapshot != null && !snapshot.exists()) {
                onLicenseDocDeleted()
                return@addSnapshotListener
            }
            // ONLY a real server round-trip counts as "we reached the licence server". Firestore
            // replays this same listener from its own offline cache, and stamping on that would mean
            // the online gate never expires — an enforcement mechanism that always says yes.
            if (snapshot != null && !snapshot.metadata.isFromCache) {
                cache.lastVerifiedAt = System.currentTimeMillis()
            }
            if (snapshot != null && snapshot.exists()) {
                syncCacheFromSnapshot(snapshot)
            }
            _state.value = cachedState()
        }
    }

    // Global enforcement switch (fail-open default). Kept separate so the owner can
    // roll the gate out safely: ship code with enforce=false, flip to true when ready.
    private fun attachEnforceConfigListener() {
        configListener = db.collection(CONFIG_COLLECTION).document(CONFIG_LICENSING)
            .addSnapshotListener { snap, e ->
                if (e == null && snap != null && snap.exists()) {
                    cache.enforce = snap.getBoolean("enforce") ?: false
                    LicenseIntegrity.seal(cache, installId) // re-seal: enforce is covered by the tag
                    _state.value = cachedState()
                }
            }
    }

    // Create the doc as `pending` ONLY if it doesn't exist yet — never overwrite an
    // admin-set status. If it exists, only touch telemetry fields (merge).
    private fun registerOrTouchLicenseDoc(docRef: com.google.firebase.firestore.DocumentReference) {
        docRef.get().addOnSuccessListener { snap ->
            if (snap == null || !snap.exists()) {
                docRef.set(
                    mapOf(
                        "status" to LicensePreferences.STATUS_PENDING,
                        "tier" to LicensePreferences.TIER_NORMAL,
                        "activationCode" to activationCode,
                        "appVersionCode" to BuildConfig.VERSION_CODE,
                        "appVersionName" to BuildConfig.VERSION_NAME,
                        // Server clock, NOT client millis: the trial window is anchored to
                        // createdAt and the rules now require createdAt == request.time, so a
                        // crafted client can't seed a far-future createdAt for an endless trial.
                        "createdAt" to FieldValue.serverTimestamp(),
                        "lastSeenAt" to FieldValue.serverTimestamp()
                    )
                )
                    // docCreated is what the activation screen reads to tell the customer
                    // whether this device is findable by their provider yet, so it may only
                    // flip once the SERVER has the doc. Setting it when the write was merely
                    // queued told people to go and activate a device nobody could look up.
                    .addOnSuccessListener {
                        cache.docCreated = true
                        _state.value = cachedState()
                    }
                    .addOnFailureListener { Log.w(TAG, "license doc create failed", it) }
            } else {
                docRef.set(
                    mapOf(
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        "appVersionCode" to BuildConfig.VERSION_CODE,
                        "appVersionName" to BuildConfig.VERSION_NAME,
                        "activationCode" to activationCode
                    ),
                    SetOptions.merge()
                )
                // The doc was already on the server, so it is findable right now.
                cache.docCreated = true
                _state.value = cachedState()
            }
        }.addOnFailureListener { Log.w(TAG, "license doc get failed", it) }
    }

    // The admin DELETED this device's license. Under enforcement that is a
    // revocation — drop entitlement so the gate locks (a fresh pending doc
    // re-created on next launch won't grant a trial either, see everEntitled).
    private fun onLicenseDocDeleted() {
        if (cache.enforce && cache.status != LicensePreferences.STATUS_INACTIVE) {
            cache.status = LicensePreferences.STATUS_INACTIVE
            LicenseIntegrity.seal(cache, installId)
        }
        _state.value = cachedState()
    }

    // Field sync from a live (existing) snapshot, verbatim — ends with a fresh integrity seal
    // so a later rooted prefs edit is detected.
    private fun syncCacheFromSnapshot(snapshot: com.google.firebase.firestore.DocumentSnapshot) {
        cache.docCreated = true
        cache.status = snapshot.getString("status") ?: cache.status
        cache.tier = snapshot.getString("tier") ?: LicensePreferences.TIER_NORMAL
        // Robust to either a Long (millis) or a Firestore Timestamp (both throw via
        // the typed getters if the stored type differs).
        cache.expiresAt = when (val raw = snapshot.get("expiresAt")) {
            is com.google.firebase.Timestamp -> raw.toDate().time
            is Number -> raw.toLong()
            else -> 0L
        }
        // createdAt is now a server Timestamp (older docs may still hold a Long, and
        // a brand-new doc's serverTimestamp is null in the local echo until the server
        // resolves it). getTimestamp()/getLong() THROW on the wrong type, so branch on
        // the raw value instead and keep the previous value when it's absent/pending.
        cache.createdAt = when (val raw = snapshot.get("createdAt")) {
            is com.google.firebase.Timestamp -> raw.toDate().time
            is Number -> raw.toLong()
            else -> cache.createdAt
        }
        if (cache.isCurrentlyEntitled()) {
            cache.lastActiveAt = System.currentTimeMillis()
            cache.everEntitled = true // remember activation → no fresh trial after removal
        }
        LicenseIntegrity.seal(cache, installId)
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
