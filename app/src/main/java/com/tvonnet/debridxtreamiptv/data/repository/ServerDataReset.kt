package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.ServerScopedDatabase
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything on this device that belongs to ONE IPTV provider, removed in one go.
 *
 * The catalogue, favourites, watch history and watched state are keyed by `streamId`, and two
 * providers both number their streams from 1. So after a change of provider the old rows are not
 * merely out of date — they are actively wrong: a poster from one server in front of a stream id
 * that now means something else on another. That is the "ghost titles" and the Continue Watching
 * entry that will not play.
 *
 * **What this deliberately does NOT touch**, because none of it is the provider's:
 *  - debrid credentials, addons and their favourites/watch state (`source = debrid`) — those ids
 *    are infoHashes and mean the same thing on every server,
 *  - `release_languages`, learned from playback and keyed by the debrid identity,
 *  - app settings (language, playback preferences, Live layout) and the licence/account state,
 *  - typed search history on a SWITCH — it is the customer's own words and points at nothing.
 *    A logout is different: the account is leaving the device, so [Scope.ACCOUNT] takes it too.
 *
 * Every write is on IO. The whole thing is one suspend call that must complete BEFORE a new sync
 * starts writing, or the sync's rows get deleted along with the old provider's.
 */
@Singleton
class ServerDataReset @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val cacheManager: CacheManager,
    private val favoritesCache: FavoritesCache,
    private val homePreferences: HomePreferences,
    private val repository: XtreamRepository,
) {

    /** How much goes. */
    enum class Scope {
        /**
         * A change of provider — no longer a caller in the app.
         *
         * Option A gave each provider its own database and preference files, so switching opens
         * the other library instead of destroying this one. The scope stays because the behaviour
         * is still meaningful (clear THIS provider and start it again from the server) and because
         * deleting it would take the source-scoping rules with it.
         */
        SERVER,

        /** A logout: the provider's data AND everything else this account left behind. */
        ACCOUNT,
    }

    /**
     * Removes the previous provider's data and records that what is on disk now belongs to the
     * active one — so an interrupted switch is retried rather than left half-done.
     *
     * Failures are logged and swallowed per store on purpose: a device that cannot delete one
     * table must still finish clearing the other twelve. The one exception is cancellation, which
     * is rethrown so a caller that goes away really does stop this work.
     */
    suspend fun purge(scope: Scope) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Purging per-server data (scope=$scope)")
        purgeCatalogue()
        purgeUserData(scope)
        purgeCachesAndStamps()
        // Last, and only after every store above has been dealt with: the on-disk data now belongs
        // to whoever the credentials point at. Written even on a partial failure — the stores that
        // did fail are empty-or-stale either way, and re-running the purge forever would trap the
        // device in a loop instead of letting the fresh sync repopulate.
        if (scope == Scope.ACCOUNT) purgeOtherProviders()
        CredentialsPreferences(context).markServerDataSynced()
        Log.i(TAG, "Purge complete")
    }

    /**
     * The OTHER providers this account ran on this device (Option A).
     *
     * The tables above only ever reach the database that is open — which is this provider's, and
     * that is the whole point of the per-provider files. On a logout that is not enough: the
     * account is leaving the device, and the two other servers' libraries would sit on disk for
     * the next person who signs in.
     *
     * Files rather than tables, because those databases are not open and opening each one just to
     * empty it would cost a Room build per provider. Their preference files go the same way.
     */
    private fun purgeOtherProviders() {
        step("other providers' databases") {
            val active = ServerScopedDatabase.nameFor(CredentialsPreferences(context).activeServerFingerprint)
            ServerScopedDatabase.allDatabaseFiles(context)
                .filterNot { it.name == active || it.name.startsWith("$active-") }
                .forEach { it.delete() }
        }
        step("other providers' preferences") {
            val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            dir.listFiles { f -> SCOPED_PREF_BASES.any { f.name.startsWith("${it}_") } }
                ?.forEach { it.delete() }
        }
    }

    /** The provider's catalogue: what to watch, and everything derived from it. */
    private suspend fun purgeCatalogue() {
        step("channels") { database.channelDao().deleteAllChannels() }
        step("categories") { database.categoryDao().deleteAllCategories() }
        step("movies") { database.vodDao().deleteAllMovies() }
        step("series") { database.seriesDao().deleteAllSeries() }
        step("seasons") { database.seriesDao().deleteAllSeasons() }
        step("episodes") { database.seriesDao().deleteAllEpisodes() }
        step("series v2") { database.seriesDaoV2().clearAll() }
        step("episodes v2") { database.episodeDaoV2().clearAll() }
        // The EPG is the one store that already replaced itself generationally on a successful
        // sync. It is still cleared here so the guide is not showing the old provider's schedule
        // in the window between the switch and the first EPG fetch.
        step("epg") { database.epgDao().clearAll() }
    }

    /** What the customer did with that catalogue. */
    private suspend fun purgeUserData(scope: Scope) {
        step("favourites") {
            when (scope) {
                Scope.SERVER -> repository.clearFavoritesForSource(WatchedStateEntity.SOURCE_XTREAM)
                Scope.ACCOUNT -> repository.clearAllFavorites()
            }
            favoritesCache.clearCache()
        }
        step("watched state") {
            when (scope) {
                Scope.SERVER ->
                    database.watchedStateDao().deleteAllForSource(WatchedStateEntity.SOURCE_XTREAM)
                Scope.ACCOUNT -> {
                    database.watchedStateDao().deleteAllForSource(WatchedStateEntity.SOURCE_XTREAM)
                    database.watchedStateDao().deleteAllForSource(WatchedStateEntity.SOURCE_DEBRID)
                }
            }
        }
        // Continue Watching and Recent Live are one preference file, and every entry in it holds a
        // stream URL built from the OLD host and the OLD username and password. Those two lists
        // are exactly what the customer reported: rows that survive a switch, confuse them, and
        // either fail to open or quietly stream from the provider they moved away from.
        step("watch history") { WatchHistoryPreferences(context).clearAll() }
        step("home rows") { homePreferences.clearSelectedCategories() }
        if (scope == Scope.ACCOUNT) {
            step("search history") { database.searchHistoryDao().deleteAllSearches() }
        }
    }

    /** The copies of the catalogue that are not the catalogue: memory, disk, freshness stamps. */
    private suspend fun purgeCachesAndStamps() {
        step("memory caches") {
            repository.clearMemoryCache()
            cacheManager.clearMemoryCache()
        }
        // Cache files AND the freshness stamps together — see the repository method for why the
        // stamps are the more dangerous half.
        step("cache files + sync stamps") { repository.clearCatalogFilesAndSyncStamps() }
        // The last channel the app auto-tunes to, and the category it was in. Both are ids from
        // the old provider, so resume-last-live would open a channel that is not there.
        step("live resume") { SettingsPreferences(context).clearLastLiveResume() }
    }

    private inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Purge step failed: $what", e)
        }
    }

    private companion object {
        private const val TAG = "ServerDataReset"

        /** The preference files Option A forks per provider; a logout removes every fork. */
        private val SCOPED_PREF_BASES = listOf("watch_history", "home_config", "sync_prefs")
    }
}
