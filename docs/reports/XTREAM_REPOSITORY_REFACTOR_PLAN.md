# XtreamRepository Refactor Mapping Plan

Date: 2026-05-25
Mode: Refactor Mapping Only
Scope: `XtreamRepository.kt`, `CacheHelper.kt`, direct repository/cache tests, and listed reports.

## Status

NO RUNTIME CODE CHANGED.

This is a pre-extraction map only. Do not extract classes, rename files, or move logic until the freeze tests below exist.

## Current Responsibility Map

### Session / API Init
- `getApiService()`
- `getUsername()`
- `getPassword()`
- `getServerUrl()`
- `initialize(baseUrl, username, password)`
- `ensureInitialized(serverUrl, username, password)`
- `isInitialized()`
- `login(username, password)`

### Full Sync
- `fetchAllAndCache()`
- `syncInitialData()`
- `refreshCategoriesAndLatest()`
- `shouldRunBackgroundSync()`
- `forceRefresh()`
- Private spine: `syncContent()`, `updateSyncProgress()`, `saveSyncMetadata()`

### Live
- `ensureLiveCategories()`
- `fetchLiveStreamsForCategory(categoryId)`
- `fetchLiveStreamsForCategoryStrict(categoryId)`
- `getCachedLiveChannelCount(categoryId)`
- `getCachedLiveStreams(categoryId)`
- `enrichChannelsWithCurrentEpg(channels)`
- `getLiveStreamById(streamId)`
- Private spine: `fetchLiveCategoriesAndStreams()`

### VOD
- `fetchVodStreamsForCategory(categoryId)`
- `ensureVodCategories()`
- `getMovieSources(streamId, title, primaryCategoryId, yearHint)`
- `getVodById(streamId)`
- Private spine: `fetchVodCategoriesAndStreams()`, `fetchLatestVodStreams()`, `updateVodCategoriesCache()`, `getVodStreamsForCategoryCached()`, `findVodById()`, `normalizeTitle()`, `extractYear()`, `buildMovieSourceLabel()`

### Series
- `ensureSeriesCategories()`
- `fetchSeriesForCategory(categoryId)`
- `getSeriesCategoryStatus(categoryId)`
- `refreshSeriesCategories()`
- `fetchSeriesDetail(seriesId, forceRefresh)`
- `getSeriesDetailFlow(seriesId)`
- `syncSeriesDetail(seriesId)`
- `getSeriesById(streamId)`
- `updateEpisodePlaybackStatus(episodeId, isWatched, resumePosition, duration)`
- Private spine: `fetchSeriesCategoriesAndStreams()`, `fetchLatestSeriesStreams()`, `fetchSeriesDetailFromNetwork()`, `fetchFallbackSeriesDetail()`, `persistSeriesCategories()`, `updateSeriesCacheForCategory()`, `fallbackSeriesFetch()`, `maybeReturnSeriesDuringCooldown()`, `handleSeriesFallback()`, `markSeriesCategoryHealthy()`, `updateSeriesCategoryStatus()`, `persistSeriesStreamsForCategory()`, `fetchAllSeries()`

### EPG
- `fetchAndSaveEpg()`
- `ensureEpgData(force, maxAgeMs)`
- `getEpgByChannel(channelId)`
- `getCurrentProgram(channelId)`
- `getNextProgram(channelId)`
- `fetchShortEpgPrograms(streamId, channelKey, limit)`
- `fetchShortEpgNowNext(streamId, channelKey, limit)`
- `getUpcomingPrograms(channelId)`
- `hasEpgData(channelId)`
- `clearOldEpg()`
- Private spine: `fetchEpg()`, `XtreamEpgListing.toEpgEntityOrNull()`, `decodeBase64IfPossible()`, `saveEpgLastSync()`

### Favorites
- `getAllFavorites()`
- `getFavoritesByType(type)`
- `isFavorite(streamId)`
- `addFavorite(streamId, type, name, iconUrl)`
- `removeFavorite(streamId)`
- `clearAllFavorites()`

### Search
- `getRecentSearches(limit)`
- `saveSearchQuery(query)`
- `removeSearchQuery(query)`
- `clearSearchHistory()`
- `searchSeries(query)`
- `searchVod(query)`
- `searchLive(query)`
- `searchEpg(query)`

### Playback URL Building
- `buildLiveStreamUrl(stream, baseServerUrl)`
- `buildVodStreamUrl(vod, baseServerUrl)`
- `buildSeriesEpisodeStreamUrl(episodeId, containerExtension, baseServerUrl)`

### Cache Helpers
- `XtreamRepository.readCache()`
- `XtreamRepository.clearMemoryCache()`
- `CacheHelper.writeCache(cache)`
- `CacheHelper.readCache()`
- `CacheHelper.clearCache()`
- `CacheHelper.memorySnapshot()`
- `CacheHelper.clearMemorySnapshot()`
- `CacheHelper.writeSeriesDetail(seriesId, detail)`
- `CacheHelper.readSeriesDetail(seriesId)`
- `CacheHelper.writeAllSeries(series)`
- `CacheHelper.readAllSeries()`

