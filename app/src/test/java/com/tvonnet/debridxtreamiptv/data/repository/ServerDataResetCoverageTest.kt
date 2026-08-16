package com.tvonnet.debridxtreamiptv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S6 — the guard that keeps the switch contract honest as the schema grows.
 *
 * The failure this exists to prevent is not a bug in `ServerDataReset`; it is a table added months
 * from now by someone who has never read it. The catalogue is keyed by `streamId`, so a forgotten
 * table does not go stale — it starts answering the NEW provider's ids with the OLD provider's
 * rows, silently, on a device nobody is testing switches on.
 *
 * So the assertion is deliberately about **classification, not behaviour**: every table the
 * database actually ships must be listed below as either purged on a change of provider or
 * knowingly kept, with the reason written down. Add a table and this goes red until somebody
 * decides which it is. Whether the purge then does what it says is proved on a device by
 * `MigrationTest` and the QA pass — a JVM test cannot open Room.
 */
class ServerDataResetCoverageTest {

    /**
     * Cleared by [ServerDataReset]. Each of these is keyed by an id that only ONE provider issues,
     * so carrying it across a switch means showing the wrong thing rather than an old thing.
     */
    private val purgedOnSwitch = setOf(
        "channels",
        "categories",
        "vod_v2",
        "series_v2",
        "seasons",
        "episodes",
        "series_v2_core",
        "episodes_v2_core",
        "epg",
        // Both are source-scoped: the xtream rows go, the debrid rows stay.
        "favorites",
        "watched_state",
    )

    /** Deliberately kept, with the reason. */
    private val keptOnSwitch = mapOf(
        "search_history" to
            "the customer's own typed words; they reference no id and are useful on any provider " +
                "(a logout still clears them — the account is leaving the device)",
        "release_languages" to
            "audio languages learned from playback, keyed by the debrid infoHash — the same " +
                "release carries the same tracks whoever the IPTV provider is",
    )

    @Test
    fun `every shipped table is classified as purged or knowingly kept`() {
        val shipped = shippedTables()
        val classified = purgedOnSwitch + keptOnSwitch.keys

        val unclassified = shipped - classified
        assertTrue(
            "table(s) $unclassified were added without deciding what a change of provider does to" +
                " them. If the rows are keyed by a provider's stream id, clear them in" +
                " ServerDataReset and add them to purgedOnSwitch; if not, add them to keptOnSwitch" +
                " with the reason. See the server-switch contract in CLAUDE.md.",
            unclassified.isEmpty()
        )

        val phantom = classified - shipped
        assertTrue(
            "table(s) $phantom are listed here but no longer exist in the schema — the purge is" +
                " carrying dead weight",
            phantom.isEmpty()
        )
    }

    @Test
    fun `the two kept tables each carry a reason`() {
        keptOnSwitch.forEach { (table, reason) ->
            assertTrue("'$table' is kept without saying why", reason.length > 20)
        }
        assertEquals("keeping a table is the exception; adding one needs a decision", 2, keptOnSwitch.size)
    }

    /** The tables the database actually ships, from the schema Room exports on every build. */
    private fun shippedTables(): Set<String> {
        val dir = sequenceOf(
            File("schemas/$SCHEMA_PACKAGE"),
            File("app/schemas/$SCHEMA_PACKAGE")
        ).firstOrNull { it.isDirectory }
            ?: error("no exported Room schemas found — is room.schemaLocation still configured?")

        val newest = dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
            ?: error("no schema JSON in ${dir.path}")

        // Hand-scanned rather than parsed: the test module has no JSON dependency, and the field
        // is unambiguous in Room's output.
        return TABLE_NAME.findAll(newest.readText()).map { it.groupValues[1] }.toSet()
    }

    private companion object {
        private const val SCHEMA_PACKAGE = "com.tvonnet.debridxtreamiptv.data.local.AppDatabase"
        private val TABLE_NAME = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"")
    }
}
