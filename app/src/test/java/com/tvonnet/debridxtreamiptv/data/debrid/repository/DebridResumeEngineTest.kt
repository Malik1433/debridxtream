package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for the Debrid Resume Engine.
 *
 * Simulates real-world time scenarios (24h, 48h, 72h+) to prove that the
 * "Fresh Metadata" lifecycle works correctly without needing to actually
 * wait days. Each test manipulates timestamps to simulate clock progression.
 *
 * Test Categories:
 * 1. Smart Cache (valid link, no re-resolution needed)
 * 2. Expired link re-resolution (24h+, 48h+, 72h+)
 * 3. expiresAt propagation through the chain
 * 4. ContinueWatchingItem.isExpired() logic
 * 5. Edge cases (null expiresAt, missing metadata, etc.)
 */
class DebridResumeEngineTest {

    private lateinit var debridRepo: DebridPlaybackRepository
    private lateinit var resolver: PlaybackResolver

    companion object {
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val FRESH_URL = "https://debrid-provider.com/dl/abc123/movie.mkv"
        private const val STALE_URL = "https://debrid-provider.com/dl/old-token/movie.mkv"
        private const val RESOLVED_URL = "https://debrid-provider.com/dl/new-token/movie.mkv"
        private const val INFO_HASH = "abc123def456"
        private const val MAGNET = "magnet:?xt=urn:btih:abc123def456"
    }

    @Before
    fun setup() {
        debridRepo = mockk()
        resolver = PlaybackResolver(debridRepo)
    }

    // =========================================================================
    // 1. SMART CACHE TESTS — Link is still valid, skip re-resolution
    // =========================================================================

    @Test
    fun `resume within 1 hour - Smart Cache returns instantly`() = runTest {
        // Scenario: User watched 1 hour ago, expiresAt is 22 hours in the future
        val expiresAt = System.currentTimeMillis() + (22 * HOUR_MS)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = FRESH_URL,
            isExpired = false,   // ContinueWatchingItem.isExpired() returns false
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = expiresAt
        )

        assertTrue("Should be Success", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should return cached URL", FRESH_URL, success.url)
        assertEquals("Should propagate original expiresAt", expiresAt, success.expiresAt)
    }

    @Test
    fun `resume within 12 hours - Smart Cache returns instantly`() = runTest {
        // Scenario: User watched 12 hours ago, expiresAt is 11 hours in the future
        val expiresAt = System.currentTimeMillis() + (11 * HOUR_MS)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = FRESH_URL,
            isExpired = false,
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = expiresAt
        )

        assertTrue("Should be Success", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should return cached URL", FRESH_URL, success.url)
        assertEquals("Should propagate expiresAt", expiresAt, success.expiresAt)
    }

    @Test
    fun `resume within 22 hours - Smart Cache still works`() = runTest {
        // Scenario: User watched 22 hours ago, expiresAt is 1 hour in the future
        val expiresAt = System.currentTimeMillis() + (1 * HOUR_MS)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = FRESH_URL,
            isExpired = false,
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = expiresAt
        )

        assertTrue("Should be Success", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should return cached URL", FRESH_URL, success.url)
    }

    // =========================================================================
    // 2. EXPIRED LINK TESTS — 24h, 48h, 72h+ scenarios
    // =========================================================================

    @Test
    fun `resume after 24 hours - triggers fresh resolution with new expiresAt`() = runTest {
        // Scenario: expiresAt was 1 hour ago (watched 24h ago with 23h TTL)
        coEvery {
            debridRepo.resolveDebridUrl(
                infoHash = INFO_HASH,
                magnet = MAGNET,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null
            )
        } returns Result.Success(RESOLVED_URL)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = STALE_URL,
            isExpired = true,    // ContinueWatchingItem.isExpired() returns true
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = System.currentTimeMillis() - (1 * HOUR_MS)  // Expired 1 hour ago
        )

        assertTrue("Should be Success", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should return NEWLY resolved URL, not stale one", RESOLVED_URL, success.url)
        assertNotNull("Must have new expiresAt", success.expiresAt)
        assertTrue(
            "New expiresAt should be ~23 hours from now",
            success.expiresAt!! > System.currentTimeMillis() + (22 * HOUR_MS)
        )
    }

