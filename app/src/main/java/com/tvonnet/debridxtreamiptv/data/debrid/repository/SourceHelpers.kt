package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus

/**
 * Phase 7 (UnifiedSourceProvider decomposition), step 1: the pure magnet / info-hash / size / cached-value
 * helpers lifted verbatim out of the UnifiedSourceProvider god-class. They hold no provider state, so they
 * move to top-level functions in the same `data.debrid.repository` package — every existing call site
 * resolves unchanged. Behaviour is byte-for-byte identical.
 */

private val ADDABLE_INFO_HASH = Regex("^(?:[a-fA-F0-9]{40}|[a-zA-Z2-7]{32})$")

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1fKB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1fMB".format(mb)
    val gb = mb / 1024.0
    if (gb < 1024) return "%.1fGB".format(gb)
    val tb = gb / 1024.0
    return "%.1fTB".format(tb)
}

internal fun normalizeInfoHash(infoHash: String?): String? {
    val trimmed = infoHash?.trim() ?: return null
    return if (trimmed.length >= 32) trimmed.lowercase() else null
}

internal fun isValidMagnet(magnet: String?): Boolean {
    if (magnet.isNullOrBlank()) return false
    val trimmed = magnet.trim()
    if (!trimmed.startsWith("magnet:", ignoreCase = true)) return false
    val btihIndex = trimmed.indexOf("btih:", ignoreCase = true)
    if (btihIndex == -1) return false
    val hash = trimmed.substring(btihIndex + 5).substringBefore('&').substringBefore('#')
    return hash.length >= 32
}

internal fun extractInfoHashFromMagnet(magnet: String?): String? {
    if (magnet.isNullOrBlank()) return null
    val btihIndex = magnet.indexOf("btih:", ignoreCase = true)
    if (btihIndex == -1) return null
    return normalizeInfoHash(magnet.substring(btihIndex + 5).substringBefore('&').substringBefore('#'))
}

internal fun normalizeAddableInfoHash(infoHash: String?): String? {
    val trimmed = infoHash?.trim() ?: return null
    return trimmed.takeIf { ADDABLE_INFO_HASH.matches(it) }?.lowercase()
}

internal fun isAddableMagnet(magnet: String?): Boolean {
    return extractAddableInfoHashFromMagnet(magnet) != null
}

internal fun extractAddableInfoHashFromMagnet(magnet: String?): String? {
    if (magnet.isNullOrBlank()) return null
    val trimmed = magnet.trim()
    if (!trimmed.startsWith("magnet:", ignoreCase = true)) return null
    val btihIndex = trimmed.indexOf("btih:", ignoreCase = true)
    if (btihIndex == -1) return null
    return normalizeAddableInfoHash(trimmed.substring(btihIndex + 5).substringBefore('&').substringBefore('#'))
}

internal fun DebridCacheStatus.toLegacyCachedFlag(): Boolean? {
    return when (this) {
        DebridCacheStatus.VERIFIED_CACHED,
        DebridCacheStatus.DIRECT_STREAM -> true
        DebridCacheStatus.NOT_CACHED -> false
        DebridCacheStatus.UNKNOWN -> null
    }
}

internal fun parseCachedValue(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        is Number -> value.toInt() != 0
        else -> null
    }
}
