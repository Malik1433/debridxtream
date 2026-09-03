package com.tvonnet.debridxtreamiptv.ui.browse

import android.content.Context
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.ui.series.SeriesViewModel
import com.tvonnet.debridxtreamiptv.ui.vod.VodViewModel

/**
 * The four virtual categories ("All Movies", "Recently Added", …) are minted inside the
 * ViewModels with English names, because a ViewModel has no Activity context — and the
 * Application context would not do: on API < 33 the in-app language (AppCompat per-app
 * locales) is only applied to Activity contexts, so `applicationContext.getString` would
 * answer in the DEVICE language on the Fire TV. So the name is resolved where it is shown.
 *
 * `resolve` is the string lookup, injected so the mapping is testable without Android.
 */
object VirtualCategoryNames {

    fun displayName(category: XtreamCategory, resolve: (Int) -> String): String =
        resIdFor(category.category_id)?.let(resolve) ?: category.category_name.orEmpty()

    fun displayName(context: Context, category: XtreamCategory): String =
        displayName(category, context::getString)

    internal fun resIdFor(categoryId: String?): Int? = when (categoryId) {
        VodViewModel.ALL_MOVIES_CATEGORY_ID -> R.string.code_cat_all_movies
        VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID -> R.string.code_cat_recently_added
        SeriesViewModel.ALL_SERIES_CATEGORY_ID -> R.string.code_cat_all_series
        SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID -> R.string.code_cat_recently_added
        else -> null
    }
}
