package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRefreshState
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen used to answer "what am I?" in two unrelated places, so it could answer twice.
 * [debridScreenOf] is now the single answer; these are the cases that were wrong before it.
 */
class DebridScreenStateTest {

    private val rows = listOf(DebridRow(id = "trending", title = "Trending", items = emptyList()))

    @Test
    fun `no addons outranks every fetch state`() {
        // A fetch against no add-ons cannot succeed, so its failure is noise on top of the reason.
        listOf(
            DebridUiState.Loading,
            DebridUiState.Error("boom"),
            DebridUiState.Content(rows, DebridRefreshState.Idle),
        ).forEach { state ->
            assertEquals(
                DebridScreen.NOT_CONNECTED,
                debridScreenOf(state, configured = false),
            )
        }
    }

    @Test
    fun `nothing yet is loading, not an error`() {
        assertEquals(DebridScreen.LOADING, debridScreenOf(null, configured = true))
        assertEquals(
            DebridScreen.LOADING,
            debridScreenOf(DebridUiState.Loading, configured = true),
        )
    }

    @Test
    fun `an error with no rows is the whole page`() {
        assertEquals(
            DebridScreen.FAILED,
            debridScreenOf(DebridUiState.Error("timed out"), configured = true),
        )
    }

    @Test
    fun `a failed refresh over rows is stale, never failed`() {
        // THE regression this guards: the rows are good, the refresh is not, and the screen must
        // keep the rows and say so rather than replacing them with an error.
        assertEquals(
            DebridScreen.STALE,
            debridScreenOf(
                DebridUiState.Content(rows, DebridRefreshState.Failed),
                configured = true,
            ),
        )
    }

    @Test
    fun `a running refresh over rows only lights the readout`() {
        assertEquals(
            DebridScreen.REFRESHING,
            debridScreenOf(
                DebridUiState.Content(rows, DebridRefreshState.Refreshing),
                configured = true,
            ),
        )
    }

    @Test
    fun `settled content is ready`() {
        assertEquals(
            DebridScreen.READY,
            debridScreenOf(DebridUiState.Content(rows, DebridRefreshState.Idle), configured = true),
        )
    }
}
