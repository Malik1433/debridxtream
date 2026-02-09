package com.tvonnet.debridxtreamiptv.features.seriesv2.domain.logic

import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {

    private val policy = RetentionPolicy()
    private val now = 1000000000000L // Arbitrary fixed time
    private val threshold = 10000L // 10 seconds

    @Test
    fun `should purge old non-favorite items`() {
        val oldItem = SeriesMeta("old_bad", now - 20000) // 20s old
        val favorites = emptySet<String>()

        val result = policy.getPurgableSeriesIds(
            listOf(oldItem),
            favorites,
            threshold,
            now
        )

        assertEquals(1, result.size)
        assertEquals("old_bad", result[0])
    }

    @Test
    fun `should keep old favorite items`() {
        val oldFav = SeriesMeta("old_good", now - 20000)
        val favorites = setOf("old_good")

        val result = policy.getPurgableSeriesIds(
            listOf(oldFav),
            favorites,
            threshold,
            now
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `should keep new items regardless of favorite status`() {
        val newItem = SeriesMeta("new_item", now - 5000) // 5s old (within 10s threshold)
        val favorites = emptySet<String>()

        val result = policy.getPurgableSeriesIds(
            listOf(newItem),
            favorites,
            threshold,
            now
        )

        assertTrue(result.isEmpty())
    }
}
