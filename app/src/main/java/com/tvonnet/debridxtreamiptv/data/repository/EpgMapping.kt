package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Base64
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpgListing

/**
 * Phase 7: pure EPG leaf-mappers lifted verbatim out of the XtreamRepository god-class. These touch
 * no session/cache state — they only convert a raw [XtreamEpgListing] into an [EpgEntity] and decode
 * the Base64-or-plain text some Xtream servers return. Same `data.repository` package, so the
 * short-EPG call site resolves unchanged. Behaviour is byte-for-byte identical; moving them to
 * top-level also makes them unit-testable (they were private before).
 */

internal fun XtreamEpgListing.toEpgEntityOrNull(channelKey: String): EpgEntity? {
    val startSec = startTimestamp?.toLongOrNull() ?: return null
    val stopSec = stopTimestamp?.toLongOrNull() ?: return null
    val startMs = startSec * 1000L
    val stopMs = stopSec * 1000L
    if (stopMs <= 0L || stopMs <= startMs) return null

    return EpgEntity(
        channelId = channelKey,
        start = startMs,
        stop = stopMs,
        title = decodeBase64IfPossible(title),
        description = decodeBase64IfPossible(description),
        category = null
    )
}

internal fun decodeBase64IfPossible(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        val decoded = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        decoded.takeIf { it.isNotBlank() } ?: raw
    } catch (_: Exception) {
        raw
    }
}
