package com.tvonnet.debridxtreamiptv.ui.live

import android.app.Activity
import android.content.Intent
import androidx.core.app.ActivityOptionsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.player.stabilized.LiveSharedPlayer
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LF-7: the **fullscreen playback launch + return-restore** concern lifted out of [LiveFragment] — the
 * two-click model ([navigateToPlayer]: 1st click previews, 2nd click goes fullscreen), the fullscreen
 * launch itself ([launchFullscreen]) and the restore-on-return ([handleLivePlayerResult]).
 *
 * INVARIANTS:
 *  - The `ActivityResultLauncher` STAYS REGISTERED IN THE FRAGMENT (its field-initializer timing before
 *    STARTED is load-bearing). Only its *body* is delegated here — the Fragment forwards results to
 *    [handleLivePlayerResult] and passes its `launch` as [onLaunch].
 *  - SHARED-PLAYER HAND-OFF (max_connections=1 safe): [launchFullscreen] does NOT release the preview and
 *    let fullscreen reconnect. It hands the RUNNING player to [LiveSharedPlayer] (`detachPlayer` + `offer`)
 *    with a last-frame snapshot cover, and launches with `sharedLivePlayer = true`; PlayerActivity ADOPTS
 *    that exact player (same provider socket, no reconnect — there is never a moment with two open
 *    connections). [handleLivePlayerResult] adopts the player back (`adoptPlayer`) so the channel does not
 *    reload on the way back either. Falls back to a fresh preview if nothing was handed off/back. This
 *    mirrors the EPG guide's proven hand-off ([LiveSharedPlayer]).
 *
 * Sibling work goes back through callbacks: [onUpdatePreviewEpg] (LF-3), [onUpdateFavoriteButton] (LF-5),
 * [onFocusChannelAt] / [onRestoreChannelFocus] (LF-6) and [markPreviewRestored] (the Fragment's shared
 * preview-restore flag).
 */
