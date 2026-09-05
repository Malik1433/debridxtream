package com.tvonnet.debridxtreamiptv.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The l10n rules, enforced (2026-09-05).
 *
 * Nothing checked these before, and both failure modes are silent: a batch adds an English string
 * and translates it into four of the five locales, or a translation drops a format argument and the
 * screen crashes with `IllegalFormatException` — but only for the users who speak that language,
 * which is nobody the owner tests as.
 *
 * The parity rule is deliberately NOT "every key must be translated". 844 of 952 keys are
 * translated on purpose; the rest are brand names, URLs, sample placeholders and pure format
 * strings that stay English. The invariant that actually matters is: **a key translated in ONE
 * locale must be translated in ALL of them.** That is the thing a partial batch breaks.
 */
class StringResourceParityTest {

    private val locales = listOf("de", "es", "fr", "it", "pt")

    private val res: File? by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src/main/res"), File(dir, "app/src/main/res"))) {
                if (File(candidate, "values/strings.xml").isFile) return@lazy candidate
            }
            dir = dir.parentFile
        }
        null
    }

    /** name -> value, for every `<string>` in every strings file in one values folder. */
    private fun stringsIn(folder: File): Map<String, String> {
        if (!folder.isDirectory) return emptyMap()
        val out = LinkedHashMap<String, String>()
        folder.listFiles { f -> f.isFile && f.name.startsWith("strings") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                STRING_TAG.findAll(file.readText()).forEach { m ->
                    out[m.groupValues[1]] = m.groupValues[2]
                }
            }
        return out
    }

    /** The set of positional specifiers a string uses, e.g. {"%1${'$'}s", "%2${'$'}d"}. */
    private fun specifiersOf(value: String): Set<String> =
        SPECIFIER.findAll(value).map { it.value }.toSet()

    @Test
    fun `a key translated in one locale is translated in all of them`() {
        val root = res
        assumeTrue("res/ not found from ${File("").absolutePath}", root != null)
        val byLocale = locales.associateWith { stringsIn(File(root, "values-$it")) }

        val everywhereItAppears = LinkedHashMap<String, MutableSet<String>>()
        byLocale.forEach { (lang, map) -> map.keys.forEach { everywhereItAppears.getOrPut(it) { mutableSetOf() }.add(lang) } }

        val partial = everywhereItAppears
            .filterValues { it.size != locales.size }
            .map { (key, has) -> key + " missing from " + (locales - has).joinToString(",") }
            .sorted()

        assertTrue(
            "these keys are translated in some locales but not others:\n  " + partial.joinToString("\n  "),
            partial.isEmpty()
        )
    }

    @Test
    fun `no locale carries a key the default language does not have`() {
        val root = res
        assumeTrue("res/ not found", root != null)
        val english = stringsIn(File(root, "values")).keys
        val orphans = locales.flatMap { lang ->
            (stringsIn(File(root, "values-$lang")).keys - english).map { "$lang: $it" }
        }.sorted()

        assertTrue(
            "these keys exist only in a translation - a rename left them behind:\n  " +
                orphans.joinToString("\n  "),
            orphans.isEmpty()
        )
    }

    @Test
    fun `a translation uses exactly the format arguments its English does`() {
        val root = res
        assumeTrue("res/ not found", root != null)
        val english = stringsIn(File(root, "values"))
        val mismatches = mutableListOf<String>()

        locales.forEach { lang ->
            stringsIn(File(root, "values-$lang")).forEach { (key, translated) ->
                val expected = specifiersOf(english[key] ?: return@forEach)
                val actual = specifiersOf(translated)
                if (expected != actual) {
                    mismatches += "$lang/$key: expected $expected, got $actual"
                }
            }
        }

        // A dropped or renumbered argument is a guaranteed IllegalFormatException at runtime.
        assertTrue("format arguments differ from the English:\n  " + mismatches.joinToString("\n  "), mismatches.isEmpty())
    }

    private companion object {
        val STRING_TAG = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val SPECIFIER = Regex("""%\d+\$[a-zA-Z]""")
    }
}
