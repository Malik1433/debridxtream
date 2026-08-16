package com.tvonnet.debridxtreamiptv.data.local

import com.tvonnet.debridxtreamiptv.data.prefs.ServerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file name decides which provider's library a customer sees, so the two ways it can be wrong
 * are worth pinning: two providers sharing one file is the cross-provider leak this design exists
 * to make impossible, and one provider getting two names is a library that vanishes and rebuilds
 * itself for no reason.
 */
class ServerScopedDatabaseTest {

    @Test
    fun `two providers never share a file`() {
        val a = ServerScopedDatabase.nameFor(ServerIdentity.fingerprint("http://a.example", "user"))
        val b = ServerScopedDatabase.nameFor(ServerIdentity.fingerprint("http://b.example", "user"))
        assertNotEquals(a, b)
    }

    @Test
    fun `the same provider always resolves to the same file`() {
        val once = ServerScopedDatabase.nameFor(ServerIdentity.fingerprint("http://a.example", "user"))
        val again = ServerScopedDatabase.nameFor(ServerIdentity.fingerprint("https://a.example/", "user"))
        assertEquals(once, again)
    }

    /**
     * A device with no provider yet still has to have somewhere to write, and it must be the name
     * every existing install already uses — otherwise signing in for the first time after an update
     * would look at an empty file.
     */
    @Test
    fun `no provider means the name every install already has`() {
        assertEquals(ServerScopedDatabase.LEGACY_NAME, ServerScopedDatabase.nameFor(ServerIdentity.NONE))
    }

    @Test
    fun `a scoped name is recognisably the legacy one plus the provider`() {
        val fingerprint = ServerIdentity.fingerprint("http://a.example", "user")
        val name = ServerScopedDatabase.nameFor(fingerprint)
        assertTrue("the sweep in allDatabaseFiles matches on this prefix", name.startsWith(ServerScopedDatabase.LEGACY_NAME))
        assertTrue("the provider has to be part of the name", name.endsWith(fingerprint))
    }
}
