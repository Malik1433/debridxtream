package com.tvonnet.debridxtreamiptv.ui.home

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
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.live.guide.LiveTvGuideFragment
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2
import com.tvonnet.debridxtreamiptv.ui.detail.phone.DetailScreens
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the router needs from the screen hosting it.
 *
 * It used to be `HomeFragment` itself, which was fine while there was one Home. There are two now
 * — the TV one and the phone one — and the resume rules in here (play-then-repair, the
 * startPositionMs that must travel on every intent) are exactly the code that must NOT be copied
 * into the second screen. So the router keeps the rules and the host supplies the four things it
 * cannot know: the fragment, the data it queries, and two hooks for the TV-only hero/focus memory
 * that the phone has no equivalent of.
 */
internal interface HomeRouterHost {
    val routerFragment: Fragment
    val routerRepository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
    val routerCredentials: com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
    val routerEpisodeDao: com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2

    /** False vetoes the hop — the TV Home uses it to swallow a second click mid-navigation. */
    fun routerMayNavigate(): Boolean = true

    /** Called once a hop is actually committed, for host bookkeeping. */
    fun onRouterNavigationCommitted() {}

    /** The TV Home repaints its hero from the clicked card; the phone hero is not addressable. */
    fun onRouterHeroSelected(item: FeaturedItem) {}

    /** Leaving for an Activity — the TV Home suppresses its focus memory so it comes back right. */
    fun onRouterLeavingForActivity() {}
}

internal class HomeNavigationRouter(private var host: HomeRouterHost?) {

    fun cleanup() {
        host = null
    }

    fun navigateToSection(section: String) {
        val h = host ?: return
        if (!h.routerMayNavigate()) return

        // Routing, the Debrid tier gate and the Live classic/guide split are shared with the
        // Live TV v2 rail — see SectionNavigator.
        com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator.navigate(h.routerFragment, section) {
            h.onRouterNavigationCommitted()
        }
    }

    fun navigateToFragment(target: Fragment) {
        val h = host ?: return
        val frag = h.routerFragment
        if (!frag.isAdded || frag.parentFragmentManager.isStateSaved) return
        h.onRouterNavigationCommitted()
        frag.parentFragmentManager.commit {
            replace(R.id.content_container, target)
            addToBackStack(null)
        }
    }

    fun onFeaturedItemClick(item: FeaturedItem) {
        val h = host ?: return
        h.onRouterHeroSelected(item)
        openFeatured(h, item)
    }

