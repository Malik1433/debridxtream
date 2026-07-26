package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Roadmap B2.1 — the Live fullscreen hand-off contract (instrumented: it needs a real
 * [ExoPlayer] and the main Looper).
 *
 * Why this test exists: on a `max_connections=1` provider account, the way in and the way out of
 * fullscreen must hand the **running** player over instead of release-then-reconnect. Every
 * incident on this path (the reload, the "grows then stalls then plays again" freeze, the
 * black-flash) came from breaking one of the invariants below — and until now the only check was
 * eyeballing the TV and grepping `LIVE_HANDOFF` logs. The Live surface is secure, so a test can
 * never screenshot it; these assert on *state* (which instance is attached, parked-vs-adopted,
 * released-vs-alive) exactly as the roadmap requires.
 *
 * Release is observed through [AnalyticsListener.onPlayerReleased] — a real signal from ExoPlayer
 * itself, not an inference.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LiveSharedPlayerHandoffTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val players = mutableListOf<Pair<ExoPlayer, ReleaseWatcher>>()

    /** Must match `LiveSharedPlayer.ADOPT_TIMEOUT_MS`; read reflectively so it cannot drift. */
    private val adoptTimeoutMs: Long =
        LiveSharedPlayer::class.java.getDeclaredField("ADOPT_TIMEOUT_MS")
            .apply { isAccessible = true }
            .getLong(LiveSharedPlayer)

    @After
    fun tearDown() {
        onMain { LiveSharedPlayer.release("test_teardown") }
        players.forEach { (player, watcher) ->
            if (!watcher.released) onMain { runCatching { player.release() } }
        }
        players.clear()
    }

    /** The way in: fullscreen must receive the SAME running instance, still alive. */
    @Test
    fun parkedPlayerIsHandedToTheAdopterUnchanged() {
        val (player, watcher) = newPlayer()

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1") }

        assertTrue("a parked hand-off must be visible to the adopting screen", LiveSharedPlayer.isParked())
        assertEquals(URL_A, LiveSharedPlayer.streamUrl)
        assertEquals("chan-1", LiveSharedPlayer.streamId)

        val adopted = onMainGet { LiveSharedPlayer.adopt(URL_A) }

        assertSame("fullscreen must adopt the running player, never a fresh one", player, adopted)
        assertStillAlive("adopting must not release the player — that is the reconnect bug", watcher)
        assertFalse(LiveSharedPlayer.isParked())
        assertNull(LiveSharedPlayer.streamUrl)
        assertNull(LiveSharedPlayer.streamId)
    }

    /** A stale offer must be refused — but refusing must not drop the player on the floor. */
    @Test
    fun adoptRejectsAMismatchedUrlAndKeepsThePlayerParked() {
        val (player, watcher) = newPlayer()

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1") }

        assertNull("a different stream must never adopt this player", onMainGet { LiveSharedPlayer.adopt(URL_B) })
        assertTrue("a refused adopt must leave the player parked, not orphaned", LiveSharedPlayer.isParked())
        assertStillAlive("a refused adopt must not release the player", watcher)

        assertSame("the rightful owner can still adopt afterwards", player, onMainGet { LiveSharedPlayer.adopt(URL_A) })
    }

    /** The way out: the return path adopts with no expected url and must take whatever is parked. */
    @Test
    fun adoptWithoutAnExpectedUrlTakesWhateverIsParked() {
        val (player, _) = newPlayer()

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1") }

        assertSame(player, onMainGet { LiveSharedPlayer.adopt() })
    }

    /**
     * LP-C-4, rejected on purpose and pinned here: `adopt()` must NOT clear the frame. The adopting
     * screen adopts FIRST and calls `takeFrame()` immediately after; clearing on adopt black-flashes
     * the hand-off. The frame is one-shot so it cannot be shown twice or leak.
     */
    @Test
    fun theHandOffFrameSurvivesAdoptAndIsOneShot() {
        val (player, _) = newPlayer()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1", frame) }
        onMainGet { LiveSharedPlayer.adopt(URL_A) }

        assertSame("adopt must not clear the cover frame — that is the black flash", frame, LiveSharedPlayer.takeFrame())
        assertNull("the frame is one-shot", LiveSharedPlayer.takeFrame())
    }

    /** Two hand-offs in flight would mean two provider sockets. The older player must die. */
    @Test
    fun offeringADifferentPlayerReleasesThePreviousOne() {
        val (first, firstWatcher) = newPlayer()
        val (second, secondWatcher) = newPlayer()

        onMain { LiveSharedPlayer.offer(first, URL_A, "chan-1") }
        onMain { LiveSharedPlayer.offer(second, URL_B, "chan-2") }

        assertTrue("the replaced player must be released, not leaked", firstWatcher.awaitRelease(RELEASE_WAIT_MS))
        assertStillAlive("the newly offered player must stay alive", secondWatcher)
        assertEquals(URL_B, LiveSharedPlayer.streamUrl)
        assertSame(second, onMainGet { LiveSharedPlayer.adopt(URL_B) })
    }

    /** Re-offering the SAME instance (a re-entered hand-off) must not kill the live stream. */
    @Test
    fun reofferingTheSamePlayerDoesNotReleaseIt() {
        val (player, watcher) = newPlayer()

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1") }
        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1") }

        assertStillAlive("re-offering the same player must never release it", watcher)
        assertSame(player, onMainGet { LiveSharedPlayer.adopt(URL_A) })
    }

    /**
     * The failsafe: if the target screen never starts, the parked player must not keep playing
     * audio in the background forever.
     */
    @Test
    fun anUnadoptedPlayerIsReleasedByTheFailsafeTimeout() {
        val (player, watcher) = newPlayer()

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1") }

        assertTrue(
            "an offer nobody adopts must be released within ${adoptTimeoutMs}ms — otherwise audio " +
                "keeps playing behind the UI",
            watcher.awaitRelease(adoptTimeoutMs + RELEASE_WAIT_MS)
        )
        assertFalse(LiveSharedPlayer.isParked())
        assertNull(onMainGet { LiveSharedPlayer.adopt() })
    }

    /** An explicit release (e.g. the guide view going away) must leave nothing behind. */
    @Test
    fun releaseClearsTheParkedStateAndTheFrame() {
        val (player, watcher) = newPlayer()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        onMain { LiveSharedPlayer.offer(player, URL_A, "chan-1", frame) }
        onMain { LiveSharedPlayer.release("guide_view_gone") }

        assertTrue(watcher.awaitRelease(RELEASE_WAIT_MS))
        assertFalse(LiveSharedPlayer.isParked())
        assertNull(LiveSharedPlayer.streamUrl)
        assertNull(LiveSharedPlayer.streamId)
        assertNull("a released hand-off must not leave a stale cover frame", LiveSharedPlayer.takeFrame())
        assertNull(onMainGet { LiveSharedPlayer.adopt() })
    }

    // ------------------------------------------------------------------ helpers

    /**
     * "This player must still be alive." `onPlayerReleased` is POSTED to the main looper, so a
     * bare `!watcher.released` read races the release it is meant to catch — it silently passed a
     * mutant that released the player. A negative assertion here has to wait for the window in
     * which the callback would have arrived.
     */
    private fun assertStillAlive(message: String, watcher: ReleaseWatcher) {
        assertFalse(message, watcher.awaitRelease(ALIVE_PROOF_MS))
    }

    private fun newPlayer(): Pair<ExoPlayer, ReleaseWatcher> {
        val watcher = ReleaseWatcher()
        val player = onMainGet {
            ExoPlayer.Builder(context).build().also { it.addAnalyticsListener(watcher) }
        }
        assertNotNull(player)
        return (player!! to watcher).also { players += it }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> onMainGet(block: () -> T): T? {
        var result: T? = null
        onMain { result = block() }
        return result
    }

    /** ExoPlayer's own "I have been released" signal — no inference, no sleeping. */
    private class ReleaseWatcher : AnalyticsListener {
        private val latch = CountDownLatch(1)
        val released: Boolean get() = latch.count == 0L
        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = latch.countDown()
        fun awaitRelease(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        private const val URL_A = "http://provider.test:8080/live/u/p/1.ts"
        private const val URL_B = "http://provider.test:8080/live/u/p/2.ts"
        private const val RELEASE_WAIT_MS = 3_000L
        /** How long a release callback gets to arrive before we call the player "still alive". */
        private const val ALIVE_PROOF_MS = 750L
    }
}
