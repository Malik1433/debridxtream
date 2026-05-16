# Player Module Report

## 1. Active Implementation Path
- **Activity**: `com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity`
- **ViewModel**: `com.tvonnet.debridxtreamiptv.player.stabilized.PlayerViewModel`
- **Layout**: `app/src/main/res/layout/activity_player.xml`

## 2. Playback Path Audit

### 2.1 IPTV Series Path
- **Detail**: `SeriesDetailFragmentV2` -> Episode Click.
- **Intent Extras**:
    - `EXTRA_STREAM_URL`
    - `EXTRA_TITLE`
    - `EXTRA_CONTENT_ID`
    - `EXTRA_CONTENT_TYPE` (EPISODE)
    - `EXTRA_SERIES_ID`
    - `EXTRA_SERIES_TITLE`
    - `EXTRA_SEASON_NUM`
    - `EXTRA_EPISODE_NUM`
- **Data Source**: `XtreamSeriesRepositoryV2.getSeasonEpisodes(seriesId, seasonNum)`.

### 2.2 Debrid Series Path
- **Detail**: `SeriesDetailActivity` -> Episode Click -> Source Selection.
- **Intent Extras**:
    - `EXTRA_STREAM_URL` (Resolved link)
    - `EXTRA_TITLE` (Combined title)
    - `EXTRA_CONTENT_ID` (tmdbId:season:episode)
    - `EXTRA_CONTENT_TYPE` (EPISODE)
    - `EXTRA_PLAYBACK_SOURCE` (DEBRID)
    - `EXTRA_SERIES_TITLE`
    - `EXTRA_SEASON_NUM`
    - `EXTRA_EPISODE_NUM`
- **Data Source**: `TmdbRemoteDataSource.getSeasonDetails(tmdbId, seasonNum)`.

## 3. Duplicate Audit
- **existing overlays found**: `view_player_channel_browser` (Live TV), `view_next_episode_prompt` (Auto-play prompt).
- **existing next episode prompt**: `view_next_episode_prompt` exists but is a simple prompt, not a list.
- **existing episode state**: `PlayerViewModel.seriesPlaylistState` already tracks the full season's episode list (`List<EpisodeEntityV2>`).
- **existing key handlers**: `PlayerActivity.dispatchKeyEvent` handles OK/UP/DOWN/LEFT/RIGHT but lacks a dedicated episode browser trigger for `SERIES`.
- **existing Debrid series path**: `SeriesDetailActivity` launches `PlayerActivity` with `PlaybackSource.DEBRID`.
- **existing IPTV series path**: `SeriesDetailFragmentV2` launches `PlayerActivity` with `ContentType.EPISODE`.
- **existing helper/controller classes**: None for episode browsing. `EPG` has its own overlay logic.
- **reused components**: `EpisodeEntityV2` model, `PlayerViewModel` playlist management, `PlayerActivity.playSeriesEpisode`.
- **new components created and why**: 
    - `view_player_episode_browser.xml`: Required for the horizontal bottom overlay requested by user.
    - `EpisodeBrowserController.kt`: To encapsulate RecyclerView binding and visibility logic, keeping `PlayerActivity` clean.
    - `item_player_episode_browser.xml`: Optimized horizontal card for the player overlay.

## 4. TASK 029 — Shared Series Episode Overlay System [COMPLETED]

### 4.1 Scope
Implementation of a shared episode browser overlay for IPTV and Debrid series.

### 4.2 Design
- **Trigger**: `DPAD_DOWN` when `isSeriesEpisodePlayback()` is true.
- **Layout**: `view_player_episode_browser.xml` (New, based on `view_player_channel_browser` pattern).
- **Controller**: `EpisodeBrowserController.kt` handles RecyclerView and Visibility.
- **ViewModel**: Uses `PlayerViewModel.seriesPlaylistState` to feed the browser.

