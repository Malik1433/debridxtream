package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver
import com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible

open class PlayerViewModelDebridDirectPassthroughTest {

    private lateinit var unifiedSourceProvider: UnifiedSourceProvider
    private lateinit var playbackResolver: PlaybackResolver
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        unifiedSourceProvider = mockk(relaxed = true)
        playbackResolver = mockk(relaxed = true)

        viewModel = PlayerViewModel(
            repository = mockk<XtreamRepository>(relaxed = true),
            seriesRepository = mockk<XtreamSeriesRepositoryV2>(relaxed = true),
            cacheManager = mockk<CacheManager>(relaxed = true),
            debridPlaybackRepository = mockk<DebridPlaybackRepository>(relaxed = true),
            unifiedSourceProvider = unifiedSourceProvider,
            playbackResolver = playbackResolver,
            tmdbRemote = mockk<TmdbRemoteDataSource>(relaxed = true),
            context = mockk<Context>(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `history movie refresh uses fresh resolver result instead of direct passthrough`() = runTest {
        val directUrl = "https://aiostreams.elfhosted.com/playback/old/token.mkv"
        val freshUrl = "https://fresh.example/movie.mkv"
        val source = movieSource(directUrl, "movie-hash-123")

        coEvery {
            unifiedSourceProvider.getMovieSources("123", "Jack Ryan", "debrid", null, "tt123")
        } returns listOf(source)
        coEvery {
            playbackResolver.resolve(
                source = "debrid",
                streamUrl = null,
                isExpired = true,
                infoHash = "movie-hash-123",
                magnet = directUrl,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = "Jack Ryan",
                allowDirectHttpPassthrough = false
            )
        } returns ResolutionResult.Success(freshUrl)

        invokeResolveDebridMovieSource(targetSource = source, title = "Jack Ryan")

        val state = viewModel.debridResolutionState.value
        assertTrue(state is DebridResolutionState.Success)
        assertEquals(freshUrl, (state as DebridResolutionState.Success).url)
    }

    @Test
    fun `history series refresh uses fresh resolver result instead of direct passthrough`() = runTest {
        val directUrl = "https://aiostreams.elfhosted.com/playback/old/episode.mkv"
        val freshUrl = "https://fresh.example/episode.mkv"
        val source = movieSource(directUrl, "series-hash-456")

        coEvery {
            unifiedSourceProvider.getSeriesEpisodeSources("series-9", 1, 2, "Nemesis", null)
        } returns listOf(source)
        coEvery {
            playbackResolver.resolve(
                source = "debrid",
                streamUrl = null,
                isExpired = true,
                infoHash = "series-hash-456",
                magnet = directUrl,
                seasonNumber = 1,
                episodeNumber = 2,
                episodeTitle = "Nemesis",
                allowDirectHttpPassthrough = false
            )
        } returns ResolutionResult.Success(freshUrl)

        invokeResolveDebridMovieSource(targetSource = source, title = "Nemesis", season = 1, episode = 2)

        val state = viewModel.debridResolutionState.value
        assertTrue(state is DebridResolutionState.Success)
        assertEquals(freshUrl, (state as DebridResolutionState.Success).url)
    }

    @Test
    fun `old aiostreams direct playback URL is not passed through when passthrough is disabled`() = runTest {
        val oldDirectUrl = "https://aiostreams.elfhosted.com/playback/old/stale-token.mkv"
        val freshUrl = "https://fresh.example/resolved.mkv"
        val source = movieSource(oldDirectUrl, "stale-direct-hash")

        coEvery {
            playbackResolver.resolve(
                source = "debrid",
                streamUrl = null,
                isExpired = true,
                infoHash = "stale-direct-hash",
                magnet = oldDirectUrl,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = "Old History Movie",
                allowDirectHttpPassthrough = false
            )
        } returns ResolutionResult.Success(freshUrl)

        invokeResolveDebridMovieSource(targetSource = source, title = "Old History Movie")

        coVerify(exactly = 1) {
            playbackResolver.resolve(
                source = "debrid",
                streamUrl = null,
                isExpired = true,
                infoHash = "stale-direct-hash",
                magnet = oldDirectUrl,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = "Old History Movie",
                allowDirectHttpPassthrough = false
            )
        }
        val state = viewModel.debridResolutionState.value
        assertTrue(state is DebridResolutionState.Success)
        assertEquals(freshUrl, (state as DebridResolutionState.Success).url)
    }

    private fun movieSource(directUrl: String, streamId: String): MovieSource {
        return MovieSource(
            stream = XtreamVodInfo(
                num = 1,
                name = "Test",
                stream_type = "movie",
                stream_id = streamId,
                stream_icon = null,
                rating = null,
                rating_5based = null,
                added = null,
                category_id = null,
                category_ids = null,
                container_extension = "mkv",
                custom_sid = null,
                direct_source = directUrl,
                releaseDate = null,
                duration = null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                youtube_trailer = null,
                cover = null,
                rating_imdb = null
            ),
            category = XtreamCategory(category_id = "1", category_name = "Debrid", parent_id = null),
            label = "Direct",
            isPrimary = true,
            provider = "aiostreams",
            sourceType = "addon",
            sourceName = "AIO Streams",
            headers = null
        )
    }

    /** Every scenario here exercises direct-preferred playback with HTTP passthrough disabled. */
    private suspend fun invokeResolveDebridMovieSource(
        targetSource: MovieSource,
        title: String?,
        season: Int? = null,
        episode: Int? = null
    ) {
        val directPreferred = true
        val allowDirectHttpPassthrough = false
        val method = PlayerViewModel::class.declaredFunctions.first { it.name == "resolveDebridMovieSource" }
        method.isAccessible = true
        method.callSuspend(
            viewModel,
            targetSource,
            season,
            episode,
            title,
            directPreferred,
            allowDirectHttpPassthrough,
            /* forceFresh= */ true
        )
    }
}

class DebridResolutionManagerDirectPassthroughTest : PlayerViewModelDebridDirectPassthroughTest()
