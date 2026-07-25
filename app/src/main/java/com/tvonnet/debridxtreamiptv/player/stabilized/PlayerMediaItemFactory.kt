package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder

/**
 * Builds the ExoPlayer [MediaItem] for a URL (Phase P24, fragment-split prep).
 *
 * Moved verbatim out of [PlayerActivity]: [buildMediaItem] resolves the MIME hint,
 * attaches subtitle configs, and — **the LiveConfiguration landmine** — sets a live
 * window ONLY for HLS/DASH, never for raw TS (which would flip the item to live-offset
 * mode and seek an unseekable stream, freezing every .ts channel on the first frame).
 * The gate is byte-identical; state via [session], the track manager via [activity].
 */
internal class PlayerMediaItemFactory(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val trackManager get() = activity.trackManager

    private val subtitleEntries: List<String> by session::subtitleEntries
    private val debridProviderExtra: String? by session::debridProviderExtra
    private val debridSourceTypeExtra: String? by session::debridSourceTypeExtra
    private val debridSourceNameExtra: String? by session::debridSourceNameExtra
    private val debridQualityExtra: String? by session::debridQualityExtra
    private val originalTitle: String? by session::originalTitle
    private val streamHeaders: Map<String, String>? by session::streamHeaders
    private val contentType: ContentType? by session::contentType

    private fun diagnosticsPlaybackFields(url: String?, mimeHint: String?) =
        activity.diagnosticsFieldsWithMime(url, mimeHint)

    fun buildMediaItem(url: String): MediaItem {
        val subtitleConfigs = trackManager.buildSubtitleConfigurations(subtitleEntries)
        val builder = MediaItem.Builder().setUri(url)
        val mimeHint = resolveMediaMimeType(
            url,
            listOf(
                url,
                debridProviderExtra,
                debridSourceTypeExtra,
                debridSourceNameExtra,
                debridQualityExtra,
                originalTitle
            ),
            streamHeaders
        )
        mimeHint?.let { builder.setMimeType(it) }
        // LiveConfiguration ONLY for HLS/DASH. On raw TS it flips the item to
        // "live window" mode and the live-offset control seeks an unseekable
        // stream ~200ms after the first frame — an endless flush/reload loop
        // that froze every .ts channel on the first frame (device-diagnosed).
        if (contentType == ContentType.LIVE_TV &&
            (mimeHint == MimeTypes.APPLICATION_M3U8 || mimeHint == MimeTypes.APPLICATION_MPD)
        ) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
        }
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }
        PlaybackDiagnosticsRecorder.record(
            activity.requireContext(),
            "media_item_built",
            diagnosticsPlaybackFields(url, mimeHint = mimeHint) + mapOf(
                "subtitleConfigCount" to subtitleConfigs.size
            )
        )
        return builder.build()
    }
}
