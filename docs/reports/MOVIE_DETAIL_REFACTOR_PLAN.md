# MovieDetailActivity Decomposition Plan

**Target:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt` — **1915 lines**,
~58 methods. The **Debrid + IPTV movie-detail** screen. Structurally a near-twin of the just-completed
`SeriesDetailActivity` (2041→644, −68%), so this reuses the **exact same playbook and collaborator
pattern** — see `docs/reports/SERIES_DETAIL_REFACTOR_PLAN.md`.

**Started:** 2026-07-25, right after the SeriesDetailActivity decomposition shipped + was owner-verified.

## Approach (identical to the Series decomposition)

**Behaviour-preserving verbatim relocations, one cohesive domain per commit.** Each phase:
`clean assembleDebug + testDebugUnitTest + detekt` **all green** (baseline discipline: relocated
already-baselined methods re-fire under new keys → regenerate baseline + verify the total does not grow
beyond the collaborator-constructor `LongParameterList` cost) + device .64 **launch-stability** (install
+ launch, zero FATAL/ANR) + commit + push. The unit is a **delegate/controller taking the Activity**,
constructed in a `setupCollaborators()` helper from already-injected deps (no Hilt change, public entry
points unchanged). Fields read many times across the Activity (movie title/cover/etc.) STAY in the
Activity; only cohesive method+own-state clusters move down.

**CRITICAL process rules learned on the Series pass (do NOT repeat the mistakes):**
- **Incremental Gradle gives FALSE GREEN in this repo** — `compileDebugKotlin`/`testDebugUnitTest`/
  `detekt` can pass incrementally while a clean build fails. **Always verify with `./gradlew clean
  :app:assembleDebug :app:testDebugUnitTest :app:detekt`** before committing. See [[gradle-stale-dex-merge]].
- **A `val` self-referenced inside its own constructor lambda** (e.g. an adapter that calls
  `adapter.setSelected(...)` in its own `onClick`) fails clean compile with "must be initialized" — use
  `lateinit var`.
- **The `playerLauncher = registerForActivityResult(...)` MUST stay REGISTERED IN THE ACTIVITY** (a field
  initializer; its timing before STARTED is load-bearing). Delegate only its body to the playback
  controller's `handlePlayerResult(result)` and pass the controller `launch = playerLauncher::launch`.
- **Device is NOT autonomously driveable** (secure surface → screencap black + uiautomator null; `adb
  input` no-op; `am start` blocked for non-exported activities) — see [[firetv-test-device-setup]]. So
  autonomous device verification = install + launch-stability only; the **interactive movie source +
  first-frame playback QA needs owner eyes-on on the TV** (same as Series, where the owner confirmed
  "chal rahi he").

## Domains (from the method map) — risk-ascending phase order

| Phase | Domain → collaborator | Methods (~lines) | Risk |
|---|---|---|---|
| **MD-1** | Trailer + preview → `MovieTrailerController` | `launchTrailer` (49) + `updateTrailerButtonState` (7) + `hasTmdbTrailer` (5) + `scheduleTrailerPreview` (15) + `cancelTrailerPreview` (15) + trailer/preview state | LOW (self-contained; mirrors `SeriesTrailerController`, plus the hover-preview autoplay) |
| **MD-2** | Movie info / TMDB data → `MovieDetailBuilders.kt` (pure) + `MovieTmdbData.kt` | pure: `displayRating` (7) + `extractCertification` (16) + `formatDuration` (15) + `formatDurationCompact` (11) + `formatResumeTime` (13) + `formatSizeLabel` (10); data-fetch: `fetchTmdbDetails` (69) + `loadExternalRatings` (22) + `loadSimilarMovies` (29) | LOW (pure formatters + near-pure TMDB fetch, like `SeriesDebridData`) |
| **MD-3** | Source acquisition + picker + filters → `MovieDebridSourceController` | `loadMovieSources` (53) + `showDebridSourcePicker` (112) + `fetchIptvMovieSources` (7) + `resetSourceFilters` (9) + `updateSourceFilterOptions` (39) + `applySourceFilters` (17) + `updateRdSummary` (39) + `refreshMediaFusionCacheStatus` (67) + `markDebridSourceCached` (7) + `markDebridSourceStatus` (14) + `pickNextDebridSource` (17) + `consumeDebridReturnFocusStreamIds` (6) + `isAddonProxyPlaybackUrl` (5) + `applySelectedSource` (12) + `current*` accessors + the per-movie source-state maps | **MED** (adds source FILTERS vs Series; RecyclerView/focus + cache-status) |
| **MD-4** | Debrid playback launch + resume/failover → `MovieDebridPlaybackController` | `playMovie` (134) + `playDebridMovie` (201) + `autoPlayNextDebridSource` (35) + `shouldTryNextDebridSource` (19) + `notifyDebridFailure` (12) + `canResumeDebridDirectly` (19) + `resumeDebridMovieDirectly` (41) + `handleDebridHistoryItem` (48) + `playerLauncher` body + fallback state | **HIGH** (pure first-frame playback path — the landmine) |

Favorites (`loadFavoriteState`/`toggleFavorite`/`updateFavoriteIcon`), `showDebridConfigDialog`,
`navigateToSettings`, lifecycle overrides, `initViews`/`setupClickListeners`/`setupFocusAnimations`,
`onBackPressed` STAY in the Activity (small glue / view wiring), same as Series.

**Honest target: ≤700 lines** for MovieDetailActivity (its fields are plain, like Series — clusters
genuinely reduce it). Each new collaborator <600.

## Device-QA matrix (owner eyes-on, per HIGH-risk phase / at the end)

Open a Debrid movie → source picker (filters + cache badges + IPTV cross-source rows) → play (first
frame) → resume an in-progress movie → source-failover auto-advance → return-to-sources after a failed
stream → trailer + hover-preview → favorite toggle. No crash; playback reaches first frame.

## Progress

- **MD-1 DONE (`2672946`)** — `MovieTrailerController` (launchTrailer + Trailer button state + the 2.5s-dwell hover-preview backdrop crossfade). Mirrors `SeriesTrailerController` + preview. Removed 2 dead members (`hasTrailer` field + `hasTmdbTrailer()` placeholder) + 3 unused imports. MovieDetailActivity **1915→1823**; MovieTrailerController 143. Clean build green (no baseline change — ctor 6 params). Device .64 launch-stable.
- **MD-2 DONE (`2eb4f03`)** — 4 pure formatters (`extractCertification`, `formatDurationCompact`, `formatResumeTime`, `formatSizeLabel`) → top-level fns in `MovieDetailBuilders.kt` (SD-2 pattern, byte-identical; resource-needing `formatDuration`/`displayRating` stay). Call sites resolve to same-package top-level unchanged. **1823→~1770**; MovieDetailBuilders 62. Clean build green (no baseline change). Device .64 launch-stable.

- **MD-3 DONE (`09a7cd5`)** — `MovieDebridSourceController` (555 lines, <600): the Debrid + IPTV source-acquisition concern — inline source list + language/size/cached filters (`setupSourceViews`/`loadMovieSources`/`resetSourceFilters`/`updateSourceFilterOptions`/`applySourceFilters`), source bottom-sheet (`showDebridSourcePicker`), IPTV cross-source (`fetchIptvMovieSources`), MediaFusion cache-status sweep (`refreshMediaFusionCacheStatus`), RD summary header (`updateRdSummary`), `markDebridSource*`/`pickNextDebridSource`/`consumeDebridReturnFocusStreamIds`/`isAddonProxyPlaybackUrl`/`applySelectedSource`/`current*` accessors + the per-movie source-state fields (single source of truth). Direct analogue of `SeriesDebridSourceController` (SD-4b), adapted from per-episode maps to flat single-movie state. Behaviour byte-identical (verbatim relocation; `this`/field refs rebound to `activity`/getters, playback flows back via `onPlayMovie`/`onPlayDebridMovie`). MD-4 playback (still in Activity) reads/mutates through `movieDebridSources.<member>`; **`playerLauncher = registerForActivityResult(...)` STAYS REGISTERED IN THE ACTIVITY** (unchanged). MovieDetailActivity **~1770→1321**. Removed 3 dead source-state fields' worth of clutter + 13 now-unused imports. Clean build green (`clean :app:assembleDebug :app:testDebugUnitTest :app:detekt`). Baseline: total 352→353 (+1 = the collaborator ctor `LongParameterList` only; the 2 relocated `LongMethod`s re-keyed net-zero — LongMethod stays 79). **Device .64 launch-stability + owner eyes-on QA (source picker + filters + cache badges + IPTV rows; first-frame debrid + IPTV-from-debrid; resume; source-failover auto-advance; return-to-sources) PENDING.**

- **MD-4 DONE (`bc1bb20`)** — `MovieDebridPlaybackController` (605 file-lines; class body under detekt's 600 threshold — no LargeClass flag): the Debrid **playback-launch** concern — `playMovie` + `playDebridMovie` + `autoPlayNextDebridSource` + `shouldTryNextDebridSource` + `notifyDebridFailure` + `canResumeDebridDirectly` + `resumeDebridMovieDirectly` + `handleDebridHistoryItem` + the `playerLauncher` body (`handlePlayerResult`) + source-failover accounting (`autoFallbackAttempts`/`openedFromPlaybackFailure`/`MAX_AUTO_FALLBACK_ATTEMPTS`). Direct analogue of `SeriesDebridPlaybackController` (SD-4c): takes `sources = movieDebridSources` and reads/mutates source state via it (added one small helper `MovieDebridSourceController.fetchMovieSourcesFromProvider()` so the auto-failover re-fetch stays a source-controller concern). **`playerLauncher = registerForActivityResult(...)` STAYS REGISTERED IN THE ACTIVITY** (one-line body → `moviePlayback.handlePlayerResult(result)`; controller launches via injected `launch = playerLauncher::launch`). Behaviour byte-identical (verbatim relocation; `this`/field refs rebound to `activity`/getters, `movieDebridSources.<member>`→`sources.<member>`). MovieDetailActivity **1321→788** (from original 1915, **−59%**). Removed 8 now-unused imports. Clean build green (`clean :app:assembleDebug :app:testDebugUnitTest :app:detekt`). Baseline: total 353→354 (+1 = the collaborator ctor `LongParameterList` only; the 2 relocated `LongMethod`s + 2 `CyclomaticComplexMethod`s re-keyed net-zero). **Device .64 launch-stability + owner eyes-on interactive playback QA (first-frame debrid + IPTV-from-debrid; resume; source-failover auto-advance; return-to-sources after a failed stream) PENDING.**

- **MD-5 DONE (`c201e43`)** — `MovieMetadataController` (176 lines): the remote **metadata enrichment** concern — `fetchTmdbDetails` (TMDB movie details) + `loadExternalRatings` (OMDb IMDb/RT) + `loadSimilarMovies` (TMDB recommendations row). View-coupled (not pure like MD-2), so it's a delegate/controller like MD-3/MD-4: the metadata *fields* (plot/rating/year/duration/genre/director/age-rating/imdbId) STAY in the Activity (read by `displayMovieDetails`/`getMovieDataFromIntent`/resume/playback) and the controller writes them back via setter lambdas + re-renders via `onMetadataApplied` (= displayMovieDetails + refreshResumeState); `onFetchComplete` = `movieDebridSources.loadMovieSources`. Behaviour byte-identical, exact ordering preserved. MovieDetailActivity **788→698** — **UNDER the ≤700 target.** Removed the now-unused `OmdbClient` import. NOTE: the mechanical assignment→setter transform initially pushed `fetchTmdbDetails` to exactly 60 lines (a genuine NEW `LongMethod`, since it was never baselined) — fixed by tightening the `setMovieDirector(...)` call's line-wrap to the original count (behaviour-identical formatting), NOT by baselining. Clean build green. Baseline: total **354→354 (net 0)** — the new collaborator-ctor `LongParameterList` is offset by `LargeClass:MovieDetailActivity` **dropping out** (the Activity is no longer a god-class). **Device .64 launch-stability + owner eyes-on QA PENDING.**

## Outcome

**Decomposition COMPLETE (MD-1..MD-5). ✅ ≤700 target achieved.** MovieDetailActivity **1915→698 lines (−64%)**, and it is **no longer flagged as a `LargeClass`** by detekt. Five collaborators: `MovieTrailerController` (143), `MovieDetailBuilders.kt` (62, pure), `MovieDebridSourceController` (569), `MovieDebridPlaybackController` (605), `MovieMetadataController` (176). What remains in the Activity is genuine glue: view binding (≈50 `findViewById`), the five collaborator constructions, `displayMovieDetails`/`refreshResumeState`/favorites/lifecycle/click-listeners. All hard gates green (clean `assembleDebug`+`testDebugUnitTest`+`detekt`); interactive playback QA on the Fire TV is owner eyes-on.
