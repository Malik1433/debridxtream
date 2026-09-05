package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The degraded-mode store has to behave like the real one, because the code above it does not know
 * it is degraded. What is actually being protected: on a device whose AndroidKeyStore path fails,
 * [DebridPreferences] must not crash AND must not write addon config links — which carry the
 * user's API key inside the URL — to a plain file.
 */
class InMemoryPrefsTest {

    @Test
    fun `defaults come back until something is written`() {
        val p = InMemoryPrefs()
        assertNull(p.getString("addons", null))
        assertEquals("fallback", p.getString("addons", "fallback"))
        assertEquals(7, p.getInt("n", 7))
        assertFalse(p.getBoolean("flag", false))
        assertFalse(p.contains("addons"))
    }

    @Test
    fun `an edit is invisible until apply, then readable`() {
        val p = InMemoryPrefs()
        val editor = p.edit().putString("addons", "https://aio.example/abc123/manifest.json")
        assertNull("buffered, not committed", p.getString("addons", null))
        editor.apply()
        assertEquals("https://aio.example/abc123/manifest.json", p.getString("addons", null))
        assertTrue(p.contains("addons"))
    }

    @Test
    fun `remove and clear behave, and putString null removes`() {
        val p = InMemoryPrefs()
        p.edit().putString("a", "1").putInt("b", 2).apply()
        p.edit().remove("a").apply()
        assertNull(p.getString("a", null))
        assertEquals(2, p.getInt("b", 0))

        p.edit().putString("b2", null).apply()
        assertFalse(p.contains("b2"))

        p.edit().clear().apply()
        assertEquals(0, p.all.size)
    }

    @Test
    fun `types do not bleed into each other`() {
        val p = InMemoryPrefs()
        p.edit().putString("k", "text").apply()
        // Deliberately DIFFERENT from the platform implementation, which throws
        // ClassCastException: a degraded-mode store must not be the thing that crashes the app.
        // This also pins the erasure bug — a generic `as? T` helper always succeeds and returned
        // the String from getInt().
        assertEquals(9, p.getInt("k", 9))
        assertEquals("text", p.getString("k", null))
    }

    @Test
    fun `a string set is copied, so the caller cannot mutate the store behind its back`() {
        val p = InMemoryPrefs()
        val live = mutableSetOf("one")
        p.edit().putStringSet("s", live).apply()
        live.add("two")
        assertEquals(setOf("one"), p.getStringSet("s", null))
    }

    @Test
    fun `listeners fire for every key an apply touched`() {
        val p = InMemoryPrefs()
        val seen = mutableListOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> seen += key.orEmpty() }
        p.registerOnSharedPreferenceChangeListener(listener)

        p.edit().putString("a", "1").putInt("b", 2).apply()
        assertEquals(listOf("a", "b"), seen.sorted())

        seen.clear()
        p.unregisterOnSharedPreferenceChangeListener(listener)
        p.edit().putString("c", "3").apply()
        assertTrue("unregistered listeners stay quiet", seen.isEmpty())
    }

    @Test
    fun `nothing survives a new instance - that is the whole point`() {
        InMemoryPrefs().edit().putString("token", "secret").apply()
        assertNull("a new process starts clean; the encrypted file on disk was never touched",
            InMemoryPrefs().getString("token", null))
    }
}
