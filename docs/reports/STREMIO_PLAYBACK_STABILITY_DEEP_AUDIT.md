# Stremio / Debrid Playback Stability Deep Audit

## 1. Scope
Audit-only review after Stremio direct playback became usable but user reported:
- App is much better overall.
- Player `DPAD_DOWN` episode browser shows `Episodes unavailable`.
- Stremio source count is lower than expected.
- Goal is app stability, smooth playback, and a complete plan before further implementation.

No app code was changed in this audit.

## 2. Context Read
- `AGENTS.md`
- `docs/reports/APP_SUCCESS_PATTERNS.md`
- `docs/reports/APP_FAILED_PATTERNS.md`
- `docs/reports/DO_NOT_REPEAT.md`
- `docs/reports/DEBRID_MODULE_REPORT.md`
- `docs/reports/PLAYER_MODULE_REPORT.md`
- `docs/reports/SERIES_MODULE_REPORT.md`

Relevant guardrails:
- Do not create duplicate player or episode systems.
- Keep one shared `PlayerActivity`.
- Do not route direct Stremio/AIOStreams URLs through app-side Real-Debrid resolver.
- Do not downgrade direct Debrid playback to IPTV identity.
- Do not claim user-facing PASS from build/install only.

## 3. Current Architecture Summary

### Stremio Source Path
- User-pasted Stremio `manifest.json` URLs are stored in `DebridPreferences.stremioAddonUrls`.
- `UnifiedSourceProvider` uses `StremioAddonFetcher` when at least one Stremio URL exists.
- `StremioAddonFetcher` derives:
  - movie: `/stream/movie/{imdbId}.json`
  - series: `/stream/series/{imdbId}:{season}:{episode}.json`
- Returned streams are converted into `MovieSource` rows.
- Direct addon HTTP URLs are launched directly with headers.
- Magnet/infoHash streams use app-side Real-Debrid resolver.

### Player Direct Debrid Path
- Direct Stremio/AIOStreams streams launch `PlayerActivity` with `PlaybackSource.DEBRID`.
- `EXTRA_DIRECT_DEBRID_PLAYBACK` prevents app-side Debrid resolver/API calls.
- Player label/history now preserve Debrid identity.

## 4. Finding: `DPAD_DOWN` Shows Episodes Unavailable

### Root Cause
The current direct Debrid guard is correct for playback stability, but it leaves no episode-list loading path for direct Stremio streams.

Current behavior:
- `PlayerActivity.showEpisodeBrowser()` checks `seriesPlaylistState`.
- If state is null:
  - normal Debrid resolver playback calls `loadDebridSeriesPlaylist()`.
  - IPTV playback calls `loadSeriesPlaylist()`.
  - direct Debrid playback does neither because `canUseDebridResolver()` is false and playback source is Debrid.
- Result: `episodeBrowserController.showMessage("Episodes unavailable")`.

This is expected from the current code, not a random player bug.

### Why It Was Introduced
It was introduced by a safety fix: direct Stremio playback had to stop entering Real-Debrid resolver and IPTV playlist APIs. That fixed false RD config/IPTV labeling, but direct Debrid still needs a separate episode-list and episode-switch path.

### Correct Fix Direction
Add a direct-Debrid episode mode, not a duplicate player:
- Load TMDB season episodes for direct Debrid playback so `DPAD_DOWN` can show the list.
- On episode selection, fetch Stremio sources for that specific episode through `UnifiedSourceProvider.getSeriesEpisodeSources`.
- Pick a playback-ready direct stream first, otherwise show source picker or a stable message.
- Keep `directDebridPlayback = true` for direct HTTP addon URLs.
- Do not use IPTV `loadSeriesPlaylist()` for direct Debrid.

## 5. Finding: Stremio Links Are Fewer Than Expected

### Root Cause 1: Stremio URL Config Switches To Stremio-Only
In `UnifiedSourceProvider`, when `debridPrefs.getStremioAddonUrls()` is not empty:
- movie path fetches only `fetchStremioMovieSources(imdbId)`.
- episode path fetches only `fetchStremioEpisodeSources(imdbId, season, episode)`.
- legacy built-in Torrentio, MediaFusion, and dynamic registries are skipped.

This was intended as a clean source path, but it reduces the total source pool unless the pasted Stremio addon itself aggregates everything.

### Root Cause 2: Strict IMDb Dependency
Native Stremio stream endpoints require IMDb IDs.
- Movies rely on `currentImdbId` being populated by TMDB details.
- Series resolves TMDB to IMDb at source-fetch time.
- If IMDb lookup fails, Stremio source result is empty.

