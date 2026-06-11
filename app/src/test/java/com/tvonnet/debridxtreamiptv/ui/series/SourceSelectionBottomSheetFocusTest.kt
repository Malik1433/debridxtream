package com.tvonnet.debridxtreamiptv.ui.series

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSelectionBottomSheetFocusTest {

    @Test
    fun `builds next then previous adjacent return focus ids`() {
        val focusIds = SourceSelectionBottomSheet.buildAdjacentReturnFocusStreamIds(
            visibleStreamIds = listOf("top", "failed", "next", "last"),
            selectedStreamId = "failed"
        )

        assertEquals(listOf("next", "top"), focusIds)
    }

    @Test
    fun `resolves next adjacent row before demoted failed row`() {
        val target = SourceSelectionBottomSheet.resolveReturnFocusStreamId(
            visibleStreamIds = listOf("top", "next", "failed"),
            returnFocusStreamIds = listOf("next", "top"),
            selectedStreamId = "failed"
        )

        assertEquals("next", target)
    }

    @Test
    fun `resolves previous adjacent row when next row is missing`() {
        val target = SourceSelectionBottomSheet.resolveReturnFocusStreamId(
            visibleStreamIds = listOf("top", "failed"),
            returnFocusStreamIds = listOf("missing-next", "top"),
            selectedStreamId = "failed"
        )

        assertEquals("top", target)
    }

    @Test
    fun `resolves first row when adjacent and selected rows are missing`() {
        val target = SourceSelectionBottomSheet.resolveReturnFocusStreamId(
            visibleStreamIds = listOf("top", "other"),
            returnFocusStreamIds = listOf("missing-next", "missing-previous"),
            selectedStreamId = "missing-failed"
        )

        assertEquals("top", target)
    }

    @Test
    fun `prefers currently focused row when it is still visible`() {
        val target = SourceSelectionBottomSheet.resolveRestoreFocusStreamId(
            visibleStreamIds = listOf("top", "focused", "other"),
            focusedStreamId = "focused",
            returnFocusStreamIds = listOf("other"),
            selectedStreamId = "top"
        )

        assertEquals("focused", target)
    }
}
