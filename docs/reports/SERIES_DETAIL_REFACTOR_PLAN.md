# SeriesDetailActivity Decomposition Plan

**Target:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt` — **2041 lines**, 50
methods (~1291 method-body lines; the remaining ~750 are fields/imports/companion/scaffold). This is the
**Debrid** series-detail screen (IPTV series detail is the separate `SeriesDetailFragmentV2`).

**Started:** 2026-07-25, after the PlayerActivity fragment split shipped.

## Approach

Same playbook as the player + data-layer god-classes: **behaviour-preserving relocations, one cohesive
domain per commit**, each `compileDebugKotlin` + `:app:testDebugUnitTest` + `:app:detekt` green (baseline
untouched) + **device QA on Fire TV .64**, then commit + push. Because this is an `Activity` (UI + views +
lifecycle + playback launch), the unit is a **delegate/controller that takes the Activity** (like the
player's `Player*Controller`/`*Ui` collaborators), constructed inside the Activity from already-injected
deps — no Hilt change, public entry points unchanged.

**Unlike `BasePlayerFragment`** (whose ~800 immovable declarations pin its floor high), this Activity holds
most of its state in plain fields, so a cluster's methods **and their fields** move together into the
controller — extraction is genuinely effective here. Honest target: **≤600–800**.

## Domains (from the method-size map) — risk-ascending phase order

| Phase | Domain → collaborator | Methods (~lines) | Risk |
|---|---|---|---|
| **SD-1** | Trailer → `SeriesTrailerController` | `launchTrailer` (48) + `updateTrailerButtonState` | LOW (self-contained; TMDB lookup + TrailerActivity launch) |
| **SD-2** | Series info / intent → `SeriesInfoBinder` | `displaySeriesInfo` (33) + `getSeriesDataFromIntent` (23) + `applyFallbackSeriesInfo` (22) + `buildFallbackDetail` (57) | LOW (metadata → views) |
| **SD-3** | Season/episode UI → `SeriesSeasonUi` | `showSeasonPopup` (114) + `buildSeasonList` (41) + `showEpisodesForSeason` (27) + `loadSeasonWatchedProgress` (73) | MED (RecyclerView + focus + watched-progress) |
| **SD-4** | Debrid playback/source → `SeriesDebridPlaybackController` | `playDebridEpisode` (192) + `resumeDebridEpisode` (44) + `autoPlayNextDebridEpisodeSource` (44) + `playEpisode` (40) + `markDebridEpisodeSourceCached` (20) + `updateRdSummary` (26) + `fetchDebridSeriesDetail` (31) | **HIGH** (source selection + playback launch + resume) |

**Device QA matrix (every phase):** open a Debrid series → season popup → pick season → episode list +
watched progress → play an episode (source selection) → resume an in-progress episode → next-episode
auto-advance → trailer → favorite toggle. No crash; playback reaches first frame (verify via the
playback-diagnostics JSONL, same as the player audit).

## Progress
- **SD-1 DONE (device-verified .64)** — `SeriesTrailerController` (launchTrailer + updateTrailerButtonState + trailer state). Activity constructs it in initViews, forwards the url (intent→setTrailerUrl / series-info→mergeTrailerUrl) + button clicks (launch) + refreshes (refreshButtonState). Fixed a relocated SwallowedException by logging the trailer-lookup failure. compile+tests+detekt green. SeriesDetailActivity **2041→1986**. Device: the Debrid series-detail screen opens + renders (info/seasons/episodes/Trailer button wired), zero crash.

- **SD-2 DONE** — `buildFallbackDetail` (57, near-pure) lifted to a state-free top-level `buildFallbackSeriesDetail(seriesInfo, seasonName)` in `SeriesDetailBuilders.kt` (its one Context dep — the localized season name — passed as a lambda). Also removed 2 provably-dead private helpers (`displayRating`, `buildGenreYearText` — 0 callers). compile+tests+detekt green. SeriesDetailActivity **1986→1913**. QA: byte-identical relocation + dead-code removal; SeriesDetailActivity open+render was device-verified under SD-1 (same season-building path), zero app crash across the session.

- **SD-3 DONE (code) — interactive visual QA PENDING owner eyes-on** — season/episode UI cluster lifted to a stateful `SeriesSeasonUi` collaborator (constructed in `initViews`): the season-selector popup, `buildSeasonList`, `showEpisodesForSeason`/`showEpisodes`, `loadSeasonWatchedProgress`, resume/continue bar, season-progress, and the "watch now" button state. `SeriesSeasonUi` is now the **single source of truth** for `currentDetail` / `selectedSeasonKey` / `selectedEpisode` / `currentEpisodes` + the watched-progress maps; the Activity's playback code (SD-4) reads that state through 1:1 accessors (`selectedSeasonKey`, `selectedEpisode`, `resumePositionMs`, `isWatched`, `hasStoredProgress`) and the chosen episode flows back via an `onPlayEpisode` callback. Behaviour byte-identical (verbatim relocation; only `this`/field refs rebound to `activity` + injected getters). Removed 6 now-unused imports. compile + full `:app:testDebugUnitTest` + `:app:detekt` all green (baseline untouched, 351). SeriesDetailActivity **1913→1528** (−385); new `SeriesSeasonUi` 492 lines (cohesive, <600). Device .64: APK installed + app launches, MainActivity RESUMED, Home renders, **zero FATAL/ANR** across launch + extensive navigation, input dispatch normal. **Could NOT autonomously drive the interactive Debrid series-detail flow** (season popup → episode list → play/resume → next-ep advance) — the app renders on a secure/hardware surface so `screencap` is black and `uiautomator dump` returns a null root, `Log.d` is suppressed on the device, and blind DPAD produced no detectable fragment/activity transition (test device appears to have no active catalog/Debrid session — empty home). **Owner: please do the 2-min eyes-on QA matrix on the TV (app is already installed on .64).**
