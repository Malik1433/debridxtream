package com.tvonnet.debridxtreamiptv.data.licensing

import com.tvonnet.debridxtreamiptv.data.prefs.LicensePreferences
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tamper-evidence for the locally cached licence state.
 *
 * The cache in [LicensePreferences] is a plaintext SharedPreferences file, so on a
 * rooted device a user could edit `status=active` / `tier=premium` / `enforce=false`
 * to unlock the premium (Debrid) tier offline. We HMAC the entitlement fields with a
 * device-bound key and store the tag alongside; every read re-verifies it. A naive
 * edit changes the fields but not the tag → [verify] fails → the premium checks in
 * [LicenseManager] refuse to honour the cached entitlement (they fall back to the
 * live Firestore value, which the device cannot forge — the rules block it).
 *
 * This is tamper-EVIDENT, not tamper-proof: the key is derived from values inside the
 * APK, so someone who reverse-engineers it can recompute the tag. It raises the bar
 * from "edit one XML value" to "reverse the app", and pairs with release signing +
 * the [com.tvonnet.debridxtreamiptv.util.AppIntegrity] signature check.
 *
 * Fail-open is preserved for BASIC access (the launch gate is unchanged); only the
 * paid Debrid tier requires a verified cache, so a corrupted tag never bricks IPTV.
 */
object LicenseIntegrity {

    // Bound to the physical install id so a tag lifted from one device is useless on
    // another. Salt is deliberately unremarkable to blend into the minified output.
    /**
     * Not a secret, and never was — it is compiled into the APK, so anyone who can read the app can
     * read this. The tag is tamper-EVIDENCE against a rooted prefs edit, not tamper-proofing against
     * someone rebuilding the app. `internal` so the migration test can construct a v1 tag the way an
     * older build did.
     */
    internal const val KEY_SALT = "dx-lic-v1"

    fun seal(prefs: LicensePreferences, installId: String) {
        prefs.integrityTag = tagFor(prefs, installId)
    }

    /** True only when a stored tag exists AND matches the current field values. */
    fun verify(prefs: LicensePreferences, installId: String): Boolean {
        val stored = prefs.integrityTag
        if (stored.isEmpty()) return false
        if (constantTimeEquals(stored, tagFor(prefs, installId))) return true

        // A tag sealed before lastVerifiedAt joined the signed set. Accept it ONCE and immediately
        // re-seal as v2 — otherwise shipping v2 invalidates every tag in the field, and because the
        // tag gates premium that would drop debrid for every user until their next successful sync.
        //
        // Upgrading on the spot is what keeps this from being a hole: the v1 form is never accepted
        // twice for the same install, so it cannot be used to keep an unsigned lastVerifiedAt.
        if (constantTimeEquals(stored, legacyTagFor(prefs, installId))) {
            seal(prefs, installId)
            return true
        }
        return false
    }

    private fun tagFor(prefs: LicensePreferences, installId: String): String =
        hmac(prefs.canonicalForSigning(), installId)

    /** The pre-v2 form, for the one-time upgrade in [verify]. */
    private fun legacyTagFor(prefs: LicensePreferences, installId: String): String =
        hmac(prefs.legacyCanonicalV1(), installId)

    private fun hmac(canonical: String, installId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$KEY_SALT:$installId".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