### 4.3 Risks Mitigation
- **Live TV Conflict**: Guarded in `dispatchKeyEvent` using `isSeriesEpisodePlayback()`.
- **VOD Conflict**: Verified only active for `ContentType.EPISODE` or `ContentType.SERIES`.
- **Debrid Resolution**: Selection in browser triggers `reResolveDebridUrl` for Debrid source.

---
**Auditor**: Antigravity
**Date**: May 13, 2026

# TASK 029-IPTV-FIX-4  Blocking Playlist Load Root Cause

## Diagnosis
`PlayerViewModel.loadSeriesPlaylist()` blocks on `seriesRepository.getSeriesById(seriesId).collect { ... }` before DB re-check and before emitting `SeriesPlaylistState`.

## Evidence
`artifacts/view_84_series_browser.xml` shows Media3 controller but no episode browser views:
- no `view_episode_browser`
- no `tv_episode_browser_title`
- no `rv_episodes`

## Conclusion
Issue is playlist state/data-load before browser render, not DPAD routing, focus, or adapter layout.

# TASK 029-IPTV-FIX-4  Blocking Playlist Load Fix

## 1. Scope
IPTV Series episode browser playlist loading only.

## 2. Root Cause
`loadSeriesPlaylist()` blocked on `seriesRepository.getSeriesById(seriesId).collect { ... }` before re-checking DB episodes and emitting `SeriesPlaylistState`.

## 3. Evidence
UI dump showed Media3 controller only and no episode browser views. Diagnosis showed no renderable playlist state reached `PlayerActivity.observeSeriesPlaylistState()`.

## 4. Fix Applied
- removed/block-limited long-running collect: replaced blocking collection with `firstOrNull()` inside `withTimeoutOrNull(8000L)`.
- local-first episode load: checks `getSeasonEpisodes()` before provider fetch.
- bounded network/provider fetch: provider fetch is capped at 8 seconds.
- guaranteed loading=false emission: success, empty, and exception paths emit final state.
- no-infinite-spinner empty/error handling: `EpisodeBrowserController.showMessage()` renders a visible message.
- final render fix: episode browser include height set to `320dp` so submitted cards are visible on TV.

## 5. Files Changed
- `PlayerViewModel.kt`
- `PlayerActivity.kt`
- `EpisodeBrowserController.kt`
- `activity_player.xml`
- `view_player_episode_browser.xml`
- report docs

## 6. Logs
- LOCAL_BEFORE: emitted by `IPTV_EP_LOAD_FIX`
- NETWORK_FETCH: emitted by `IPTV_EP_LOAD_FIX`
- LOCAL_AFTER: emitted by `IPTV_EP_LOAD_FIX`
- MAPPED: emitted by `IPTV_EP_LOAD_FIX`
- EMIT: emitted by `IPTV_EP_LOAD_FIX`
- OBSERVED: `OBSERVED loading=false count=10 error=null`
- CONTROLLER: `CONTROLLER submit count=10`

## 7. Build Result
- clean assembleDebug: initial clean build produced APK but the tool timed out; after clearing Gradle daemon state, final `:app:compileDebugKotlin` and `:app:assembleDebug` passed.
- APK timestamp: `D:\cursor working\debxtrem\app\build\outputs\apk\debug\app-debug.apk`, 31,425,266 bytes, `16/05/2026 18:14:10`.

## 8. Device QA
### 192.168.0.84:5555
- browser: not deep-navigated post-final install; app launched to home successfully.
- list or message: not exercised post-final install.
- spinner stops: not exercised post-final install.
- seek/controller/back: not exercised post-final install.

### 192.168.0.21:5555
- browser: PASS, `view_episode_browser` visible.
- list or message: PASS, `rv_episodes` visible with E1-E5 cards.
- spinner stops: PASS, no infinite spinner; logs show controller submit.
- seek/controller/back: PASS, BACK closes browser; OK/CENTER shows controller; seek controls visible.

