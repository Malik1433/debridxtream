package com.tvonnet.debridxtreamiptv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B3 — the redactor, which is about to matter a lot more.
 *
 * This app holds the user's IPTV provider credentials and Debrid tokens, and Tier G plans to route
 * diagnostics off the device. Everything that leaves — logs, the diagnostics JSONL, a Crashlytics
 * key — goes through here first, so "did it actually redact?" needs to be a test rather than a
 * reading of the regexes. Each case below asserts both halves: the secret is gone, and something
 * useful is left to debug with.
 */
class SensitiveLogRedactorTest {

    // ── describeUrl: shape only, never the address ───────────────────────────

    @Test
    fun `stream urls are described, never printed`() {
        val url = "http://provider.test:8080/live/myuser/mypass/12345.ts"

        val described = SensitiveLogRedactor.describeUrl(url)

        assertEquals("<redacted-url>", described)
        assertNoSecret(described, "myuser", "mypass", "provider.test")
    }

    @Test
    fun `magnets are called out separately from urls`() {
        assertEquals("<redacted-magnet>", SensitiveLogRedactor.describeUrl("magnet:?xt=urn:btih:abc123"))
        assertEquals("<redacted-url>", SensitiveLogRedactor.describeUrl("stremio://addon/manifest.json"))
    }

    @Test
    fun `a missing url is distinguishable from a present one`() {
        assertEquals("missing", SensitiveLogRedactor.describeUrl(null))
        assertEquals("missing", SensitiveLogRedactor.describeUrl("   "))
        assertEquals(
            "an opaque value still reports its length, which is what makes a log useful",
            "present(length=9)",
            SensitiveLogRedactor.describeUrl("some/path")
        )
    }

    @Test
    fun `secrets and hashes report only their length`() {
        assertEquals("<redacted-credential>(length=8)", SensitiveLogRedactor.describeSecret("hunter22"))
        assertEquals("<redacted-hash>(length=6)", SensitiveLogRedactor.describeHash("abc123"))
        assertEquals("missing", SensitiveLogRedactor.describeSecret(""))
        assertEquals("missing", SensitiveLogRedactor.describeHash(null))
    }

    @Test
    fun `an exception is reduced to its type`() {
        assertEquals(
            "IllegalStateException",
            SensitiveLogRedactor.describeException(IllegalStateException("token abc123 rejected"))
        )
        assertEquals("UnknownException", SensitiveLogRedactor.describeException(null))
    }

    // ── redact: free-text scrubbing ──────────────────────────────────────────

    @Test
    fun `a bare url anywhere in a message is scrubbed`() {
        val message = "failed to open http://provider.test:8080/live/myuser/mypass/1.ts after 3 tries"

        val redacted = SensitiveLogRedactor.redact(message)

        assertNoSecret(redacted, "myuser", "mypass", "provider.test")
        assertTrue("the surrounding message must survive", redacted.contains("after 3 tries"))
    }

    @Test
    fun `query-string credentials and tokens are scrubbed`() {
        val redacted = SensitiveLogRedactor.redact("sync username=malik&password=hunter22 done")

        assertNoSecret(redacted, "malik", "hunter22")
        assertTrue(redacted.contains("<redacted-credential>"))
    }

    @Test
    fun `bearer tokens and api keys are scrubbed`() {
        assertNoSecret(SensitiveLogRedactor.redact("header Bearer eyJhbGciOi.J9.abc"), "eyJhbGciOi")
        assertNoSecret(SensitiveLogRedactor.redact("call api_key=SEKRET123 ok"), "SEKRET123")
        assertNoSecret(SensitiveLogRedactor.redact("addon realdebrid=RD_TOKEN_9 ok"), "RD_TOKEN_9")
    }

    @Test
    fun `a magnet inside a message is scrubbed`() {
        assertNoSecret(
            SensitiveLogRedactor.redact("resolving magnet:?xt=urn:btih:deadbeef&dn=Movie now"),
            "deadbeef"
        )
    }

    /** A value that is itself a url collapses to the shape form rather than a half-scrubbed string. */
    @Test
    fun `a message that is only a url collapses to the url description`() {
        assertEquals(
            "<redacted-url>",
            SensitiveLogRedactor.redact("https://provider.test/live/u/p/1.ts")
        )
    }

    @Test
    fun `an ordinary message passes through unchanged`() {
        assertEquals("player stalled at 12s", SensitiveLogRedactor.redact("player stalled at 12s"))
        assertEquals("missing", SensitiveLogRedactor.redact(null))
    }

    private fun assertNoSecret(redacted: String, vararg secrets: String) {
        secrets.forEach { secret ->
            assertFalse("'$secret' leaked into: $redacted", redacted.contains(secret, ignoreCase = true))
        }
    }
}
