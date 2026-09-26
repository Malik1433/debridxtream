package com.tvonnet.debridxtreamiptv.data.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity proof and the activation code are both checked by the SERVER (firestore.rules and
 * admin-panel/functions/index.js), so what the app computes has to match what they expect exactly.
 */
class DeviceIdentityTest {

    private val noSecret: () -> String = { error("ANDROID_ID path must not touch the install secret") }

    @Test
    fun `proof is 64 lowercase hex, the shape the rules accept`() {
        val proof = DeviceIdentity.proofFor("a1b2c3d4e5f60718", noSecret)
        assertTrue(proof, proof.matches(Regex("^[a-f0-9]{64}$")))
    }

    @Test
    fun `proof is stable for a device so a new anonymous uid can still prove it`() {
        assertEquals(
            DeviceIdentity.proofFor("a1b2c3d4e5f60718", noSecret),
            DeviceIdentity.proofFor("a1b2c3d4e5f60718", noSecret),
        )
    }

    @Test
    fun `proof cannot be read off the installId shown on screen`() {
        val androidId = "a1b2c3d4e5f60718"
        val proof = DeviceIdentity.proofFor(androidId, noSecret)
        val installIdHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("debridxtream-device-v1:$androidId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertNotEquals(installIdHash, proof)
        assertTrue(!proof.startsWith(installIdHash.take(32)))
    }

    @Test
    fun `without a usable ANDROID_ID the proof comes from the install secret`() {
        val a = DeviceIdentity.proofFor(null, { "secret-a" })
        val b = DeviceIdentity.proofFor("", { "secret-b" })
        val emulator = DeviceIdentity.proofFor("9774d56d682e549c", { "secret-a" })
        assertNotEquals(a, b)
        assertEquals(a, emulator)
    }

    // Vectors produced by deriveActivationCode in admin-panel/functions/index.js. If this fails, the
    // Functions no longer recognise the codes the TVs display and no device can be activated.
    @Test
    fun `activation code matches the Cloud Functions port`() {
        assertEquals("F6ZW-B42U", LicenseManager.deriveActivationCode("hw-0123456789abcdef0123456789abcdef"))
        assertEquals("7GGS-LT82", LicenseManager.deriveActivationCode("3f2b8c1e-1111-4222-8333-944455556666"))
    }
}
