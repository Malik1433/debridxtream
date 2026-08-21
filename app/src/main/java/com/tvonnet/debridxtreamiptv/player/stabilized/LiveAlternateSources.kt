package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner

/**
 * The other feeds of the channel the customer is watching.
 *
 * A provider carries the same channel several times — different bitrate, different server, same
 * programme. When one of those feeds dies the app used to stop and tell the customer to "try
 * another channel or source", which is asking them to do by hand the one thing it is in a better
 * position to do itself: it knows the name, it has the catalogue, and it can try the next feed
 * before they have finished reading the error.
 *
 * Pure list logic, so it is testable without a network: [rank] decides WHICH feeds are candidates
 * and in what order; the caller does the searching and the tuning.
 */
internal object LiveAlternateSources {

    /** How many to keep. Beyond a handful, a customer would rather pick than watch us guess. */
    private const val MAX_ALTERNATES = 4

    /**
     * @param currentName the channel as the customer sees it now
     * @param currentStreamId the feed that just failed
     * @param candidates whatever a name search returned, unfiltered
     * @param alreadyTried feeds tried in this sitting, which must not come round again
     */
    fun rank(
        currentName: String?,
        currentStreamId: String?,
        candidates: List<XtreamStream>,
        alreadyTried: Set<String>,
    ): List<XtreamStream> = candidates
        .asSequence()
        .filter { it.stream_id != null && it.stream_id != currentStreamId }
        .filter { it.stream_id !in alreadyTried }
        .filter { LiveChannelIdentity.isSameChannel(currentName, it.name) }
        .distinctBy { it.stream_id }
        // Prefer a feed that ADVERTISES a quality tier: on this catalogue an "HD"/"FHD" entry is
        // usually the maintained one, and the bare name the leftover. It is a weak signal, so it
        // only orders the list — it never decides membership.
        .sortedByDescending { qualityRank(it.name) }
        .take(MAX_ALTERNATES)
        .toList()

    /** What to search for: the channel's name without the tags, which is what a search matches on. */
    fun searchTerm(currentName: String?): String = MediaTitleCleaner.clean(currentName).trim()

    private fun qualityRank(name: String?): Int {
        val lower = name?.lowercase().orEmpty()
        return when {
            lower.contains("uhd") || lower.contains("4k") -> 3
            lower.contains("fhd") || lower.contains("1080") -> 2
            lower.contains("hd") -> 1
            else -> 0
        }
    }
}
