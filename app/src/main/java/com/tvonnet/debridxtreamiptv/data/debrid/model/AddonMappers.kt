package com.tvonnet.debridxtreamiptv.data.debrid.model

import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor

/**
 * Helpers to map raw add-on payloads into a normalized AddonStream representation.
 */
object AddonStreamMapper {

    fun fromMediaFusion(source: MediaFusionStream): AddonStream {
        // Parse languages if not provided by API
        val parsedLanguages = if (source.languages.isNullOrEmpty()) {
            val textToAnalyze = listOfNotNull(
                source.title,
                source.description,
                source.name,
                source.behaviorHints?.filename
            ).joinToString(" ")
            com.tvonnet.debridxtreamiptv.data.debrid.util.LanguageParser.extractLanguages(textToAnalyze)
        } else {
            source.languages
        }

        // Generate language flag emoji
        val languageFlag = if (parsedLanguages.isNotEmpty()) {
            com.tvonnet.debridxtreamiptv.data.debrid.util.LanguageParser.getLanguageFlag(parsedLanguages)
        } else {
            null
        }

        val headers = source.behaviorHints?.proxyHeaders ?: source.behaviorHints?.headers

        val extras = mutableMapOf<String, Any?>()
        source.description?.let { extras["description"] = it }
        source.name?.let { extras["sourceName"] = it }
        source.behaviorHints?.let {
            extras["videoQuality"] = it.videoQuality
            extras["videoCodec"] = it.videoCodec
            extras["duration"] = it.durationSeconds
        }
        languageFlag?.let { extras["languageFlag"] = it }

        return AddonStream(
            source = AddonSourceType.MEDIA_FUSION,
            title = try {
                source.behaviorHints?.filename
            } catch (e: Exception) {
                null
            } ?: source.title ?: source.name,

            url = source.url?.let { originalUrl ->
                val processed = originalUrl.replace("stremio://", "https://")
                android.util.Log.d(
                    "AddonMapper",
                    "Mapping URL: original=${SensitiveLogRedactor.describeUrl(originalUrl)} processed=${SensitiveLogRedactor.describeUrl(processed)}"
                )
                processed
            },
            infoHash = source.infoHash,
            magnet = source.magnet,
            sizeBytes = source.sizeBytes ?: source.videoSize,
            seeders = source.seeders,
            peers = source.peers,
            quality = source.quality ?: source.behaviorHints?.videoQuality,
            languages = parsedLanguages,
            subtitles = source.subtitles,
            extras = extras,
            headers = headers
        )
    }

    fun fromMediaFusionExpanded(source: MediaFusionStream): List<AddonStream> {
        val urls = buildMediaFusionUrls(source)
        if (urls.isEmpty()) {
            return listOf(fromMediaFusion(source))
        }

        return urls.mapIndexed { index, url ->
            val mapped = fromMediaFusion(source.copy(url = url))
            val extras = if (urls.size > 1) {
                mapped.extras + mapOf(
                    "sourceIndex" to index + 1,
                    "sourceTotal" to urls.size
                )
            } else {
                mapped.extras
            }
            mapped.copy(extras = extras)
        }
    }

    fun fromZilean(source: ZileanStream): AddonStream {
        val extras = buildMap<String, Any?> {
            source.rawTitle?.let { put("rawTitle", it) }
        }

        return AddonStream(
            source = AddonSourceType.ZILEAN,
            title = source.title ?: source.rawTitle,
            infoHash = source.infoHash,
            magnet = source.magnet,
            sizeBytes = source.sizeBytes,
            seeders = source.seeders,
            peers = source.peers,
            quality = source.quality,
            languages = source.languages ?: source.audioLanguages,
            subtitles = source.subtitles,
            extras = extras
        )
    }

    private fun buildMediaFusionUrls(source: MediaFusionStream): List<String> {
        val candidates = mutableListOf<String>()
        source.url?.let { candidates.add(it) }
        source.sources?.let { candidates.addAll(it) }

        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace("stremio://", "https://") }
            .distinct()
    }
}
