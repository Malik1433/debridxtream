# XtreamRepository Deep Audit Report

Date: 2026-05-25
Scope: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepository.kt`
Mode: Audit only. No Android source, UI, Player, Home, Series, Debrid, Live, or VOD code changed.

## Executive Summary

`XtreamRepository.kt` is a god-class risk: 2,274 lines, 92 functions, and direct ownership of API initialization, credentials, sync orchestration, JSON cache, Room writes, EPG parsing, VOD/Series fallbacks, favorites, search, and playback URL construction.

Compared with current Android architecture guidance and mature TV/IPTV architecture patterns, the main gap is not one bug. The issue is that too many critical responsibilities share one mutable singleton state. This makes stale data, wrong-server playback URLs, partial cache overwrites, hidden fallback success, and concurrency races hard to reason about and hard to test.

## Standards Baseline

- Android official data-layer guidance: repositories should coordinate data sources and resolve conflicts around a clear single source of truth.
- Android Paging/RemoteMediator guidance: for network plus local database flows, the database should drive the UI as the source of truth; network fetches update local storage.
- Android domain-layer guidance: complex reusable business logic should be split into use cases to reduce large classes and improve testability.
- OWASP MASVS: mobile apps should protect sensitive storage, network communication, code quality, and sensitive logging.
- Android TV / Live TV reference architecture: mature TV apps separate TV/source integration, data storage, playback, and UI concerns rather than centralizing all runtime behavior in one repository.

Sources:
- https://developer.android.com/topic/architecture/data-layer
- https://developer.android.com/topic/libraries/architecture/paging/v3-network-db
- https://developer.android.com/topic/architecture/domain-layer
- https://mas.owasp.org/MASVS/
- https://source.android.com/devices/tv

## Evidence Snapshot

| Metric | Value |
|---|---:|
| Lines | 2,274 |
| Functions | 92 |
| Constructor dependencies | 8 |
| Mutable caches/state fields | 10+ |
| Direct public responsibilities | login, sync, live, VOD, series, EPG, favorites, search, URL building |

High-risk line anchors:
- Mutable singleton credentials/API state: `XtreamRepository.kt:114-125`, `156-185`
- Full sync writes success cache even after individual fetch failure: `241-413`
- VOD per-category locks/cache: `679-728`
- Series fallback/cooldown/client-side all-series filtering: `1049-1199`, `1997-2228`
- Series detail fallback and debug logging: `1237-1410`
- Full EPG clear-before-parse flow: `1590-1717`
- Playback URL construction with embedded username/password: `1928-1946`
- Non-atomic JSON cache helper: `data/cache/CacheHelper.kt:13-72`

## Findings

### P0-1: Partial Sync Can Be Marked Successful And Overwrite Good Cache

`syncContent()` converts failed live/VOD/series fetches into empty data via `getOrNull() ?: emptyList()` and still writes a new `IptvCache` with `SyncState.SUCCESS`.

Impact:
- A temporary endpoint failure can replace good cache with empty sections.
- UI may show stale/empty categories as a successful refresh.
- This matches prior stale-data risk patterns in project memory and `DO_NOT_REPEAT.md`.

Evidence:
- `liveResult.getOrNull() ?: LiveCacheData(emptyList(), emptyList())`
- `vodResult.getOrNull() ?: VodCacheData(emptyList(), emptyList())`
- `seriesResult.getOrNull() ?: SeriesCacheData(emptyList(), emptyList())`
- `cacheHelper.writeCache(cache)` after those substitutions.

World-standard expectation:
- Treat each content domain as independently versioned.
- Do not overwrite a healthy domain with an empty fallback unless the provider explicitly confirms empty data.
- Persist per-domain status, timestamp, error, and source.

Recommended fix direction:
- Split full sync into `LiveSyncRepository`, `VodSyncRepository`, `SeriesSyncRepository`.
- Keep previous domain cache when a domain fetch fails.
- Return `PARTIAL_SUCCESS` with per-domain errors instead of `SUCCESS`.

### P0-2: Mutable Singleton Credentials And API Service Can Drift Across Providers

The repository stores `username`, `password`, `baseUrl`, and `apiService` as mutable fields. `ensureInitialized()` only initializes when `apiService == null`; it does not update if the user changes provider/server while the service remains non-null.

Impact:
- Wrong provider credentials can be used for later Live/VOD/Series/EPG calls.
- Playback URLs can be built against a caller-provided `baseServerUrl` while using stale repository `username/password`.
- Bugs look like provider failure or stale cache, not obvious config drift.

Evidence:
- `private var username = "username"`, `password = "password"`, `baseUrl = ""`
- `ensureInitialized()` checks only `!isInitialized()`
- URL builders use repository credentials plus supplied `baseServerUrl`.

World-standard expectation:
- Provider session should be immutable or explicitly versioned.
- Changing server/user should create a new API session and invalidate only the relevant caches.

Recommended fix direction:
- Introduce `XtreamSession(serverUrl, username, password, sessionId)`.
- Make API calls take or resolve a session snapshot.
- Reinitialize when server/user changes.
- Remove public `getPassword()` unless a dedicated secret-safe component needs it.

### P0-3: EPG Sync Clears Existing Guide Before New Parse Is Proven

`fetchAndSaveEpg()` calls `epgDao.clearAll()` before streaming parse and batch insert. If network body parsing, memory pressure, cancellation, or DB insert fails after the clear, the app can lose all guide rows.

Impact:
- Manual/scheduled EPG refresh can convert usable guide data into empty guide data.
- On low-memory TV devices, this is a high-probability failure mode.

Evidence:
- `epgDao.clearAll()` before `response.body()!!.use`
- cancellation/OOM paths return `Result.Error` after data may already be cleared.

World-standard expectation:
- Stage into a temporary table or transaction and swap only after parse success.
- Keep old guide if refresh fails.

Recommended fix direction:
- Parse into staged batches keyed by `syncRunId`.
- Replace old EPG only after final batch succeeds.
- Keep `lastSuccessfulSync` separate from attempted sync.

### P1-1: Cache Writes Are Not Atomic

`CacheHelper.writeCache()` writes directly to `iptv_cache.json` using `FileWriter`. If the app dies mid-write, the cache file can be partially written. `readCache()` clears corrupt cache on parse failure.

Impact:
- A crash during cache write can delete the only offline fallback.
- Combined with P0-1, partial network failure can permanently degrade local data.

Evidence:
- `BufferedWriter(FileWriter(file))`
- no temp file, fsync, or atomic rename.
- parse failure calls `clearCache()`.

Recommended fix direction:
- Write to `iptv_cache.json.tmp`, flush/sync, then atomic rename.
- Prefer Room as the single source of truth for large datasets.

### P1-2: Multiple Mutable Maps Are Not Thread-Safe

The singleton has `mutableMapOf` caches for series details, per-category series, all-series fallback, and VOD lists. Calls can happen from UI, workers, ViewModels, and background sync paths.

Impact:
- Race conditions during category switching, background sync, EPG sync, and detail loading.
- Hard-to-reproduce stale reads or lost writes.

Evidence:
- `seriesDetailCache = mutableMapOf`
- `perCategorySeriesCache = mutableMapOf`
- `perCategoryVodCache = mutableMapOf`
- only VOD fetch locks are per-category; the maps themselves are not consistently protected.

Recommended fix direction:
- Use `ConcurrentHashMap` or a single repository-state mutex.
- Better: push cached collections into Room and expose `Flow` from DAO/Paging.

### P1-3: Series Fallback Can Hide Provider Outage As Empty Success

Series fallback/cooldown returns `Result.Success(emptyList())` in several places when no cached fallback exists or all-series filtering returns no match.

Impact:
- UI cannot distinguish "category truly empty" from "provider endpoint failed/cooling down".
- Can cause empty-state flashes or false no-content screens.

Evidence:
- `maybeReturnSeriesDuringCooldown()` returns `emptyList()`.
- `fallbackSeriesFetch()` returns `Result.Success(emptyList())` on unmatched category.

Recommended fix direction:
- Add typed result states: `EmptyConfirmed`, `UnavailableWithCache`, `UnavailableNoCache`, `PartialFromFallback`.
- Preserve last good category rows when endpoint is unavailable.

### P1-4: Sensitive Data And User Content Logging Is Too Broad

The file does not appear to log raw username/password directly, but it logs search queries, favorite names, category ids, series names, and fallback sample series. Exceptions can include URLs depending on lower layers.

Impact:
- QA logcat/report artifacts can expose viewing habits or provider catalog details.
- This conflicts with the project Sensitive Log Redaction pattern and OWASP MASVS logging expectations.

Evidence:
- `Saved search query: $query`
- `Added favorite: $streamId (name: $name, type: $type)`
- `Sample Series: name=${it.name}, cat_id=...`
- `errorMessage = e.message`

Recommended fix direction:
- Route logs through `SensitiveLogRedactor`.
- Log counts, hashed ids, and source domains only.
- Never put exception raw messages into user-visible sync state without sanitization.

### P1-5: Direct Source Fields Are Present But URL Builders Ignore Them

Models include `direct_source`, but URL builders always construct `/live/username/password`, `/movie/username/password`, or `/series/username/password`.

Impact:
- Providers that supply direct stream URLs may be forced through credential URL templates.
- Can break non-standard Xtream providers and cause unnecessary credential-bearing URLs.

Evidence:
- `XtreamStream.direct_source`, `XtreamVodInfo.direct_source`, `XtreamEpisodeInfo.direct_source`
- `buildLiveStreamUrl()`, `buildVodStreamUrl()`, `buildSeriesEpisodeStreamUrl()` ignore direct source fields.

Recommended fix direction:
- Add a URL policy helper that prefers validated `direct_source` when present and allowed.
- Keep HTTP/HTTPS and cleartext policy centralized.

### P2-1: VOD Per-Category Lock And Cache Growth Is Unbounded

`vodFetchLocks` and `perCategoryVodCache` grow by category id and are not pruned.

Impact:
- Large providers with many VOD categories can grow memory over a long session.
- TV devices are memory-constrained; this risk compounds with poster grids and playback.

Evidence:
- `vodFetchLocks.getOrPut(categoryId) { Mutex() }`
- `perCategoryVodCache[categoryId] = streams`
- no TTL, max size, or removal.

Recommended fix direction:
- Use bounded LRU/TTL cache.
- Remove lock after failed/successful fetch when no longer needed.
- Prefer Room/Paging as UI cache.

### P2-2: Class Boundaries Block Good Tests

Existing tests cover some stream lookup and URL construction paths, but the high-risk behavior is not covered: partial sync, provider switch, cache corruption, EPG failure after clear, concurrent category loads, and fallback result semantics.

Impact:
- Refactors are risky because behavior is broad and implicit.
- Bugs can pass compile/build and still fail in runtime data state.

Recommended test gaps:
- Partial live failure preserves previous live cache and returns partial status.
- Provider switch invalidates/rebuilds API service.
- EPG parse failure does not clear old guide.
- Cache write crash leaves previous cache readable.
- Series 404 fallback returns typed unavailable state, not empty success.
- Direct source URL is preferred when policy allows.

## Comparison Against Mature IPTV / Android TV Architecture

| Area | Current `XtreamRepository` | World-standard target |
|---|---|---|
| Data ownership | JSON cache + Room + mutable maps all mixed | Room/Paging as UI source of truth; network writes through mediators |
| Provider session | Mutable singleton fields | Immutable/versioned session object |
| Sync result | Mostly single success/error | Per-domain result with partial states |
| EPG refresh | Clear then parse/insert | Stage then atomic swap |
| Cache file | Direct JSON write | Atomic write or database-backed cache |
| URL building | Embedded in repository | Dedicated stream URL policy/resolver |
| Fallback semantics | Empty list can mean outage | Typed empty/unavailable/fallback states |
| Testing | URL/lookup tests present | Concurrency, cache integrity, provider drift, partial failure tests |
| Security | Better than raw credential logging, but broad content logs remain | Redacted structured logs and no raw exception leakage |

Best IPTV apps and reference TV stacks generally optimize for:
- Fast offline startup from local cache/database.
- Per-category lazy loading with stable paging.
- Clear provider/session isolation.
- Playback URL resolution separate from catalog sync.
- Atomic guide/catalog refresh so failed sync never destroys current UI data.
- Log redaction because IPTV URLs commonly embed credentials.

## No-Code Refactor Plan

Do not implement all at once. The safe path is phased and test-led.

### Phase 0: Freeze Behavior With Tests

Add tests for:
- provider switch reinitialization
- partial sync preserving old cache
- EPG failure preserving old guide
- cache corruption/atomic write behavior
- series unavailable vs empty semantics

### Phase 1: Extract Session And URL Policy

Create:
- `XtreamSession`
- `XtreamSessionManager`
- `XtreamStreamUrlBuilder`

Keep old public repository methods as wrappers during migration.

### Phase 2: Split Sync Domains

Create:
- `LiveCatalogRepository`
- `VodCatalogRepository`
- `SeriesCatalogRepository`
- `EpgRepository`

Each owns its cache status, stale policy, and tests.

### Phase 3: Move Large Lists Toward Room/Paging Source Of Truth

Use Room-backed flows/paging for Live/VOD/Series list UI and keep JSON cache only as migration/offline bootstrap if still needed.

### Phase 4: Harden Logging And Cache Writes

Add atomic file writes and structured redacted logging. Replace raw exception messages in UI state with safe error classes.

## Acceptance Criteria For Future Fixes

- No source behavior changes without focused tests first.
- Old cache remains available after failed sync.
- Switching IPTV credentials cannot reuse old API service or old playback credentials.
- Full EPG refresh failure cannot erase old EPG rows.
- Empty list means confirmed empty, not network failure.
- Build passes and user-facing behavior is verified on device when touched flows are changed.

## Current Verdict

Not production-standard yet for a core IPTV data spine. The file contains several good local fixes, such as sync mutexes, VOD fetch throttling, streaming EPG parse, short EPG support, and non-TS live extension preservation. But as a whole, it is too centralized and too tolerant of failure-as-success. The next work should be audit-driven tests and small extractions, not broad rewrite.