## Shared Mutable State Map

| State | Writers | Readers | Extraction Risk |
|---|---|---|---|
| `apiService` | `initialize()`, failure path in `initialize()` | all network methods, `getApiService()`, `isInitialized()` | High: provider switch/session drift boundary. |
| `username`, `password`, `baseUrl` | `initialize()` | getters, login/network calls, URL builders, EPG | High: credential/session state crosses all domains. |
| `GlobalConfig.baseUrl` | `initialize()` | outside current scope | High: hidden global side effect. |
| `memoryCache` | `syncContent()`, category cache updates, `readCache()`, `clearMemoryCache()`, lookup methods | `readCache()`, lookups, VOD search, fallback paths | High: JSON cache and Room/cache-manager can disagree. |
| `CacheHelper.inMemoryCache` | `CacheHelper.writeCache()`, `readCache()`, `clearCache()`, `clearMemorySnapshot()` | `memorySnapshot()`, repository lookup fallback | Medium: process-wide cache not scoped to provider session. |
| `seriesDetailCache` | `fetchSeriesDetail()`, `fetchSeriesDetailFromNetwork()`, `clearMemoryCache()` | `fetchSeriesDetail()` | Medium: no session/version key. |
| `perCategorySeriesCache` | `updateSeriesCacheForCategory()`, `clearMemoryCache()` | series lookup, fallback, cooldown | High: outage fallback and confirmed-empty semantics. |
| `allSeriesCacheFallback` | `fetchAllSeries()`, fallback reads, `persistSeriesCategories()`, `clearMemoryCache()` | series fetch/fallback | High: broad all-series fallback can mask provider failures. |
| `perCategoryVodCache` | `fetchVodStreamsForCategory()`, cached category lookup, `clearMemoryCache()` | VOD fetch, `getMovieSources()`, VOD lookup | Medium: no TTL/size bound; URL/source extraction touches it. |
| `vodFetchLocks` | `fetchVodStreamsForCategory()` | `fetchVodStreamsForCategory()` | Low/medium: lock lifecycle is coupled to VOD cache growth. |
| `syncMutex`, `_syncProgress`, `syncPrefs` | sync methods | sync methods, UI observers | High: full-sync partial success behavior must be frozen first. |
| `epgSyncMutex`, `epgPrefs` | EPG sync/save methods | EPG freshness methods | High: clear-before-parse risk must be frozen first. |
| `seriesCategoryStatus` | series fallback/cooldown helpers | `getSeriesCategoryStatus()`, series fetch | High: outage vs empty behavior must be explicit before extraction. |

## Existing Test Coverage

### Real repository/cache behavior coverage
- `app/src/test/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepositoryTest.kt`
  - Covers initialize smoke, login success/failure via reflected `apiService`, no-API cache fallback, no-API errors, `readCache()`, memory-cache reuse, `clearMemoryCache()`, and `forceRefresh()` cache path.
  - Important note inside test: repository creates its own API service and needs DI refactor for better testability.
- `app/src/test/java/com/tvonnet/debridxtreamiptv/repository/XtreamRepositoryStreamLookupTest.kt`
  - Covers `getLiveStreamById()`, `getVodById()`, `getSeriesById()`, `getMovieSources()` aggregation/year filtering, `buildLiveStreamUrl()`, `buildVodStreamUrl()`, mixed cache lookup, and large-cache lookup.
- `app/src/test/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivityTest.kt`
  - Uses a mocked repository for series detail/favorites and checks `buildSeriesEpisodeStreamUrl()` expected format via the repository mock. It protects call expectation, not repository implementation.

### Mock-only or weak freeze coverage
- `SeriesRepositoryErrorHandlingTest.kt` spies/mocks `fetchSeriesForCategory()` and mostly verifies mocked `Result` behavior, so it does not freeze real fallback semantics.
- `SeriesSyncWorkerTest.kt` mocks `XtreamRepository`; useful for worker behavior, not repository extraction safety.
- ViewModel/Paging tests that import `XtreamRepository` are caller tests, not repository behavior freeze tests.

## Missing Tests Required Before Extraction

1. Provider switch reinitializes API/session:
   - Given initialized server A credentials, when `ensureInitialized(serverB, userB, passB)` is called, API/session and URL credentials must not remain server A.
2. Partial sync does not overwrite good cache:
   - Given old cache with live/VOD/series, when one network domain fails, old good domain data must not be replaced by empty data and sync must not report clean success.
3. EPG failure does not erase old guide:
   - Given existing EPG rows, when full EPG fetch/parse/insert fails after start, old guide remains readable.
4. Cache write corruption does not destroy old cache:
   - Given valid `iptv_cache.json`, when a write is interrupted/corrupt, next read preserves or recovers previous valid cache instead of clearing it.
