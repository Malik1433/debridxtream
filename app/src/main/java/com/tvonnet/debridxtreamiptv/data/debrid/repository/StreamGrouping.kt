package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream

/**
 * H1: collapse the addon flood into one row per REAL file, keeping the copies.
 *
 * A duplicate is not noise to discard (owner decision): the copy count is a reliability
 * signal and the alternates are the H2 failover path — a dead link retries the same file
 * from another addon before the file is given up on.
 *
 * MUST run before DebridCacheVerifier.verifyRealDebridCacheStatuses: the verifier only
 * checks the first 10 hashes, and on a duplicate-heavy list those checks were being spent
 * on copies of the same 3-4 files.
 */
data class StreamGroup(
    val identity: FileIdentity,
    val primary: AddonStream,
    val alternates: List<AddonStream>,
)

/**
 * Groups by [StreamIdentity.fileIdentity], preserving first-seen order of groups.
 *
 * Primary within a group: highest [metadataScore] first (the row the picker shows should
 * carry the richest metadata), then a direct URL over a magnet — the real distinction the
 * old variant key preserved (a proxy URL streams immediately; a magnet goes through the
 * RD resolution chain) without spending a picker row on it. [reliability] is a hook for
 * H6's per-addon score; until then every addon is neutral.
 */
fun groupStreamsByFile(
    streams: List<AddonStream>,
    reliability: (AddonStream) -> Double = { NEUTRAL_RELIABILITY },
): List<StreamGroup> {
    if (streams.isEmpty()) return emptyList()

    val byIdentity = LinkedHashMap<String, MutableList<Pair<FileIdentity, AddonStream>>>()
    for (stream in streams) {
        val identity = StreamIdentity.fileIdentity(stream)
        byIdentity.getOrPut(identity.key) { mutableListOf() }.add(identity to stream)
    }

    return byIdentity.values.map { members ->
        val identity = members.first().first
        val ordered = members.map { it.second }.sortedWith(
            compareByDescending<AddonStream> { reliability(it) }
                .thenByDescending { metadataScore(it) }
                .thenByDescending { if (it.url.isNullOrBlank()) 0 else 1 }
        )
        StreamGroup(
            identity = identity,
            primary = ordered.first(),
            alternates = ordered.drop(1),
        )
    }
}

/** Alternates keyed by their group's primary, for the conversion step. */
fun alternatesByPrimary(groups: List<StreamGroup>): Map<AddonStream, List<AddonStream>> =
    groups.asSequence()
        .filter { it.alternates.isNotEmpty() }
        .associate { it.primary to it.alternates }

internal const val NEUTRAL_RELIABILITY = 0.5
