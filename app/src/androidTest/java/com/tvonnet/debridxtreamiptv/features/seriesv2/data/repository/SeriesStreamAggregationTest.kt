package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.entity.CategoryEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap C5 — the multi-category "SELECT STREAM" pipeline, against a real Room database.
 *
 * This is the half of C5 that unit tests cannot reach: [SeriesSiblingFinder] matches through a SQL
 * `LIKE`, so which listings group together depends on the query as much as on the title rules. It
 * runs on a seeded in-memory database rather than the device's live catalog, so the assertions are
 * about behaviour, not about whatever the provider happens to be serving today.
 *
 * What is pinned here is what breaks visibly when it regresses:
 *  - decorated variants of one show collapse into one panel (too strict → "1 source" on a show
 *    with five);
 *  - a different-year show never joins them (too loose → episodes of the wrong programme);
 *  - a sibling with no cached episodes still gets a row, unresolved, rather than disappearing;
 *  - the sweep **fails open** — a failed fetch keeps the row, only a successful fetch that still
 *    lacks the episode drops it. That distinction once emptied whole panels under throttling.
 */
@RunWith(AndroidJUnit4::class)
class SeriesStreamAggregationTest {

    private lateinit var db: AppDatabase
    private lateinit var finder: SeriesSiblingFinder

    /** Records which siblings the sweep tried, and answers however the test tells it to. */
    private class FakeWarmer(
        private val outcome: (String) -> Boolean = { true }
    ) : EpisodeCacheWarmer {
        val warmed = mutableListOf<String>()
        override suspend fun ensureEpisodesCached(seriesId: String): Boolean {
            warmed += seriesId
            return outcome(seriesId)
        }
    }

    private fun aggregator(warmer: EpisodeCacheWarmer = FakeWarmer()) = SeriesStreamAggregator(
        episodeDao = db.episodeDaoV2(),
        categoryDao = db.categoryDao(),
        siblingFinder = finder,
        cacheWarmer = warmer,
        streamUrl = { episodeId, ext -> "http://host/series/u/p/$episodeId.${ext ?: "mp4"}" },
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        finder = SeriesSiblingFinder(db.seriesDao(), db.seriesDaoV2())
    }

    @After
    fun tearDown() = db.close()

    // ── seeding helpers ────────────────────────────────────────────────────────

    private fun legacySeries(id: String, name: String, categoryIds: List<String>) = SeriesEntity(
        seriesId = id,
        name = name,
        cover = null, plot = null, cast = null, director = null, genre = null,
        releaseDate = null, rating = null, rating5based = null,
        categoryId = categoryIds.firstOrNull(),
        category_ids = categoryIds,
        num = null
    )

    private fun v2Series(id: String, name: String, categoryId: String?) = SeriesEntityV2(
        seriesId = id, num = null, name = name, title = name, year = null,
        streamType = "series", cover = null, plot = null, cast = null, director = null,
        genre = null, releaseDate = null, rating = null, rating5based = null,
        youtubeTrailer = null, episodeRunTime = null, categoryId = categoryId, lastModified = null
    )

    private fun episode(seriesId: String, episodeId: String, season: Int, number: Int, ext: String?) =
        EpisodeEntityV2(
            episodeId = episodeId, seriesId = seriesId,
            seasonNumber = season, episodeNumber = number,
            title = "Episode $number", containerExtension = ext, streamType = "series",
            durationSecs = null, added = null, customSid = null, directSource = null,
            thumbnail = null, plot = null, cast = null, director = null, genre = null,
            releaseDate = null, rating = null, duration = 0L
        )

    /**
     * One show listed four times: the opened listing, two decorated variants in other categories,
     * and a same-named show from a different year that must NOT join.
     */
    private suspend fun seedWalkingDead() {
        db.categoryDao().insertCategories(
            listOf(
                CategoryEntity("c1", "|EN| TOP SERIES", "series"),
                CategoryEntity("c2", "[FR] SERIES", "series"),
                CategoryEntity("c3", "MULTI| NETFLIX 4K", "series"),
            )
        )
        db.seriesDao().insertSeries(
            listOf(
                legacySeries("100", "EN - The Walking Dead (2010)", listOf("c1")),
                legacySeries("200", "FR - The Walking Dead", listOf("c2")),
                legacySeries("300", "|MULTI| THE WALKING DEAD 4K", listOf("c3")),
                legacySeries("900", "EN - The Walking Dead (2021)", listOf("c1")),
            )
        )
        // Only the opened listing and one variant have cached episodes.
        db.seriesDaoV2().insertAll(
            listOf(v2Series("100", "EN - The Walking Dead (2010)", "c1"), v2Series("200", "FR - The Walking Dead", "c2"))
        )
        db.episodeDaoV2().insertAll(
            listOf(
                episode("100", "1001", 1, 1, "mkv"),
                episode("200", "2001", 1, 1, null),
            )
        )
    }

