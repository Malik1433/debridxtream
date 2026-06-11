package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.testutil.MainDispatcherRule
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DebridPlaybackRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var remote: RealDebridRemoteDataSource
    private lateinit var accountRepository: DebridAccountRepository
    private lateinit var rateLimiter: RealDebridRateLimiter
    private lateinit var repository: DebridPlaybackRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        remote = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        rateLimiter = mockk(relaxed = true)
        every { rateLimiter.globalCooldown() } returns null
        every { rateLimiter.sourceCooldown(any()) } returns null
        coEvery { accountRepository.getCachedAuthState() } returns null

        repository = DebridPlaybackRepository(
            realDebridRemote = remote,
            debridAccountRepository = accountRepository,
            okHttpClient = OkHttpClient(),
            realDebridRateLimiter = rateLimiter
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `fresh direct addon passthrough still works for live selection`() = runTest {
        val directUrl = "https://mediafusion.elfhosted.com/playback/abc/video.mkv"

        val result = repository.resolveDebridUrl(
            infoHash = "abcdef123456",
            magnet = directUrl,
            allowDirectStreamUrlPassthrough = true
        )

        assertTrue(result is Result.Success)
        assertEquals(directUrl, (result as Result.Success).data)
    }

    @Test
    fun `history resume rejects stale direct addon passthrough and forces refresh`() = runTest {
        val directUrl = "https://mediafusion.elfhosted.com/playback/abc/video.mkv"

        val result = repository.resolveDebridUrl(
            infoHash = "abcdef123456",
            magnet = directUrl,
            allowDirectStreamUrlPassthrough = false
        )

        assertTrue(result is Result.Error)
    }

    @Test
    fun `addon proxy error redirect is terminal`() = runTest {
        val testRepository = repositoryWithClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "/error?reason=api_exception")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }

        val result = testRepository.getAddonProxyPlaybackReadiness(
            url = "https://mediafusion.elfhosted.com/playback/abc/video.mkv"
        )

        assertTrue(result is Result.Success)
        assertEquals(AddonProxyReadiness.TERMINAL, (result as Result.Success).data)
    }

    @Test
    fun `addon proxy video response is ready`() = runTest {
        val testRepository = repositoryWithClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "video/mp4")
                .header("Content-Length", "1000000")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }

        val result = testRepository.getAddonProxyPlaybackReadiness(
            url = "https://aiostreams.elfhosted.com/playback/abc/video.mkv"
        )

        assertTrue(result is Result.Success)
        assertEquals(AddonProxyReadiness.READY, (result as Result.Success).data)
    }

    @Test
    fun `addon proxy network failure is uncertain not terminal`() = runTest {
        val testRepository = repositoryWithClient {
            throw java.net.SocketTimeoutException("timeout")
        }

        val result = testRepository.getAddonProxyPlaybackReadiness(
            url = "https://aiostreams.elfhosted.com/playback/abc/video.mkv"
        )

        assertTrue(result is Result.Success)
        assertEquals(AddonProxyReadiness.UNCERTAIN, (result as Result.Success).data)
    }

    private fun repositoryWithClient(block: (Interceptor.Chain) -> Response): DebridPlaybackRepository {
        return DebridPlaybackRepository(
            realDebridRemote = remote,
            debridAccountRepository = accountRepository,
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain -> block(chain) }
                .build(),
            realDebridRateLimiter = rateLimiter
        )
    }
}
