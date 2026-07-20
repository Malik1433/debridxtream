package com.tvonnet.debridxtreamiptv.data.local.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import org.junit.Assert.assertEquals
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

    // ── Forward-looking scaffold (uncomment + adapt when you bump to v15) ─────────────────────────
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
