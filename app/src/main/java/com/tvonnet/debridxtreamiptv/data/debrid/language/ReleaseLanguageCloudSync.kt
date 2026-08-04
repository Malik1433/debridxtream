package com.tvonnet.debridxtreamiptv.data.debrid.language

import android.util.Log
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * H5: the community half of learned languages — `release_languages/{infoHash}` in
 * Firestore. One user's playback teaches every device.
 *
 * Languages are stored as a MAP (`{en: true, hi: true}`) rather than an array:
 * `set(merge)` on a map is a native set-union, so codes only ever widen and no
 * client can shrink somebody else's observation. Publish is fire-and-forget,
 * fetch is a batched whereIn on document ids.
 *
 * Only real 40-hex infoHashes are shared. Hashless releases fall back to
 * title.hashCode ids that are app-instance-specific and would poison a global table.
 *
 * Nothing here may ever delay or break the source sheet: fetch failures return empty,
 * publish failures are logged and dropped, and the caller bounds fetch with a timeout.
 */
@Singleton
class ReleaseLanguageCloudSync @Inject constructor() {

    /** Fire-and-forget: merge this device's observation into the shared record. */
    fun publish(hash: String, languages: Collection<String>) {
        if (!INFO_HASH_40.matches(hash) || languages.isEmpty()) return
        try {
            FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(hash)
                .set(
                    mapOf(
                        "languages" to languages.associateWith { true },
                        "reports" to FieldValue.increment(1),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                )
                .addOnFailureListener { e -> Log.i(TAG, "H5 publish unavailable (${e.message})") }
        } catch (e: Exception) {
            // A misconfigured/absent Firebase component must never reach the player path.
            Log.w(TAG, "H5 publish skipped", e)
        }
    }

    /**
     * Batched lookup of shared records: hash -> language codes. Non-hash ids are
     * ignored; any failure degrades to "nothing known".
     */
    suspend fun fetch(hashes: Collection<String>): Map<String, List<String>> {
        val ids = hashes.filter { INFO_HASH_40.matches(it) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            val db = FirebaseFirestore.getInstance()
            ids.chunked(WHERE_IN_LIMIT)
                .flatMap { chunk -> fetchChunk(db, chunk) }
                .toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "H5 fetch unavailable (${e.message})")
            emptyMap()
        }
    }

    private suspend fun fetchChunk(
        db: FirebaseFirestore,
        chunk: List<String>
    ): List<Pair<String, List<String>>> =
        db.collection(COLLECTION)
            .whereIn(FieldPath.documentId(), chunk)
            .get()
            .await()
            .documents
            .mapNotNull { doc ->
                val langs = (doc.get("languages") as? Map<*, *>)
                    ?.keys
                    ?.filterIsInstance<String>()
                    ?.filter { it.isNotBlank() }
                if (langs.isNullOrEmpty()) null else doc.id to langs
            }

    companion object {
        private const val TAG = "ReleaseLanguageCloud"
        const val COLLECTION = "release_languages"
        /** Firestore whereIn accepts at most 10 values per query. */
        private const val WHERE_IN_LIMIT = 10
        private val INFO_HASH_40 = Regex("^[a-fA-F0-9]{40}$")
    }
}
