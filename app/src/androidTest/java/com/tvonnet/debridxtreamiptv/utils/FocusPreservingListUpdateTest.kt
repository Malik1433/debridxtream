package com.tvonnet.debridxtreamiptv.utils

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap B2.2 — "a data refresh must never steal the user's focus".
 *
 * The focus audit (`docs/reports/FOCUS_AUDIT_MASTER_PLAN.md`) named CC-1 the dominant defect class
 * in this app: adapters that `notifyDataSetChanged()` + `scrollToPosition(0)` on every async emit,
 * so a 30-second EPG tick yanks the user out of whatever row they were on.
 * [updatePreservingFocus] is the shared fix, and this pins its four rules on a real window — focus
 * behaviour cannot be proven in an emulator screenshot or a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class FocusPreservingListUpdateTest {

    /** The user is on the sidebar; a background refresh lands. Focus must not move. */
    @Test
    fun aRefreshDoesNotStealFocusFromOutsideTheList() = withActivity { activity ->
        setRows(activity, (1L..20L).toList())
        onMain { activity.outsideButton.requestFocus() }
        awaitTrue("the outside control should hold focus") { activity.outsideButton.isFocused }

        onMain { activity.recyclerView.updatePreservingFocus { activity.listAdapter.setIds((100L..120L).toList()) } }
        idle()

        assertTrue(
            "a refresh must never pull focus into the list while the user is elsewhere",
            activity.outsideButton.isFocused
        )
        assertFalse(activity.recyclerView.isFocusInsideThis())
    }

    /** The user is on a row; the refresh reorders the list. Focus follows the ITEM, not the index. */
    @Test
    fun focusFollowsTheSameItemWhenARefreshReordersTheList() = withActivity { activity ->
        setRows(activity, (1L..20L).toList())
        focusRow(activity, index = 5)
        val focusedId = focusedRowId(activity)
        assertEquals(6L, focusedId)

        // Same items, id 6 moved to the front.
        val reordered = listOf(6L) + (1L..20L).filter { it != 6L }
        onMain { activity.recyclerView.updatePreservingFocus { activity.listAdapter.setIds(reordered) } }

        awaitTrue("focus should be restored onto the item the user was on") {
            focusedRowId(activity) == 6L
        }
        assertEquals(
            "restore is by stable id, so the item's new index is where focus must be",
            0,
            focusedRowIndex(activity)
        )
    }

    /** The focused row is gone after the refresh: fall back to the nearest surviving row, not row 0. */
    @Test
    fun whenTheFocusedItemDisappearsFocusFallsBackToTheNearestSurvivingRow() = withActivity { activity ->
        setRows(activity, (1L..20L).toList())
        focusRow(activity, index = 5)
        assertEquals(6L, focusedRowId(activity))

        onMain {
            activity.recyclerView.updatePreservingFocus {
                activity.listAdapter.setIds((1L..20L).filter { it != 6L })
            }
        }

        awaitTrue("focus must land somewhere inside the list, not be dropped") {
            activity.recyclerView.isFocusInsideThis()
        }
        assertEquals(
            "the fallback is the same index, i.e. the nearest surviving row — never a jump to the top" +
                " [${describeFocus(activity)}]",
            5,
            focusedRowIndex(activity)
        )
    }

    /** An empty refresh must leave focus alone rather than yanking it somewhere arbitrary. */
    @Test
    fun anEmptyRefreshDoesNotYankFocusOrCrash() = withActivity { activity ->
        setRows(activity, (1L..20L).toList())
        focusRow(activity, index = 3)

        onMain { activity.recyclerView.updatePreservingFocus { activity.listAdapter.setIds(emptyList()) } }
        awaitTrue("the emptied list should finish laying out") { activity.recyclerView.childCount == 0 }
        idle()

        assertEquals(0, activity.listAdapter.itemCount)
        assertNull(
            "there is no row left to focus, and the helper must not invent one [${describeFocus(activity)}]",
            focusedRowView(activity)
        )
    }

    /** The snapshot must survive an update that keeps the item but replaces every view holder. */
    @Test
    fun focusIsRestoredEvenWhenEveryViewHolderIsRebound() = withActivity { activity ->
        setRows(activity, (1L..20L).toList())
        focusRow(activity, index = 2)
        val before = focusedRowView(activity)
        assertNotNull(before)

        onMain { activity.recyclerView.updatePreservingFocus { activity.listAdapter.setIds((1L..20L).toList()) } }

        awaitTrue("the same item must still hold focus after a full rebind") {
            focusedRowId(activity) == 3L
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun withActivity(block: (FocusTestActivity) -> Unit) {
        ActivityScenario.launch(FocusTestActivity::class.java).use { scenario ->
            var activity: FocusTestActivity? = null
            scenario.onActivity { activity = it }
            idle()
            block(requireNotNull(activity) { "activity never started" })
        }
    }

    private fun setRows(activity: FocusTestActivity, ids: List<Long>) {
        onMain { activity.listAdapter.setIds(ids) }
        awaitTrue("rows should be laid out") { activity.recyclerView.childCount > 0 }
    }

    private fun focusRow(activity: FocusTestActivity, index: Int) {
        onMain {
            activity.recyclerView.scrollToPosition(index)
        }
        awaitTrue("row $index should be attached") {
            activity.recyclerView.layoutManager?.findViewByPosition(index) != null
        }
        onMain { activity.recyclerView.layoutManager?.findViewByPosition(index)?.requestFocus() }
        awaitTrue("row $index should hold focus") { focusedRowIndex(activity) == index }
    }

    private fun focusedRowView(activity: FocusTestActivity): View? {
        val focused = activity.recyclerView.findFocus() ?: return null
        return activity.recyclerView.findContainingItemView(focused)
    }

    private fun focusedRowId(activity: FocusTestActivity): Long? =
        focusedRowView(activity)?.tag as? Long

    private fun focusedRowIndex(activity: FocusTestActivity): Int? {
        val row = focusedRowView(activity) ?: return null
        return activity.recyclerView.getChildAdapterPosition(row).takeIf { it >= 0 }
    }

    /** Diagnostic: what exactly holds focus in the window right now. */
    private fun describeFocus(activity: FocusTestActivity): String {
        var out = ""
        onMain {
            val windowFocus = activity.window.decorView.findFocus()
            val rvFocus = activity.recyclerView.findFocus()
            out = "window=${windowFocus?.javaClass?.simpleName}/tag=${windowFocus?.tag}" +
                " rvFocus=${rvFocus?.javaClass?.simpleName}/tag=${rvFocus?.tag}" +
                " rvIsFocused=${activity.recyclerView.isFocused}" +
                " children=${activity.recyclerView.childCount}"
        }
        return out
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    /**
     * Bounded wait on a UI condition. Deliberately NOT a stopwatch assertion (roadmap B0): the
     * timeout only bounds the wait, the assertion is on the state.
     */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + CONDITION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            onMain { satisfied = condition() }
            if (satisfied) return
            idle()
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting: $what")
    }

    private companion object {
        private const val CONDITION_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 25L
    }
}