## 9. Regression Smoke
- Live TV: not modified.
- VOD: home/VOD surface launched.
- Debrid untouched: no Debrid code path changed.

## 10. Final Status
PASS  episodes load or safe message appears, no infinite spinner.

# TASK 029-FIX-5  Episode Browser Focus and Controller Suppression Scope

## What Now Works
IPTV Series and Debrid Series episode browser data now reaches the overlay. IPTV episode lists render, Debrid episode lists render, and the previous endless-loading state has been eliminated.

## What Remains Broken
When the episode browser is open, LEFT/RIGHT can still allow the Media3/player controller to appear behind the overlay. Episode focus is not visually obvious enough for TV use. IPTV episode thumbnails may be blank while Debrid episode images work.

## What Must Not Be Touched
Do not change Debrid resolving, Live TV behavior, VOD/Movie behavior, repository/database schema, playback engine behavior, series grid/card/sidebar UI, or global DPAD routing outside episode browser mode.

## Exact Scope
Limit TASK 029-FIX-5 to episode browser focus visibility, browser-visible key ownership/controller suppression, and IPTV image fallback or clean placeholder rendering in the existing shared episode browser.

# TASK 029-FIX-5  Episode Browser Focus and Controller Suppression

## 1. Scope
Focus visibility, controller suppression while the episode browser is open, and IPTV image fallback/placeholder rendering.

## 2. User-Reported Issues
- Controller opens behind browser during LEFT/RIGHT.
- Focus location is unclear.
- IPTV episode images are missing while Debrid images work.

## 3. Files Changed
- `PlayerActivity.kt`
- `EpisodeBrowserController.kt`
- `item_player_episode_browser.xml`
- `selector_episode_focus.xml`
- `bg_episode_placeholder.xml`
- `bg_episode_playing_badge.xml`
- `selector_episode_title_color.xml`
- report docs

## 4. Controller Suppression Fix
When `EpisodeBrowserController` is visible, `PlayerActivity.dispatchKeyEvent()` hides the Media3 controller first, routes the key to `episodeBrowserController.handleKeyEvent(event)`, logs `EP_BROWSER_FOCUS_FIX`, and consumes handled browser keys before generic player/controller handling can run. `onUserInteraction()` also hides and returns while the episode browser is visible.

## 5. Browser Key Handling Fix
`EpisodeBrowserController.handleKeyEvent()` now owns browser-visible keys:
- LEFT/RIGHT moves episode focus only.
- UP/DOWN are consumed inside the browser.
- OK/CENTER selects the focused episode.
- BACK closes the browser.

## 6. Focus Visual Fix
Focused episode card now uses a strong cyan focused background/border, programmatic scale-up, and elevation. The currently playing episode is separately marked with a gold `Playing` badge, so focus and active playback are distinct.

## 7. IPTV Image Fallback
Episode cards now use `episode.thumbnail` first, then player `posterUrl`, then `backdropUrl`. If no image is available, the card shows a clean gradient placeholder with the episode number instead of a blank/broken image.

## 8. Build Result
- `clean assembleDebug`: attempted with installed Gradle binary, but the tool timed out after 10 minutes after cleaning and did not leave an APK.
- `:app:compileDebugKotlin --no-daemon --offline --console plain`: PASS.
- `:app:assembleDebug --no-daemon --offline --console plain`: PASS.
- APK: `D:\cursor working\debxtrem\app\build\outputs\apk\debug\app-debug.apk`, 31,338,911 bytes, `16/05/2026 19:22:41`.

## 9. Device QA
### 192.168.0.84:5555
- Install: PASS.
- Force-stop after install: PASS.
- IPTV Series: not manually traversed post-install.
- Debrid Series: not manually traversed post-install.
- Live TV: not manually traversed post-install.
- VOD: not manually traversed post-install.
- Debrid Movie: not manually traversed post-install.

