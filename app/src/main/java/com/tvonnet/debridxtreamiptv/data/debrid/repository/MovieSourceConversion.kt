package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.repository.AddableTorrentIdentity
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

private const val TAG = "UnifiedSourceProvider"

/**
 * Convert AddonStream objects to MovieSource objects with enhanced metadata support
 */
internal fun convertAddonStreamsToMovieSources(
    addonStreams: List<AddonStream>,
    title: String?,
    cacheStatusByHash: Map<String, DebridCacheStatus>
): List<MovieSource> {
    val movieTitle = title ?: "Unknown Movie"
    Log.d(TAG, "🔄 Converting ${addonStreams.size} enhanced addon streams to MovieSource objects")

    return addonStreams.mapIndexedNotNull { index, addonStream ->
        // Filter out Addon error streams (e.g. MediaFusion/Torrentio API limits)
        val streamTitleLower = (addonStream.title ?: "").lowercase()
        val streamNameLower = (addonStream.extras["sourceName"] as? String ?: "").lowercase()
        val streamDescLower = (addonStream.extras["description"] as? String ?: "").lowercase()
        val combinedText = "$streamTitleLower $streamNameLower $streamDescLower"
        
        val errorMarkers = listOf(
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
        if (errorMarkers.any { combinedText.contains(it) }) {
            Log.w(TAG, "[SKIP] Filtering out Addon error stream: provider=${addonStream.source}, hasTitle=${!addonStream.title.isNullOrBlank()}")
            return@mapIndexedNotNull null
        }

        // Validate that we have a valid source (either url, infoHash or magnet)
        val validInfoHash = normalizeInfoHash(addonStream.infoHash)
        val validMagnet = addonStream.magnet?.takeIf { isValidMagnet(it) }
        val magnetInfoHash = extractInfoHashFromMagnet(validMagnet)
        val addableInfoHash = normalizeAddableInfoHash(addonStream.infoHash)
        val addableMagnet = addonStream.magnet?.takeIf { isAddableMagnet(it) }
        val addableMagnetInfoHash = extractAddableInfoHashFromMagnet(addableMagnet)
        val addableTorrentIdentity = (addableInfoHash ?: addableMagnetInfoHash)?.let { infoHash ->
            AddableTorrentIdentity(infoHash = infoHash, magnet = addableMagnet)
        }
        val hasValidSource = !addonStream.url.isNullOrBlank() || validInfoHash != null || validMagnet != null

        if (!hasValidSource) {
            Log.w(TAG, "⚠️ [SKIP] Source skipped - no valid URL, magnet or infoHash")
            return@mapIndexedNotNull null
        }

        // Construct the magnet link or use a direct URL (MediaFusion prefers direct URLs)
        val mediaFusionUrl = addonStream.url
            ?.takeIf { addonStream.source == AddonSourceType.MEDIA_FUSION && it.isNotBlank() }
        val directUrl = addonStream.url?.takeIf { addonStream.source != AddonSourceType.MEDIA_FUSION && isDirectStreamUrl(it) }
        val directSource = when {
            mediaFusionUrl != null -> mediaFusionUrl
            directUrl != null -> directUrl
            validMagnet != null -> validMagnet
            validInfoHash != null -> "magnet:?xt=urn:btih:$validInfoHash"
            !addonStream.url.isNullOrBlank() -> addonStream.url
            else -> null
        }

        val streamId = if (mediaFusionUrl != null) {
            mediaFusionUrl.hashCode().toString() + "_$index"
        } else {
            (validInfoHash ?: addonStream.title.hashCode().toString()) + "_$index"
        }
        val providerLabel = (addonStream.extras["providerName"] as? String) ?: getSourceLabel(addonStream.source)
        val sourceName = addonStream.extras["sourceName"] as? String
        val bingeGroup = addonStream.extras["bingeGroup"] as? String
        val fileIdx = when (val rawFileIdx = addonStream.extras["fileIdx"]) {
            is Int -> rawFileIdx
            is Number -> rawFileIdx.toInt()
            is String -> rawFileIdx.toIntOrNull()
            else -> null
        }
        val cacheStatus = resolveCacheStatus(
            addonStream = addonStream,
            mediaFusionUrl = mediaFusionUrl,
            directUrl = directUrl,
            infoHash = validInfoHash ?: magnetInfoHash,
            cacheStatusByHash = cacheStatusByHash
        )
        val cachedFlag = cacheStatus.toLegacyCachedFlag()
        val codecTag = extractCodecTag(addonStream.title)

        // Enhanced label creation with rich metadata from extras
        val label = (addonStream.extras["displayTitle"] as? String)
            ?: buildString {
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

        // Enhanced XtreamVodInfo with metadata
        MovieSource(
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
                duration = addonStream.extras["duration"]?.let {
                    when (it) {
                        is Long -> "${it}s"
                        is Number -> "${it.toLong()}s"
                        else -> it.toString()
                    }
                },
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
            isCached = cachedFlag,
            cacheStatus = cacheStatus,
            provider = providerLabel,
            sourceType = addonStream.source.name,
            sourceName = sourceName,
            bingeGroup = bingeGroup,
            fileIdx = fileIdx,
            headers = addonStream.headers,
            addableTorrentIdentity = addableTorrentIdentity
        )
    }.also { filteredSources ->
        Log.e(TAG, "🎯 Final conversion: ${filteredSources.size} valid MovieSource objects")

        // Log conversion success statistics
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

/**
 * Format file size in bytes to human readable format
 */
internal fun isDirectStreamUrl(url: String): Boolean {
    val lowered = url.lowercase()
    if (!lowered.startsWith("http")) return false
    if (lowered.contains("mediafusion.elfhosted.com")) return true
    if (lowered.contains("aiostreams.elfhosted.com")) return true
    if (lowered.contains("/stremio/")) return true
    if (lowered.contains("/stream/")) return true
    if (lowered.contains("/play/")) return true
    if (lowered.contains("/playback/")) return true

    val clean = lowered.substringBefore('?').substringBefore('#')
    return clean.endsWith(".mp4") ||
        clean.endsWith(".mkv") ||
        clean.endsWith(".m3u8") ||
        clean.endsWith(".mpd") ||
        clean.endsWith(".webm") ||
        clean.endsWith(".m4v") ||
        clean.endsWith(".mov") ||
        clean.endsWith(".ts") ||
        clean.endsWith(".m2ts")
}
