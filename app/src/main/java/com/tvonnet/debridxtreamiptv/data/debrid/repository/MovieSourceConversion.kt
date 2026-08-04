package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.repository.AddableTorrentIdentity
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

private const val TAG = "UnifiedSourceProvider"

// Addon error streams (e.g. MediaFusion/Torrentio API limits) masquerade as sources.
// Every spelling here was observed in the wild — add to the list, never prune it.
private val ADDON_ERROR_MARKERS = listOf(
    "api exception",
    "api_exception",
    "api-exception",
    "rate limit",
    "rate_limit",
    "rate-limit",
    "limit reached",
    "limit_reached",
    "limit-reached",
    "quota exceeded",
    "quota_exceeded",
    "provider error",
    "provider_error",
    "invalid token",
    "invalid_token",
    "error occurred",
    "debrid error",
    "torrent not downloaded",
    "torrent_not_downloaded",
    "not cached",
    "not_cached",
    "not-cached",
    "not ready",
    "not_ready",
    "not-ready",
    "timeout",
    "timed out"
)

/**
 * Convert AddonStream objects to MovieSource objects with enhanced metadata support
 */
internal fun convertAddonStreamsToMovieSources(
    addonStreams: List<AddonStream>,
    title: String?,
    cacheStatusByHash: Map<String, DebridCacheStatus>,
    // H1: other addons' copies of the same file, keyed by the group primary.
    alternatesByStream: Map<AddonStream, List<AddonStream>> = emptyMap()
): List<MovieSource> {
    val movieTitle = title ?: "Unknown Movie"
    Log.d(TAG, "🔄 Converting ${addonStreams.size} enhanced addon streams to MovieSource objects")

    return addonStreams.mapIndexedNotNull { index, addonStream ->
        convertOneStream(index, addonStream, movieTitle, cacheStatusByHash)
            ?.withAlternates(alternatesByStream[addonStream])
    }.also { filteredSources ->
        logConversionStats(addonStreams, filteredSources)
    }
}

// H1: carry the collapsed copies onto the picker row (count badge now, H2 failover later).
private fun MovieSource.withAlternates(alternates: List<AddonStream>?): MovieSource {
    if (alternates.isNullOrEmpty()) return this
    return copy(alternates = alternates.map { it.toSourceAlternate() })
}

private fun AddonStream.toSourceAlternate(): com.tvonnet.debridxtreamiptv.data.repository.SourceAlternate {
    val ids = resolveTorrentIdentifiers(this)
    val mediaFusionUrl = url?.takeIf { source == AddonSourceType.MEDIA_FUSION && it.isNotBlank() }
    val directUrl = url?.takeIf { source != AddonSourceType.MEDIA_FUSION && isDirectStreamUrl(it) }
    return com.tvonnet.debridxtreamiptv.data.repository.SourceAlternate(
        provider = (extras["providerName"] as? String) ?: getSourceLabel(source),
        sourceType = source.name,
        sourceName = extras["sourceName"] as? String,
        directSource = resolveDirectSource(this, ids, mediaFusionUrl, directUrl),
        headers = headers,
        identityKey = StreamIdentity.fileIdentity(this).key,
    )
}

// Filter out Addon error streams (e.g. MediaFusion/Torrentio API limits)
private fun isAddonErrorStream(addonStream: AddonStream): Boolean {
    val streamTitleLower = (addonStream.title ?: "").lowercase()
    val streamNameLower = (addonStream.extras["sourceName"] as? String ?: "").lowercase()
    val streamDescLower = (addonStream.extras["description"] as? String ?: "").lowercase()
    val combinedText = "$streamTitleLower $streamNameLower $streamDescLower"
    return ADDON_ERROR_MARKERS.any { combinedText.contains(it) }
}

/** The torrent-identity cluster for one stream: playable + addable forms, resolved once. */
private class TorrentIdentifiers(
    val validInfoHash: String?,
    val validMagnet: String?,
    val magnetInfoHash: String?,
    val addableTorrentIdentity: AddableTorrentIdentity?,
)

