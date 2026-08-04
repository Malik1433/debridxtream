package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream

/**
 * H0: the one answer to "which real file is this stream?" (see
 * docs/reports/SOURCE_LIST_QUALITY_PLAN.md, P0).
 *
 * The identity deliberately contains NOTHING except what names the file itself:
 * no languages, no delivery (magnet vs direct), no provider, no bingeGroup —
 * those are presentation. Two addons offering the same torrent must produce the
 * SAME identity, or the picker shows the customer the same file twice and the
 * 10-hash cache-verification budget is spent on copies.
 *
 * Strength ladder:
 *  - [FileIdentity.Strong]  — an infoHash, from the field, the magnet, or parsed out of a
 *    debrid-proxy URL path (".../<40-hex>/<fileIdx>/", cf. StremioAddonFetcher's
 *    NO_FILE_INDEX_REGEX). `fileIdx` is part of the identity: one torrent can hold many
 *    files (a season pack), and different indices are different real files.
 *  - [FileIdentity.Weak]    — no hash anywhere: a normalised release name AND a 50 MB size
 *    bucket, both required. Collapsing two DIFFERENT files is far worse than showing two
 *    rows (the customer picks a row and gets the wrong film, and the H2 failover then
 *    retries the wrong file), so a single agreeing signal is not enough.
 *  - [FileIdentity.Unique]  — placeholder-titled or sizeless leftovers: never collapsed.
 */
sealed class FileIdentity {
    /** Stable string form usable as a map key. */
    abstract val key: String

    /**
     * [fileIdx] is normalised: a stream that declares no index counts as file 0 —
     * a single-file torrent IS file 0, and a magnet-only row for a pack resolves via
     * the RD chain to the torrent's main file anyway. Without this, the same movie
     * offered "by hash" and "by proxy URL .../0/" would refuse to collapse.
     */
    data class Strong(val hash: String, val fileIdx: Int) : FileIdentity() {
        override val key: String get() = "$hash#$fileIdx"
    }

    data class Weak(val nameKey: String, val sizeBucket: Long) : FileIdentity() {
        override val key: String get() = "weak:$nameKey@$sizeBucket"
    }

    data class Unique(val objectId: String) : FileIdentity() {
        override val key: String get() = "unique:$objectId"
    }
}

object StreamIdentity {

    // Debrid proxy (StremThru/Debridio "Torz") playback URLs carry the torrent hash and the
    // in-torrent file index as path segments: ".../<40-hex>/<fileIdx>/". The trailing
    // boundary mirrors StremioAddonFetcher.NO_FILE_INDEX_REGEX ("/-1(?:/|\?|$)").
    private val URL_HASH_SEGMENT = Regex("/([a-fA-F0-9]{40})(?:/(-?\\d+))?(?:/|\\?|$)")

    // Titles the fetchers substitute when the addon sent nothing usable
    // ("Stremio Stream" — StremioAddonFetcher; "Unknown" — DynamicAddonFetcher).
    // A shared placeholder must never make two different files "the same name".
    private val PLACEHOLDER_TITLES = setOf("stremio stream", "unknown", "")

    private const val SIZE_BUCKET_BYTES = 50L * 1024 * 1024 // 50 MB

    fun fileIdentity(stream: AddonStream): FileIdentity {
        val urlIdentity = urlHashAndIdx(stream.url)
        val hash = normalizeInfoHash(stream.infoHash)
            ?: extractInfoHashFromMagnet(stream.magnet)
            ?: urlIdentity?.first
        if (hash != null) {
            return FileIdentity.Strong(hash, fileIdxOf(stream) ?: urlIdentity?.second ?: 0)
        }

        val nameKey = normalizedNameKey(stream.title)
        val sizeBytes = stream.sizeBytes
        if (nameKey != null && sizeBytes != null && sizeBytes > 0) {
            return FileIdentity.Weak(nameKey, sizeBytes / SIZE_BUCKET_BYTES)
        }

        return FileIdentity.Unique(Integer.toHexString(System.identityHashCode(stream)))
    }

    /** The 40-hex hash + optional file index parsed from a proxy URL path, or null. */
    internal fun urlHashAndIdx(url: String?): Pair<String, Int?>? {
        if (url.isNullOrBlank()) return null
        val match = URL_HASH_SEGMENT.find(url) ?: return null
        val hash = match.groupValues[1].lowercase()
        val idx = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()
            // -1 means "no matching file" (the fetchers already drop those rows);
            // treat it as unknown rather than a real index.
            ?.takeIf { it >= 0 }
        return hash to idx
    }

    /** The addon-declared in-torrent file index, when present in extras. */
    internal fun fileIdxOf(stream: AddonStream): Int? {
        return when (val raw = stream.extras["fileIdx"]) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }?.takeIf { it >= 0 }
    }

    /**
     * A release name reduced to what survives addon formatting differences:
     * lowercase alphanumerics only. Null when blank or a known fetcher placeholder.
     */
    internal fun normalizedNameKey(title: String?): String? {
        val trimmed = title?.trim()?.lowercase() ?: return null
        if (trimmed in PLACEHOLDER_TITLES) return null
        val key = trimmed.replace(Regex("[^a-z0-9]+"), "")
        return key.takeIf { it.isNotBlank() }
    }
}
