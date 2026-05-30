package com.tvonnet.debridxtreamiptv.util

object SensitiveLogRedactor {
    private const val REDACTED_URL = "<redacted-url>"
    private const val REDACTED_TOKEN = "<redacted-token>"
    private const val REDACTED_MAGNET = "<redacted-magnet>"
    private const val REDACTED_HASH = "<redacted-hash>"
    private const val REDACTED_CREDENTIAL = "<redacted-credential>"

    fun describeUrl(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "missing"
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return REDACTED_MAGNET
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("stremio://", ignoreCase = true)
        ) {
            return REDACTED_URL
        }
        return "present(length=${trimmed.length})"
    }

    fun describeSecret(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) "missing" else "$REDACTED_CREDENTIAL(length=${trimmed.length})"
    }

    fun describeHash(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) "missing" else "$REDACTED_HASH(length=${trimmed.length})"
    }

    fun describeException(error: Throwable?): String {
        return error?.javaClass?.simpleName ?: "UnknownException"
    }

    fun redact(value: String?): String {
        val original = value?.trim().orEmpty()
        if (original.isEmpty()) return "missing"
        var redacted = original
            .replace(Regex("(?i)https?://[^\\s]+"), REDACTED_URL)
            .replace(Regex("(?i)stremio://[^\\s]+"), REDACTED_URL)
            .replace(Regex("(?i)magnet:\\?[^\\s]+"), REDACTED_MAGNET)
            .replace(Regex("(?i)realdebrid=[^|/\\s&]+"), "realdebrid=$REDACTED_TOKEN")
            .replace(Regex("(?i)(access_token|refresh_token|token|api[_-]?key)=[^&\\s]+"), REDACTED_TOKEN)
            .replace(Regex("(?i)(username|password)=[^&\\s]+"), REDACTED_CREDENTIAL)
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/]+=*"), "Bearer $REDACTED_TOKEN")

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
