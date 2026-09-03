package com.tvonnet.debridxtreamiptv.data.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parental gate, pinned: what counts as adult, what "hidden" means, how long an unlock
 * lasts, and that the PIN itself is never what gets stored.
 */
class ParentalPolicyTest {

    // ── the detector ────────────────────────────────────────────────────────

    @Test
    fun `provider spellings of adult are recognised`() {
        listOf(
            "XXX", "|XXX| ADULT", "[18+] MOVIES", "+18 CHANNELS", "★ ADULT ★", "Adults Only",
            "PORN HD", "Erotik DE", "ADULTOS ES", "FR | ADULTE", "IT ADULTI", "🔞 NIGHT", "18 plus",
        ).forEach { assertTrue(it, AdultContentDetector.isAdultCategoryName(it)) }
    }

    @Test
    fun `ordinary categories are not`() {
        listOf(
            "SPORTS", "|UK| ENTERTAINMENT", "KIDS", "SEX AND THE CITY", "CHANNEL 18", "MADULTA TV",
            "18 NOV SPECIAL", "News HD", "", null,
        ).forEach { assertFalse(it ?: "null", AdultContentDetector.isAdultCategoryName(it)) }
    }

    // ── hidden means hidden, and only while locked ───────────────────────────

    private data class Cat(val id: String, val name: String)

    private val cats = listOf(Cat("1", "SPORTS"), Cat("2", "|XXX| ADULT"), Cat("3", "KIDS"), Cat("4", "18+"))

    @Test
    fun `controls off - nothing is filtered`() {
        val p = ParentalPolicy()
        assertEquals(cats, p.filterCategories(false, cats) { it.name })
    }

    @Test
    fun `controls on and locked - adult categories and their streams disappear`() {
        val p = ParentalPolicy()
        assertEquals(listOf("1", "3"), p.filterCategories(true, cats) { it.name }.map { it.id })
        val adultIds = AdultContentDetector.adultIds(cats, { it.id }, { it.name })
        assertEquals(setOf("2", "4"), adultIds)
        val streams = listOf("s1" to "1", "s2" to "2", "s3" to "4", "s4" to null)
        assertEquals(listOf("s1", "s4"), p.filterByCategory(true, streams, adultIds) { it.second }.map { it.first })
    }

    @Test
    fun `an unlock lasts the session window and then closes`() {
        var now = 1_000L
        val p = ParentalPolicy { now }
        assertTrue(p.hidesAdult(true))
        p.unlockForSession()
        assertFalse(p.hidesAdult(true))
        assertEquals(cats, p.filterCategories(true, cats) { it.name })
        now += ParentalPolicy.SESSION_UNLOCK_MS - 1
        assertFalse(p.hidesAdult(true))
        now += 2
        assertTrue("expired", p.hidesAdult(true))
        p.unlockForSession(); p.lockNow()
        assertTrue("lockNow closes it", p.hidesAdult(true))
    }

    // ── the PIN is a salted hash, verified in constant time ─────────────────

    @Test
    fun `pin validity - four digits only`() {
        assertTrue(ParentalPolicy.isValidPin("0000"))
        assertTrue(ParentalPolicy.isValidPin("4821"))
        listOf("", "123", "12345", "12a4", "١٢٣٤").forEach { assertFalse(it, ParentalPolicy.isValidPin(it)) }
    }

    @Test
    fun `the stored hash is not the pin and only the right pin matches`() {
        val salt = ParentalPolicy.newSalt()
        val hash = ParentalPolicy.hashPin(salt, "1234")
        assertNotEquals("1234", hash)
        assertFalse(hash.contains("1234"))
        assertTrue(ParentalPolicy.matches(hash, salt, "1234"))
        assertFalse(ParentalPolicy.matches(hash, salt, "1235"))
        assertFalse(ParentalPolicy.matches(hash, ParentalPolicy.newSalt(), "1234"))
        assertFalse(ParentalPolicy.matches(null, salt, "1234"))
        assertFalse(ParentalPolicy.matches(hash, null, "1234"))
    }

    @Test
    fun `two devices never share a salt`() {
        assertNotEquals(ParentalPolicy.newSalt(), ParentalPolicy.newSalt())
    }
}