// Validate that we have a valid source (either url, infoHash or magnet)
private fun resolveTorrentIdentifiers(addonStream: AddonStream): TorrentIdentifiers {
    val validInfoHash = normalizeInfoHash(addonStream.infoHash)
    val validMagnet = addonStream.magnet?.takeIf { isValidMagnet(it) }
    val magnetInfoHash = extractInfoHashFromMagnet(validMagnet)
    val addableInfoHash = normalizeAddableInfoHash(addonStream.infoHash)
    val addableMagnet = addonStream.magnet?.takeIf { isAddableMagnet(it) }
    val addableMagnetInfoHash = extractAddableInfoHashFromMagnet(addableMagnet)
    val addableTorrentIdentity = (addableInfoHash ?: addableMagnetInfoHash)?.let { infoHash ->
        AddableTorrentIdentity(infoHash = infoHash, magnet = addableMagnet)
    }
    return TorrentIdentifiers(validInfoHash, validMagnet, magnetInfoHash, addableTorrentIdentity)
}

private fun convertOneStream(
    index: Int,
    addonStream: AddonStream,
    movieTitle: String,
    cacheStatusByHash: Map<String, DebridCacheStatus>
): MovieSource? {
    if (isAddonErrorStream(addonStream)) {
        Log.w(TAG, "[SKIP] Filtering out Addon error stream: provider=${addonStream.source}, hasTitle=${!addonStream.title.isNullOrBlank()}")
        return null
    }

    val ids = resolveTorrentIdentifiers(addonStream)
    val hasValidSource = !addonStream.url.isNullOrBlank() || ids.validInfoHash != null || ids.validMagnet != null
    if (!hasValidSource) {
        Log.w(TAG, "⚠️ [SKIP] Source skipped - no valid URL, magnet or infoHash")
        return null
    }

    // Construct the magnet link or use a direct URL (MediaFusion prefers direct URLs)
    val mediaFusionUrl = addonStream.url
        ?.takeIf { addonStream.source == AddonSourceType.MEDIA_FUSION && it.isNotBlank() }
    val directUrl = addonStream.url?.takeIf { addonStream.source != AddonSourceType.MEDIA_FUSION && isDirectStreamUrl(it) }
    val directSource = resolveDirectSource(addonStream, ids, mediaFusionUrl, directUrl)
    val streamId = streamIdFor(index, mediaFusionUrl, ids, addonStream)
    val providerLabel = (addonStream.extras["providerName"] as? String) ?: getSourceLabel(addonStream.source)
    val cacheStatus = resolveCacheStatus(
        addonStream = addonStream,
        mediaFusionUrl = mediaFusionUrl,
        directUrl = directUrl,
        infoHash = ids.validInfoHash ?: ids.magnetInfoHash,
        cacheStatusByHash = cacheStatusByHash
    )
    val codecTag = extractCodecTag(addonStream.title)

    // Enhanced label creation with rich metadata from extras
    val label = (addonStream.extras["displayTitle"] as? String)
        ?: buildSourceLabel(addonStream, providerLabel, movieTitle, codecTag)

    return buildMovieSource(
        index = index,
        addonStream = addonStream,
        movieTitle = movieTitle,
        facts = ConvertedStreamFacts(
            streamId = streamId,
            directSource = directSource,
            label = label,
            providerLabel = providerLabel,
            cacheStatus = cacheStatus,
            codecTag = codecTag,
            addableTorrentIdentity = ids.addableTorrentIdentity
        )
    )
}

/** The per-stream facts resolved by convertOneStream, bundled for the assembly step. */
private data class ConvertedStreamFacts(
    val streamId: String,
    val directSource: String?,
    val label: String,
    val providerLabel: String,
    val cacheStatus: DebridCacheStatus,
    val codecTag: String?,
    val addableTorrentIdentity: AddableTorrentIdentity?,
)

