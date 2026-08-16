package com.tvonnet.debridxtreamiptv.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The fingerprint decides whether a device's whole catalogue and watch history get thrown away, so
 * what counts as "the same server" is worth pinning down. Both directions matter equally: reading
 * one provider as two wipes data nobody asked to lose, and reading two providers as one is the bug
 * this exists to fix.
 */
class ServerIdentityTest {

    @Test
    fun `same provider written differently is one fingerprint`() {
        val canonical = ServerIdentity.fingerprint("http://tv.example", "user1")
        assertEquals(canonical, ServerIdentity.fingerprint("https://tv.example", "user1"))
        assertEquals(canonical, ServerIdentity.fingerprint("http://tv.example/", "user1"))
        assertEquals(canonical, ServerIdentity.fingerprint("http://TV.Example", "user1"))
        assertEquals(canonical, ServerIdentity.fingerprint("  http://tv.example  ", "user1"))
        assertEquals(canonical, ServerIdentity.fingerprint("tv.example", "user1"))
        assertEquals(canonical, ServerIdentity.fingerprint("http://tv.example/player_api.php", "user1"))
    }

    @Test
    fun `a different host is a different fingerprint`() {
        assertNotEquals(
            ServerIdentity.fingerprint("http://tv.example", "user1"),
            ServerIdentity.fingerprint("http://other.example", "user1"),
        )
    }

    @Test
    fun `a port is part of the identity`() {
        assertNotEquals(
            ServerIdentity.fingerprint("http://tv.example", "user1"),
            ServerIdentity.fingerprint("http://tv.example:8080", "user1"),
        )
    }

    @Test
    fun `two accounts on one host are two fingerprints`() {
        assertNotEquals(
            ServerIdentity.fingerprint("http://tv.example", "user1"),
            ServerIdentity.fingerprint("http://tv.example", "user2"),
        )
    }

    @Test
    fun `username case is significant because Xtream treats it that way`() {
        assertNotEquals(
            ServerIdentity.fingerprint("http://tv.example", "User1"),
            ServerIdentity.fingerprint("http://tv.example", "user1"),
        )
    }

    @Test
    fun `an incomplete credential identifies nothing`() {
        assertEquals(ServerIdentity.NONE, ServerIdentity.fingerprint(null, "user1"))
        assertEquals(ServerIdentity.NONE, ServerIdentity.fingerprint("", "user1"))
        assertEquals(ServerIdentity.NONE, ServerIdentity.fingerprint("http://tv.example", null))
        assertEquals(ServerIdentity.NONE, ServerIdentity.fingerprint("http://tv.example", " "))
    }

    @Test
    fun `credentials embedded in the url do not change the host`() {
        assertEquals(
            ServerIdentity.fingerprint("http://tv.example", "user1"),
            ServerIdentity.fingerprint("http://someone:secret@tv.example", "user1"),
        )
    }

    @Test
    fun `the fingerprint reveals nothing and is a fixed width`() {
        val fingerprint = ServerIdentity.fingerprint("http://tv.example", "user1")
        assertEquals(16, fingerprint.length)
        assert(!fingerprint.contains("tv.example"))
        assert(!fingerprint.contains("user1"))
    }
}