    // ── sibling matching ───────────────────────────────────────────────────────

    @Test
    fun decoratedVariantsOfTheSameShowAreFoundAsSiblings() = runBlocking {
        seedWalkingDead()
        val ids = finder.find("100").map { it.seriesId }.toSet()
        assertTrue("primary must always be present: $ids", "100" in ids)
        assertTrue("FR variant must group: $ids", "200" in ids)
        assertTrue("MULTI 4K variant must group: $ids", "300" in ids)
    }

    @Test
    fun aDifferentYearIsADifferentShowEvenWithAnIdenticalTitle() = runBlocking {
        seedWalkingDead()
        val ids = finder.find("100").map { it.seriesId }.toSet()
        assertTrue("2021 listing must NOT group with the 2010 one: $ids", "900" !in ids)
    }

    @Test
    fun countCategoryListingsCountsDistinctCategoriesAndNeverReturnsZero() = runBlocking {
        seedWalkingDead()
        assertEquals(3, finder.countCategoryListings("100"))
        // A series nobody has catalogued still reports one listing — the hero line must not say 0.
        assertEquals(1, finder.countCategoryListings("does-not-exist"))
    }

    @Test
    fun aTitleWithNoCatalogMatchYieldsNoSiblingsRatherThanAStrayRow() = runBlocking {
        seedWalkingDead()
        assertTrue(finder.findByTitle("Some Show Nobody Has", null).isEmpty())
    }

    @Test
    fun titleKeyedLookupFindsTheSameGroupAsIdKeyedLookup() = runBlocking {
        seedWalkingDead()
        val byTitle = finder.findByTitle("The Walking Dead", "2010").map { it.seriesId }.toSet()
        assertTrue("expected the three 2010-compatible listings, got $byTitle", byTitle.containsAll(setOf("100", "200", "300")))
        assertTrue("the 2021 show must stay out: $byTitle", "900" !in byTitle)
    }

    // ── group building ─────────────────────────────────────────────────────────

    @Test
    fun everyCategoryBecomesItsOwnGroupWithThePrimaryCategoryLeading() = runBlocking {
        seedWalkingDead()
        val groups = aggregator().aggregateEpisodeStreams("100", 1, 1)
        assertEquals(3, groups.size)
        assertEquals("the opened listing's category must lead", "c1", groups.first().categoryId)
        assertEquals("|EN| TOP SERIES", groups.first().categoryName)
        assertEquals("EN", groups.first().categoryTag)
    }

    @Test
    fun cachedEpisodesGetAPlayableUrlAndUncachedSiblingsStayPending() = runBlocking {
        seedWalkingDead()
        val streams = aggregator().aggregateEpisodeStreams("100", 1, 1).flatMap { it.streams }

        val cached = streams.single { it.sourceSeriesId == "100" }
        assertEquals("http://host/series/u/p/1001.mkv", cached.playUrl)
        assertEquals("MKV", cached.container)

        // Missing container extension must fall back to mp4, not to an empty URL.
        val defaulted = streams.single { it.sourceSeriesId == "200" }
        assertEquals("http://host/series/u/p/2001.mp4", defaulted.playUrl)

        // The sibling with no cached episodes is still offered, marked pending.
        val pending = streams.single { it.sourceSeriesId == "300" }
        assertEquals("", pending.playUrl)
        assertTrue("expected a pending marker, got ${pending.streamId}", pending.streamId.startsWith("pending:"))
    }

    @Test
    fun anEpisodeNoListingHasStillProducesTheFullPendingPanel() = runBlocking {
        seedWalkingDead()
        // Season 9 is cached nowhere — every row must be pending rather than the panel being empty.
        val streams = aggregator().aggregateEpisodeStreams("100", 9, 9).flatMap { it.streams }
        assertEquals(3, streams.size)
        assertTrue(streams.all { it.playUrl.isBlank() })
    }

    // ── the availability sweep ─────────────────────────────────────────────────