// Playable-source preference order, verbatim: MediaFusion url, then a direct-streamable
// url, then the magnet forms, then any raw url at all.
private fun resolveDirectSource(
    addonStream: AddonStream,
    ids: TorrentIdentifiers,
    mediaFusionUrl: String?,
    directUrl: String?
): String? = when {
    mediaFusionUrl != null -> mediaFusionUrl
    directUrl != null -> directUrl
    ids.validMagnet != null -> ids.validMagnet
    ids.validInfoHash != null -> "magnet:?xt=urn:btih:${ids.validInfoHash}"
    !addonStream.url.isNullOrBlank() -> addonStream.url
    else -> null
}

private fun streamIdFor(
    index: Int,
    mediaFusionUrl: String?,
    ids: TorrentIdentifiers,
    addonStream: AddonStream
): String = if (mediaFusionUrl != null) {
    mediaFusionUrl.hashCode().toString() + "_$index"
} else {
    // H5b: debrid-proxy rows (StremThru/Debridio "DIR") carry the torrent hash only in
    // the URL path — parse it (H0's rule) before falling back to the title hash, so
    // these rows share a real 40-hex identity with H4/H5 learned languages.
    val urlHash = StreamIdentity.urlHashAndIdx(addonStream.url)?.first
    (ids.validInfoHash ?: urlHash ?: addonStream.title.hashCode().toString()) + "_$index"
}

private fun buildSourceLabel(
    addonStream: AddonStream,
    providerLabel: String,
    movieTitle: String,
    codecTag: String?
): String = buildString {
    append(providerLabel)
    append(": ")
    append(addonStream.title ?: movieTitle)

    // Add language flag if available
    val languageFlag = addonStream.extras["languageFlag"] as? String
    if (languageFlag != null) {
        append(" $languageFlag")
    }

    // Add quality if available
    if (!addonStream.quality.isNullOrBlank() && addonStream.quality != "Unknown") {
        append(" ${addonStream.quality}")
    }

    if (!codecTag.isNullOrBlank()) {
        append(" $codecTag")
    }

    // Add size if available
    val sizeFormatted = addonStream.sizeBytes?.let { formatFileSize(it) }
    if (!sizeFormatted.isNullOrBlank()) {
        append(" ($sizeFormatted)")
    }

    // Add seeders info if available
    addonStream.seeders?.let { seeders ->
        append(" [$seeders seeders]")
    }

    // Add binge group indicator if available
    val bingeGroup = addonStream.extras["bingeGroup"] as? String
    if (!bingeGroup.isNullOrBlank()) {
        append(" 🔗")
    }

    // Add file index if available
    val fileIdx = addonStream.extras["fileIdx"] as? Int
    if (fileIdx != null) {
        append(" [File: $fileIdx]")
    }
}

private fun parseFileIdx(rawFileIdx: Any?): Int? = when (rawFileIdx) {
    is Int -> rawFileIdx
    is Number -> rawFileIdx.toInt()
    is String -> rawFileIdx.toIntOrNull()
    else -> null
}

private fun buildMovieSource(
    index: Int,
    addonStream: AddonStream,
    movieTitle: String,
    facts: ConvertedStreamFacts
): MovieSource {
    val (streamId, directSource, label, providerLabel, cacheStatus, codecTag, addableTorrentIdentity) = facts
    // Enhanced XtreamVodInfo with metadata
    return MovieSource(
        stream = com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo(
            stream_id = streamId,
            name = addonStream.title ?: "$movieTitle ${addonStream.quality}",
            direct_source = directSource,
            num = index + 1,
            stream_type = "movie",
            category_id = null,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            rating = null,
            rating_5based = null,
            added = null,
            cast = null,
            director = null,
            plot = addonStream.extras["description"] as? String, // Use description if available
            releaseDate = null,
            duration = addonStream.extras["duration"]?.let { formatDurationExtra(it) },
            genre = null,
            youtube_trailer = null,
            cover = null,
            rating_imdb = null,
            stream_icon = null
        ),
        category = null, // debrid is not an XtreamCategory
        label = label,
        isPrimary = index == 0,
        languages = addonStream.languages,
        quality = addonStream.quality,
        codec = codecTag,
        sizeBytes = addonStream.sizeBytes,
        seeders = addonStream.seeders,
        isCached = cacheStatus.toLegacyCachedFlag(),
        cacheStatus = cacheStatus,
        provider = providerLabel,
        sourceType = addonStream.source.name,
        sourceName = addonStream.extras["sourceName"] as? String,
        bingeGroup = addonStream.extras["bingeGroup"] as? String,
        fileIdx = parseFileIdx(addonStream.extras["fileIdx"]),
        headers = addonStream.headers,
        addableTorrentIdentity = addableTorrentIdentity
    )
}

