package com.tvonnet.debridxtreamiptv.ui.live.guide

import androidx.annotation.ColorRes
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream

/**
 * Genre buckets used to tint channels/programs in the EPG guide (spec §6).
 */
enum class EpgGenre(@ColorRes val colorRes: Int) {
    NEWS(R.color.epg_genre_news),
    SPORTS(R.color.epg_genre_sports),
    MOVIES(R.color.epg_genre_movies),
    ENTERTAINMENT(R.color.epg_genre_entertainment),
    KIDS(R.color.epg_genre_kids),
    MUSIC(R.color.epg_genre_music),
    GENERAL(R.color.epg_genre_general);

    val label: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /** Best-effort mapping from an EPG category and/or channel name to a genre bucket. */
        fun from(category: String?, channelName: String? = null): EpgGenre {
            val hay = "${category.orEmpty()} ${channelName.orEmpty()}".lowercase()
            return when {
                hay.containsAny("news", "cnn", "bbc", "aljazeera", "24") -> NEWS
                hay.containsAny("sport", "espn", "football", "soccer", "cricket", "nba", "ufc", "bein") -> SPORTS
                hay.containsAny("movie", "cinema", "film", "hbo", "cine") -> MOVIES
                hay.containsAny("kid", "cartoon", "disney", "nick", "baby", "junior") -> KIDS
                hay.containsAny("music", "mtv", "hits", "radio", "vevo") -> MUSIC
                hay.containsAny("entertain", "drama", "series", "comedy", "show", "life") -> ENTERTAINMENT
                else -> GENERAL
            }
        }

        private fun String.containsAny(vararg needles: String) = needles.any { this.contains(it) }
    }
}

/**
 * A program cell in the guide grid. Times are Unix ms.
 */
data class GuideProgram(
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
    val genre: EpgGenre,
    val hasReminder: Boolean = false
) {
    // Phase 6: view-populated cache of the formatted "HH:mm - HH:mm" label, so the EPG grid
    // doesn't re-run SimpleDateFormat + allocate Dates on every onDraw frame during scroll.
    var cachedTimeLabel: String? = null

    fun isNow(now: Long) = now in startMs until stopMs
    fun hasEnded(now: Long) = now >= stopMs
    fun durationMinutes(): Int = (((stopMs - startMs) / 60000L).toInt()).coerceAtLeast(1)
}

/**
 * A channel row in the guide grid.
 */
data class GuideChannel(
    val streamId: String,
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val genre: EpgGenre,
    val quality: String?,      // "4K" / "HD" / null
    val isFavorite: Boolean,
    val stream: XtreamStream,  // used to tune/play
    val programs: List<GuideProgram>
) {
    fun currentProgram(now: Long): GuideProgram? = programs.firstOrNull { it.isNow(now) }
    fun initials(): String {
        val parts = name.trim().split(" ", "-", "_").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    companion object {
        /** Detect a quality tag from a channel name. */
        fun qualityOf(name: String?): String? {
            val n = name?.uppercase() ?: return null
            return when {
                n.contains("4K") || n.contains("UHD") -> "4K"
                n.contains("FHD") || n.contains("1080") -> "FHD"
                n.contains("HD") -> "HD"
                else -> null
            }
        }
    }
}
