package com.tvonnet.debridxtreamiptv.player.stabilized

import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/**
 * Binds the VOD title/poster/subtitle/quality chrome (Phase P23, fragment-split prep).
 *
 * Moved verbatim out of [PlayerActivity]: [bindModernMetadata] populates the poster,
 * cleaned title, "MOVIE • DEBRID" / "S1 • E2 • IPTV" subtitle and the quality pill
 * from the current [session] identity. The views are looked up on the PlayerView each
 * call (they were never stored as fields). Body is byte-identical.
 */
internal class PlayerMetadataBinder(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val playerView get() = activity.playerView

    private val posterUrlExtra: String? by session::posterUrlExtra
    private val playbackSource: PlaybackSource by session::playbackSource
    private val contentType: ContentType? by session::contentType
    private val seasonNumberExtra: Int? by session::seasonNumberExtra
    private val episodeNumberExtra: Int? by session::episodeNumberExtra
    private val debridQualityExtra: String? by session::debridQualityExtra

    private fun getString(resId: Int): String = activity.getString(resId)

    fun bindModernMetadata(title: String?) {
        val posterView = playerView.findViewById<ImageView>(R.id.iv_player_poster)
        val titleView = playerView.findViewById<TextView>(R.id.tv_player_title)
        val subtitleView = playerView.findViewById<TextView>(R.id.tv_player_subtitle)
        val qualityView = playerView.findViewById<TextView>(R.id.tv_player_quality)

        posterView?.let { GlideUtils.loadMoviePoster(it, posterUrlExtra) }

        val displayTitle = if (playbackSource == PlaybackSource.DEBRID) {
            cleanTitle(title ?: getString(R.string.app_name))
        } else {
            title ?: getString(R.string.app_name)
        }

        titleView?.text = displayTitle

        val contentLabel = when (contentType) {
            ContentType.MOVIE -> getString(R.string.label_movie)
            ContentType.SERIES -> getString(R.string.label_series)
            ContentType.EPISODE -> getString(R.string.label_episode)
            else -> getString(R.string.label_video)
        }
        val sourceLabel = when (playbackSource) {
            PlaybackSource.DEBRID -> getString(R.string.label_debrid)
            PlaybackSource.IPTV -> getString(R.string.label_iptv)
        }

        val subtitle = if (contentType == ContentType.EPISODE || contentType == ContentType.SERIES) {
            val season = seasonNumberExtra
            val episode = episodeNumberExtra
            if (season != null && episode != null) {
                "S${season} • E${episode} • $sourceLabel"
            } else {
                "$contentLabel • $sourceLabel"
            }
        } else {
            "$contentLabel • $sourceLabel"
        }

        subtitleView?.text = subtitle
        qualityView?.apply {
            text = debridQualityExtra
            isVisible = !debridQualityExtra.isNullOrBlank()
        }
    }
}