class LivePlaybackLauncher(
    private val fragment: Fragment,
    private val viewModel: LiveViewModel,
    private val repository: XtreamRepository,
    private val channelPagingAdapter: ChannelPagingAdapter,
    private val previewPanel: () -> PreviewPlayerPanel?,
    private val zoom: LiveFullscreenZoom?,
    private val onLaunch: (Intent, ActivityOptionsCompat) -> Unit,
    private val onUpdatePreviewEpg: (XtreamStream) -> Unit,
    private val onUpdateFavoriteButton: (XtreamStream?) -> Unit,
    private val onFocusChannelAt: (Int) -> Unit,
    private val onRestoreChannelFocus: () -> Unit,
    private val markPreviewRestored: () -> Unit,
) {

    /**
     * Navigate to PlayerActivity to play a stream.
     * In 3-column: 1st click = preview, 2nd click = fullscreen.
     */
    fun navigateToPlayer(stream: XtreamStream) {
        // In 3-column: 1st click = preview, 2nd click = fullscreen
        if (previewPanel()?.getCurrentStream()?.stream_id == stream.stream_id && previewPanel()?.isPlaying() == true) {
            // Second click: go fullscreen
            launchFullscreen(stream)
        } else {
            // First click: play in preview
            previewPanel()?.play(stream)
            onUpdatePreviewEpg(stream)
            viewModel.onEvent(LiveEvent.RememberPreviewStream(stream))
            onUpdateFavoriteButton(stream)
        }
    }

    /**
     * Launch fullscreen player. Part B: detach the RUNNING preview player and LIVE-ZOOM-GROW it to
     * fullscreen (the video keeps playing as it enlarges — a TextureView renders through the transform),
     * then cover the one hand-off switch, hand the player off and launch once the grow finishes (~300ms).
     */
    fun launchFullscreen(stream: XtreamStream) {
        viewModel.onEvent(LiveEvent.RememberFullscreenLaunch(stream))
        // Snapshot the preview BEFORE detaching: growLive shows it (still, at tile size) only for as long
        // as the surface switch takes, so the zoom itself is always live video.
        val panel = previewPanel()
        val preFrame = panel?.captureFrame()
        val player = panel?.detachPlayer()

        // Build the intent CONCURRENTLY with the grow. Credentials are prefs I/O and resolveLiveChannelIds
        // hits the cached-streams store on Dispatchers.IO — doing that AFTER the grow (with the player
        // already detached from every surface) left the video rendering nowhere for as long as it took,
        // which is the "grows, then stops, then plays again" stall. By grow-end this is already done, so
        // the hand-off is: cover → offer → launch, with no I/O in between.
        val prepared = fragment.viewLifecycleOwner.lifecycleScope.async {
            prepareLaunch(stream, shared = player != null)
        }

        val z = zoom
        if (z != null && player != null) {
            z.growLive(player, preFrame) { completeLaunch(stream, player, prepared, preFrame) }
        } else {
            completeLaunch(stream, player, prepared, preFrame)
        }
    }

    /** Everything that needs I/O, done off the hand-off critical path. */
    private suspend fun prepareLaunch(stream: XtreamStream, shared: Boolean): PreparedLaunch? {
        val credentialsPrefs = CredentialsPreferences(fragment.requireContext())
        val serverUrl = credentialsPrefs.getServerUrl() ?: return null
        val username = credentialsPrefs.getUsername() ?: return null
        val password = credentialsPrefs.getPassword() ?: return null
        val categoryId = stream.category_id ?: viewModel.uiState.value.selectedCategoryId
        val streamUrl = stream.toLiveStreamUrl(serverUrl, username, password)
        val channelIds = resolveLiveChannelIds(categoryId, stream.stream_id)

        val intent = PlayerActivity.createIntent(
            context = fragment.requireContext(),
            streamUrl = streamUrl,
            title = stream.name ?: fragment.getString(R.string.player_epg_channel_unknown),
            channelName = stream.name,
            channelLogo = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
            epgChannelId = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString(),
            contentId = stream.stream_id ?: streamUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
            liveCategoryId = categoryId,
            liveChannelIds = channelIds.takeIf { it.isNotEmpty() },
            baseServerUrl = serverUrl,
            sharedLivePlayer = shared
        )
        return PreparedLaunch(intent, streamUrl)
    }

    /**
     * Grow finished (or was skipped): hand the RUNNING player to [LiveSharedPlayer] and launch. The SAME
     * provider socket travels to fullscreen (adopted, no reconnect), so max_connections=1 is never
     * violated and the channel does not reload on the way in.
     *
     * The player is deliberately LEFT ATTACHED to the (now fullscreen) zoom bridge: PlayerActivity takes
     * ~300ms to start before it adopts, and detaching here left the video rendering nowhere for that whole
     * window — the "grows, then stops, then plays again" stall. Staying attached means the bridge keeps
     * showing LIVE fullscreen video until the fullscreen session actually takes the surface over; the
     * bridge's TextureView is simply orphaned at that point (media3 rebinds the surface to the adopting
     * view) and is reset on return. [handoffFrame] (captured before the grow) is the adopting side's
     * warm-up cover — reusing it avoids a ~40ms getBitmap() readback on the critical path.
     */
    private fun completeLaunch(
        stream: XtreamStream,
        player: ExoPlayer?,
        prepared: Deferred<PreparedLaunch?>,
        handoffFrame: android.graphics.Bitmap?,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val launchInfo = prepared.await() ?: run { abortLaunch(player); return@launch }
            val startedAt = android.os.SystemClock.uptimeMillis()
            if (player != null) {
                LiveSharedPlayer.offer(player, launchInfo.streamUrl, stream.stream_id, handoffFrame)
            }
            // No activity animation — the zoom bridge (already fullscreen) IS the transition, so the
            // Activity must appear instantly under it with no fade (which would flash).
            val options = ActivityOptionsCompat.makeCustomAnimation(fragment.requireContext(), 0, 0)
            onLaunch(launchInfo.intent, options)
            runCatching { fragment.requireActivity().overridePendingTransition(0, 0) }
            android.util.Log.i(
                "LIVE_HANDOFF",
                "launchFullscreen: shared=${player != null} id=${stream.stream_id} " +
                    "handoffGapMs=${android.os.SystemClock.uptimeMillis() - startedAt}"
            )
        }
    }

    /** Launch aborted (missing creds): the player was already detached, so release it + clear the bridge. */
    private fun abortLaunch(player: ExoPlayer?) {
        runCatching { player?.release() }
        zoom?.hide()
    }

    private class PreparedLaunch(val intent: Intent, val streamUrl: String)

    fun handleLivePlayerResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return

        val streamId = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val streamUrl = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_STREAM_URL)
            ?.takeIf { it.isNotBlank() }
        val channelName = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_NAME)
            ?.takeIf { it.isNotBlank() }
        val logoUrl = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_LOGO)
            ?.takeIf { it.isNotBlank() }
        val epgChannelId = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_EPG_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
        val categoryId = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CATEGORY_ID)
            ?: viewModel.uiState.value.selectedCategoryId

        val returnedStream = XtreamStream(
            num = null,
            name = channelName,
            stream_type = "live",
            stream_id = streamId,
            stream_icon = logoUrl,
            epg_channel_id = epgChannelId,
            added = null,
            category_id = categoryId,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = streamUrl,
            tv_archive = null,
            tv_archive_duration = null
        )

        markPreviewRestored()
        // Part B: cover the whole screen with fullscreen's last frame the instant we return (the bridge
        // was already fullscreen from the way in, so this is seamless — no bare-UI flash), adopt the
        // player back UNDER it, then shrink the cover onto the preview tile to reveal the live video.
        val returnFrame = LiveSharedPlayer.takeFrame()
        zoom?.showFullscreenCover(returnFrame)
        val adopted = LiveSharedPlayer.adopt()
        if (adopted != null) {
            android.util.Log.i("LIVE_HANDOFF", "handleLivePlayerResult: ADOPTED back (no reload) id=$streamId")
            previewPanel()?.adoptPlayer(adopted, returnedStream)
        } else {
            android.util.Log.i("LIVE_HANDOFF", "handleLivePlayerResult: no parked player -> FRESH play (reload) id=$streamId")
            previewPanel()?.play(returnedStream, streamUrl)
        }
        onUpdatePreviewEpg(returnedStream)
        onUpdateFavoriteButton(returnedStream)
        viewModel.onEvent(LiveEvent.RememberPreviewStream(returnedStream))

        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == streamId }
        if (index >= 0) {
            onFocusChannelAt(index)
        } else {
            onRestoreChannelFocus()
        }
        // Shrink the cover back onto the preview (reveals the live video once done).
        zoom?.shrink()
    }

    private suspend fun resolveLiveChannelIds(
        categoryId: String?,
        currentStreamId: String?
    ): ArrayList<String> {
        val snapshotIds = channelPagingAdapter.snapshot().items.mapNotNull { it.stream_id }.distinct()
        val cachedIds = if (!categoryId.isNullOrBlank() && categoryId != FAVORITES_CATEGORY_ID) {
            withContext(Dispatchers.IO) {
                repository.getCachedLiveStreams(categoryId)
                    ?.mapNotNull { it.stream_id }
                    .orEmpty()
            }
        } else {
            emptyList()
        }

        return LiveZapList.merge(cachedIds, snapshotIds, currentStreamId)
    }
}
