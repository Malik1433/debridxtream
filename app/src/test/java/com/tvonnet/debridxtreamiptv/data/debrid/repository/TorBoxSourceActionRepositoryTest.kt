package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.google.gson.JsonParser
import com.tvonnet.debridxtreamiptv.data.debrid.api.TorBoxApiService
import com.tvonnet.debridxtreamiptv.data.prefs.TorBoxPreferences
import com.tvonnet.debridxtreamiptv.data.repository.AddableTorrentIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TorBoxSourceActionRepositoryTest {

    private val service = mockk<TorBoxApiService>()
    private val preferences = mockk<TorBoxPreferences>()
    private var nowMs = 0L
    private val pacer = TorBoxRequestPacer(
        nowMs = { nowMs },
        sleep = { waitMs -> nowMs += waitMs }
    )

    @Test
    fun `invalid info hash is rejected without enqueue request`() = runTest {
        every { preferences.getToken() } returns "token"
        val repository = repository()

        val result = repository.enqueue(AddableTorrentIdentity(infoHash = "not-a-valid-hash"))

        assertEquals(TorBoxSourceActionStatus.FAILED, result.status)
        coVerify(exactly = 0) { service.createTorrent(any()) }
    }

    @Test
    fun `hash-only identity enqueues canonical magnet`() = runTest {
        every { preferences.getToken() } returns "token"
        coEvery { service.createTorrent(any()) } returns createResponse(101L)
        val repository = repository()

        val result = repository.enqueue(AddableTorrentIdentity(infoHash = HEX_HASH.uppercase()))

        assertEquals(TorBoxSourceActionResult(TorBoxSourceActionStatus.QUEUED, 101L), result)
        coVerify(exactly = 1) {
            service.createTorrent(match { it.readUtf8() == "magnet:?xt=urn:btih:$HEX_HASH" })
        }
    }

    @Test
    fun `same token and torrent identity reuses session enqueue result`() = runTest {
        every { preferences.getToken() } returns "token"
        coEvery { service.createTorrent(any()) } returns createResponse(102L)
        val repository = repository()
        val identity = AddableTorrentIdentity(infoHash = HEX_HASH)

        val first = repository.enqueue(identity)
        val second = repository.enqueue(identity)

        assertEquals(first, second)
        coVerify(exactly = 1) { service.createTorrent(any()) }
    }

    @Test
    fun `concurrent enqueue requests for same identity submit only once`() = runTest {
        every { preferences.getToken() } returns "token"
        coEvery { service.createTorrent(any()) } coAnswers {
            delay(100)
            createResponse(105L)
        }
        val repository = repository()
        val identity = AddableTorrentIdentity(infoHash = HEX_HASH)

        val first = async { repository.enqueue(identity) }
        val second = async { repository.enqueue(identity) }

        assertEquals(first.await(), second.await())
        coVerify(exactly = 1) { service.createTorrent(any()) }
    }

    @Test
    fun `token change isolates session enqueue state`() = runTest {
        var token = "token-a"
        every { preferences.getToken() } answers { token }
        coEvery { service.createTorrent(any()) } returnsMany listOf(createResponse(103L), createResponse(104L))
        val repository = repository()
        val identity = AddableTorrentIdentity(infoHash = HEX_HASH)

        val first = repository.enqueue(identity)
        token = "token-b"
        val second = repository.enqueue(identity)

        assertEquals(103L, first.torrentId)
        assertEquals(104L, second.torrentId)
        coVerify(exactly = 2) { service.createTorrent(any()) }
    }

    @Test
    fun `refresh only updates session entry for active token`() = runTest {
        var token = "token-a"
        every { preferences.getToken() } answers { token }
        coEvery { service.createTorrent(any()) } returnsMany listOf(createResponse(301L), createResponse(301L))
        coEvery { service.getTorrentStatus(301L, true) } returns statusResponse(
            downloadState = "cached",
            cached = true,
            downloadPresent = true,
            downloadFinished = true,
            progress = 1.0
        )
        val repository = repository()
        val identity = AddableTorrentIdentity(infoHash = HEX_HASH)

        repository.enqueue(identity)
        token = "token-b"
        repository.enqueue(identity)
        repository.refreshStatus(301L)

        assertEquals(TorBoxSourceActionStatus.READY, repository.peek(identity)?.status)
        token = "token-a"
        assertEquals(TorBoxSourceActionStatus.QUEUED, repository.peek(identity)?.status)
    }

    @Test
    fun `refresh returns ready only for confirmed cached contract`() = runTest {
        every { preferences.getToken() } returns "token"
        coEvery { service.getTorrentStatus(201L, true) } returns statusResponse(
            downloadState = "cached",
            cached = true,
            downloadPresent = true,
            downloadFinished = true,
            progress = 1.0
        )
        val repository = repository()

        val result = repository.refreshStatus(201L)

        assertEquals(TorBoxSourceActionStatus.READY, result.status)
    }

    @Test
    fun `completed state remains caching`() = runTest {
        every { preferences.getToken() } returns "token"
        coEvery { service.getTorrentStatus(202L, true) } returns statusResponse(
            downloadState = "completed",
            cached = false,
            downloadPresent = true,
            downloadFinished = true,
            progress = 1.0
        )
        val repository = repository()

        val result = repository.refreshStatus(202L)

        assertEquals(TorBoxSourceActionStatus.CACHING, result.status)
    }

    @Test
    fun `request pacer spaces enqueue and refresh operations`() = runTest {
        val waits = mutableListOf<Long>()
        var clock = 0L
        val requestPacer = TorBoxRequestPacer(
            nowMs = { clock },
            sleep = { waitMs ->
                waits += waitMs
                clock += waitMs
            }
        )

        requestPacer.awaitEnqueueSlot()
        requestPacer.awaitEnqueueSlot()
        requestPacer.awaitRefreshSlot()
        requestPacer.awaitRefreshSlot()

        assertEquals(listOf(6_500L, 250L), waits)
    }

    private fun repository() = TorBoxSourceActionRepository(service, preferences, pacer)

    private fun createResponse(torrentId: Long): Response<com.google.gson.JsonObject> {
        return Response.success(
            JsonParser.parseString(
                """{"success":true,"data":{"torrent_id":$torrentId}}"""
            ).asJsonObject
        )
    }

    private fun statusResponse(
        downloadState: String,
        cached: Boolean,
        downloadPresent: Boolean,
        downloadFinished: Boolean,
        progress: Double
    ): Response<com.google.gson.JsonObject> {
        return Response.success(
            JsonParser.parseString(
                """
                {
                  "success": true,
                  "data": {
                    "download_state": "$downloadState",
                    "cached": $cached,
                    "download_present": $downloadPresent,
                    "download_finished": $downloadFinished,
                    "progress": $progress
                  }
                }
                """.trimIndent()
            ).asJsonObject
        )
    }

    private fun RequestBody.readUtf8(): String {
        return Buffer().also(::writeTo).readUtf8()
    }

    private companion object {
        const val HEX_HASH = "0123456789abcdef0123456789abcdef01234567"
    }
}
