package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver
import com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridItemType
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow
import com.tvonnet.debridxtreamiptv.ui.debrid.seeall.DebridSeeAllActivity
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Click + resume behavior for Debrid catalog items. (Originally mirrored the legacy
 * DebridFragment's onContentItemClick/tryResumeDebrid; that fragment is deleted and
 * this is now the only copy.)
 */
internal class StremioDebridActions(
    private val fragment: Fragment,
    private val playbackResolver: PlaybackResolver,
    private val resolutionOverlay: View
) {
    private var resolving = false

    /**
     * The resolve in flight, so the customer can stop it.
     *
     * Resolving a debrid link on cellular takes as long as it takes, and until now the only way
     * out was to leave the screen — the coroutine kept running and could still throw the player up
     * afterwards. Cancelling the job unwinds the whole chain, because every stage inside it is a
     * suspending call.
     */
    private var resolveJob: Job? = null

    private fun cancelResolve() {
        resolveJob?.cancel()
        resolveJob = null
        resolving = false
        resolutionOverlay.isVisible = false
    }

    fun click(item: DebridContentItem) {
        if (item.type == "see_all") return
        if (item.isContinueWatching) { resume(item); return }
        navigateToDetail(item)
    }

    private fun resume(item: DebridContentItem) {
        if (resolving) return
        resolving = true
        val resumePosition = item.resumePositionMs ?: 0L
        resolutionOverlay.findViewById<View>(R.id.resolving_cancel)?.setOnClickListener { cancelResolve() }
        resolveJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            resolutionOverlay.isVisible = true
            val result = playbackResolver.resolve(
                source = item.source,
                streamUrl = item.streamUrl,
                isExpired = item.isExpired(),
                infoHash = item.debridInfoHash,
                magnet = item.debridMagnet,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle
            )
            resolutionOverlay.isVisible = false
            resolving = false
            resolveJob = null
            if (!fragment.isAdded) return@launch
            when (result) {
                is ResolutionResult.Success -> launchPlayer(item, result.url, resumePosition)
                is ResolutionResult.RefreshRequired -> navigateToDetail(item)
                is ResolutionResult.Error -> Toast.makeText(
                    fragment.requireContext(), result.message, Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchPlayer(item: DebridContentItem, url: String, resumePosition: Long) {
        val contentType = item.contentType
            ?: if (DebridItemType.isSeries(item.type)) ContentType.EPISODE else ContentType.MOVIE
        val intent = PlayerActivity.createIntent(
            context = fragment.requireContext(),
            streamUrl = url,
            title = item.title,
            startPositionMs = resumePosition,
            contentId = item.tmdbId ?: item.id,
            contentType = contentType,
            playbackSource = if (item.source == "debrid") PlaybackSource.DEBRID else PlaybackSource.IPTV,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            tmdbId = item.tmdbId,
            imdbId = item.imdbId,
            seriesTitle = item.seriesTitle,
            episodeTitle = item.episodeTitle,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            debridInfoHash = item.debridInfoHash,
            debridMagnet = item.debridMagnet
        )
        fragment.startActivity(intent)
    }

    fun seeAll(row: DebridRow) {
        val ctx = fragment.context ?: return
        val intent = Intent(ctx, DebridSeeAllActivity::class.java).apply {
            putExtra(DebridSeeAllActivity.EXTRA_ROW_ID, row.id)
            putExtra(DebridSeeAllActivity.EXTRA_ROW_TITLE, row.title)
        }
        fragment.startActivity(intent)
    }

    fun navigateToDetail(item: DebridContentItem) {
        val ctx = fragment.context ?: return
        // The type is asked through DebridItemType, not compared to one spelling: a favourited
        // movie arrives as "vod" and used to fall into the series branch, which is why every
        // favourite opened an empty season list.
        if (!DebridItemType.isSeries(item.type)) {
            val intent = Intent(ctx, MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            fragment.startActivity(intent)
        } else {
            val intent = Intent(ctx, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            fragment.startActivity(intent)
        }
    }
}
