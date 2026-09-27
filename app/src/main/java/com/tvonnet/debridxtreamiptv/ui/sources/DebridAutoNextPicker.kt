package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

/**
 * Which source plays next after a debrid source failed - without ever changing the language.
 *
 * A viewer watching in German wants German; quality and speed come second. The old rule took the
 * next row of the sorted list, and that list is only language-FIRST when the user filtered by
 * language: with just the settings priority (or no preference) cache status leads the sort, so the
 * row after a failed German source could be English, and after the last German one it always was -
 * played with a "trying next source" toast and nothing else.
 *
 * Now the next source must carry the language that was playing:
 *  1. the language the user FILTERED by, if any;
 *  2. else the settings priority language, when the failed source carried it;
 *  3. else every specific language the failed source carried (a DE+EN release needs DE+EN).
 * "Multi", "Unknown" and unlabelled sources never satisfy a requirement - "Multi" says several
 * languages without saying which, so the viewer picks those from the list themselves. When the
 * failed source carried no known language (and no filter applies) there is nothing to preserve,
 * and the old next-row rule applies unchanged.
 *
 * Languages come from [MovieSource.languages], which already carries the H4 overlay - languages a
 * player actually HEARD for that release replace the ones parsed from its title.
 *
 * Pure list logic: the controllers do the fetching, the toast and the list.
 */
object DebridAutoNextPicker {

    sealed class Result {
        data class Next(val source: MovieSource) : Result()

        /** Sources are left, but none in [language] - hand the choice to the viewer. */
        data class NoneInLanguage(val language: StreamLanguage) : Result()

        object NoneLeft : Result()
    }

    /**
     * @param sorted the list as the viewer sees it (filtered + sorted), failed sources removed.
     * @param all every source for the title, to find the failed one even when a filter hides it.
     * @param filterLanguage the language filter the viewer applied ("All"/null = none).
     * @param priorityLanguage the settings priority language (`preferredAudioLang`).
     */
    fun pick(
        sorted: List<MovieSource>,
        all: List<MovieSource>,
        failedStreamId: String,
        filterLanguage: String?,
        priorityLanguage: String?,
    ): Result {
        if (sorted.isEmpty()) return Result.NoneLeft
        val failedIndex = sorted.indexOfFirst { it.stream.stream_id == failedStreamId }
        val start = when {
            failedIndex in 0 until sorted.lastIndex -> failedIndex + 1
            failedIndex == -1 -> 0
            else -> return Result.NoneLeft
        }
        val failed = all.firstOrNull { it.stream.stream_id == failedStreamId }
        val required = requiredLanguages(failed, filterLanguage, priorityLanguage)
        val remaining = sorted.subList(start, sorted.size)
        if (required.isEmpty()) return Result.Next(remaining.first())
        return remaining.firstOrNull { specificLanguages(it).containsAll(required) }
            ?.let { Result.Next(it) }
            ?: Result.NoneInLanguage(required.first())
    }

    internal fun requiredLanguages(
        failed: MovieSource?,
        filterLanguage: String?,
        priorityLanguage: String?,
    ): Set<StreamLanguage> {
        specific(StreamLanguage.parse(filterLanguage))?.let { return setOf(it) }
        val failedLanguages = failed?.let { specificLanguages(it) }.orEmpty()
        val priority = specific(StreamLanguage.parse(priorityLanguage))
        if (priority != null && (failed == null || priority in failedLanguages)) return setOf(priority)
        return failedLanguages
    }

    private fun specificLanguages(source: MovieSource): Set<StreamLanguage> =
        source.languages.orEmpty().mapNotNull { specific(StreamLanguage.parse(it)) }.toSet()

    private fun specific(language: StreamLanguage): StreamLanguage? = when (language) {
        StreamLanguage.MULTI, StreamLanguage.ALL, StreamLanguage.UNKNOWN -> null
        else -> language
    }
}

/**
 * The controllers' side of [DebridAutoNextPicker]: runs the pick and, when sources remain but none
 * in the viewer's language, says so instead of switching language silently. Returning null sends
 * both detail screens to the source list, exactly as when nothing was left at all.
 */
internal fun android.content.Context.nextInSameLanguage(
    sorted: List<MovieSource>,
    all: List<MovieSource>,
    failedStreamId: String,
    filterState: SourceFilterState,
    prefs: com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences,
): MovieSource? = when (
    val result = DebridAutoNextPicker.pick(
        sorted = sorted,
        all = all,
        failedStreamId = failedStreamId,
        filterLanguage = filterState.preferredLanguage,
        priorityLanguage = prefs.preferredAudioLang,
    )
) {
    is DebridAutoNextPicker.Result.Next -> result.source
    is DebridAutoNextPicker.Result.NoneInLanguage -> {
        android.widget.Toast.makeText(
            this,
            getString(R.string.c_no_more_sources_in_language, displayName(result.language)),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        null
    }
    DebridAutoNextPicker.Result.NoneLeft -> null
}

/** The language in the viewer's own UI language ("Deutsch", "Allemand"), else its English label. */
private fun displayName(language: StreamLanguage): String {
    val code = ISO_CODES[language] ?: return language.label
    return java.util.Locale(code).getDisplayLanguage(java.util.Locale.getDefault())
        .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
        .ifBlank { language.label }
}

private val ISO_CODES = mapOf(
    StreamLanguage.ENGLISH to "en", StreamLanguage.HINDI to "hi", StreamLanguage.PUNJABI to "pa",
    StreamLanguage.GERMAN to "de", StreamLanguage.FRENCH to "fr", StreamLanguage.URDU to "ur",
    StreamLanguage.TAMIL to "ta", StreamLanguage.TELUGU to "te", StreamLanguage.MALAYALAM to "ml",
    StreamLanguage.KANNADA to "kn", StreamLanguage.ITALIAN to "it", StreamLanguage.RUSSIAN to "ru",
    StreamLanguage.SPANISH to "es", StreamLanguage.PORTUGUESE to "pt", StreamLanguage.DUTCH to "nl",
    StreamLanguage.POLISH to "pl", StreamLanguage.TURKISH to "tr",
)
