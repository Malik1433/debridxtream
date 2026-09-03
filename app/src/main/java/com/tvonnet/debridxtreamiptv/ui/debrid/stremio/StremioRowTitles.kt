package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.content.Context
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow

/**
 * The Debrid home rows the ViewModel builds itself ("My Library", "Trending Movies", …) carry
 * an English title because the ViewModel has no Activity context (and the Application context
 * ignores the in-app language on API < 33 — see [com.tvonnet.debridxtreamiptv.ui.browse.VirtualCategoryNames]).
 * The title is resolved by the row's stable id where it is rendered; catalogue rows keep the
 * title the add-on gave them.
 */
object StremioRowTitles {

    fun displayTitle(row: DebridRow, resolve: (Int) -> String): String =
        resIdFor(row.id)?.let(resolve) ?: row.title

    fun displayTitle(context: Context, row: DebridRow): String = displayTitle(row, context::getString)

    internal fun resIdFor(rowId: String): Int? = when (rowId) {
        "my_library" -> R.string.code_row_my_library
        "trending_movies" -> R.string.code_row_trending_movies
        "trending_series" -> R.string.code_row_trending_series
        else -> null
    }
}