    private fun openFeatured(h: HomeRouterHost, item: FeaturedItem) {
        val frag = h.routerFragment
        if (item.sourceType == SourceType.TMDB) {
            val context = frag.context ?: return
            if (item.contentType == ContentType.MOVIE) {
                val intent = Intent(context, MovieDetailActivity::class.java).apply {
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.contentId)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                    putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                }
                startActivityPreservingContentFocus(intent)
            } else if (item.contentType == ContentType.SERIES) {
                val intent = Intent(context, SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.contentId)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                    putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                }
                startActivityPreservingContentFocus(intent)
            } else {
                showHomeActionUnavailable()
            }
        } else {
            when (item.contentType) {
                ContentType.MOVIE -> {
                    val detailFragment = DetailScreens.movie(
                        context = frag.requireContext(),
                        
                        streamId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl,
                        plot = item.description,
                        directSource = item.streamUrl,
                        trailer = item.trailerValue
                    )
                    navigateToFragment(detailFragment)
                }
                ContentType.SERIES -> {
                    val detailFragment = DetailScreens.series(
                        context = frag.requireContext(),
                        
                        seriesId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl,
                        trailer = item.trailerValue
                    )
                    navigateToFragment(detailFragment)
                }
                ContentType.LIVE_TV -> {
                    launchLiveStream(item.contentId, item.title, item.posterUrl, item.streamUrl)
                }
                else -> showHomeActionUnavailable()
            }
        }
    }

    fun openFeaturedDetails(item: FeaturedItem) {
        val h = host ?: return
        val frag = h.routerFragment
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
                    startActivityPreservingContentFocus(intent)
                }
                ContentType.SERIES -> {
                    val intent = Intent(context, SeriesDetailActivity::class.java).apply {
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.contentId)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                        putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
                    }
                    startActivityPreservingContentFocus(intent)
                }
                else -> showHomeActionUnavailable()
            }
        } else {
            when (item.contentType) {
                ContentType.MOVIE -> {
                    val detailFragment = DetailScreens.movie(
                        context = frag.requireContext(),
                        
                        streamId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl,
                        plot = item.description,
                        directSource = item.streamUrl,
                        trailer = item.trailerValue
                    )
                    navigateToFragment(detailFragment)
                }
                ContentType.SERIES -> {
                    val detailFragment = DetailScreens.series(
                        context = frag.requireContext(),
                        
                        seriesId = item.contentId,
                        title = item.title,
                        backdropUrl = item.backdropUrl,
                        posterUrl = item.posterUrl,
                        trailer = item.trailerValue
                    )
                    navigateToFragment(detailFragment)
                }
                else -> showHomeActionUnavailable()
            }
        }
    }

    fun onContinueWatchingItemClick(item: ContinueWatchingItem) {
        val h = host ?: return
        val frag = h.routerFragment
        val context = frag.context ?: return
        val streamUrl = item.streamUrl?.takeIf { it.isNotBlank() }
        val isDebrid = item.source == "debrid"
        val hasResolutionInfo = !item.debridInfoHash.isNullOrBlank() || !item.debridMagnet.isNullOrBlank()
        val canFreshResolveDirectDebrid = isDebrid &&
            item.directDebridPlayback &&
            !item.contentId.isNullOrBlank() &&
            !((item.seriesTitle ?: item.title).isNullOrBlank())
        val expired = item.isExpired()

        Log.e("HISTORY_DEBUG", "Click: ${item.title} | source=${item.source} | stream=${SensitiveLogRedactor.describeUrl(streamUrl)} | expired=$expired | hasResInfo=$hasResolutionInfo")

        frag.viewLifecycleOwner.lifecycleScope.launch {
            when (item.contentType) {
                ContentType.MOVIE, ContentType.EPISODE -> resumeOrOpenCwDetail(
                    h, context, item,
                    CwResumeFacts(streamUrl, isDebrid, hasResolutionInfo, canFreshResolveDirectDebrid, expired)
                )
                ContentType.SERIES -> openContinueWatchingDetail(item)
                else -> {
                    Log.w("HISTORY_DEBUG", "Unsupported content type for resume: ${item.contentType}")
                    showHomeActionUnavailable()
                }
            }
        }
    }

    /** The click-time resume facts for a Continue Watching card, computed once. */
    private data class CwResumeFacts(
        val streamUrl: String?,
        val isDebrid: Boolean,
        val hasResolutionInfo: Boolean,
        val canFreshResolveDirectDebrid: Boolean,
        val expired: Boolean,
    )

    // The resume rule (play-then-repair): play the link we hold, or anything the player can
    // repair/re-resolve itself; only a card with nothing playable falls back to the detail page.
    private suspend fun resumeOrOpenCwDetail(
        h: HomeRouterHost,
        context: android.content.Context,
        item: ContinueWatchingItem,
        facts: CwResumeFacts
    ) {
        val serverUrl = h.routerCredentials.getServerUrl() ?: ""
        val canResumeDirectly = (facts.streamUrl != null && !facts.expired) ||
            (facts.isDebrid && facts.hasResolutionInfo) ||
            facts.canFreshResolveDirectDebrid

        Log.e("HISTORY_DEBUG", "RESUME_DECISION: canResumeDirectly=$canResumeDirectly | isDebrid=${facts.isDebrid} | hasResInfo=${facts.hasResolutionInfo} | streamUrl=${SensitiveLogRedactor.describeUrl(facts.streamUrl)}")

        if (!canResumeDirectly) {
            Log.e("HISTORY_DEBUG", "RESUME_PATH: FALLBACK to Detail (canResumeDirectly=false)")
            openContinueWatchingDetail(item)
            return
        }

        Log.e("HISTORY_DEBUG", "RESUME_PATH: DIRECT to PlayerActivity")
        val resumeSeriesId = resumeSeriesIdFor(item, facts.isDebrid)
        Log.e(
            "TASK030_CW_SERIES",
            "DIRECT_RESUME type=${item.contentType} source=${item.source} contentId=${item.contentId} seriesId=$resumeSeriesId season=${item.seasonNumber} episode=${item.episodeNumber}"
        )
        startActivityPreservingContentFocus(
            buildCwResumeIntent(context, item, facts, serverUrl, resumeSeriesId)
        )
    }

    private suspend fun resumeSeriesIdFor(item: ContinueWatchingItem, isDebrid: Boolean): String? =
        if (item.contentType == ContentType.EPISODE) {
            item.seriesId?.takeIf { it.isNotBlank() }
                ?: if (!isDebrid) resolveIptvSeriesIdForContinueWatching(item) else null
        } else {
            null
        }

    // The direct-resume intent, verbatim — startPositionMs always travels (the detail-page
    // resume bug was exactly a createIntent path dropping it).
    private fun buildCwResumeIntent(
        context: android.content.Context,
        item: ContinueWatchingItem,
        facts: CwResumeFacts,
        serverUrl: String,
        resumeSeriesId: String?
    ): Intent = PlayerActivity.createIntent(
        context = context,
        streamUrl = if (facts.canFreshResolveDirectDebrid) "" else facts.streamUrl ?: "",
        title = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title,
        startPositionMs = item.currentPosition,
        contentId = item.tmdbId?.takeIf { it.isNotBlank() } ?: item.contentId,
        contentType = item.contentType,
        playbackSource = if (facts.isDebrid) {
            com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID
        } else {
            com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.IPTV
        },
        posterUrl = item.posterUrl,
        backdropUrl = item.backdropUrl,
        tmdbId = item.tmdbId,
        imdbId = item.imdbId,
        seriesTitle = item.seriesTitle,
        episodeTitle = item.episodeTitle,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        debridInfoHash = item.debridInfoHash,
        debridMagnet = item.debridMagnet,
        directDebridPlayback = item.directDebridPlayback,
        debridProvider = item.debridProvider,
        debridSourceType = item.debridSourceType,
        debridSourceName = item.debridSourceName,
        debridLanguages = item.debridLanguages,
        debridQuality = item.debridQuality,
        debridStreamId = item.debridStreamId,
        debridBingeGroup = item.debridBingeGroup,
        debridFileIdx = item.debridFileIdx,
        expiresAt = item.expiresAt,
        baseServerUrl = serverUrl,
        seriesId = resumeSeriesId
    )

    suspend fun openContinueWatchingDetail(item: ContinueWatchingItem) {
        val h = host ?: return
        val frag = h.routerFragment
        val context = frag.context ?: return
        val isDebrid = item.source == "debrid"
        when (item.contentType) {
            ContentType.MOVIE, ContentType.EPISODE -> {
                if (item.contentType == ContentType.MOVIE) {
                    openContinueWatchingMovie(context, item, isDebrid)
                } else {
                    openContinueWatchingSeries(context, item, isDebrid)
                }
            }
            ContentType.SERIES -> openContinueWatchingSeries(context, item, isDebrid)
            else -> showHomeActionUnavailable()
        }
    }

    private fun openContinueWatchingMovie(
        context: android.content.Context,
        item: ContinueWatchingItem,
        isDebrid: Boolean
    ) {
        if (isDebrid) {
            val movieIntent = Intent(context, MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.tmdbId ?: item.contentId)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                putExtra(MovieDetailActivity.EXTRA_SOURCE_RAIL, "Continue Watching")
            }
            startActivityPreservingContentFocus(movieIntent)
        } else {
            val detailFragment = DetailScreens.movie(
                        context = context,
                        
                streamId = item.contentId,
                title = item.title,
                backdropUrl = item.backdropUrl,
                posterUrl = item.posterUrl
            )
            navigateToFragment(detailFragment)
        }
    }

    // Shared by the EPISODE and SERIES arms of openContinueWatchingDetail — their bodies were
    // byte-identical before extraction.
    private suspend fun openContinueWatchingSeries(
        context: android.content.Context,
        item: ContinueWatchingItem,
        isDebrid: Boolean
    ) {
        val seriesId = if (isDebrid) {
            item.seriesId?.takeIf { it.isNotBlank() }
                ?: item.tmdbId?.takeIf { it.isNotBlank() }
                ?: item.contentId
        } else {
            resolveIptvSeriesIdForContinueWatching(item) ?: item.contentId
        }
        if (seriesId == null) {
            showHomeActionUnavailable()
            return
        }
        if (isDebrid) {
            val seriesIntent = Intent(context, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.seriesTitle ?: item.title)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, item.backdropUrl)
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivityPreservingContentFocus(seriesIntent)
        } else {
            val detailFragment = DetailScreens.series(
                        context = context,
                        
                seriesId = seriesId,
                title = item.seriesTitle ?: item.title,
                backdropUrl = item.backdropUrl,
                posterUrl = item.posterUrl
            )
            navigateToFragment(detailFragment)
        }
    }

    suspend fun resolveIptvSeriesIdForContinueWatching(item: ContinueWatchingItem): String? {
        val h = host ?: return null
        item.seriesId?.takeIf { it.isNotBlank() }?.let { return it }
        if (item.source == "debrid") return null
        if (item.contentType != ContentType.EPISODE && item.contentType != ContentType.SERIES) return null
        val resolved = withContext(Dispatchers.IO) {
            runCatching { h.routerEpisodeDao.getEpisodeById(item.contentId)?.seriesId }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        Log.d(
            "TASK030_CW_SERIES",
            "RESOLVE_SERIES_ID contentId=${item.contentId} saved=${item.seriesId} resolved=$resolved"
        )
        return resolved
    }

    fun onRecentLiveItemClick(item: RecentLiveChannelItem) {
        host ?: return
        Log.e("HISTORY_DEBUG", "Click Recent Live: ${item.channelName} | id=${item.channelId} | stream=${SensitiveLogRedactor.describeUrl(item.streamUrl)}")
        launchLiveStream(
            streamId = item.channelId,
            fallbackTitle = item.channelName,
            fallbackLogo = item.channelLogo,
            fallbackUrl = item.streamUrl,
            epgChannelId = item.epgChannelId
        )
    }

    fun launchLiveStream(
        streamId: String?,
        fallbackTitle: String?,
        fallbackLogo: String?,
        fallbackUrl: String?,
        epgChannelId: String? = null
    ) {
        val h = host ?: return
        val frag = h.routerFragment
        frag.viewLifecycleOwner.lifecycleScope.launch {
            val stream = streamId?.let { h.routerRepository.getLiveStreamById(it) }
            val serverUrl = h.routerCredentials.getServerUrl()
            val resolvedUrl = when {
                stream != null && !serverUrl.isNullOrBlank() -> h.routerRepository.buildLiveStreamUrl(stream, serverUrl)
                else -> fallbackUrl
            }

            if (resolvedUrl.isNullOrBlank()) {
                Toast.makeText(frag.requireContext(), frag.requireContext().getString(R.string.c_stream_unavailable), Toast.LENGTH_SHORT).show()
                return@launch
            }

            startActivityPreservingContentFocus(
                buildLiveIntent(
                    frag, stream, resolvedUrl, serverUrl ?: "",
                    LiveLaunchFallbacks(streamId, fallbackTitle, fallbackLogo, epgChannelId)
                )
            )
        }
    }

    /** The caller-supplied fallbacks for a live launch when the DB row is missing fields. */
    private data class LiveLaunchFallbacks(
        val streamId: String?,
        val title: String?,
        val logo: String?,
        val epgChannelId: String?,
    )

    private fun buildLiveIntent(
        frag: Fragment,
        stream: XtreamStream?,
        resolvedUrl: String,
        serverUrl: String,
        fallbacks: LiveLaunchFallbacks
    ): Intent {
        val absoluteIcon = (stream?.stream_icon ?: fallbacks.logo).toAbsoluteUrl(serverUrl)
        return PlayerActivity.createIntent(
            context = frag.requireContext(),
            streamUrl = resolvedUrl,
            title = fallbacks.title ?: stream?.name ?: frag.getString(R.string.player_epg_channel_unknown),
            channelName = stream?.name ?: fallbacks.title,
            channelLogo = absoluteIcon,
            epgChannelId = stream?.epg_channel_id?.takeIf { it.isNotBlank() } ?: fallbacks.epgChannelId ?: fallbacks.streamId,
            contentId = stream?.stream_id ?: fallbacks.streamId ?: resolvedUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = absoluteIcon,
            liveCategoryId = stream?.category_id
        )
    }

    fun startActivityPreservingContentFocus(intent: Intent) {
        val h = host ?: return
        val frag = h.routerFragment
        h.onRouterLeavingForActivity()
        frag.startActivity(intent)
    }

    fun showHomeActionUnavailable() {
        val h = host ?: return
        val frag = h.routerFragment
        if (!frag.isAdded) return
        Toast.makeText(frag.requireContext(), frag.requireContext().getString(R.string.c_not_available), Toast.LENGTH_SHORT).show()
    }
}
