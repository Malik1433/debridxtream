package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.ServerClock
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When a Live stream stopped for a while, when it stopped. Wall clock, because the catch-up start
 * is named in the provider's local time. Pure.
 */
internal class LiveOutageClock(private val nowMs: () -> Long) {
    private var outageStartMs: Long? = null

    /** Buffering or an error while live: the outage starts at the FIRST one. */
    fun onStalled() {
        if (outageStartMs == null) outageStartMs = nowMs()
    }

    /** Playing again: returns when the outage started (null if there was none) and closes it. */
    fun onPlaying(): Long? = outageStartMs.also { outageStartMs = null }

    fun reset() {
        outageStartMs = null
    }
}

/**
 * C2 of docs/reports/LIVE_CATCHUP_DESIGN.md: "watch from where it stopped".
 *
 * A Live outage that lasted long enough on a channel that keeps recordings ends with a prompt -
 * default "Stay live", gone after [PROMPT_MS]. "Watch from there" plays the provider's timeshift
 * recording from half a minute before the picture stopped. Recordings download at line speed, so
 * that stretch plays with a real buffer even when live cannot.
 *
 * First version, on purpose: when the recording chunk ENDS (the provider serves ~10-minute chunks,
 * C0), or if it fails, we go back to live with a toast rather than chaining the next chunk. Chaining
 * (the seam) is proven separately, before delayed-live (C4) relies on it.
 *
 * Every hook returns false when catch-up is not in play, so the ordinary Live recovery (retries,
 * alternate feeds, match mode) is untouched. A zap during catch-up is noticed through currentUrl
 * and simply ends it.
 */