### 192.168.0.21:5555
- Install: PASS.
- Force-stop after install: PASS.
- IPTV Series: not manually traversed post-install.
- Debrid Series: not manually traversed post-install.
- Live TV: not manually traversed post-install.
- VOD: not manually traversed post-install.
- Debrid Movie: not manually traversed post-install.

## 10. Final Status
PARTIAL  code/build/install completed; full two-device manual QA remains.

## 11. Remaining Issues
Manual device validation must confirm controller suppression, focus visibility, image fallback/placeholder rendering, and regression smoke across IPTV Series, Debrid Series, Live TV, VOD, and Debrid Movie.

# TASK 030  IPTV Series Continue Watching Metadata Root Cause

## Diagnosis
IPTV Series playback launched from Series detail passes `seriesId`, season, episode, series title, and episode id into `PlayerActivity`. Continue Watching direct resume preserved episode playback fields but did not persist or relaunch with the IPTV `seriesId`.

## Evidence
The working `SeriesDetailFragmentV2` launch calls `PlayerActivity.createIntent(... seriesId = event.seriesId)`. The Continue Watching direct resume path called `PlayerActivity.createIntent()` without `seriesId`, so `PlayerActivity.loadSeriesPlaylist()` could not load the season playlist for episode browser and next episode behavior.

## Scope
Fix only IPTV Series Continue Watching metadata persistence and relaunch extras. Do not change episode browser UI, Debrid resolving, Live TV, VOD/Movie, playback engine, or the working Series detail path.

# TASK 030  IPTV Series Continue Watching Metadata Fix

## 1. Scope
IPTV Series Continue Watching metadata only.

## 2. Root Cause
Continue Watching direct resume did not preserve/pass the IPTV `seriesId` required by `PlayerActivity.loadSeriesPlaylist()`. The player received an episode id and season/episode fields, but no season playlist key, so episode browser and next episode could not resolve the series playlist.

## 3. Fix Applied
- Added nullable `seriesId` to `ContinueWatchingItem`.
- `PlayerActivity.recordContinueWatchingHistory()` now saves `EXTRA_SERIES_ID` into Continue Watching for IPTV Series episodes.
- `HomeFragment.onContinueWatchingItemClick()` now passes `seriesId` to `PlayerActivity.createIntent()` for IPTV episode resumes.
- Added a local-only fallback lookup by `episodeId` in `EpisodeDaoV2.getEpisodeById()` so older Continue Watching rows can recover `seriesId` from the existing episode table.
- Continue Watching series dedupe now prefers `seriesId` when present.

## 4. Files Changed
- `HomeScreenModels.kt`
- `WatchHistoryPreferences.kt`
- `EpisodeDaoV2.kt`
- `HomeFragment.kt`
- `PlayerActivity.kt`
- report docs

## 5. Build Result
- `:app:compileDebugKotlin --no-daemon --offline --console plain`: PASS.
- `:app:assembleDebug --no-daemon --offline --console plain`: PASS.
- APK: `D:\cursor working\debxtrem\app\build\outputs\apk\debug\app-debug.apk`, 31,532,404 bytes, `16/05/2026 19:55:56`.

## 6. Install Result
- `192.168.0.84:5555`: install PASS, force-stop PASS.
- `192.168.0.21:5555`: install PASS, force-stop PASS.

## 7. Device QA
Manual Continue Watching navigation was not completed in this turn. Expected verification logs are `TASK030_CW_SERIES RESOLVE_SERIES_ID` and `TASK030_CW_SERIES DIRECT_RESUME` showing a non-null `seriesId` for IPTV episode resumes.

## 8. Final Status
PARTIAL  code/build/install completed; manual Continue Watching episode browser and next episode validation remains.

# Player Polish  IPTV TMDB Posters and Seek-Bar Focus

## Scope
Polish IPTV episode browser artwork and player controller seek focus. No repository schema change, no Debrid resolving change, no Live TV/VOD behavior change.

