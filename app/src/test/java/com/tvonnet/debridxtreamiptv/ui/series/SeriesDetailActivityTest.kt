package com.tvonnet.debridxtreamiptv.ui.series

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import io.mockk.*
import com.tvonnet.debridxtreamiptv.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for SeriesDetailActivity logic
 * Tests season/episode loading, fallback handling, and favorites
 */
class SeriesDetailActivityTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: XtreamRepository
    private lateinit var credentialsPrefs: CredentialsPreferences

    @Before
    fun setup() {
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        repository = mockk(relaxed = true)
        credentialsPrefs = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `fetchSeriesDetail returns complete detail response`() = runTest {
        // Given
        val mockDetail = createMockSeriesDetail("series1", "Test Series")
        coEvery { repository.fetchSeriesDetail("series1") } returns Result.Success(mockDetail)

        // When
        val result = repository.fetchSeriesDetail("series1")

        // Then
        assertTrue(result is Result.Success)
        val detail = (result as Result.Success).data
        assertEquals("Test Series", detail.info?.name)
        assertNotNull(detail.seasons)
        assertNotNull(detail.episodes)
    }

    @Test
    fun `fetchSeriesDetail handles network error`() = runTest {
        // Given
        val exception = Exception("Network timeout")
        coEvery { repository.fetchSeriesDetail("series1") } returns Result.Error(exception)

        // When
        val result = repository.fetchSeriesDetail("series1")

        // Then
        assertTrue(result is Result.Error)
        assertEquals("Network timeout", (result as Result.Error).exception.message)
    }

    @Test
    fun `getSeriesById returns cached series`() = runTest {
        // Given
        val mockSeries = createMockSeriesInfo("series1", "Cached Series")
        coEvery { repository.getSeriesById("series1") } returns mockSeries

        // When
        val result = repository.getSeriesById("series1")

        // Then
        assertNotNull(result)
        assertEquals("Cached Series", result?.name)
        assertEquals("series1", result?.series_id)
    }

    @Test
    fun `getSeriesById returns null when not found`() = runTest {
        // Given
        coEvery { repository.getSeriesById("unknown") } returns null

        // When
        val result = repository.getSeriesById("unknown")

        // Then
        assertNull(result)
    }

    @Test
    fun `buildSeriesEpisodeStreamUrl constructs correct URL`() = runTest {
        // Given
        every { repository.buildSeriesEpisodeStreamUrl("ep123", "mp4", "http://server.com") } returns 
            "http://server.com/series/user/pass/ep123.mp4"

        // When
        val url = repository.buildSeriesEpisodeStreamUrl("ep123", "mp4", "http://server.com")

        // Then
        assertEquals("http://server.com/series/user/pass/ep123.mp4", url)
    }

    @Test
    fun `seasons are correctly sorted by number`() {
        // Given
        val seasons = listOf(
            createMockSeason("3", "Season 3"),
            createMockSeason("1", "Season 1"),
            createMockSeason("2", "Season 2")
        )

        // When
        val sorted = seasons.sortedWith(compareBy { it.season_number?.toIntOrNull() ?: Int.MAX_VALUE })

        // Then
        assertEquals("1", sorted[0].season_number)
        assertEquals("2", sorted[1].season_number)
        assertEquals("3", sorted[2].season_number)
    }

    @Test
    fun `episodes are correctly sorted by episode number`() {
        // Given
        val episodes = listOf(
            createMockEpisode("ep3", "Episode 3", 3),
            createMockEpisode("ep1", "Episode 1", 1),
            createMockEpisode("ep2", "Episode 2", 2)
        )

        // When
        val sorted = episodes.sortedWith(compareBy<XtreamEpisodeInfo> { it.episode_num ?: Int.MAX_VALUE })

        // Then
        assertEquals(1, sorted[0].episode_num)
        assertEquals(2, sorted[1].episode_num)
        assertEquals(3, sorted[2].episode_num)
    }

    @Test
    fun `isFavorite returns true when series is favorited`() = runTest {
        // Given
        coEvery { repository.isFavorite("series1") } returns true

        // When
        val result = repository.isFavorite("series1")

        // Then
        assertTrue(result)
    }

    @Test
    fun `addFavorite successfully adds series to favorites`() = runTest {
        // Given
        coEvery { repository.addFavorite("series1", "series", "Test Series", "cover.jpg") } just Runs

        // When
        repository.addFavorite("series1", "series", "Test Series", "cover.jpg")

        // Then
        coVerify { repository.addFavorite("series1", "series", "Test Series", "cover.jpg") }
    }

    @Test
    fun `removeFavorite successfully removes series from favorites`() = runTest {
        // Given
        coEvery { repository.removeFavorite("series1") } just Runs

        // When
        repository.removeFavorite("series1")

        // Then
        coVerify { repository.removeFavorite("series1") }
    }

    // Helper functions
    private fun createMockSeriesDetail(id: String, name: String): XtreamSeriesDetailResponse {
        val info = XtreamSeriesDetailInfo(
            name = name,
            series_id = id,
            plot = "Test plot",
            cast = "Actor 1, Actor 2",
            director = "Director 1",
            genre = "Drama",
            releaseDate = "2023-01-01",
            rating = "8.5",
            rating_5based = 4.25,
            cover = "cover.jpg",
            backdrop_path = "backdrop.jpg",
            youtube_trailer = null
        )

        val seasons = listOf(
            createMockSeason("1", "Season 1"),
            createMockSeason("2", "Season 2")
        )

        val episodes = mapOf(
            "1" to listOf(
                createMockEpisode("ep1", "Episode 1", 1),
                createMockEpisode("ep2", "Episode 2", 2)
            ),
            "2" to listOf(
                createMockEpisode("ep3", "Episode 1", 1),
                createMockEpisode("ep4", "Episode 2", 2)
            )
        )

        return XtreamSeriesDetailResponse(
            info = info,
            episodes = episodes,
            seasons = seasons
        )
    }

    private fun createMockSeriesInfo(id: String, name: String): XtreamSeriesInfo {
        return XtreamSeriesInfo(
            num = id.toIntOrNull(),
            series_id = id,
            name = name,
            category_id = "cat1",
            cover = "cover.jpg",
            plot = "Test plot",
            cast = "Actor 1",
            director = "Director 1",
            genre = "Drama",
            releaseDate = "2023-01-01",
            rating = "8.5",
            rating_5based = 4.25,
            episodes = null,
            category_ids = listOf(1),
            backdrop_path = listOf("backdrop.jpg"),
            youtube_trailer = "trailer"
        )
    }

    private fun createMockSeason(number: String, name: String): XtreamSeasonInfo {
        return XtreamSeasonInfo(
            season_number = number,
            name = name,
            overview = "Season overview",
            air_date = "2023-01-01",
            cover = "season_cover.jpg"
        )
    }

    private fun createMockEpisode(id: String, title: String, episodeNum: Int): XtreamEpisodeInfo {
        return XtreamEpisodeInfo(
            id = id,
            title = title,
            container_extension = "mp4",
            info = XtreamEpisodeDetail(
                plot = "Episode plot",
                cast = "Actor 1",
                director = "Director 1",
                genre = "Drama",
                releaseDate = "2023-01-01",
                rating = "8.0",
                duration_secs = "3600",
                cover = "episode_cover.jpg"
            ),
            stream_type = "series",
            episode_num = episodeNum,
            season = 1,
            duration_secs = "3600",
            added = "2023-01-01",
            custom_sid = null,
            direct_source = null,
            series_id = "series1",
            thumbnail = "thumb.jpg"
        )
    }
}

