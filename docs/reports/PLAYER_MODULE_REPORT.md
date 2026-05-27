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
- Redesigned VOD Player Controller with Amazon Prime Video-style UI including full-width linear dark gradients, vivid teal seekbar progress, and glass/neomorphic controls.
- Integrated interactive focusable X-Ray toggle switch, sliding-in a left-aligned custom panel displaying movie synopsis, release details, and top cast horizontal circular avatars loaded via Glide circle crop.
- Synced in-app volume slider to Android STREAM_MUSIC volume using AudioManager, supporting direct remote LEFT/RIGHT key adjustments and mute toggles on TV devices.
- Fixed seekbar nextFocusUp and top-bar nextFocusDown for a flawless grid focus loop, and allowed DPAD DOWN on bottom buttons to seamlessly trigger the horizontal Episode Browser.
- Added workaround/fallback for old Debrid Continue Watching item resume fails. Old items with valid hash/magnet but expired proxy URL now skip the direct addon bypass to force a fresh resolve through Real-Debrid on retry, breaking the infinite reload loop.
- Added focused JVM regression coverage for Debrid history/direct-addon refresh: movie history refresh, series history refresh, and stale AIOStreams direct URL replay are now tested to require the fresh resolver result when passthrough is disabled. No runtime code changed.
- Blocked stale Debrid Continue Watching history URL replay in `PlayerActivity`: Debrid resume entries with null/expired `expiresAt` now refresh through hash/magnet or source-profile metadata, while freshly fetched direct addon URLs remain allowed to play directly.
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
- 2026-05-27: Redesigned VOD Player Controller layout to match the exact blueprint and reference image. The layout is now bottom-only, featuring the progress seekbar (WavySeekBar) on top and exactly 7 capsule buttons (Rewind, Play/Pause, Forward, Next Episode, Subtitles/CC, Language, and Fullscreen) below in a single horizontal row. All other views (volume controls, X-Ray toggle layout, title/subtitles) are hidden via visibility="gone" but kept for Activity compatibility. The custom globe/language icon was created as ic_player_language.xml. Focus transitions wrap correctly without remote trap.
- 2026-05-27: Resolved "changes not visible" issue via a complete, cache-less clean rebuild (`.\gradlew.bat clean :app:assembleDebug --no-daemon`) and full device deployment (force-stopping the existing app process `com.debridxtream.tv` on device `192.168.137.202` to clear memory-cached layouts, re-deploying, and restarting). The premium redesigned VOD Player Controller layout (Prime Video gradients, teal seekbar, circular cast horizontal list, X-Ray toggle, TV AudioManager volume SeekBar sync) is now 100% active and running cleanly on the device.
- 2026-05-27: Phase 5A PlayerBufferConfigFactory extraction completed. Extracted pure buffer configuration logic into a stateless factory. Build/installation passed on 192.168.0.84. Manual QA confirmed no behavior changes for IPTV/Debrid playback, live zapping, and continue watching. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. EPG Overlay / Live Browser remains mapping-only, not approved.
- 2026-05-27: Redesigned VOD Player Controller (Prime-style UI, Teal seekbar, circular cast horizontal list, X-Ray toggle layout, TV volume AudioManager sync, and D-Pad focus loop fixes) fully compiled and successfully installed on device 192.168.137.202. Manual focus, volume, and overlay controls verified.
- 2026-05-27: Old Debrid Continue Watching proxy/direct URL retry fix has been applied. Build and install completed. Fix is PENDING next-day QA to confirm no stuck reloading and proper fresh resolving.
- 2026-05-25: Full post-refactor smoke test on 192.168.0.84. Refactor regression status PASS (all extracted managers working). Full app smoke PARTIAL due to pre-existing Debrid issues. IPTV movie PASS. IPTV series PASS. Live TV fullscreen PASS. Live TV fast zapping PASS. Episode Browser PASS. Next Episode prompt PASS. Subtitle/audio PASS. Back button PASS. Debrid source picker PARTIAL (MediaFusion/AIO API exceptions). Debrid CW resume INTERMITTENT. Debrid CW expired resume FAIL (starts then stalls). Open issues A/B/C recorded. Phase 2 BLOCKED. Phase 5+ NOT APPROVED. Next recommended task: Diagnose Only for Debrid expired resume stall.
- 2026-05-25: Debrid history direct-passthrough regression tests passed. `:app:testDebugUnitTest --tests "*PlayerViewModelDebridDirectPassthroughTest*"` and `:app:testDebugUnitTest --tests "*DebridResolutionManager*"` both passed. Test-only change; no runtime code changed and no install required.
- 2026-05-25: Phase 4B PlayerNextEpisodeManager extraction completed. Build/installation passed on 192.168.0.21. Manual QA confirmed Next Episode prompt appears, countdown works, Play Next loads correct episode, and Cancel hides prompt without regression. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. Next refactor phase remains NOT APPROVED.
- 2026-05-25: Phase 3A Subtitle/Audio extraction completed. Build/installation passed on 192.168.0.21. Manual QA confirmed IPTV playback, Debrid playback, Subtitle/audio behavior, and Live TV zapping all work. No behavior regression found. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. Next extraction phase is NOT approved yet.
- 2026-05-25: Phase 1 PlayerHistoryManager extraction completed. Build/installation passed on 192.168.0.21. Manual QA confirmed Debrid Continue Watching same-day resume works, and VOD playback triggers onStop history save correctly. No behavior regression reported in tested history path. Phase 2 PlayerNetworkStallManager remains NOT APPROVED / DO NOT START.
- 2026-05-24: `:app:compileDebugKotlin` and `:app:assembleDebug` passed. Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`; app PIDs confirmed. Manual shared Player regression remains required for Debrid Continue Watching movie/series resume, direct addon resume, resolver-backed resume, normal Debrid source picker playback, IPTV Continue Watching, and Live TV zapping.
- 2026-05-24 follow-up: expired/null `expiresAt` Debrid movie Continue Watching resume passed on `192.168.0.21:5555`. PlayerActivity logged the Debrid resume refresh branch, `PlaybackResolver` reported success, and playback opened in `player_view` with no stuck resolving state observed.
- 2026-05-23: compile, assemble, install/launch/PID/crash scan passed on `192.168.0.21:5555` and `192.168.0.84:5555` after playlist fallback guard.
- 2026-05-23: manual QA passed for IPTV Series and Debrid Series episode browser plus Next Episode behavior.
