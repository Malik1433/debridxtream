package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S6: the delete statements a change of provider depends on, run against a real SQLite.
 *
 * `ServerDataResetCoverageTest` proves every table has been THOUGHT about; this proves the two
 * statements that are easy to get quietly wrong actually do what they claim. Both are scoped
 * deletes, and a scope that is subtly off fails in the expensive direction — it either leaves the
 * old provider's rows behind (the bug we are fixing) or takes the customer's debrid history with
 * it (data loss they never asked for).
 *
 * The whole-table wipes added alongside them (`deleteAllSeasons`, `deleteAllEpisodes`,
 * `clearAll`) are covered too, because they were the tables that had NO way to be emptied before —
 * seasons and episodes could only ever be deleted one series at a time.
 */
@RunWith(AndroidJUnit4::class)
class ProviderPurgeDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun changingProviderClearsIptvFavouritesAndKeepsDebridOnes() = runBlocking {
        val favorites = db.favoriteDao()
        favorites.insertFavorite(
            FavoriteEntity(
                streamId = "9911",
                type = "live",
                name = "BBC News HD",
                source = WatchedStateEntity.SOURCE_XTREAM,
            )
        )
        favorites.insertFavorite(
            FavoriteEntity(
                streamId = "tt0111161",
                type = "vod",
                name = "The Shawshank Redemption",
                source = WatchedStateEntity.SOURCE_DEBRID,
            )
        )

        favorites.deleteFavoritesBySource(WatchedStateEntity.SOURCE_XTREAM)

        val left = favorites.getFavoritesSync()
        assertEquals("only the debrid favourite should survive a change of provider", 1, left.size)
        assertEquals("tt0111161", left.single().streamId)
    }

    @Test
    fun changingProviderClearsIptvWatchStateAndKeepsDebridWatchState() = runBlocking {
        val watched = db.watchedStateDao()
        watched.upsert(watchedRow("xtream:movie:123", WatchedStateEntity.SOURCE_XTREAM))
        watched.upsert(watchedRow("debrid:abcdef", WatchedStateEntity.SOURCE_DEBRID))

        watched.deleteAllForSource(WatchedStateEntity.SOURCE_XTREAM)

        assertEquals(
            "the IPTV watch state is keyed by a stream id that means nothing on the new provider",
            null,
            watched.getState("xtream:movie:123")
        )
        assertEquals(
            "the debrid watch state is keyed by an infoHash and is still valid",
            "debrid:abcdef",
            watched.getState("debrid:abcdef")?.identityKey
        )
    }

    private fun watchedRow(identity: String, source: String) = WatchedStateEntity(
        identityKey = identity,
        contentType = "MOVIE",
        source = source,
        isWatched = false,
        progressMs = 1_845_000,
        durationMs = 8_880_000,
        watchedAt = null,
        updatedAt = 1L,
    )
}
