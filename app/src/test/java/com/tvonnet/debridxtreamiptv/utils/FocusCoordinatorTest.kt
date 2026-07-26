package com.tvonnet.debridxtreamiptv.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B2.2 — the focus-ownership gate.
 *
 * `FocusCoordinator` is the single thing standing between two components that both decide to grab
 * focus in the same frame (fragment restore vs a player overlay vs a data refresh). The audit
 * (`docs/reports/FOCUS_AUDIT_MASTER_PLAN.md`) found "refresh steals focus" to be the dominant defect
 * class on this TV app, and this object is the arbiter — so its rules deserve to be pinned rather
 * than re-derived by reading it.
 *
 * Pure Kotlin: `DEBUG` is a `const false`, so no `android.util.Log` call survives compilation and
 * this needs no Robolectric.
 */
class FocusCoordinatorTest {

    @After
    fun tearDown() = FocusCoordinator.clear()

    @Test
    fun `the first requester runs its action`() {
        var ran = false

        FocusCoordinator.requestFocus("LIVE_GRID") { ran = true }

        assertTrue(ran)
    }

    @Test
    fun `a second owner is blocked while the first still holds the lock`() {
        var intruderRan = false

        FocusCoordinator.requestFocus("LIVE_GRID") { }
        FocusCoordinator.requestFocus("PLAYER_OVERLAY") { intruderRan = true }

        assertFalse("a competing component must not be able to yank focus mid-flight", intruderRan)
    }

    @Test
    fun `the same owner is re-entrant`() {
        var runs = 0

        FocusCoordinator.requestFocus("LIVE_GRID") { runs++ }
        FocusCoordinator.requestFocus("LIVE_GRID") { runs++ }

        assertEquals("the retry dance re-enters with the same owner and must not deadlock", 2, runs)
    }

    @Test
    fun `releasing hands the lock to the next owner`() {
        var secondRan = false

        FocusCoordinator.requestFocus("LIVE_GRID") { }
        FocusCoordinator.release("LIVE_GRID")
        FocusCoordinator.requestFocus("PLAYER_OVERLAY") { secondRan = true }

        assertTrue(secondRan)
    }

    @Test
    fun `a release from someone who does not hold the lock is ignored`() {
        var intruderRan = false

        FocusCoordinator.requestFocus("LIVE_GRID") { }
        FocusCoordinator.release("PLAYER_OVERLAY")
        FocusCoordinator.requestFocus("PLAYER_OVERLAY") { intruderRan = true }

        assertFalse("a stale release must never unlock someone else's ownership", intruderRan)
    }

    @Test
    fun `force overrides a live owner`() {
        var forcedRan = false

        FocusCoordinator.requestFocus("LIVE_GRID") { }
        FocusCoordinator.force("MODAL_DISMISS") { forcedRan = true }

        assertTrue("force exists for transitions that MUST claim focus", forcedRan)
    }

    /**
     * Crash-safety: if the action throws, ownership must not be left held forever — that would
     * freeze focus for the rest of the session (nothing here times out).
     */
    @Test
    fun `a throwing action does not strand the lock`() {
        var nextOwnerRan = false

        FocusCoordinator.requestFocus("LIVE_GRID") { throw IllegalStateException("view detached") }
        FocusCoordinator.requestFocus("PLAYER_OVERLAY") { nextOwnerRan = true }

        assertTrue("an exception inside a focus action must release ownership", nextOwnerRan)
    }

    @Test
    fun `a throwing forced action does not strand the lock either`() {
        var nextOwnerRan = false

        FocusCoordinator.force("MODAL_DISMISS") { throw IllegalStateException("view detached") }
        FocusCoordinator.requestFocus("LIVE_GRID") { nextOwnerRan = true }

        assertTrue(nextOwnerRan)
    }

    @Test
    fun `clear is the safety valve on teardown`() {
        var afterClearRan = false

        FocusCoordinator.requestFocus("LIVE_GRID") { }
        FocusCoordinator.clear()
        FocusCoordinator.requestFocus("VOD_RESTORE") { afterClearRan = true }

        assertTrue(afterClearRan)
    }
}
