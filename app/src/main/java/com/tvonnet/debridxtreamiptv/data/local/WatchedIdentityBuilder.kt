package com.tvonnet.debridxtreamiptv.data.local

object WatchedIdentityBuilder {
    fun iptvMovie(
        streamId: String?,
        title: String? = null,
        year: String? = null
    ): String {
        val stableStreamId = streamId.cleaned()
        return if (stableStreamId != null) {
            "movie:xtream:stream:$stableStreamId"
        } else {
            "movie:xtream:title:${normalizeKeyPart(title)}:year:${yearKey(year)}"
        }
    }

    fun debridMovie(
        tmdbId: String? = null,
        imdbId: String? = null,
        title: String? = null,
        year: String? = null
    ): String {
        val stableTmdbId = tmdbId.cleaned()
        val stableImdbId = imdbId.cleaned()
        return when {
            stableTmdbId != null -> "movie:debrid:tmdb:$stableTmdbId"
            stableImdbId != null -> "movie:debrid:imdb:$stableImdbId"
            else -> "movie:debrid:title:${normalizeKeyPart(title)}:year:${yearKey(year)}"
        }
    }

    fun iptvEpisode(
        episodeId: String?,
        seriesId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        fallbackDiscriminator: String? = null
    ): String {
        val stableEpisodeId = episodeId.cleaned()
        return if (stableEpisodeId != null) {
            "episode:xtream:episode:$stableEpisodeId"
        } else {
            "episode:xtream:series:${normalizeKeyPart(seriesId)}${episodeNumberSuffix(seasonNumber, episodeNumber, fallbackDiscriminator)}"
        }
    }

    fun debridEpisode(
        seriesIdentity: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        seriesTitle: String? = null,
        seriesYear: String? = null,
        fallbackDiscriminator: String? = null
    ): String {
        val stableSeriesIdentity = seriesIdentity.cleaned()
        return if (stableSeriesIdentity != null) {
            "episode:debrid:series:$stableSeriesIdentity${episodeNumberSuffix(seasonNumber, episodeNumber, fallbackDiscriminator)}"
        } else {
            "episode:debrid:title:${normalizeKeyPart(seriesTitle)}:year:${yearKey(seriesYear)}${episodeNumberSuffix(seasonNumber, episodeNumber, fallbackDiscriminator)}"
        }
    }

    fun normalizeKeyPart(value: String?): String {
        val normalized = value
            .orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        return normalized.ifEmpty { UNKNOWN }
    }

    fun stableIdPart(value: String?): String? = value.cleaned()

    private fun String?.cleaned(): String? {
        val value = this?.trim().orEmpty()
        if (isUnsafeIdentity(value)) return null
        return value.ifEmpty { null }
    }

    private fun yearKey(year: String?): String = normalizeKeyPart(year)

    private fun numberKey(number: Int?): String = number?.takeIf { it > 0 }?.toString() ?: UNKNOWN

    private fun episodeNumberSuffix(
        seasonNumber: Int?,
        episodeNumber: Int?,
        fallbackDiscriminator: String?
    ): String {
        val seasonKey = numberKey(seasonNumber)
        val episodeKey = numberKey(episodeNumber)
        val base = ":s:$seasonKey:e:$episodeKey"
        if (seasonKey != UNKNOWN && episodeKey != UNKNOWN) return base
        return "$base:fallback:${normalizeKeyPart(fallbackDiscriminator)}"
    }

    private fun isUnsafeIdentity(value: String): Boolean {
        if (value.isBlank()) return false
        val lower = value.lowercase()
        if ("://" in lower || lower.startsWith("magnet:")) return true
        if (CREDENTIAL_MARKERS.any { it in lower }) return true
        return INFO_HASH_HEX.matches(value) || INFO_HASH_BASE32.matches(value)
    }

    private val CREDENTIAL_MARKERS = listOf(
        "token=", "access_token=", "username=", "password=",
        "apikey=", "api_key=", "authorization="
    )

    private const val UNKNOWN = "unknown"
    private val INFO_HASH_HEX = Regex("^[a-fA-F0-9]{40}$")
    private val INFO_HASH_BASE32 = Regex("^[A-Z2-7a-z]{32}$")
}
