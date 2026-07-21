package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo

/**
 * Phase 7: source/cache models lifted verbatim out of the XtreamRepository god-class. They stay in
 * the same `data.repository` package, so every existing reference resolves unchanged — this is a
 * pure relocation with no behaviour change.
 */

enum class DebridCacheStatus {
    VERIFIED_CACHED,
    NOT_CACHED,
    UNKNOWN,
    DIRECT_STREAM
}

data class AddableTorrentIdentity(
    val infoHash: String,
    val magnet: String? = null
)

data class MovieSource(
    val stream: XtreamVodInfo,
    val category: XtreamCategory?,
    val label: String,
    val isPrimary: Boolean,
    val languages: List<String>? = null,
    val quality: String? = null,
    val codec: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val isCached: Boolean? = null,
    val cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
    val provider: String? = null,
    val sourceType: String? = null,
    val sourceName: String? = null,
    val bingeGroup: String? = null,
    val fileIdx: Int? = null,
    val headers: Map<String, String>? = null,
    val addableTorrentIdentity: AddableTorrentIdentity? = null
)