## Fix Applied
- IPTV Series playlist items now attempt a bounded TMDB TV search by series title and season.
- If TMDB season details provide episode stills, blank IPTV episode thumbnails are replaced in the in-memory player playlist with TMDB still URLs.
- Existing provider thumbnails remain preferred and are not overwritten.
- When the Media3 controller is hidden, OK/LEFT/RIGHT now opens the controller with focus on `exo_progress`.
- Hidden-controller LEFT/RIGHT is forwarded into the focused seek bar so the seek action works immediately.
- Focused `WavySeekBar` now uses a brighter cyan played line and scrubber glow.

## Logs
- `IPTV_TMDB_POSTERS`: TMDB search/match/error path.
- `PLAYER_SEEK_FOCUS`: controller open and seek-bar focus path.

## Verification
- `:app:compileDebugKotlin --no-daemon --offline --console plain`: PASS.
- `:app:assembleDebug --no-daemon --offline --console plain`: PASS.
- APK: `D:\cursor working\debxtrem\app\build\outputs\apk\debug\app-debug.apk`, 31,734,096 bytes, `16/05/2026 22:33:16`.
- Install `192.168.0.84:5555`: PASS.
- Install `192.168.0.21:5555`: PASS.

## Remaining QA
Manual device QA should confirm TMDB stills appear for IPTV episodes when TMDB match exists, and that hidden-controller OK/LEFT/RIGHT focuses the seek bar and LEFT/RIGHT seek immediately.

# IPTV Episode TMDB Poster Matching Tightening

## Scope
IPTV Series episode artwork only. Seek-bar behavior is already working and UI polish is deferred.

## Fix Applied
- Reused the existing `TmdbRemoteDataSource` already injected into `PlayerViewModel`.
- Cleaned noisy IPTV series titles before TMDB search.
- Replaced first-result TMDB selection with scored title matching across bounded query variants.
- Preserved provider episode thumbnails when present.
- Filled only missing IPTV episode thumbnails from TMDB season stills.
- Kept existing poster/backdrop/placeholder fallback after TMDB when no still is available.

## Verification
- `:app:compileDebugKotlin --no-daemon --offline --console plain`: PASS.
- `:app:assembleDebug --no-daemon --offline --console plain`: PASS.
- APK: `D:\cursor working\debxtrem\app\build\outputs\apk\debug\app-debug.apk`, 31,737,501 bytes, `16/05/2026 23:12:21`.
- Install `192.168.0.84:5555`: PASS, force-stop PASS.
- Install `192.168.0.21:5555`: PASS, force-stop PASS.

# Continue Watching Series Poster Fix

## Scope
Home Continue Watching artwork for IPTV Series/Episode rows only.

## Root Cause
Continue Watching episode rows can store the episode id as `contentId`. Home artwork enrichment was using that id to look up a series cover, so the series lookup missed and the row fell back to the solid placeholder/title box.

## Fix Applied
- Resolve real `seriesId` from saved Continue Watching metadata first.
- Heal older episode rows by resolving `seriesId` from `EpisodeDaoV2.getEpisodeById(contentId)`.
- Prefer IPTV provider artwork from local legacy/V2 series caches.
- Use existing TMDB TV search as a bounded fallback only when provider artwork is missing.
- Keep Continue Watching adapter/layout unchanged; it now receives a usable `posterUrl`/`backdropUrl`.

## Logs
- `CW_SERIES_ARTWORK`: provider/TMDB/missing artwork resolution path.

## Verification
- `:app:compileDebugKotlin --no-daemon --offline --console plain`: PASS.
- `:app:assembleDebug --no-daemon --offline --console plain`: PASS.
- APK: `D:\cursor working\debxtrem\app\build\outputs\apk\debug\app-debug.apk`, 31,737,501 bytes, `16/05/2026 23:43:39`.
- Install `192.168.0.84:5555`: PASS, force-stop PASS.
- Install `192.168.0.21:5555`: PASS, force-stop PASS.
