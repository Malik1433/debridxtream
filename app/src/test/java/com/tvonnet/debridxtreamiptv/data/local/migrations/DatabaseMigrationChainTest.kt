package com.tvonnet.debridxtreamiptv.data.local.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Roadmap B2.4 — the guard that makes the NEXT schema bump safe.
 *
 * The on-device `MigrationTest` can only replay paths that have an exported start schema (export
 * was off before v14), so the thing most likely to go wrong — bumping `@Database(version = …)` and
 * forgetting to write or register the migration — would not be caught there. On a real device that
 * mistake means Room throws on every launch, or worse, a destructive fallback quietly deletes the
 * user's watch progress and favourites.
 *
 * This runs on the JVM in milliseconds, so there is no excuse for it not to run.
 */
class DatabaseMigrationChainTest {

    private val migrations = DatabaseMigrations.getAllMigrations()

    /**
     * The version the database actually ships at, taken from the schema JSON Room's compiler
     * exports on every build. Room's `@Database` annotation is BINARY-retained, so it cannot be
     * read reflectively — and the exported schema is the better source anyway: it is generated
     * from the real annotation, so it cannot drift from it.
     */
    private val declaredVersion: Int
        get() {
            val dir = sequenceOf(
                File("schemas/$SCHEMA_PACKAGE"),
                File("app/schemas/$SCHEMA_PACKAGE")
            ).firstOrNull { it.isDirectory }
                ?: error("no exported Room schemas found — is room.schemaLocation still configured?")

            val versions = dir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                .orEmpty()

            assertTrue("no schema JSON in ${dir.path}", versions.isNotEmpty())
            return versions.max()
        }

    @Test
    fun `every registered migration advances exactly one version`() {
        migrations.forEach { migration ->
            assertEquals(
                "migration ${migration.startVersion}->${migration.endVersion} must be a single step;" +
                    " multi-step migrations hide gaps",
                migration.startVersion + 1,
                migration.endVersion
            )
        }
    }

    @Test
    fun `the chain has no gaps and no duplicates`() {
        val starts = migrations.map { it.startVersion }

        assertEquals("two migrations registered for the same version bump", starts.size, starts.toSet().size)

        val sorted = starts.sorted()
        sorted.zipWithNext { a, b ->
            assertEquals("no migration registered for version $a -> ${a + 1}", a + 1, b)
        }
    }

    /**
     * The one that fires on the next bump: the chain must reach the version the database ships at.
     * Raise `@Database(version = 15)` without writing **and registering** `MIGRATION_14_15` and this
     * goes red immediately, on the JVM, before anything reaches a device.
     */
    @Test
    fun `the chain reaches the version the database ships at`() {
        val reachable = migrations.maxOf { it.endVersion }

        assertEquals(
            "AppDatabase ships at version $declaredVersion but the migrations only reach $reachable —" +
                " write MIGRATION_${reachable}_${reachable + 1} and add it to getAllMigrations()",
            declaredVersion,
            reachable
        )
    }

    @Test
    fun `the floor is documented and stable`() {
        val floor = migrations.minOf { it.startVersion }

        assertEquals(
            "v4 is the migration floor: pre-14 versions have no exported schema, and pre-4 has no" +
                " migration at all. Moving this silently strands existing installs.",
            4,
            floor
        )
        assertTrue("there must be at least one migration", migrations.isNotEmpty())
    }

    private companion object {
        private const val SCHEMA_PACKAGE = "com.tvonnet.debridxtreamiptv.data.local.AppDatabase"
    }
}
