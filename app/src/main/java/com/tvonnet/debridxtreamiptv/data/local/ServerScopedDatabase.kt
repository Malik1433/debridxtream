package com.tvonnet.debridxtreamiptv.data.local

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.prefs.ServerIdentity
import java.io.File

/**
 * Which database file this device's ACTIVE provider uses (Option A).
 *
 * Option B made a change of provider safe by throwing the old provider's data away. That is correct
 * and it is also expensive: the customer runs three servers, and every switch cost a full re-sync
 * and lost that server's resume list and favourites for good.
 *
 * The fix is isolation rather than deletion — each provider gets its own file, so switching is
 * reopening something that is already there.
 *
 * **Why a file and not a column.** The obvious design is a `serverId` on every row. That would mean
 * adding the scope to 111 DAO queries, widening 10 primary keys (a stream id is only unique WITHIN
 * a provider) and threading it through 17 raw-query builders — and a single missed query is a
 * silent cross-provider leak, which is exactly the class of bug this whole effort exists to remove.
 * A separate file cannot leak: there is no query that can reach the other provider's rows because
 * they are not in the database that is open.
 *
 * The cost, stated plainly: each provider keeps its own catalogue on disk. The owner's 141k-title
 * server takes about 30MB, so three of them is roughly 100MB.
 */
object ServerScopedDatabase {

    private const val TAG = "ServerScopedDatabase"

    /** The single-provider file every install had before this existed. */
    const val LEGACY_NAME: String = AppDatabase.DATABASE_NAME

    /**
     * The provider THIS PROCESS opened its database for.
     *
     * The database and every DAO are bound once per process, so this is the honest answer to "whose
     * data is this app currently holding" — and it is not the same question as "whose data should
     * it be holding", which the credentials answer and which can change while the app runs.
     *
     * Comparing the two is what makes a restart-on-switch safe: without it the new process would
     * still see the fingerprints disagreeing and restart itself again, for ever.
     */
    @Volatile
    var processFingerprint: String = ServerIdentity.NONE
        private set

    /**
     * @return the file name for [fingerprint] — the legacy name when there is no provider yet, so a
     *   device that has not signed in anywhere still has somewhere to write.
     */
    fun nameFor(fingerprint: String): String =
        if (fingerprint == ServerIdentity.NONE) LEGACY_NAME else "${LEGACY_NAME}_$fingerprint"

    /**
     * Hands the current provider the data this device already has.
     *
     * Every existing install has one database, under the legacy name, holding the catalogue of the
     * provider it is signed into. Without this it would open a fresh empty file after the update
     * and re-sync the whole catalogue for no reason — an app UPDATE reading as data loss, which is
     * the exact failure the fingerprint adoption was written to prevent on the other side.
     *
     * Renamed rather than copied: a 30MB copy on the main thread at launch is not acceptable, and a
     * rename is atomic. WAL and SHM travel with it — leaving them behind would make SQLite reject
     * the file it is now paired with.
     *
     * Idempotent: once the scoped file exists, the legacy one is never touched again.
     */
    fun adoptLegacyDatabase(context: Context, fingerprint: String) {
        processFingerprint = fingerprint
        if (fingerprint == ServerIdentity.NONE) return
        val scoped = context.getDatabasePath(nameFor(fingerprint))
        if (scoped.exists()) return
        val legacy = context.getDatabasePath(LEGACY_NAME)
        if (!legacy.exists()) return

        val renamed = SIDECARS.all { suffix ->
            val from = File(legacy.path + suffix)
            // A missing sidecar is normal — WAL and SHM only exist while the database is open.
            !from.exists() || from.renameTo(File(scoped.path + suffix))
        } && legacy.renameTo(scoped)

        if (renamed) {
            Log.i(TAG, "Adopted the existing database for the active provider")
        } else {
            // Not fatal: the app opens an empty scoped file and syncs. Worth saying loudly, because
            // the visible symptom is a customer watching their library rebuild for no reason.
            Log.w(TAG, "Could not adopt the existing database; the provider will start empty")
        }
    }

    /** Every database file on this device, so a logout can remove all of them. */
    fun allDatabaseFiles(context: Context): List<File> {
        val dir = context.getDatabasePath(LEGACY_NAME).parentFile ?: return emptyList()
        return dir.listFiles { f -> f.name == LEGACY_NAME || f.name.startsWith("${LEGACY_NAME}_") }
            .orEmpty()
            .toList()
    }

    /** Suffixes SQLite keeps beside the database in WAL mode. Order matters only for logging. */
    private val SIDECARS = listOf("-wal", "-shm")
}