### Root Cause 3: Stremio Manifest Capabilities Are Not Audited In UI
The app accepts manifest URLs but does not show whether the addon supports:
- `movie` stream resource
- `series` stream resource
- configured Real-Debrid account inside the addon
- regional/language settings
- direct URL vs infoHash streams

### Root Cause 4: Deduplication Removes Same-Hash Duplicates
`deduplicateStreams()` keeps one stream per infoHash. This is good for avoiding duplicates and rate-limit waste, but the visible count can be lower than raw Stremio/Stremio-like apps that show duplicate variants.

### Correct Fix Direction
Use a hybrid aggregation mode:
- User Stremio addons remain priority.
- Built-in Torrentio/MediaFusion/dynamic registries can optionally run as fallback or supplemental providers.
- Deduplicate after all providers return.
- Preserve rate-limit caps for Real-Debrid availability checks.
- Show provider breakdown so the user knows whether missing links are from addon config, IMDb mismatch, or provider result limits.

## 6. Finding: Playback Smoothness Is Mostly Protected But Needs Direct-Mode Gaps Closed

What is stable:
- One shared `PlayerActivity`.
- Direct addon URLs skip app-side Real-Debrid auth.
- Debrid identity is preserved for direct playback.
- Media3 seamless switch avoids player view rebinding.
- Browser-visible keys are consumed before Media3 controls.
- Terminal Real-Debrid failures are typed and rate-limit aware.

Remaining risks:
- Direct Debrid episode browser has no playlist/source-switch implementation.
- Direct addon next-episode auto-play is disabled by the resolver guard.
- Next-episode source selection does not preserve the user's chosen source family/provider/language. `loadNextDebridEpisode()` currently tries exact same infoHash, then falls back to cache/quality ordering, which can switch from Hindi/German/multi to English or from one addon/provider to another.
- Stremio `fileIdx` is displayed but not passed into Real-Debrid file selection.
- Stremio subtitles are not mapped yet.
- Some logs still include noisy debug tags and mojibake characters, reducing QA readability.
- User-facing source count does not show provider breakdown or why results are missing.

## 7. Finding: Manual QA Gaps
Current build/install smoke is not enough for user-facing PASS.

Required manual tests after implementation:
- Movie direct Stremio source plays and label says Debrid.
- Series direct Stremio source plays and label says Debrid.
- `DPAD_DOWN` on direct Debrid episode opens episode list instead of unavailable.
- Selecting next episode from browser fetches sources and starts playback.
- Episode browser selection, Next button, and auto-next preserve the selected source family/provider and language when a matching next-episode source exists.
- Back from player returns to sources when playback fails early.
- Blocked/copyright/429 sources do not trigger request storms.
- Live TV `DPAD_DOWN` still zaps channel down.
- IPTV series episode browser still works.
- Debrid resolver-backed series still works.

## 8. Priority Plan

### Phase 1: Direct Debrid Episode Browser
Goal: Fix `DPAD_DOWN -> Episodes unavailable`.

Implementation plan:
- Add a direct Debrid playlist loader in `PlayerViewModel`, likely reusing TMDB season details from `loadDebridSeriesPlaylist()`.
- Allow `PlayerActivity.showEpisodeBrowser()` to load that playlist when `directDebridPlayback == true`.
- Add a `SelectedSourceProfile`/playback continuity profile for Debrid series:
  - provider/addon name (`MovieSource.provider`)
  - source type/family where available (`Stremio`, `Torrentio`, `MediaFusion`, `Dynamic`)
  - languages (`MovieSource.languages`)
  - quality preference
  - cache/direct readiness
  - optional `bingeGroup`/`fileIdx` when available
- Add a direct Debrid episode selection path:
  - fetch `UnifiedSourceProvider.getSeriesEpisodeSources(tmdbId, targetSeason, targetEpisode, seriesTitle, null)`.
  - rank candidates by same provider/source family first, same language second, playback-ready/direct third, then quality/seeders.
  - if selected source is direct HTTP, switch with headers and keep `directDebridPlayback = true`.
  - if selected source is magnet/infoHash, use resolver-backed Debrid path.
- Use the same continuity profile for all next-episode entry points:
  - episode browser selection
  - Next button
  - auto-next countdown
- If no same-provider/same-language source exists, fall back to the best playable source and show/record that continuity degraded instead of silently picking a random source.
- Keep controller and DPAD behavior unchanged.

