# Player Module Report
Status: canonical/current
Scope: shared `PlayerActivity` / `PlayerViewModel` playback path.

## Current Active Runtime Flow
- All playback enters `player/stabilized/PlayerActivity` through `PlayerActivity.createIntent()`.
- Media3 `ExoPlayer` is shared for Live TV, IPTV VOD, IPTV Series, Debrid movies, and Debrid series.
- `PlayerViewModel` owns EPG overlay state, Live zapping state, Series playlist state, and Debrid resolution state.
- `PlayerKeyDispatcher`, `PlayerBrowserManager`, `EpisodeBrowserController`, `PlayerTrackManager`, and `PlayerHistoryManager` handle source-aware controls.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt` - shared player host and intent contract.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerViewModel.kt` - player state, zapping, playlist, Debrid resolution entrypoints.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerKeyDispatcher.kt` - D-pad/back/controller routing.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerBrowserManager.kt` - Live browser, zapping, episode browser orchestration.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/EpisodeBrowserController.kt` - in-player episode list UI.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/SeriesPlaylistManager.kt` - IPTV/Debrid episode playlist state.

## What Must Not Break
- Live TV zapping and `DPAD_DOWN` behavior.
- IPTV movie and IPTV series playback.
- Debrid movie and Debrid series playback.
- Episode Browser ownership of D-pad/OK/Back when visible.
- Source-aware return contracts such as `EXTRA_RETURN_TO_SOURCES` and Live return extras.
- Sensitive URL/token/hash redaction.

## Known Bugs / Open Issues
- Remote-control edge cases should remain under Player task docs.
- Broad Player regressions still need real functional QA when shared behavior changes.

## Recent Fixes
- Added `isFinishing || isDestroyed` checks to player initialization and URL switching to prevent orphaned ExoPlayer instances (phantom audio) after delayed retries when the Activity has already exited.
- Implemented latest-zap-wins logic using `zapRequestId` and `streamId` tokens to fix the Live TV fast-zapping race condition.
- Reverted unintended `PlayerViewModel` changes to restore direct Debrid/addon HTTP stream passthrough and resolve the "Real Debrid config missing" regression.
- Re-enabled Live TV EPG zapping initialization during Player creation.
- Redefined IPTV Series playback checks for Next Episode and episode list behavior.
- Changed controller seekbar `DPAD_UP` to target audio/subtitle/language row instead of Play/Pause.
- Added `seriesId`, `seasonNum`, and `sourceType` to playlist state to prevent stale Episode Browser data.
- Rejected stream URL fallback values as IPTV episode ids.
- Removed episode-zero fallback when current episode identity is missing.

## Failed Approaches / Avoid
- Do not blindly allow overlapping ExoPlayer async loads or overlapping EPG updates; use request ID/stream ID filtering to enforce latest-zap-wins.
- Do not create duplicate player activities.
- Do not change global D-pad behavior without `ContentType` and `PlaybackSource` guards.
- Do not use `EXTRA_STREAM_URL` or `currentUrl` as an IPTV episode id.
- Do not fall back to episode 0 when playlist identity is missing.
- Do not route terminal Debrid failures through transient source retry loops.

## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Install/launch on requested devices.
- Manual regression: Live TV zapping, IPTV VOD, IPTV Series, Debrid movie, Debrid series, Episode Browser, D-pad/back.

## Last Verified State
- 2026-05-23: compile, assemble, install/launch/PID/crash scan passed on `192.168.0.21:5555` and `192.168.0.84:5555` after playlist fallback guard.
- 2026-05-23: manual QA passed for IPTV Series and Debrid Series episode browser plus Next Episode behavior.
