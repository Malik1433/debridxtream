package com.tvonnet.debridxtreamiptv.ui.home

import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.FeaturedItem
import com.tvonnet.debridxtreamiptv.data.model.NewAddedItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem

/**
 * Provides lightweight placeholder content so the redesigned home screen can
 * render meaningful cards even when the real cache or history stores are empty.
 * These entries intentionally omit playable URLs, ensuring taps simply show the
 * existing "Stream unavailable" toast until real data arrives.
 */
object HomeSampleData {

    // Real TMDB Image URLs for testing
    private const val BACKDROP_1 = "https://image.tmdb.org/t/p/w1280/zfbjgQE1uW9uONYnUy7Om5ivRwO.jpg" // The Godfather
    private const val BACKDROP_2 = "https://image.tmdb.org/t/p/w1280/9yBVqNruk6Ykr9zD297HQ6OSKM.jpg" // Mortal Kombat
    private const val BACKDROP_3 = "https://image.tmdb.org/t/p/w1280/rSPw7tgCH9c6NqICZef4kZjFOQ5.jpg" // Godfather 2
    
    private const val POSTER_1 = "https://image.tmdb.org/t/p/w500/3bhkrj58Vtu7enYsRolD1fZdja1.jpg" // The Godfather
    private const val POSTER_2 = "https://image.tmdb.org/t/p/w500/6Wdl9N6dL0Hi0T1MILWsJJkHMQQ.jpg" // Mortal Kombat
    private const val POSTER_3 = "https://image.tmdb.org/t/p/w500/hek3koDUyRLauyGYLjEXnRPTm2K.jpg" // Godfather 2
    private const val POSTER_4 = "https://image.tmdb.org/t/p/w500/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg" // Shawshank
    private const val POSTER_5 = "https://image.tmdb.org/t/p/w500/rCzpDGLbOoPwLjy3OAm5NUPOTrC.jpg" // LOTR

    private const val LOGO_1 = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1a/CNN_logo.svg/1200px-CNN_logo.svg.png"
    private const val LOGO_2 = "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6f/BBC_News_2019.svg/1200px-BBC_News_2019.svg.png"
    private const val LOGO_3 = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/05/Sky_Sports_logo_2017.svg/1200px-Sky_Sports_logo_2017.svg.png"
    private const val LOGO_4 = "https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/Cartoon_Network_logo_2010.svg/1200px-Cartoon_Network_logo_2010.svg.png"

    fun featuredItems(): List<FeaturedItem> = listOf(
        FeaturedItem(
            contentId = "sample_featured_live",
            contentType = ContentType.LIVE_TV,
            title = "The Godfather",
            backdropUrl = BACKDROP_1,
            posterUrl = POSTER_1,
            description = "Spanning the years 1945 to 1955, a chronicle of the fictional Italian-American Corleone crime family.",
            streamUrl = null
        ),
        FeaturedItem(
            contentId = "sample_featured_movie",
            contentType = ContentType.MOVIE,
            title = "Mortal Kombat",
            backdropUrl = BACKDROP_2,
            posterUrl = POSTER_2,
            description = "Washed-up MMA fighter Cole Young, unaware of his heritage, is hunted by Emperor Shang Tsung's best warrior.",
            streamUrl = null
        ),
        FeaturedItem(
            contentId = "sample_featured_series",
            contentType = ContentType.SERIES,
            title = "The Godfather Part II",
            backdropUrl = BACKDROP_3,
            posterUrl = POSTER_3,
            description = "In the continuing saga of the Corleone crime family, a young Vito Corleone grows up in Sicily and in 1910s New York.",
            streamUrl = null
        )
    )

    fun newAddedItems(): List<NewAddedItem> {
        val now = System.currentTimeMillis()
        return listOf(
            NewAddedItem("1", ContentType.MOVIE, "The Godfather", POSTER_1, now.toString(), now, "1972", "4K", null),
            NewAddedItem("2", ContentType.MOVIE, "Mortal Kombat", POSTER_2, now.toString(), now, "2021", "HD", null),
            NewAddedItem("3", ContentType.MOVIE, "Godfather II", POSTER_3, now.toString(), now, "1974", "HD", null),
            NewAddedItem("4", ContentType.MOVIE, "Shawshank", POSTER_4, now.toString(), now, "1994", "HD", null),
            NewAddedItem("5", ContentType.MOVIE, "LOTR", POSTER_5, now.toString(), now, "2001", "4K", null)
        )
    }

    fun continueWatchingItems(): List<ContinueWatchingItem> {
        val now = System.currentTimeMillis()
        return listOf(
            ContinueWatchingItem("1", ContentType.MOVIE, "The Godfather", POSTER_1, BACKDROP_1, 900000, 5400000, now, null),
            ContinueWatchingItem("2", ContentType.SERIES, "Mortal Kombat", POSTER_2, BACKDROP_2, 1200000, 2700000, now, null),
            ContinueWatchingItem("3", ContentType.MOVIE, "Godfather II", POSTER_3, BACKDROP_3, 600000, 7200000, now, null)
        )
    }

    fun recentLiveChannels(): List<RecentLiveChannelItem> {
        val now = System.currentTimeMillis()
        return listOf(
            RecentLiveChannelItem("1", "CNN", LOGO_1, now, null, "1"),
            RecentLiveChannelItem("2", "BBC News", LOGO_2, now, null, "2"),
            RecentLiveChannelItem("3", "Sky Sports", LOGO_3, now, null, "3"),
            RecentLiveChannelItem("4", "Cartoon Network", LOGO_4, now, null, "4")
        )
    }
}