    @Test
    fun `resume after 48 hours - triggers fresh resolution successfully`() = runTest {
        // Scenario: User last watched 48 hours ago
        coEvery {
            debridRepo.resolveDebridUrl(
                infoHash = INFO_HASH,
                magnet = MAGNET,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null
            )
        } returns Result.Success(RESOLVED_URL)

        val staleExpiresAt = System.currentTimeMillis() - (25 * HOUR_MS) // Expired 25 hours ago

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = STALE_URL,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = staleExpiresAt
        )

        assertTrue("Should be Success after 48h", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should resolve fresh URL", RESOLVED_URL, success.url)
        assertTrue(
            "New expiresAt should be ~23h in the future",
            success.expiresAt!! > System.currentTimeMillis() + (22 * HOUR_MS)
        )
    }

    @Test
    fun `resume after 72+ hours - triggers fresh resolution successfully`() = runTest {
        // Scenario: User last watched 3+ days ago — worst case
        coEvery {
            debridRepo.resolveDebridUrl(
                infoHash = INFO_HASH,
                magnet = MAGNET,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null
            )
        } returns Result.Success(RESOLVED_URL)

        val veryStaleExpiresAt = System.currentTimeMillis() - (49 * HOUR_MS) // Expired 49 hours ago

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = STALE_URL,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = veryStaleExpiresAt
        )

        assertTrue("Should be Success after 72h+", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should resolve fresh URL", RESOLVED_URL, success.url)
        assertNotNull("Must have new expiresAt", success.expiresAt)
        assertTrue(
            "New expiresAt should be in the future",
            success.expiresAt!! > System.currentTimeMillis()
        )
    }

    // =========================================================================
    // 3. EXPIRATION PROPAGATION — Ensures fresh timestamps survive the chain
    // =========================================================================

    @Test
    fun `fresh resolution generates expiresAt approximately 23 hours from now`() = runTest {
        coEvery {
            debridRepo.resolveDebridUrl(any(), any(), any(), any(), any())
        } returns Result.Success(RESOLVED_URL)

        val beforeResolve = System.currentTimeMillis()

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = null,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET
        )

        val afterResolve = System.currentTimeMillis()

        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        val expiresAt = success.expiresAt!!

