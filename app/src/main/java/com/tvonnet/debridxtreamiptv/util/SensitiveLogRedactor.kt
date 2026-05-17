package com.tvonnet.debridxtreamiptv.util

import java.net.URI

object SensitiveLogRedactor {
    private const val REDACTED = "[redacted]"

    fun describeUrl(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "missing"
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return "magnet:$REDACTED"
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("stremio://", ignoreCase = true)
        ) {
            val normalized = trimmed.replaceFirst("stremio://", "https://", ignoreCase = true)
            return runCatching {
                val uri = URI(normalized)
                val scheme = uri.scheme ?: "url"
                val host = uri.host ?: "unknown-host"
                "$scheme://$host/$REDACTED"
            }.getOrDefault("url:$REDACTED")
        }
        return "present(length=${trimmed.length})"
    }

    fun describeSecret(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) "missing" else "present(length=${trimmed.length})"
    }

    fun describeHash(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) "missing" else "present(length=${trimmed.length})"
    }

    fun redact(value: String?): String {
        val original = value?.trim().orEmpty()
        if (original.isEmpty()) return "missing"
        var redacted = original
            .replace(Regex("(?i)(realdebrid=)[^|/\\s&]+"), "\$1$REDACTED")
            .replace(Regex("(?i)(access_token=)[^&\\s]+"), "\$1$REDACTED")
            .replace(Regex("(?i)(refresh_token=)[^&\\s]+"), "\$1$REDACTED")
            .replace(Regex("(?i)(token=)[^&\\s]+"), "\$1$REDACTED")
            .replace(Regex("(?i)(api[_-]?key=)[^&\\s]+"), "\$1$REDACTED")
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/]+=*"), "Bearer $REDACTED")
            .replace(Regex("(?i)magnet:\\?[^\\s]+"), "magnet:$REDACTED")

        if (looksLikeSensitiveUrl(redacted)) {
            redacted = describeUrl(redacted)
        }
        return redacted
    }

    private fun looksLikeSensitiveUrl(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("stremio://") ||
            lower.startsWith("magnet:")
    }
}
