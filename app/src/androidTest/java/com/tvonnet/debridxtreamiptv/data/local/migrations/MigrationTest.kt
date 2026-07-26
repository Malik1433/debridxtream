package com.tvonnet.debridxtreamiptv.data.local.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Phase 8: Room migration test net (instrumented — needs a device/emulator).
 *
 * exportSchema was turned OFF historically, so the only exported schema is v14. That means we can
 * validate the v14 schema and the real migration container, and every FUTURE version bump becomes
 * testable (see the scaffold at the bottom). The pre-14 migrations (4->14, in DatabaseMigrations)
 * have no exported start-schema JSON, so MigrationTestHelper cannot replay them retroactively — this
 * is why removing fallbackToDestructiveMigration (Phase 7.5) must keep a v14 floor.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test-db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /** The exported v14 schema is valid and Room can materialize an empty database from it. */
    @Test
    @Throws(IOException::class)
    fun createV14FromSchema() {
        helper.createDatabase(TEST_DB, 14).close()
    }

    /**
     * The real migration container (DatabaseMigrations.getAllMigrations) + the current entity set
     * open cleanly and land on v14. This catches an entity/query change that was shipped without a
     * matching migration (Room throws IllegalStateException on schema mismatch).
     */
    @Test
    @Throws(IOException::class)
    fun realDatabaseOpensAtV14() {
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            "open-smoke-db"
        )
            .addMigrations(
                *com.tvonnet.debridxtreamiptv.data.local.migrations.DatabaseMigrations.getAllMigrations()
            )
            .build()
        try {
            val version = db.openHelper.readableDatabase.version
            assertEquals(14, version)
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .deleteDatabase("open-smoke-db")
        }
    }

    /**
     * Roadmap B2.4 — the rule this whole file exists for: **opening an existing user database must
     * never wipe user data.** `fallbackToDestructiveMigration` silently deletes watch progress and
     * favourites, and it looked harmless right up until it shipped. Here a real v14 database is
     * populated with the two things a user would actually mourn, closed, and reopened through the
     * production migration container.
     */
    @Test
    @Throws(IOException::class)
    fun reopeningAPopulatedDatabaseKeepsWatchProgressAndFavourites() {
        helper.createDatabase(TEST_DB, 14).apply {
            execSQL(
                "INSERT INTO watched_state " +
                    "(identity_key, content_type, source, is_watched, progress_ms, duration_ms, " +
                    "watched_at, updated_at) " +
                    "VALUES ('movie:inception', 'MOVIE', 'xtream', 0, 1845000, 8880000, NULL, 1)"
            )
            execSQL(
                "INSERT INTO favorites (streamId, type, addedAt, name, iconUrl) " +
                    "VALUES ('9911', 'live', 1, 'BBC News HD', NULL)"
            )
            close()
        }

        val db = openWithProductionMigrations(TEST_DB)
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            db.openHelper.readableDatabase.query(
                "SELECT progress_ms FROM watched_state WHERE identity_key = 'movie:inception'"
            ).use { cursor ->
                assertTrue("watch progress must survive reopening the database", cursor.moveToFirst())
                assertEquals(1_845_000L, cursor.getLong(0))
            }
            db.openHelper.readableDatabase.query(
                "SELECT name FROM favorites WHERE streamId = '9911'"
            ).use { cursor ->
                assertTrue("favourites must survive reopening the database", cursor.moveToFirst())
                assertEquals("BBC News HD", cursor.getString(0))
            }
        } finally {
            db.close()
            deleteTestDb(TEST_DB)
        }
    }

    /**
     * The other half of "never destructive": a database stamped BELOW the v4 migration floor has no
     * path forward. Room must fail loudly so the problem is visible, rather than quietly recreating
     * the file and taking the user's history with it.
     */
    @Test
    @Throws(IOException::class)
    fun aDatabaseBelowTheMigrationFloorFailsLoudlyInsteadOfBeingWiped() {
        helper.createDatabase(TEST_DB, 14).apply {
            execSQL(
                "INSERT INTO watched_state " +
                    "(identity_key, content_type, source, is_watched, progress_ms, duration_ms, " +
                    "watched_at, updated_at) " +
                    "VALUES ('movie:inception', 'MOVIE', 'xtream', 0, 1845000, 8880000, NULL, 1)"
            )
            // Pre-floor stamp: there is no MIGRATION_3_4, so nothing can carry this forward.
            execSQL("PRAGMA user_version = 3")
            close()
        }

        val db = openWithProductionMigrations(TEST_DB)
        try {
            var failedLoudly = false
            try {
                db.openHelper.readableDatabase.version
            } catch (e: IllegalStateException) {
                failedLoudly = true
            }
            assertTrue(
                "a missing migration must throw — a silent destructive fallback would delete the" +
                    " user's watch progress and favourites",
                failedLoudly
            )
        } finally {
            runCatching { db.close() }
            deleteTestDb(TEST_DB)
        }
    }

    private fun openWithProductionMigrations(name: String): AppDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            name
        )
            .addMigrations(*DatabaseMigrations.getAllMigrations())
            .build()

    private fun deleteTestDb(name: String) {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name)
    }

    // ── Forward-looking scaffold (uncomment + adapt when you bump to v15) ─────────────────────────
    // The chain itself (every bump has a registered migration) is guarded on the JVM side by
    // DatabaseMigrationChainTest, which fails the moment @Database(version=…) moves ahead of
    // getAllMigrations().
    //
    // @Test
    // @Throws(IOException::class)
    // fun migrate14To15() {
    //     helper.createDatabase(TEST_DB, 14).apply {
    //         // insert a v14-shaped row with raw SQL so the migration has real data to carry over
    //         close()
    //     }
    //     helper.runMigrationsAndValidate(TEST_DB, 15, true, DatabaseMigrations.MIGRATION_14_15)
    // }
}