Validation:
- focused compile
- assembleDebug
- install both devices
- manual player `DPAD_DOWN` test on affected direct Stremio series
- manual continuity test: start with a Hindi/German/multi source, then use episode browser, Next button, and auto-next; each should choose the same provider/language when available

### Phase 2: Hybrid Source Aggregation
Goal: Increase source count without mixing old broken UI/storage paths.

Implementation plan:
- Add a mode inside `UnifiedSourceProvider`:
  - Stremio configured URLs run first.
  - If Stremio count is below a threshold or no playback-ready sources exist, run built-in Torrentio/MediaFusion/dynamic definitions as supplemental fallback.
  - Merge, prioritize, deduplicate, then verify only the top limited hashes.
- Add provider breakdown logging/UI summary:
  - Stremio count
  - Torrentio count
  - MediaFusion count
  - Dynamic count
  - direct/verified/unknown count
- Keep Real-Debrid availability cap to avoid `429`.

Validation:
- existing Stremio fetcher tests
- add unit test for hybrid merge/dedup fallback behavior
- manual compare source counts on the same title before/after

### Phase 3: Stremio Addon Health Diagnostics
Goal: Make bad addon config obvious.

Implementation plan:
- Add validation when saving a manifest URL:
  - manifest reachable
  - supports `stream`
  - supports `movie` and/or `series`
  - redacted host/name shown in settings
- Add a small status line in Manage Stremio Addons:
  - OK
  - no movie stream support
  - no series stream support
  - unreachable
  - likely needs addon-side Real-Debrid configuration

Validation:
- use fake manifest/unit tests
- manual add/remove broken and valid manifests

### Phase 4: Playback Smoothness Hardening
Goal: Reduce edge-case stalls and wrong episode picks.

Implementation plan:
- Preserve headers across direct episode switch.
- Add source-switch telemetry counters without printing URLs.
- Pass Stremio `fileIdx` into resolver selection path if magnet/infoHash source includes it.
- Map Stremio subtitles from stream response where available.
- Improve direct-stream failure fallback: early player failure should return to source picker with failed source marked unavailable.

Validation:
- direct stream with headers
- resolver-backed torrent with multi-file season pack
- blocked source
- 429 cooldown
- subtitles if addon returns them

### Phase 5: Full Regression QA
Goal: Stabilize before next feature work.

Required QA matrix:
- `192.168.0.84:5555`
- `192.168.0.21:5555`
- Direct Debrid movie
- Direct Debrid series
- Resolver-backed Debrid movie
- Resolver-backed Debrid series
- IPTV series browser
- Live TV zapping
- VOD playback
- Back/return-to-sources
- Continue Watching save/resume
- crash log scan

## 9. Risks
- Increasing source aggregation can increase network calls. Must keep Real-Debrid availability checks capped and sequential.
- Auto-playing next episode from Stremio can pick a wrong source if ranking is too aggressive. Prefer direct/playback-ready sources and fall back to picker when confidence is low.
- Some Stremio addons return direct error videos/pages when their own RD config is missing. The app can detect bad HTTP/player failures, but cannot fix addon-side credentials.
- Provider-side copyright removals and legal restrictions cannot be bypassed by client code.

## 10. Recommended Next Task
Start Phase 1 only:

`TASK 011-STREMIO-DIRECT-DEBRID-EPISODE-BROWSER`

Definition of done:
- `DPAD_DOWN` on direct Stremio Debrid episode opens a real episode list.
- Selecting an episode fetches that episode's Stremio sources and plays a direct source when available.
- IPTV and Live TV DPAD behavior remain unchanged.
- `compileDebugKotlin`, `assembleDebug`, install on both devices, launch smoke, and manual affected-series playback QA are completed.

## 11. Final Audit Status
PARTIAL  Architecture is now stable enough to continue, but direct Debrid episode navigation and hybrid source aggregation are still required before the Stremio path feels complete and Stremio-level.

---

## 12. Phase 1 Implementation Update
`TASK 012-STREMIO-DIRECT-DEBRID-EPISODE-CONTINUITY-RESUME` implemented the Phase 1 player-side foundation:
- Direct Debrid/Stremio series now use the existing Debrid/TMDB playlist state for episode browser data.
- Episode browser selection, Next, and auto-next use one Debrid source-selection path.
- Selected source profile metadata is carried through source conversion, player launch, and Continue Watching.
- Direct Debrid Continue Watching can refresh from stable metadata instead of trusting expired direct addon URLs.

Status:
- Build/install/launch smoke passed on `192.168.0.84:5555`.
- `192.168.0.21:5555` was unreachable during QA.
- Manual playback QA is still required before marking this user-facing behavior PASS.