    @Test
    fun theSweepResolvesRowsItCanAndDropsOnlyConfirmedDeadOnes() = runBlocking {
        seedWalkingDead()
        val groups = aggregator().aggregateEpisodeStreams("100", 1, 1)

        // The pending sibling "300" reports a successful fetch but still has no episode row,
        // so it is definitively absent and its group disappears.
        val warmer = FakeWarmer { true }
        val verified = aggregator(warmer).verifyStreamGroups(groups, 1, 1)

        assertEquals("only the uncached sibling should have been fetched", listOf("300"), warmer.warmed)
        assertEquals(2, verified.size)
        assertTrue(verified.flatMap { it.streams }.none { it.sourceSeriesId == "300" })
    }

    @Test
    fun aFailedFetchKeepsTheRowSoThrottlingCannotEmptyThePanel() = runBlocking {
        seedWalkingDead()
        val groups = aggregator().aggregateEpisodeStreams("100", 1, 1)

        val verified = aggregator(FakeWarmer { false }).verifyStreamGroups(groups, 1, 1)

        assertEquals("nothing may be dropped when the fetch itself failed", 3, verified.size)
        val stillPending = verified.flatMap { it.streams }.single { it.sourceSeriesId == "300" }
        assertTrue(stillPending.playUrl.isBlank())
    }

    @Test
    fun theSweepDoesNoWorkWhenEveryRowIsAlreadyResolved() = runBlocking {
        seedWalkingDead()
        val resolvedOnly = aggregator().aggregateEpisodeStreams("100", 1, 1)
            .mapNotNull { g ->
                val streams = g.streams.filter { it.playUrl.isNotBlank() }
                if (streams.isEmpty()) null else g.copy(streams = streams)
            }
        val warmer = FakeWarmer()
        val verified = aggregator(warmer).verifyStreamGroups(resolvedOnly, 1, 1)

        assertTrue("no network work was needed", warmer.warmed.isEmpty())
        assertEquals(resolvedOnly, verified)
    }

    // ── click-time resolution ──────────────────────────────────────────────────

    @Test
    fun pickingAnAlreadyResolvedRowDoesNotFetchAnything() = runBlocking {
        seedWalkingDead()
        val option = aggregator().aggregateEpisodeStreams("100", 1, 1)
            .flatMap { it.streams }.single { it.sourceSeriesId == "100" }

        val warmer = FakeWarmer()
        assertEquals(option, aggregator(warmer).resolveStreamOption(option, 1, 1))
        assertTrue(warmer.warmed.isEmpty())
    }

    @Test
    fun pickingAPendingRowFetchesThatSiblingOnlyAndResolvesIt() = runBlocking {
        seedWalkingDead()
        val pending = aggregator().aggregateEpisodeStreams("100", 1, 1)
            .flatMap { it.streams }.single { it.sourceSeriesId == "300" }

        // The fetch "succeeds" by writing the rows the real sync would have written.
        val warmer = object : EpisodeCacheWarmer {
            val warmed = mutableListOf<String>()
            override suspend fun ensureEpisodesCached(seriesId: String): Boolean {
                warmed += seriesId
                db.seriesDaoV2().insertAll(listOf(v2Series("300", "|MULTI| THE WALKING DEAD 4K", "c3")))
                db.episodeDaoV2().insertAll(listOf(episode("300", "3001", 1, 1, "mp4")))
                return true
            }
        }

        val resolved = aggregator(warmer).resolveStreamOption(pending, 1, 1)
        assertNotNull(resolved)
        assertEquals(listOf("300"), warmer.warmed)
        assertEquals("3001", resolved!!.streamId)
        assertEquals("http://host/series/u/p/3001.mp4", resolved.playUrl)
    }

    @Test
    fun aSiblingThatGenuinelyLacksTheEpisodeResolvesToNullRatherThanABlankUrl() = runBlocking {
        seedWalkingDead()
        val pending = aggregator().aggregateEpisodeStreams("100", 1, 1)
            .flatMap { it.streams }.single { it.sourceSeriesId == "300" }

        assertNull(aggregator(FakeWarmer { true }).resolveStreamOption(pending, 1, 1))
    }

    @Test
    fun theDebridPathResolvesAnIptvListingToUrlExtensionAndEpisodeId() = runBlocking {
        seedWalkingDead()
        val triple = aggregator().resolveIptvPlayUrl("100", 1, 1)
        assertEquals(Triple("http://host/series/u/p/1001.mkv", "mkv", "1001"), triple)
        assertNull(aggregator(FakeWarmer { true }).resolveIptvPlayUrl("300", 1, 1))
    }
}
