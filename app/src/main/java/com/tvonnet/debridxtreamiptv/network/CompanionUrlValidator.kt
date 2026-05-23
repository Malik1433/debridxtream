package com.tvonnet.debridxtreamiptv.network

import java.net.URI

object CompanionUrlValidator {
    fun normalizeSafeHttpUrl(candidate: String): String? {
        val normalized = candidate
            .trim()
            .trimEnd('/')

        return if (isSafeHttpUrl(normalized)) normalized else null
    }

    fun normalizeSafeAddonUrl(candidate: String): String? {
        val normalized = candidate
            .trim()
            .replaceFirst("stremio://", "https://", ignoreCase = true)
            .trimEnd('/')

        return if (isSafeHttpUrl(normalized)) normalized else null
    }

    fun normalizeSafeAddonUrls(candidates: Collection<String>?): List<String> {
        return candidates
            ?.mapNotNull { normalizeSafeAddonUrl(it) }
            ?.distinct()
            .orEmpty()
    }

    fun isSafeHttpUrl(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.isBlank() || trimmed.any { it.isISOControl() }) return false

        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!parsed.userInfo.isNullOrBlank()) return false

        val host = parsed.host?.trim().orEmpty()
        return host.isNotEmpty()
    }
}
