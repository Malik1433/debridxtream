package com.tvonnet.debridxtreamiptv.ui.trailer

import java.net.URI

object TrailerValueParser {
    data class ParsedTrailer(val youtubeId: String)

    private val youtubeIdRegex = Regex("^[A-Za-z0-9_-]{8,24}$")

    fun parse(value: String?): ParsedTrailer? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        extractYouTubeId(trimmed)?.let { id ->
            return ParsedTrailer(id)
        }

        return null
    }

    fun extractYouTubeId(value: String): String? {
        if (isValidYouTubeId(value)) return value

        val normalized = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            value
        } else {
            "https://$value"
        }

        return runCatching {
            val uri = URI(normalized)
            val host = uri.host.orEmpty().lowercase()
            when {
                host == "youtu.be" || host.endsWith(".youtu.be") -> {
                    uri.path.trim('/').substringBefore('/').takeIf(::isValidYouTubeId)
                }
                host == "youtube.com" || host.endsWith(".youtube.com") -> {
                    extractQueryParameter(uri.rawQuery, "v")?.takeIf(::isValidYouTubeId)
                        ?: extractPathId(uri.path)?.takeIf(::isValidYouTubeId)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun extractPathId(path: String?): String? {
        val parts = path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return when (parts[0].lowercase()) {
            "embed", "shorts", "live" -> parts[1]
            else -> null
        }
    }

    private fun extractQueryParameter(query: String?, key: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun isValidYouTubeId(value: String): Boolean {
        return youtubeIdRegex.matches(value)
    }
}
