package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity

/**
 * The player's exit-with-result / terminal-failure cluster (extra Stage-1 extraction,
 * fragment-split prep). Owner chose to shrink PlayerActivity further before the
 * fragment split, so this cohesive "how the screen leaves and reports back" logic
 * comes out on its own.
 *
 * Moved verbatim out of [PlayerActivity]: the two result-setters the `finish()`
 * override calls ([setLiveExitResultIfNeeded] / [setExitResultIfNeeded]), the two
 * [PlayerRecoveryController.RecoveryHost] members ([finishWithReturnToSources] /
 * [handleTerminalPlaybackFailure]) and the debrid failure→detail redirect
 * ([redirectToFailureDetail]). Bodies are byte-identical apart from the mechanical
 * `activity.` / `this`→`activity` rewrites the concrete-collaborator pattern needs.
 *
 * The shared-player handoff (performBackExit / handBackSharedPlayerIfNeeded — the
 * landmine) deliberately STAYS in the Activity; only the result/failure exit paths
 * move here.
 */
internal class PlayerExitController(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val player get() = activity.player

    private val contentType: ContentType? by session::contentType
    private var exitResultHandled: Boolean by session::exitResultHandled
    private val contentId: String? by session::contentId
    private val pendingChannelName: String? by session::pendingChannelName
    private val channelLogoUrl: String? by session::channelLogoUrl
    private val currentEpgChannelId: String? by session::currentEpgChannelId
    private val currentUrl: String? by session::currentUrl
    private val liveCategoryId: String? by session::liveCategoryId
    private val returnToSourcesOnExit: Boolean by session::returnToSourcesOnExit
    private val playbackSource: PlaybackSource by session::playbackSource
    private val didPlaybackComplete: Boolean by session::didPlaybackComplete
    private val lastPlaybackPositionMs: Long by session::lastPlaybackPositionMs
    private val manualExit: Boolean by session::manualExit
    private val debridStreamIdExtra: String? by session::debridStreamIdExtra
    private val debridInfoHashExtra: String? by session::debridInfoHashExtra
    private val tmdbIdExtra: String? by session::tmdbIdExtra
    private val seriesTitleExtra: String? by session::seriesTitleExtra
    private val originalTitle: String? by session::originalTitle
    private val posterUrlExtra: String? by session::posterUrlExtra
    private val backdropUrlExtra: String? by session::backdropUrlExtra

    fun setLiveExitResultIfNeeded() {
        if (contentType != ContentType.LIVE_TV || exitResultHandled) return
        val streamId = contentId?.takeIf { it.isNotBlank() } ?: return
        activity.setResult(
            Activity.RESULT_OK,
            buildLiveReturnIntent(
                LiveReturnChannel(
                    channelId = streamId,
                    channelName = activity.tvChannelName?.text?.toString() ?: pendingChannelName,
                    channelLogoUrl = channelLogoUrl,
                    epgChannelId = currentEpgChannelId,
                    streamUrl = currentUrl,
                    categoryId = liveCategoryId
                )
            )
        )
    }

    /** Return-to-sources only applies to a debrid playback that opted in. */
    private fun returnToSourcesApplies(): Boolean =
        returnToSourcesOnExit && playbackSource == PlaybackSource.DEBRID

    fun setExitResultIfNeeded() { if (exitResultHandled || !returnToSourcesApplies() || didPlaybackComplete) return; val pos = player?.currentPosition ?: lastPlaybackPositionMs; if (manualExit || pos < RETURN_TO_SOURCES_THRESHOLD_MS) { exitResultHandled = true; activity.setResult(Activity.RESULT_OK, buildReturnToSourcesIntent()) } }

    fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean { if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || exitResultHandled) return false; exitResultHandled = true; PlaybackDiagnosticsRecorder.record(activity.requireContext(), "return_to_sources", activity.diagnosticsPlaybackFields() + mapOf("reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(reason), "autoPlayNext" to autoPlayNext)); activity.setResult(Activity.RESULT_OK, buildFailedSourceReturnIntent(failedStreamId = debridStreamIdExtra ?: debridInfoHashExtra ?: contentId ?: currentUrl, reason = reason, autoPlayNext = autoPlayNext)); activity.releasePlayer("return_to_sources"); PlaybackDiagnosticsRecorder.finishSession(activity.requireContext(), "return_to_sources"); activity.finish(); return true }

    fun handleTerminalPlaybackFailure(reason: String, preferReturnToSources: Boolean) {
        PlaybackDiagnosticsRecorder.record(
            activity.requireContext(),
            "terminal_failure",
            activity.diagnosticsPlaybackFields() + mapOf(
                "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(reason),
                "httpStatusCode" to PlaybackDiagnosticsRecorder.httpStatusCode(reason)
            )
        )
        // H6: the helper itself skips sources that already rendered a frame.
        activity.recordAddonReliability(success = false)
        // autoPlayNext=true: the detail screens auto-try the next ranked source
        // (capped there) instead of dropping the user at the picker.
        if (preferReturnToSources && finishWithReturnToSources(autoPlayNext = true, reason = reason)) {
            return
        }
        val redirected = redirectToFailureDetail(reason)
        if (redirected) {
            return
        }
        // LIVE: before telling the customer to "try another channel or source", try one. The
        // provider carries the same channel several times, and the app is in a far better position
        // to find the next feed than someone holding a remote in front of a dead picture. It gives
        // up honestly — through the very error below — the moment there is nothing left to try.
        if (contentType == ContentType.LIVE_TV &&
            activity.tryLiveAlternateSource { showTerminalError(reason) }
        ) {
            return
        }
        showTerminalError(reason)
    }

    private fun showTerminalError(reason: String) {
        if (!finishWithReturnToSources(autoPlayNext = true, reason = reason)) {
            val isHttpError = reason.contains("HTTP", ignoreCase = true)
            activity.showError(
                when {
                    contentType == ContentType.LIVE_TV ->
                        "Channel stream is broken at the provider\n\nTry another channel or source"
                    reason.contains("timeout", ignoreCase = true) ->
                        "Connection timeout\n\nStream is too slow or unavailable"
                    // Plain IPTV/Xtream dead listing (405/404/5xx): the provider simply
                    // doesn't have this file — steer the user to a debrid source.
                    playbackSource == PlaybackSource.IPTV && isHttpError ->
                        "This IPTV listing isn't available from your provider\n\nTry a debrid source or another listing"
                    else -> "Playback failed\n\n$reason"
                }
            )
            Handler(Looper.getMainLooper()).postDelayed({ activity.finish() }, 3000)
        }
    }

    private fun redirectToFailureDetail(reason: String): Boolean {
        if (playbackSource != PlaybackSource.DEBRID || contentType == ContentType.LIVE_TV || activity.isFinishing) return false
        val detailIntent = when (contentType) {
            ContentType.MOVIE -> Intent(activity.requireContext(), MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, tmdbIdExtra ?: contentId ?: debridStreamIdExtra ?: debridInfoHashExtra)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, originalTitle)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, posterUrlExtra)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, backdropUrlExtra)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            ContentType.SERIES, ContentType.EPISODE -> Intent(activity.requireContext(), SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, activity.debridSeriesLookupId())
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, seriesTitleExtra ?: originalTitle)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, posterUrlExtra)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, backdropUrlExtra)
                // This redirect only runs for DEBRID playback (guarded at the top of the method),
                // so open the detail screen in debrid mode — without this it loaded as Xtream and
                // showed "No episodes" for debrid content.
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            null, ContentType.LIVE_TV -> null
        } ?: return false

        detailIntent.putExtra(PlayerActivity.EXTRA_OPENED_FROM_PLAYBACK_FAILURE, true)
        detailIntent.putExtra(PlayerActivity.EXTRA_FAIL_REASON, reason)
        activity.releasePlayer("failure_detail_redirect")
        PlaybackDiagnosticsRecorder.finishSession(activity.requireContext(), "failure_detail_redirect")
        activity.startActivity(detailIntent)
        activity.finish()
        return true
    }

    private companion object {
        // Below this watched-position (and on a manual BACK), a debrid VOD exit hands the
        // ranked source list back to the detail screen instead of just closing. Moved with
        // setExitResultIfNeeded — it is the only reader.
        private const val RETURN_TO_SOURCES_THRESHOLD_MS = 60_000L
    }
}
