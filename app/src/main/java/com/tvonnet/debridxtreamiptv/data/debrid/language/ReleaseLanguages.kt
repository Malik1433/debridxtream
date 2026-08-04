package com.tvonnet.debridxtreamiptv.data.debrid.language

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * H4: languages we KNOW a release carries, learned from the player's own track list
 * the first time the release actually plays (see PlayerEventListener.onTracksChanged).
 * Keyed by the release's stable identity — the debrid infoHash (or the hashless
 * fallback id) with the volatile `_<idx>` suffix stripped.
 *
 * `origin` records where the knowledge came from: "local" (this device played it) or,
 * from H5 on, "shared" (another user's device taught us via Firestore). Local
 * observations always outrank shared ones.
 */
@Entity(tableName = "release_languages")
data class ReleaseLanguageEntity(
    @PrimaryKey val hash: String,
    /** Comma-joined lowercase codes, e.g. "hi,en". */
    val languages: String,
    val origin: String,
    val updatedAt: Long,
) {
    companion object {
        const val ORIGIN_LOCAL = "local"
        const val ORIGIN_SHARED = "shared"
    }
}

@Dao
interface ReleaseLanguageDao {

    @Query("SELECT * FROM release_languages WHERE hash = :hash")
    suspend fun byHash(hash: String): ReleaseLanguageEntity?

    @Query("SELECT * FROM release_languages WHERE hash IN (:hashes)")
    suspend fun byHashes(hashes: List<String>): List<ReleaseLanguageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReleaseLanguageEntity)
}
