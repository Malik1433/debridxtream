package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.content.Context
import androidx.fragment.app.Fragment
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2

/**
 * The one place that decides which Detail screen a device gets.
 *
 * Every caller that used to build `MovieDetailFragmentV2` or `SeriesDetailFragmentV2` directly now
 * comes through here, for the same reason `SectionNavigator.createHomeFragment` exists: with four
 * call sites and two form factors, a new caller would otherwise be one forgotten branch away from
 * putting the 10-foot screen on a handset.
 *
 * The argument lists are deliberately identical to the V2 fragments', so a caller passes what it
 * always passed and never learns which screen it got.
 */
object DetailScreens {

    private fun isTv(context: Context) = context.resources.getBoolean(R.bool.ui_uses_dpad_focus)

    fun movie(
        context: Context,
        streamId: String?,
        title: String?,
        backdropUrl: String? = null,
        posterUrl: String? = null,
        plot: String? = null,
        year: String? = null,
        genre: String? = null,
        rating: String? = null,
        containerExtension: String? = null,
        directSource: String? = null,
        trailer: String? = null,
    ): Fragment = if (isTv(context)) {
        MovieDetailFragmentV2.newInstance(
            streamId = streamId.orEmpty(),
            title = title,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            plot = plot,
            year = year,
            genre = genre,
            rating = rating,
            containerExt = containerExtension,
            directSource = directSource,
            trailer = trailer,
        )
    } else {
        PhoneMovieDetailFragment.newInstance(
            streamId = streamId,
            title = title,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            plot = plot,
            year = year,
            genre = genre,
            rating = rating,
            containerExtension = containerExtension,
            directSource = directSource,
            trailer = trailer,
        )
    }

    /**
     * The Bundle door, for the two browse screens that already assemble their own arguments. The
     * phone fragment reads the same keys, so neither caller has to be rewritten.
     */
    fun movie(context: Context, args: android.os.Bundle): Fragment = if (isTv(context)) {
        MovieDetailFragmentV2.newInstance(args)
    } else {
        movie(
            context = context,
            streamId = args.getString("stream_id"),
            title = args.getString("title"),
            backdropUrl = args.getString("backdrop_url"),
            posterUrl = args.getString("poster_url"),
            plot = args.getString("plot"),
            year = args.getString("year"),
            genre = args.getString("genre"),
            rating = args.getString("rating"),
            containerExtension = args.getString("container_extension"),
            directSource = args.getString("direct_source"),
            trailer = args.getString("trailer"),
        )
    }

    fun series(
        context: Context,
        seriesId: String?,
        title: String?,
        backdropUrl: String? = null,
        posterUrl: String? = null,
        trailer: String? = null,
    ): Fragment = if (isTv(context)) {
        SeriesDetailFragmentV2.newInstance(
            seriesId = seriesId.orEmpty(),
            title = title,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            trailer = trailer,
        )
    } else {
        PhoneSeriesDetailFragment.newInstance(
            seriesId = seriesId,
            title = title,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
        )
    }

    /**
     * The Bundle door for series. Two browse screens built `SeriesDetailFragmentV2()` directly and
     * filled `arguments` themselves — which is exactly how they slipped past the first pass at
     * routing and kept opening the 10-foot screen on a phone.
     */
    fun series(context: Context, args: android.os.Bundle): Fragment = if (isTv(context)) {
        SeriesDetailFragmentV2().apply { arguments = args }
    } else {
        series(
            context = context,
            seriesId = args.getString("series_id"),
            title = args.getString("title"),
            backdropUrl = args.getString("backdrop_url"),
            posterUrl = args.getString("poster_url"),
            trailer = args.getString("trailer"),
        )
    }
}
