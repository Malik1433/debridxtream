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

- **MD-3 + MD-4 — REMAINING (the two big phases).** These are the source-acquisition (~350 lines: `loadMovieSources` + `showDebridSourcePicker` + the source FILTERS `resetSourceFilters`/`updateSourceFilterOptions`/`applySourceFilters` + `updateRdSummary` + `refreshMediaFusionCacheStatus` + `markDebridSource*`/`pickNextDebridSource`/`consumeReturnFocus`/`isAddonProxyPlaybackUrl` + `applySelectedSource` + `current*` accessors + the per-movie source-state fields) and the playback-launch (~500 lines: `playMovie` + `playDebridMovie` + `autoPlayNextDebridSource` + `shouldTryNextDebridSource` + `notifyDebridFailure` + `canResumeDebridDirectly` + `resumeDebridMovieDirectly` + `handleDebridHistoryItem` + the `playerLauncher` body). **They are the direct analogues of `SeriesDebridSourceController` (SD-4b) and `SeriesDebridPlaybackController` (SD-4c) — same design applies verbatim:** source controller owns the state + acquisition + filters + cache-status and calls back to playback via `onPlayMovie`/`onPlayDebridMovie`/`onPlayIptvSource`; playback controller reads state via the source controller; **the `playerLauncher = registerForActivityResult(...)` STAYS REGISTERED IN THE ACTIVITY** (body delegates to `handlePlayerResult`, controller launches via injected `launch`). detekt will re-fire relocated LongMethod/Cyclomatic → regenerate baseline + verify total near-unchanged. **QA (owner eyes-on): source picker + filters + cache badges + IPTV rows; first-frame debrid + IPTV-from-debrid; resume; source-failover auto-advance; return-to-sources.** These two are best executed with fresh context budget (they are SD-4b/SD-4c-scale each) so they get the full careful clean-verify treatment rather than being rushed.
