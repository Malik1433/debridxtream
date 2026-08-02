package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation for the Stremio home. Mirrors [com.tvonnet.debridxtreamiptv.ui.home.HomeNavigationRouter]
 * but is decoupled from the legacy sidebar HomeFragment so both homes can coexist.
 */
internal class StremioNavigationRouter(private var fragment: StremioHomeFragment?) {

    fun cleanup() { fragment = null }

    private fun navigateToFragment(target: Fragment) {
        val frag = fragment ?: return
        if (!frag.isAdded || frag.parentFragmentManager.isStateSaved) return
        frag.isNavigatingAway = true
        frag.parentFragmentManager.commit {
            replace(R.id.content_container, target)
            addToBackStack(null)
        }
    }

    fun onFeaturedItemClick(item: FeaturedItem) = openFeaturedDetails(item)

    fun openFeaturedDetails(item: FeaturedItem) {
        val frag = fragment ?: return
        if (item.sourceType == SourceType.TMDB) {
            val context = frag.context ?: return
            when (item.contentType) {
                ContentType.MOVIE -> {
                    val intent = Intent(context, MovieDetailActivity::class.java).apply {
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.contentId)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                    }
                    frag.startActivity(intent)
                }
                ContentType.SERIES -> {
                    val intent = Intent(context, SeriesDetailActivity::class.java).apply {
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.contentId)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                        putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                    }
                    frag.startActivity(intent)
                }
                else -> showHomeActionUnavailable()
            }
        } else {
            when (item.contentType) {
                ContentType.MOVIE -> navigateToFragment(
                    MovieDetailFragmentV2.newInstance(
                        streamId = item.contentId, title = item.title,
                        backdropUrl = item.backdropUrl, posterUrl = item.posterUrl,
                        plot = item.description, directSource = item.streamUrl,
                        trailer = item.trailerValue
                    )
                )
                ContentType.SERIES -> navigateToFragment(
                    SeriesDetailFragmentV2.newInstance(
                        seriesId = item.contentId, title = item.title,
                        backdropUrl = item.backdropUrl, posterUrl = item.posterUrl,
                        trailer = item.trailerValue
                    )
                )
                ContentType.LIVE_TV -> launchLiveStream(item.contentId, item.title, item.posterUrl, item.streamUrl)
                else -> showHomeActionUnavailable()
            }
        }
    }

    fun onContinueWatchingItemClick(item: ContinueWatchingItem) {
        val frag = fragment ?: return
        val context = frag.context ?: return
        val streamUrl = item.streamUrl?.takeIf { it.isNotBlank() }
        val isDebrid = item.source == "debrid"
        val hasResolutionInfo = !item.debridInfoHash.isNullOrBlank() || !item.debridMagnet.isNullOrBlank()
        val canFreshResolveDirectDebrid = isDebrid && item.directDebridPlayback &&
            item.contentId.isNotBlank() && !((item.seriesTitle ?: item.title).isBlank())
        val expired = item.isExpired()

        frag.viewLifecycleOwner.lifecycleScope.launch {
            when (item.contentType) {
                ContentType.MOVIE, ContentType.EPISODE -> {
                    val serverUrl = frag.credentialsPrefs.getServerUrl() ?: ""
                    val canResumeDirectly = (streamUrl != null && !expired) ||
                        (isDebrid && hasResolutionInfo) || canFreshResolveDirectDebrid
                    if (!canResumeDirectly) {
                        openContinueWatchingDetail(item)
                        return@launch
                    }
                    val resumeSeriesId = if (item.contentType == ContentType.EPISODE) {
                        item.seriesId?.takeIf { it.isNotBlank() }
                            ?: if (!isDebrid) resolveIptvSeriesIdForContinueWatching(item) else null
                    } else null
                    val intent = PlayerActivity.createIntent(
                        context = context,
                        streamUrl = if (canFreshResolveDirectDebrid) "" else streamUrl ?: "",
                        title = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title,
                        startPositionMs = item.currentPosition,
                        contentId = item.tmdbId?.takeIf { it.isNotBlank() } ?: item.contentId,
                        contentType = item.contentType,
                        playbackSource = if (isDebrid) {
                            com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID
                        } else {
                            com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.IPTV
                        },
                        posterUrl = item.posterUrl, backdropUrl = item.backdropUrl,
                        tmdbId = item.tmdbId, imdbId = item.imdbId,
                        seriesTitle = item.seriesTitle, episodeTitle = item.episodeTitle,
                        seasonNumber = item.seasonNumber, episodeNumber = item.episodeNumber,
                        debridInfoHash = item.debridInfoHash, debridMagnet = item.debridMagnet,
                        directDebridPlayback = item.directDebridPlayback,
                        debridProvider = item.debridProvider, debridSourceType = item.debridSourceType,
                        debridSourceName = item.debridSourceName, debridLanguages = item.debridLanguages,
                        debridQuality = item.debridQuality, debridStreamId = item.debridStreamId,
                        debridBingeGroup = item.debridBingeGroup, debridFileIdx = item.debridFileIdx,
                        expiresAt = item.expiresAt, baseServerUrl = serverUrl, seriesId = resumeSeriesId
                    )
                    frag.startActivity(intent)
                }
                ContentType.SERIES -> openContinueWatchingDetail(item)
                else -> showHomeActionUnavailable()
            }
        }
    }

    fun showContinueWatchingActions(item: ContinueWatchingItem, anchor: android.view.View? = null) {
        val frag = fragment ?: return
        if (!frag.isAdded) return
        val dialogView = frag.layoutInflater.inflate(R.layout.view_continue_watching_action_menu, null, false)
        val rawTitle = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title
        dialogView.findViewById<TextView>(R.id.tv_cw_menu_title).text =
            com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(rawTitle).ifBlank { rawTitle }
        run {
            val row = dialogView.findViewById<android.view.View>(R.id.row_cw_progress)
            if (item.totalDuration <= 0L) {
                row.visibility = android.view.View.GONE
            } else {
                dialogView.findViewById<android.widget.ProgressBar>(R.id.pb_cw_menu_progress).progress =
                    item.progressPercentage.coerceIn(0, 100)
                dialogView.findViewById<TextView>(R.id.tv_cw_menu_progress).text = item.formattedProgress
            }
        }
        val openDetailChip = dialogView.findViewById<android.view.View>(R.id.chip_cw_open_detail)
        val clearStatusChip = dialogView.findViewById<android.view.View>(R.id.chip_cw_clear_status)

        val dialog = AlertDialog.Builder(frag.requireContext(), R.style.CwActionMenuDialog).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.setCanceledOnTouchOutside(true)
        openDetailChip.setOnClickListener {
            dialog.dismiss()
            frag.viewLifecycleOwner.lifecycleScope.launch { openContinueWatchingDetail(item) }
        }
        clearStatusChip.setOnClickListener {
            dialog.dismiss()
            frag.viewModel.clearContinueWatchingItem(item)
            Toast.makeText(frag.requireContext(), "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
        positionDialogOverAnchor(dialog, dialogView, anchor)
        openDetailChip.requestFocus()
    }

    /** Centre the dialog over its anchor card, clamped to the screen with a small margin. */
    private fun positionDialogOverAnchor(
        dialog: android.app.Dialog,
        dialogView: android.view.View,
        anchor: android.view.View?
    ) {
        val win = dialog.window ?: return
        if (anchor == null || anchor.width == 0 || anchor.height == 0) return
        dialogView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val mw = dialogView.measuredWidth
        val mh = dialogView.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = anchor.resources.displayMetrics
        val margin = (12 * dm.density).toInt()
        win.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        val lp = win.attributes
        lp.x = (loc[0] + anchor.width / 2 - mw / 2)
            .coerceIn(margin, (dm.widthPixels - mw - margin).coerceAtLeast(margin))
        lp.y = (loc[1] + anchor.height / 2 - mh / 2)
            .coerceIn(margin, (dm.heightPixels - mh - margin).coerceAtLeast(margin))
        win.attributes = lp
    }

    private suspend fun openContinueWatchingDetail(item: ContinueWatchingItem) {
        val frag = fragment ?: return
        val context = frag.context ?: return
        val isDebrid = item.source == "debrid"
        when (item.contentType) {
            ContentType.MOVIE -> {
                if (isDebrid) {
                    val movieIntent = Intent(context, MovieDetailActivity::class.java).apply {
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.tmdbId ?: item.contentId)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                        putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                        putExtra(MovieDetailActivity.EXTRA_SOURCE_RAIL, "Continue Watching")
                    }
                    frag.startActivity(movieIntent)
                } else {
                    navigateToFragment(
                        MovieDetailFragmentV2.newInstance(
                            streamId = item.contentId, title = item.title,
                            backdropUrl = item.backdropUrl, posterUrl = item.posterUrl
                        )
                    )
                }
            }
            ContentType.EPISODE, ContentType.SERIES -> {
                val seriesId = if (isDebrid) {
                    item.seriesId?.takeIf { it.isNotBlank() }
                        ?: item.tmdbId?.takeIf { it.isNotBlank() } ?: item.contentId
                } else {
                    resolveIptvSeriesIdForContinueWatching(item) ?: item.contentId
                }
                if (isDebrid) {
                    val seriesIntent = Intent(context, SeriesDetailActivity::class.java).apply {
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.seriesTitle ?: item.title)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                        putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                    }
                    frag.startActivity(seriesIntent)
                } else {
                    navigateToFragment(
                        SeriesDetailFragmentV2.newInstance(
                            seriesId = seriesId, title = item.seriesTitle ?: item.title,
                            backdropUrl = item.backdropUrl, posterUrl = item.posterUrl
                        )
                    )
                }
            }
            else -> showHomeActionUnavailable()
        }
    }

    private suspend fun resolveIptvSeriesIdForContinueWatching(item: ContinueWatchingItem): String? {
        val frag = fragment ?: return null
        item.seriesId?.takeIf { it.isNotBlank() }?.let { return it }
        if (item.source == "debrid") return null
        if (item.contentType != ContentType.EPISODE && item.contentType != ContentType.SERIES) return null
        return withContext(Dispatchers.IO) {
            runCatching { frag.episodeDaoV2.getEpisodeById(item.contentId)?.seriesId }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    fun launchLiveStream(
        streamId: String?, fallbackTitle: String?, fallbackLogo: String?, fallbackUrl: String?,
        epgChannelId: String? = null
    ) {
        val frag = fragment ?: return
        frag.viewLifecycleOwner.lifecycleScope.launch {
            val stream = streamId?.let { frag.repository.getLiveStreamById(it) }
            val serverUrl = frag.credentialsPrefs.getServerUrl()
            val resolvedUrl = when {
                stream != null && !serverUrl.isNullOrBlank() -> frag.repository.buildLiveStreamUrl(stream, serverUrl)
                else -> fallbackUrl
            }
            if (resolvedUrl.isNullOrBlank()) {
                Toast.makeText(frag.requireContext(), "Stream unavailable", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val finalServerUrl = serverUrl ?: ""
            val absoluteIcon = (stream?.stream_icon ?: fallbackLogo).toAbsoluteUrl(finalServerUrl)
            val intent = PlayerActivity.createIntent(
                context = frag.requireContext(), streamUrl = resolvedUrl,
                title = fallbackTitle ?: stream?.name ?: frag.getString(R.string.player_epg_channel_unknown),
                channelName = stream?.name ?: fallbackTitle, channelLogo = absoluteIcon,
                epgChannelId = stream?.epg_channel_id?.takeIf { it.isNotBlank() } ?: epgChannelId ?: streamId,
                contentId = stream?.stream_id ?: streamId ?: resolvedUrl,
                contentType = ContentType.LIVE_TV, posterUrl = absoluteIcon,
                liveCategoryId = stream?.category_id
            )
            frag.startActivity(intent)
        }
    }

    fun showHomeActionUnavailable() {
        val frag = fragment ?: return
        if (!frag.isAdded) return
        Toast.makeText(frag.requireContext(), "Not available", Toast.LENGTH_SHORT).show()
    }
}