        // expiresAt should be approximately 23 hours from now (within 5 seconds tolerance)
        val expected23h = 23 * HOUR_MS
        assertTrue(
            "expiresAt should be ~23h from now. Expected: ${beforeResolve + expected23h}, Got: $expiresAt",
            expiresAt >= beforeResolve + expected23h - 5000 &&
            expiresAt <= afterResolve + expected23h + 5000
        )
    }

    @Test
    fun `Smart Cache preserves original expiresAt without modification`() = runTest {
        val originalExpiresAt = System.currentTimeMillis() + (10 * HOUR_MS)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = FRESH_URL,
            isExpired = false,
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = originalExpiresAt
        )

        val success = result as ResolutionResult.Success
        assertEquals(
            "Smart Cache should return EXACT original expiresAt, not a new one",
            originalExpiresAt,
            success.expiresAt
        )
    }

    // =========================================================================
    // 4. ContinueWatchingItem.isExpired() LOGIC
    // =========================================================================

    @Test
    fun `isExpired returns true when expiresAt is null`() {
        val item = ContinueWatchingItem(
            contentId = "test",
            contentType = ContentType.MOVIE,
            title = "Test Movie",
            posterUrl = null,
            backdropUrl = null,
            currentPosition = 5000L,
            totalDuration = 100000L,
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = FRESH_URL,
            expiresAt = null  // <-- null means expired
        )

        assertTrue("null expiresAt should be treated as expired", item.isExpired())
    }

    @Test
    fun `isExpired returns true when expiresAt is in the past`() {
        val item = ContinueWatchingItem(
            contentId = "test",
            contentType = ContentType.MOVIE,
            title = "Test Movie",
            posterUrl = null,
            backdropUrl = null,
            currentPosition = 5000L,
            totalDuration = 100000L,
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = STALE_URL,
            expiresAt = System.currentTimeMillis() - (1 * HOUR_MS)  // 1 hour ago
        )

        assertTrue("Past expiresAt should be treated as expired", item.isExpired())
    }

    @Test
    fun `isExpired returns false when expiresAt is in the future`() {
        val item = ContinueWatchingItem(
            contentId = "test",
            contentType = ContentType.MOVIE,
            title = "Test Movie",
            posterUrl = null,
            backdropUrl = null,
            currentPosition = 5000L,
            totalDuration = 100000L,
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = FRESH_URL,
            expiresAt = System.currentTimeMillis() + (10 * HOUR_MS)  // 10 hours from now
        )

        assertFalse("Future expiresAt should NOT be expired", item.isExpired())
    }

    @Test
    fun `isExpired simulated 24h scenario - expiresAt set at 23h, checked at 24h`() {
        // Simulate: User played at T=0, expiresAt = T+23h, now it's T+24h
        val playTime = System.currentTimeMillis() - (24 * HOUR_MS) // 24 hours ago
        val expiresAt = playTime + (23 * HOUR_MS)  // Was set to 23h after play = 1h ago

        val item = ContinueWatchingItem(
            contentId = "test",
            contentType = ContentType.MOVIE,
            title = "Test Movie",
            posterUrl = null,
            backdropUrl = null,
            currentPosition = 5000L,
            totalDuration = 100000L,
            lastWatchedTimestamp = playTime,
            streamUrl = STALE_URL,
            expiresAt = expiresAt
        )

        assertTrue("At T+24h with T+23h expiresAt, should be expired", item.isExpired())
    }

    @Test
    fun `isExpired simulated 48h scenario - definitely expired`() {
        val playTime = System.currentTimeMillis() - (48 * HOUR_MS)
        val expiresAt = playTime + (23 * HOUR_MS) // Expired 25 hours ago

        val item = ContinueWatchingItem(
            contentId = "test",
            contentType = ContentType.MOVIE,
            title = "Test Movie",
            posterUrl = null,
            backdropUrl = null,
            currentPosition = 5000L,
            totalDuration = 100000L,
            lastWatchedTimestamp = playTime,
            streamUrl = STALE_URL,
            expiresAt = expiresAt
        )

        assertTrue("At T+48h, should definitely be expired", item.isExpired())
    }

    // =========================================================================
    // 5. EDGE CASES
    // =========================================================================

    @Test
    fun `null expiresAt with valid streamUrl forces re-resolution`() = runTest {
        // isExpired() returns true when expiresAt is null
        coEvery {
            debridRepo.resolveDebridUrl(any(), any(), any(), any(), any())
        } returns Result.Success(RESOLVED_URL)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = STALE_URL,
            isExpired = true,  // null expiresAt → isExpired() = true
            infoHash = INFO_HASH,
            magnet = MAGNET,
            currentExpiresAt = null
        )

        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals("Should resolve fresh URL", RESOLVED_URL, success.url)
        assertNotNull("Must generate new expiresAt", success.expiresAt)
    }

    @Test
    fun `missing infoHash and magnet returns RefreshRequired`() = runTest {
        val result = resolver.resolve(
            source = "debrid",
            streamUrl = STALE_URL,
            isExpired = true,
            infoHash = null,
            magnet = null
        )

        assertTrue("Missing metadata should return RefreshRequired", result is ResolutionResult.RefreshRequired)
    }

    @Test
    fun `Xtream source ignores expiry logic entirely`() = runTest {
        val result = resolver.resolve(
            source = "xtream",
            streamUrl = "http://iptv.example.com/live/stream.m3u8",
            isExpired = false,
            infoHash = null,
            magnet = null
        )

        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertNull("Xtream should NOT have expiresAt", success.expiresAt)
    }

    @Test
    fun `resolution failure after 2 attempts returns Error`() = runTest {
        coEvery {
            debridRepo.resolveDebridUrl(any(), any(), any(), any(), any())
        } returns Result.Error(Exception("Provider unreachable"))

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = null,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET
        )

        assertTrue("Should return Error after 2 failed attempts", result is ResolutionResult.Error)
        val error = result as ResolutionResult.Error
        assertTrue("Error message should mention provider", error.message.contains("unreachable"))
    }

    @Test
    fun `first attempt fails, second succeeds - returns Success with fresh expiresAt`() = runTest {
        var callCount = 0
        coEvery {
            debridRepo.resolveDebridUrl(any(), any(), any(), any(), any())
        } answers {
            callCount++
            if (callCount == 1) {
                Result.Error(Exception("Temporary failure"))
            } else {
                Result.Success(RESOLVED_URL)
            }
        }

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = null,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET
        )

        assertTrue("Should succeed on second attempt", result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals(RESOLVED_URL, success.url)
        assertNotNull("Must have expiresAt from successful resolution", success.expiresAt)
    }

    // =========================================================================
    // 6. FULL LIFECYCLE SIMULATION — End-to-End Round Trip
    // =========================================================================

    @Test
    fun `full lifecycle - play, save, wait 24h, resume, re-resolve, save again`() = runTest {
        coEvery {
            debridRepo.resolveDebridUrl(any(), any(), any(), any(), any())
        } returns Result.Success(RESOLVED_URL)

        // STEP 1: First play — resolve fresh link
        val firstResult = resolver.resolve(
            source = "debrid",
            streamUrl = null,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET
        )
        assertTrue(firstResult is ResolutionResult.Success)
        val firstSuccess = firstResult as ResolutionResult.Success
        val savedExpiresAt = firstSuccess.expiresAt!!

        // STEP 2: Simulate saving to ContinueWatchingItem
        val savedItem = ContinueWatchingItem(
            contentId = "movie_123",
            contentType = ContentType.MOVIE,
            title = "Test Movie",
            posterUrl = null,
            backdropUrl = null,
            currentPosition = 300000L,  // 5 minutes in
            totalDuration = 7200000L,   // 2 hour movie
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = firstSuccess.url,
            debridInfoHash = INFO_HASH,
            debridMagnet = MAGNET,
            source = "debrid",
            expiresAt = savedExpiresAt
        )

        // STEP 3: Simulate checking immediately (should NOT be expired)
        assertFalse("Should not be expired immediately after saving", savedItem.isExpired())

        // STEP 4: Simulate 24h later by creating a new item with shifted expiresAt
        val itemAfter24h = savedItem.copy(
            expiresAt = System.currentTimeMillis() - (1 * HOUR_MS)  // Now expired
        )
        assertTrue("Should be expired after 24h", itemAfter24h.isExpired())

        // STEP 5: Resume after 24h — should trigger re-resolution
        val secondResult = resolver.resolve(
            source = "debrid",
            streamUrl = itemAfter24h.streamUrl,
            isExpired = itemAfter24h.isExpired(),
            infoHash = itemAfter24h.debridInfoHash,
            magnet = itemAfter24h.debridMagnet,
            currentExpiresAt = itemAfter24h.expiresAt
        )
        assertTrue("Should succeed on re-resolution", secondResult is ResolutionResult.Success)
        val secondSuccess = secondResult as ResolutionResult.Success
        assertEquals("Should have fresh URL", RESOLVED_URL, secondSuccess.url)

        // STEP 6: New expiresAt should be ~23h in the future
        val newExpiresAt = secondSuccess.expiresAt!!
        assertTrue(
            "New expiresAt should be in the future",
            newExpiresAt > System.currentTimeMillis()
        )
        assertTrue(
            "New expiresAt should be ~23 hours from now",
            newExpiresAt > System.currentTimeMillis() + (22 * HOUR_MS)
        )

        // STEP 7: Create updated item with new expiresAt (simulates DB save)
        val updatedItem = itemAfter24h.copy(
            streamUrl = secondSuccess.url,
            expiresAt = newExpiresAt
        )
        assertFalse("Updated item should NOT be expired", updatedItem.isExpired())
    }

    @Test
    fun `series episode lifecycle - play S01E01, wait 48h, resume S01E01`() = runTest {
        coEvery {
            debridRepo.resolveDebridUrl(
                infoHash = INFO_HASH,
                magnet = MAGNET,
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = "Pilot"
            )
        } returns Result.Success(RESOLVED_URL)

        // Simulate resuming a series episode after 48 hours
        val staleExpiresAt = System.currentTimeMillis() - (25 * HOUR_MS)

        val result = resolver.resolve(
            source = "debrid",
            streamUrl = STALE_URL,
            isExpired = true,
            infoHash = INFO_HASH,
            magnet = MAGNET,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Pilot",
            currentExpiresAt = staleExpiresAt
        )

        assertTrue(result is ResolutionResult.Success)
        val success = result as ResolutionResult.Success
        assertEquals(RESOLVED_URL, success.url)
        assertNotNull(success.expiresAt)
        assertTrue(
            "Episode expiresAt should be ~23h in the future",
            success.expiresAt!! > System.currentTimeMillis() + (22 * HOUR_MS)
        )
    }
}
