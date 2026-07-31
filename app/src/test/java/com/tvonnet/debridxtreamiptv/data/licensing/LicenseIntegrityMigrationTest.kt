package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.prefs.LicensePreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1 → v2 integrity-tag migration.
 *
 * This is the risky part of adding `lastVerifiedAt` to the signed set. The tag gates PREMIUM
 * (`isPremiumCached`), so if a tag sealed by an older build stopped verifying, **every existing user
 * would lose debrid** until their next successful sync — silently, and with nothing in the app to
 * explain it. The migration is what prevents that, so it is pinned here rather than reasoned about.
 *
 * The second test is the one that matters for the other direction: accepting v1 must not become a
 * permanent door that leaves `lastVerifiedAt` effectively unsigned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LicenseIntegrityMigrationTest {

    private lateinit var prefs: LicensePreferences
    private val installId = "install-abc"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LicensePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = LicensePreferences(context)
        prefs.status = LicensePreferences.STATUS_ACTIVE
        prefs.tier = LicensePreferences.TIER_PREMIUM
        prefs.expiresAt = 0L
        prefs.createdAt = 1_700_000_000_000L
        prefs.enforce = true
    }

    /** Reproduces what an older build left behind: a tag over the v1 field set. */
    private fun sealAsV1() {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                "${LicenseIntegrity.KEY_SALT}:$installId".toByteArray(Charsets.UTF_8),
                "HmacSHA256"
            )
        )
        prefs.integrityTag = mac.doFinal(prefs.legacyCanonicalV1().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a tag sealed by an older build still verifies`() {
        sealAsV1()
        assertTrue(
            "an existing install must not lose premium the moment it updates",
            LicenseIntegrity.verify(prefs, installId)
        )
    }

    @Test
    fun `and is upgraded on the spot, so v1 is never a lasting hole`() {
        sealAsV1()
        val legacyTag = prefs.integrityTag

        LicenseIntegrity.verify(prefs, installId)

        assertNotEquals("verifying a v1 tag must re-seal it as v2", legacyTag, prefs.integrityTag)

        // The upgraded tag now covers lastVerifiedAt: moving it invalidates the tag, which is the
        // whole point of signing it — an unsigned freshness stamp could be parked in the future.
        prefs.lastVerifiedAt = System.currentTimeMillis() + 999_999_999L
        assertFalse(
            "lastVerifiedAt must be covered by the v2 tag",
            LicenseIntegrity.verify(prefs, installId)
        )
    }

    @Test
    fun `a v2 tag verifies and an edited field breaks it`() {
        LicenseIntegrity.seal(prefs, installId)
        assertTrue(LicenseIntegrity.verify(prefs, installId))

        prefs.tier = LicensePreferences.TIER_NORMAL
        assertFalse("a rooted prefs edit must still be detected", LicenseIntegrity.verify(prefs, installId))
    }

    @Test
    fun `no tag at all is not trusted`() {
        prefs.integrityTag = ""
        assertFalse(LicenseIntegrity.verify(prefs, installId))
    }
}
