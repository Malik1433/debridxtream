package com.tvonnet.debridxtreamiptv.data.debrid.language

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * H4: single owner of the learned-languages table. Local playback observations
 * are authoritative: once this device has HEARD the release's audio tracks,
 * a shared (H5) record may never overwrite them.
 */
@Singleton
class ReleaseLanguageRepository @Inject constructor(
    private val dao: ReleaseLanguageDao,
) {

    /**
     * Record what the player actually exposed for this release. Merges with any
     * previously learned codes (a re-play that surfaces fewer tracks — e.g. a
     * codec-limited session — must not erase knowledge).
     */
    suspend fun recordVerified(hash: String, languages: List<String>) = withContext(Dispatchers.IO) {
        if (hash.isBlank() || languages.isEmpty()) return@withContext
        val existing = dao.byHash(hash)
        val merged = buildSet {
            if (existing?.origin == ReleaseLanguageEntity.ORIGIN_LOCAL) {
                addAll(existing.languages.split(',').filter { it.isNotBlank() })
            }
            addAll(languages)
        }.sorted()
        dao.upsert(
            ReleaseLanguageEntity(
                hash = hash,
                languages = merged.joinToString(","),
                origin = ReleaseLanguageEntity.ORIGIN_LOCAL,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Batch lookup for the source-list overlay: hash -> learned language codes. */
    suspend fun verifiedLanguagesFor(hashes: Collection<String>): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            if (hashes.isEmpty()) return@withContext emptyMap()
            hashes.distinct()
                .chunked(SQLITE_IN_LIMIT)
                .flatMap { dao.byHashes(it) }
                .associate { row -> row.hash to row.languages.split(',').filter { it.isNotBlank() } }
        }

    private companion object {
        // SQLite's bound-variable ceiling is 999; stay far under it per IN clause.
        const val SQLITE_IN_LIMIT = 900
    }
}