private fun formatDurationExtra(raw: Any): String = when (raw) {
    is Long -> "${raw}s"
    is Number -> "${raw.toLong()}s"
    else -> raw.toString()
}

// Log conversion success statistics
private fun logConversionStats(addonStreams: List<AddonStream>, filteredSources: List<MovieSource>) {
    Log.e(TAG, "🎯 Final conversion: ${filteredSources.size} valid MovieSource objects")

    val successRate = if (addonStreams.isNotEmpty()) {
        (filteredSources.size.toDouble() / addonStreams.size * 100).toInt()
    } else 0
    Log.e(TAG, "📊 Success rate: $successRate% (${filteredSources.size}/${addonStreams.size}) sources converted")

    val qualityBreakdown = filteredSources.groupBy { source ->
        // Extract quality from label
        when {
            source.label.contains("4K") -> "4K"
            source.label.contains("1080p") -> "1080p"
            source.label.contains("720p") -> "720p"
            source.label.contains("480p") -> "480p"
            else -> "Other"
        }
    }
    Log.d(TAG, "🎨 Quality breakdown:")
    qualityBreakdown.forEach { (quality, sources) ->
        Log.d(TAG, "   - $quality: ${sources.size} sources")
    }
}

/**
 * Pull codec/format markers out of a release name so the picker can show
 * "4K HEVC" vs "4K REMUX" — the difference between a 5GB and a 60GB play.
 */
internal fun extractCodecTag(title: String?): String? {
    val text = title?.lowercase() ?: return null
    val parts = mutableListOf<String>()
    if (text.contains("remux")) parts.add("REMUX")
    when {
        Regex("hevc|[hx][ .\\-]?265").containsMatchIn(text) -> parts.add("HEVC")
        text.contains("av1") -> parts.add("AV1")
        Regex("avc\\b|[hx][ .\\-]?264").containsMatchIn(text) -> parts.add("H264")
    }
    when {
        Regex("\\b(dv|dovi)\\b|dolby[ .\\-]?vision").containsMatchIn(text) -> parts.add("DV")
        text.contains("hdr") -> parts.add("HDR")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

// Addon-proxy hosts / path markers whose http urls always stream directly.
private val DIRECT_URL_MARKERS = listOf(
    "mediafusion.elfhosted.com",
    "aiostreams.elfhosted.com",
    "/stremio/",
    "/stream/",
    "/play/",
    "/playback/",
)

// Playable container extensions. Invariant (C6): ".m4v" must be video here AND in
// TorrentFileMatcher's isVideoFile — the two lists must not drift apart.
private val DIRECT_STREAM_EXTENSIONS = listOf(
    ".mp4", ".mkv", ".m3u8", ".mpd", ".webm", ".m4v", ".mov", ".ts", ".m2ts",
)

internal fun isDirectStreamUrl(url: String): Boolean {
    val lowered = url.lowercase()
    if (!lowered.startsWith("http")) return false
    if (DIRECT_URL_MARKERS.any { lowered.contains(it) }) return true

    val clean = lowered.substringBefore('?').substringBefore('#')
    return DIRECT_STREAM_EXTENSIONS.any { clean.endsWith(it) }
}
