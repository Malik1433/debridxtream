package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.network.NetworkQuality
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.util.PlayerCacheManager
import com.tvonnet.debridxtreamiptv.util.DeviceProfile
import okhttp3.OkHttpClient

/**
 * Builds the tuned ExoPlayer instance (Phase P19a).
 *
 * Everything here is construction: data sources, cache policy, load control, track
 * selector, renderers and the player itself. Wiring the built player to the view,
 * listeners, media item, prepare/seek and the monitors stays in [PlayerActivity].
 *
 * The comments that justify each choice moved with the code — several of them record
 * device-verified regressions (raw-TS extractor flags, low-RAM byte cap, DV codec
 * selector) and must not be "cleaned up".
 */
internal data class PlaybackEngineConfig(
    val streamUrl: String,
    val contentType: ContentType?,
    val playbackSource: PlaybackSource,
    val requestHeaders: Map<String, String>?,
    val disableTunneling: Boolean,
    val preferredAudioLanguage: String?,
    val preferredSubtitleLanguage: String?
)

/** What the Activity needs to keep hold of after the build. */
internal data class PlaybackEngine(
    val player: ExoPlayer,
    val bandwidthMeter: DefaultBandwidthMeter
)

internal class PlayerEngineFactory(
        private val context: Context,
        private val okHttpClient: OkHttpClient,
        private val settings: SettingsPreferences
    ) {

    fun build(
        config: PlaybackEngineConfig,
        loadErrorPolicy: LoadErrorHandlingPolicy,
        codecSelector: MediaCodecSelector
    ): PlaybackEngine {
        val isDebrid = config.playbackSource == PlaybackSource.DEBRID
        val isLive = config.contentType == ContentType.LIVE_TV
        val mediaSourceFactory = buildMediaSourceFactory(config, isDebrid, isLive, loadErrorPolicy)
        val loadControl = buildLoadControl(config, isDebrid, isLive)
        val trackSelector = buildTrackSelector(config)
        val renderersFactory = buildRenderersFactory(codecSelector)
        val meter = DefaultBandwidthMeter.getSingletonInstance(context)
        return PlaybackEngine(
            player = buildExoPlayer(mediaSourceFactory, loadControl, trackSelector, renderersFactory, meter),
            bandwidthMeter = meter
        )
    }

    private fun buildMediaSourceFactory(
        config: PlaybackEngineConfig,
        isDebrid: Boolean,
        isLive: Boolean,
        loadErrorPolicy: LoadErrorHandlingPolicy
    ): DefaultMediaSourceFactory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        config.requestHeaders?.takeIf { it.isNotEmpty() }?.let {
            httpDataSourceFactory.setDefaultRequestProperties(it)
        }
        if (config.requestHeaders?.keys?.any { it.equals("User-Agent", ignoreCase = true) } != true) {
            // Xtream panels throttle/starve stream requests from unknown player UAs
            // (first GOP then nothing) — identify playback exactly like our API
            // client does. Debrid CDNs don't care, keep the app UA there.
            httpDataSourceFactory.setUserAgent(
                if (isDebrid) "DebridXtream/1.0 (Linux; Android 10; TV)" else "IPTVSmartersPlayer"
            )
        }

        // Bypass physical disk caching for high-bitrate Debrid streams to prevent I/O choking.
        // Using pure upstream network resolution (RAM only) ensures the decoder isn't starved by
        // slow eMMC writes. Live streams also bypass: an infinite TS stream through a 500MB LRU
        // cache is pure eviction churn with zero reuse value.
        // NOTE: do NOT set TsExtractor FLAG_ALLOW_NON_IDR_KEYFRAMES / FLAG_DETECT_ACCESS_UNITS
        // here — they froze video (audio kept playing) on some AVC live TS channels on MTK Fire TVs.
        return if (isDebrid || isLive) {
            DefaultMediaSourceFactory(httpDataSourceFactory)
        } else {
            val dataSourceFactory = CacheDataSource.Factory()
                .setCache(PlayerCacheManager.getCache(context))
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory().setCache(PlayerCacheManager.getCache(context))
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            DefaultMediaSourceFactory(dataSourceFactory)
        }.setLoadErrorHandlingPolicy(loadErrorPolicy)
    }

    private fun buildLoadControl(
        config: PlaybackEngineConfig,
        isDebrid: Boolean,
        isLive: Boolean
    ): DefaultLoadControl {
        val bufferConfig = PlayerBufferConfigFactory.buildConfig(
            context, settings, config.contentType, config.streamUrl, isDebrid
        )
        val isLowRam = DeviceProfile.isLowRamDevice(context)
        val backBufferMs = if (!isLive && !isLowRam) 15000 else 0
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferConfig.minBufferMs,
                bufferConfig.maxBufferMs,
                bufferConfig.startPlaybackMs,
                bufferConfig.rebufferPlaybackMs
            )
            // Increase the target buffer bytes threshold for Debrid to allow heavy pre-buffering.
            .setTargetBufferBytes(PlayerBufferConfigFactory.resolveTargetBufferBytes(context, isDebrid))
            // On low-RAM devices the byte cap must win: with time-priority a single
            // high-bitrate 4K stream buffers past the heap limit and OOMs the app.
            .setPrioritizeTimeOverSizeThresholds(!isLowRam)
            .setBackBuffer(backBufferMs, /* retainBackBufferFromKeyframe= */ true)
            .build()
    }

    private fun buildTrackSelector(config: PlaybackEngineConfig): DefaultTrackSelector {
        val trackSelector = DefaultTrackSelector(context)
        // Hardware video tunneling offloads A/V sync to the hardware composer.
        var parametersBuilder = trackSelector.buildUponParameters()
            .setTunnelingEnabled(!config.disableTunneling)
        config.preferredAudioLanguage?.let { parametersBuilder = parametersBuilder.setPreferredAudioLanguage(it) }
        config.preferredSubtitleLanguage?.let { parametersBuilder = parametersBuilder.setPreferredTextLanguage(it) }
        // On a known-slow connection, cap adaptive selections at 1080p/8Mbps so HLS/DASH
        // masters don't pick a 4K rendition that immediately stalls. Single-track streams
        // (torrents, raw TS) are unaffected.
        val savedNetworkQuality = runCatching {
            NetworkQuality.valueOf(settings.getNetworkQuality())
        }.getOrDefault(NetworkQuality.MODERATE)
        if (savedNetworkQuality == NetworkQuality.SLOW) {
            parametersBuilder = parametersBuilder
                .setMaxVideoSize(1920, 1080)
                .setMaxVideoBitrate(8_000_000)
        }
        trackSelector.parameters = parametersBuilder.build()
        return trackSelector
    }

    private fun buildRenderersFactory(codecSelector: MediaCodecSelector): DefaultRenderersFactory {
        val extensionMode = if (settings.isSoftwareAudioEnabled()) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        // WedgeEscapeRenderersFactory = DefaultRenderersFactory + the 5.1 upmix escape
        // for the wedged-HDMI-primary-mixer freeze (inert until a wedge is detected).
        return WedgeEscapeRenderersFactory(context)
            .setExtensionRendererMode(extensionMode)
            // If the primary hardware decoder fails to initialize (e.g. HEVC on a busy or
            // incapable SoC), fall back to a lower-priority decoder instead of erroring.
            .setEnableDecoderFallback(true)
            // Dolby Vision on a non-DV display renders to a black screen (audio plays).
            // When the display can't do DV, hide the DV decoder so media3 falls back to its
            // HEVC base layer (the HDR10/SDR image of DV profile 7/8), which plays.
            .setMediaCodecSelector(codecSelector)
    }

    private fun buildExoPlayer(
        mediaSourceFactory: DefaultMediaSourceFactory,
        loadControl: DefaultLoadControl,
        trackSelector: DefaultTrackSelector,
        renderersFactory: DefaultRenderersFactory,
        meter: DefaultBandwidthMeter
    ): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setBandwidthMeter(meter)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            // Gentle live-latency catch-up: only activates for sources that carry a
            // LiveConfiguration (HLS/DASH). The raw-TS branch of buildMediaItem deliberately
            // sets NO LiveConfiguration (documented TS flush-loop device bug), so TS playback
            // is unaffected by this.
            .setLivePlaybackSpeedControl(
                androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .setTrackSelector(trackSelector)
            // Skip buttons = 10s fine step (streaming standard: Netflix/Disney/AppleTV skip
            // is 10s). The seek BAR is the "fast" control — it accelerates on hold, see
            // PlayerVodControlsUi.installControllerSeekNavigation.
            .setSeekForwardIncrementMs(10000)
            .setSeekBackIncrementMs(10000)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
    }
    }
