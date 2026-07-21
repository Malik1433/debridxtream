package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.LiveCacheData
import com.tvonnet.debridxtreamiptv.data.model.SeriesCacheData
import com.tvonnet.debridxtreamiptv.data.model.VodCacheData
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Phase 7 freeze tests for IptvCache?.withSeriesData — the cache builder consolidated out of
 * persistSeriesCategories and persistSeriesStreamsForCategory. Locks the invariant the audit cares
 * about: replacing the series slice must NOT disturb the live / vod / epg domains. Pure JVM.
 */
class SeriesCachePersistenceTest {

    private val newSeries = SeriesCacheData(categories = emptyList(), streams = emptyList())

    @Test
    fun `preserves live vod epg and only swaps series on an existing cache`() {
        val live = LiveCacheData(categories = emptyList(), streams = emptyList())
        val vod = VodCacheData(categories = emptyList(), streams = emptyList())
        val epg = mapOf("chan" to emptyList<com.tvonnet.debridxtreamiptv.data.model.EpgProgram>())
        val existing = IptvCache(
            timestamp = 1L,
            live = live,
            vod = vod,
            series = SeriesCacheData(categories = emptyList(), streams = emptyList()),
            epg = epg
        )

        val updated = existing.withSeriesData(newSeries, timestamp = 999L)

        assertEquals(999L, updated.timestamp)
        assertSame(newSeries, updated.series)
        assertSame(live, updated.live)
        assertSame(vod, updated.vod)
        assertSame(epg, updated.epg)
    }

    @Test
    fun `mints a fresh series-only cache when there is no existing cache`() {
        val updated = (null as IptvCache?).withSeriesData(newSeries, timestamp = 7L)

        assertEquals(7L, updated.timestamp)
        assertSame(newSeries, updated.series)
        assertNull(updated.live)
        assertNull(updated.vod)
        assertNull(updated.epg)
    }
}
