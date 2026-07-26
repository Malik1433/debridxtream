package com.tvonnet.debridxtreamiptv.ui.live

import android.app.Activity
import android.content.Intent
import androidx.core.app.ActivityOptionsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LF-7: the **fullscreen playback launch + return-restore** concern lifted out of [LiveFragment] — the
 * two-click model ([navigateToPlayer]: 1st click previews, 2nd click goes fullscreen), the fullscreen
 * launch itself ([launchFullscreen]) and the restore-on-return ([handleLivePlayerResult]).
 *
 * TWO LOAD-BEARING INVARIANTS carried over verbatim:
 *  - The `ActivityResultLauncher` STAYS REGISTERED IN THE FRAGMENT (its field-initializer timing before
 *    STARTED is load-bearing). Only its *body* is delegated here — the Fragment forwards results to
 *    [handleLivePlayerResult] and passes its `launch` as [onLaunch].
 *  - In [launchFullscreen] the preview's live socket is torn down **synchronously before** the launch:
 *    `previewPanel().releasePlayer()` runs immediately before [onLaunch]. The account is
 *    `max_connections=1`; a lifecycle pause only pauses the preview player, it does NOT close the socket,
 *    so a still-connected preview + the fullscreen session would be two live connections and the server
 *    rejects the second. Do NOT reorder these two calls.
 *
 * Sibling work goes back through callbacks: [onUpdatePreviewEpg] (LF-3), [onUpdateFavoriteButton] (LF-5),
 * [onFocusChannelAt] / [onRestoreChannelFocus] (LF-6) and [markPreviewRestored] (the Fragment's shared
 * preview-restore flag). Behaviour byte-identical; only `this`/field refs were rebound.
 */
class LivePlaybackLauncher(
    private val fragment: Fragment,
    private val viewModel: LiveViewModel,
    private val repository: XtreamRepository,
    private val channelPagingAdapter: ChannelPagingAdapter,
    private val previewPanel: () -> PreviewPlayerPanel?,
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
     * Launch fullscreen player.
     */
    fun launchFullscreen(stream: XtreamStream) {
        viewModel.onEvent(LiveEvent.RememberFullscreenLaunch(stream))
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val credentialsPrefs = CredentialsPreferences(fragment.requireContext())
            val serverUrl = credentialsPrefs.getServerUrl() ?: return@launch
            val username = credentialsPrefs.getUsername() ?: return@launch
            val password = credentialsPrefs.getPassword() ?: return@launch
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
                baseServerUrl = serverUrl
            )
            val options = ActivityOptionsCompat.makeCustomAnimation(
                fragment.requireContext(),
                R.anim.fade_in,
                R.anim.fade_out
            )
            // Tear down the preview's live connection synchronously BEFORE PlayerActivity
            // opens its own. The account is max_connections=1 — lifecycle pause only
            // pauses the preview player, it does not close the socket, so without this
            // the fullscreen session and the (still-connected) preview would be two
            // simultaneous live connections and the server rejects the second. The
            // classic screen's preview is muted/secondary, so a reconnect on return is
            // acceptable (unlike the guide's shared-player hand-off).
            previewPanel()?.releasePlayer()
            onLaunch(intent, options)
        }
    }

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
        previewPanel()?.play(returnedStream, streamUrl)
        onUpdatePreviewEpg(returnedStream)
        onUpdateFavoriteButton(returnedStream)
        viewModel.onEvent(LiveEvent.RememberPreviewStream(returnedStream))

        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == streamId }
        if (index >= 0) {
            onFocusChannelAt(index)
        } else {
            onRestoreChannelFocus()
        }
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

        val merged = linkedSetOf<String>()
        if (cachedIds.isNotEmpty()) {
            cachedIds.forEach { merged.add(it) }
        }
        snapshotIds.forEach { merged.add(it) }
        currentStreamId?.let { merged.add(it) }
        return ArrayList(merged)
    }
}