5. Series outage is not treated as confirmed empty:
   - Given provider 404/500/timeout with no valid fallback, result should be distinguishable from a confirmed empty category.
6. Playback URL builder preserves existing behavior:
   - Freeze current live/VOD/series formats, trailing slash behavior, default extensions, non-TS live extension preservation, and initialized credential behavior before moving URL construction.

## Safest Next Phase

Recommended: Phase A - test freeze only.

Why:
- The highest-risk boundaries are not URL formatting alone; they are session drift, partial sync/cache overwrite, EPG destructive refresh, and series outage semantics.
- Phase B (`XtreamStreamUrlBuilder`) is mechanically tempting because URL builders are small, but it would not protect the current P0 cache/session risks and could still be wrong if repository credentials are stale.
- Phase C (`XtreamSession` snapshot) is the correct architecture direction, but changing session handling before tests would touch the widest runtime surface.

Phase A deliverable:
- Add only focused tests for the six missing cases above.
- No runtime behavior changes.
- No extraction classes.
- No Player/Debrid/Live/Continue Watching/Episode Browser edits.

After Phase A passes, choose between:
- Phase B if the goal is a strict low-risk extraction with existing behavior frozen.
- Phase C only after provider-switch tests prove the current behavior and desired compatibility contract.

## Test Freeze Pass - 2026-05-25

Mode: Test Freeze Only

Status: NO RUNTIME CODE CHANGED.

### Tests Added

- `XtreamRepositoryStreamLookupTest.buildLiveStreamUrl uses initialized credentials and preserves trailing slash behavior`
  - Freezes live URL format, initialized credential use, trailing slash trimming, and non-TS live extension preservation.
- `XtreamRepositoryStreamLookupTest.ensureInitialized currently keeps first initialized provider when service already exists`
  - Freezes current provider-switch risk: after server A is initialized, `ensureInitialized(serverB, userB, passB)` currently leaves URL/session credentials on server A.
- `XtreamRepositoryStreamLookupTest.buildSeriesEpisodeStreamUrl formats URL and trims trailing slash`
  - Freezes series episode URL format and trailing slash behavior.
- `XtreamRepositoryStreamLookupTest.buildSeriesEpisodeStreamUrl uses default mp4 extension when blank`
  - Freezes default series episode extension behavior.
- `XtreamRepositoryStreamLookupTest.readCache currently clears corrupt cache file and returns null`
  - Freezes current corrupt-read behavior: a corrupt `iptv_cache.json` read returns null and deletes the cache file.
- `XtreamRepositoryTest.fetchAllAndCache currently writes empty success cache when domain fetches fail`
  - Freezes current partial-sync/cache overwrite risk: live/VOD/series fetch failures still produce a successful empty cache write.

### Tests Blocked

- Desired provider-switch behavior is blocked by current implementation: `ensureInitialized()` does not rebuild the API/session when `apiService` already exists. The active test freezes the current risk; the desired contract needs an approved runtime behavior change later.
- Series outage vs confirmed empty remains blocked for real repository semantics. Existing seams require broader API/fallback scaffolding to distinguish provider outage from confirmed empty without changing production architecture.
- EPG failure after refresh start remains blocked. A safe unit test needs focused EPG parser/DAO setup or a production seam; adding that scaffold would exceed this test-freeze-only pass.
- Desired partial-sync behavior, "do not overwrite good cache on partial failure," is not current behavior. The active test freezes the existing empty-success overwrite risk; the desired contract needs an approved behavior change.

### Current Behavior Frozen

- Live playback URLs use initialized repository credentials, trim trailing server slash, and preserve a non-TS live extension when present.
- VOD playback URL format remains covered by the existing stream lookup test.
- Series episode playback URLs trim trailing server slash and default blank extension to `mp4`.
- `ensureInitialized()` currently does not switch provider/session once `apiService` exists.
- `CacheHelper.readCache()` currently treats corrupt JSON as destructive: returns null and clears the cache file.
- `fetchAllAndCache()` currently writes an empty successful cache when category/domain fetches fail.

### Test Results

- `.\gradlew.bat :app:testDebugUnitTest --tests "*XtreamRepository*"`: blocked before execution during `:app:compileDebugUnitTestKotlin` by unrelated compile errors in `PlayerViewModelDebridDirectPassthroughTest.kt` (`DebridResolutionManager` unresolved references).
- `.\gradlew.bat :app:testDebugUnitTest --tests "*CacheHelper*"`: blocked by the same unrelated compile errors.
- `.\gradlew.bat :app:testDebugUnitTest`: blocked by the same unrelated compile errors.

### Remaining Risk Before Extraction

- The new freeze tests cannot execute until the unrelated player unit-test compile error is fixed outside this Xtream-only scope.
- EPG destructive-refresh behavior is still report-only and should be frozen before extracting EPG responsibilities.
- Series outage semantics are still report-only and should be frozen before moving series fallback logic.
- Provider-switch and partial-sync tests intentionally freeze current risky behavior, not the desired future behavior.