internal class LiveCatchupController(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
    private val account: () -> CatchupUrlBuilder.Account,
) {
    private val clock = LiveOutageClock { System.currentTimeMillis() }
    private var liveUrl: String? = null
    private var catchupUrl: String? = null
    private var catchupStreamId: String? = null
    private var catchupPlayingSinceMs: Long? = null
    private val failedStreamIds = HashSet<String>()
    private val archivedStreamIds = HashSet<String>()
    // C2-3: every feed of the channel that played since the zap. Live failover moves between feeds
    // of one channel, and only some of them carry EPG and recordings.
    private val feedsSinceZap = LinkedHashSet<String>()
    private var prompt: AlertDialog? = null
    // An outage needs something to be interrupted: slow first tuning is not one.
    private var playedSinceZap = false

    private val isLive get() = session.contentType == ContentType.LIVE_TV

    /** True while a recording plays AND the viewer has not zapped away from it. */
    val inCatchup: Boolean
        get() {
            val url = catchupUrl ?: return false
            if (session.currentUrl == url) return true
            clearCatchup() // zapped elsewhere: whatever plays now is live
            return false
        }

    /** The viewer changed channel: an open outage and any recording belong to the old one. */
    fun onUserZap() {
        playedSinceZap = false
        feedsSinceZap.clear()
        clock.reset()
        clearCatchup()
        prompt?.takeIf { it.isShowing }?.dismiss()
        prompt = null
    }

    fun onPlaybackState(state: Int) {
        if (!isLive) return
        if (inCatchup) {
            if (state == Player.STATE_READY && catchupPlayingSinceMs == null) {
                catchupPlayingSinceMs = System.currentTimeMillis()
            }
            return
        }
        when (state) {
            Player.STATE_BUFFERING -> if (playedSinceZap) clock.onStalled()
            Player.STATE_READY -> {
                playedSinceZap = true
                session.contentId?.let { feedsSinceZap += it }
                clock.onPlaying()?.let { maybeOffer(it) }
            }
        }
    }

    /** @return true when the error belonged to a recording and has been handled (back to live). */
    fun onError(): Boolean {
        if (!isLive) return false
        if (!inCatchup) {
            if (playedSinceZap) clock.onStalled()
            return false
        }
        val playedMs = catchupPlayingSinceMs?.let { System.currentTimeMillis() - it }
        val naturalEnd = CatchupAvailability.isNaturalEnd(playedMs)
        Log.i("PlayerActivity", "catch-up: recording stopped by an error after ${playedMs?.div(1000)}s natural_end=$naturalEnd")
        if (naturalEnd) {
            backToLive(R.string.catchup_back_to_live)
        } else {
            catchupStreamId?.let { failedStreamIds += it }
            backToLive(R.string.catchup_unavailable)
        }
        return true
    }

    /**
     * C2-5 (QA round 3): the stall monitor, the buffer watchdog and the terminal-failure path reach
     * the recovery code without passing through the player's error callback. During a recording each
     * of them would re-request the recording from its start, fail over to another feed, or close the
     * player. They ask here first; a recording ends the same way as on a player error.
     *
     * @return true when a recording was playing and has been handled (back to live).
     */
    fun onRecordingStalled(): Boolean = isLive && inCatchup && onError()

    /** @return true when the recording chunk ended and we went back to live. */
    fun onEnded(): Boolean {
        if (!isLive || !inCatchup) return false
        Log.i("PlayerActivity", "catch-up: recording ended after ${catchupPlayingSinceMs?.let { (System.currentTimeMillis() - it) / 1000 }}s")
        backToLive(R.string.catchup_back_to_live)
        return true
    }

    private fun maybeOffer(outageStartMs: Long) {
        val streamId = session.contentId ?: return
        val now = System.currentTimeMillis()
        if (now - outageStartMs < CatchupAvailability.MIN_OUTAGE_MS) return
        val urlAtOutage = session.currentUrl
        activity.viewLifecycleOwner.lifecycleScope.launch {
            val zone = withContext(Dispatchers.IO) { ServerClock.zone(activity.requireContext()) }
            val (archiveId, hasArchive) = withTimeoutOrNull(ARCHIVE_SEARCH_MS) {
                archivedFeed(listOf(streamId) + feedsSinceZap)
            } ?: (streamId to null)
            val failed = archiveId in failedStreamIds
            val offer = CatchupAvailability.canOfferResume(
                outageStartMs, System.currentTimeMillis(), zone != null, hasArchive, failed
            )
            Log.i(
                "PlayerActivity",
                "catch-up: outage ${(now - outageStartMs) / 1000}s feed=$streamId archiveFeed=$archiveId " +
                    "archive=$hasArchive zone=${zone != null} failed=$failed offer=$offer"
            )
            if (!offer || zone == null) return@launch
            if (!activity.isResumed || session.currentUrl != urlAtOutage || inCatchup) return@launch
            showPrompt(outageStartMs, archiveId, zone)
        }
    }

    /**
     * C2-1: the provider's answer was seen flipping true/false for the same channel minutes apart,
     * so a "yes" is remembered for the sitting and anything else is asked once more before giving up.
     */
    /** The first feed of this channel that keeps recordings, with the last answer when none does. */
    private suspend fun archivedFeed(feeds: List<String>): Pair<String, Boolean?> {
        val current = feeds.first()
        val currentVerdict = archiveOf(current)
        if (currentVerdict == true) return current to true
        for (id in feeds.distinct().drop(1)) {
            if (archiveOf(id) == true) return id to true
        }
        // C2-5: the viewer may have opened a sibling feed with no EPG (failover hands that feed back
        // to the Live list), so none of the feeds played knows about recordings. Ask the channel's
        // other feeds, as failover would find them.
        for (id in siblingFeeds() - feeds.toSet()) {
            if (archiveOf(id) == true) return id to true
        }
        return current to currentVerdict
    }

    private suspend fun siblingFeeds(): List<String> {
        val name = session.pendingChannelName ?: activity.tvChannelName?.text?.toString()
        val term = LiveAlternateSources.searchTerm(name)
        if (term.isBlank()) return emptyList()
        val found = try {
            withTimeoutOrNull(SIBLING_SEARCH_MS) {
                withContext(Dispatchers.IO) { activity.xtreamRepository.searchLive(term) }
            }.orEmpty()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("PlayerActivity", "catch-up: sibling feed search failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        return LiveAlternateSources.rank(name, session.contentId, found, emptySet())
            .mapNotNull { it.stream_id }
            .take(MAX_SIBLING_FEEDS)
    }

    private suspend fun archiveOf(streamId: String): Boolean? {
        if (streamId in archivedStreamIds) return true
        var verdict: Boolean? = null
        withTimeoutOrNull(ARCHIVE_LOOKUP_MS) {
            repeat(ARCHIVE_LOOKUP_TRIES) { attempt ->
                if (attempt > 0) delay(ARCHIVE_RETRY_DELAY_MS)
                verdict = withContext(Dispatchers.IO) { activity.xtreamRepository.channelHasArchive(streamId) }
                if (verdict == true) return@withTimeoutOrNull
            }
        }
        if (verdict == true) archivedStreamIds += streamId
        return verdict
    }

    private fun showPrompt(outageStartMs: Long, streamId: String, zone: java.util.TimeZone) {
        prompt?.takeIf { it.isShowing }?.dismiss()
        val minutes = ((System.currentTimeMillis() - outageStartMs) / 60_000L).coerceAtLeast(1L).toInt()
        val dialog = AlertDialog.Builder(activity.requireContext())
            .setTitle(R.string.catchup_resume_title)
            .setMessage(activity.getString(R.string.catchup_resume_message, minutes))
            .setPositiveButton(R.string.catchup_resume_watch) { _, _ -> startCatchup(outageStartMs, streamId, zone) }
            .setNegativeButton(R.string.catchup_resume_stay_live, null)
            .create()
        prompt = dialog
        dialog.setOnShowListener {
            // TV: the safe choice holds focus, so an accidental OK keeps the viewer live.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
        activity.retryHandler.postDelayed({
            if (prompt === dialog && dialog.isShowing && activity.isAdded) dialog.dismiss()
        }, PROMPT_MS)
    }

    private fun startCatchup(outageStartMs: Long, streamId: String, zone: java.util.TimeZone) {
        val live = session.currentUrl ?: return
        val start = outageStartMs - LEAD_IN_MS
        val minutes = ((System.currentTimeMillis() - start) / 60_000L).toInt() + EXTRA_MINUTES
        val url = CatchupUrlBuilder.url(account(), streamId, start, minutes, zone)
        Log.i("PlayerActivity", "catch-up: playing the recording from ${CatchupUrlBuilder.startParam(start, zone)}")
        PlaybackDiagnosticsRecorder.record(activity.requireContext(), RecoveryScoreboard.CATCHUP_RESUME)
        liveUrl = live
        catchupUrl = url
        catchupStreamId = streamId
        session.currentUrl = url
        activity.liveTuner.performSeamlessSwitch(url)
    }

    private fun backToLive(message: Int) {
        val live = liveUrl
        clearCatchup()
        clock.reset()
        if (live == null || !activity.isAdded) return
        activity.showToast(activity.getString(message))
        session.currentUrl = live
        activity.liveTuner.performSeamlessSwitch(live)
    }

    private fun clearCatchup() {
        catchupUrl = null
        catchupStreamId = null
        catchupPlayingSinceMs = null
        liveUrl = null
    }

    private companion object {
        const val PROMPT_MS = 8_000L
        const val ARCHIVE_LOOKUP_MS = 6_000L
        /** Every feed together: past this the outage is old news and the prompt would be late. */
        const val ARCHIVE_SEARCH_MS = 15_000L
        const val SIBLING_SEARCH_MS = 5_000L
        const val MAX_SIBLING_FEEDS = 3
        const val ARCHIVE_LOOKUP_TRIES = 2
        const val ARCHIVE_RETRY_DELAY_MS = 1_000L
        /** Start a little before the picture stopped, so nothing is missed at the join. */
        const val LEAD_IN_MS = 30_000L
        /** Ignored by the provider (it serves ~10-minute chunks) but required by the endpoint. */
        const val EXTRA_MINUTES = 10
    }
}
