# Player Module Report
Status: canonical/current
Scope: shared `PlayerActivity` / `PlayerViewModel` playback path.

## Current Active Runtime Flow
- All playback enters `player/stabilized/PlayerActivity` through `PlayerActivity.createIntent()`.
- Media3 `ExoPlayer` is shared for Live TV, IPTV VOD, IPTV Series, Debrid movies, and Debrid series.
- `PlayerViewModel` owns EPG overlay state, Live zapping state, Series playlist state, and Debrid resolution state.
- `PlayerKeyDispatcher`, `PlayerBrowserManager`, `EpisodeBrowserController`, `PlayerTrackManager`, and `PlayerHistoryManager` handle source-aware controls.
- `PlayerHistoryManager` aligns Debrid Continue Watching IDs with canonical watched-state keys and avoids raw info-hash durable CW identifiers.

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
- `PlayerHistoryManager` aligns Debrid Continue Watching IDs with canonical watched-state keys and avoids raw info-hash durable CW identifiers.

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
- **Direct Debrid No-First-Frame Timeout Return (2026-06-03):** Direct addon/proxy Debrid playback that times out before rendering a first frame now returns to the source picker with the failed stream id and auto-next hint instead of retrying the same URL or forcing full source extraction first. This keeps terminal provider failures in the source-selection recovery surface while preserving Debrid identity, headers, and direct-play guards.
- **Debrid Playback Stall Recovery Hardening (2026-06-03):** `PlayerActivity` now treats Debrid `READY`-state position stalls as multi-strike warnings before synthesizing a playback error, while IPTV keeps its previous watchdog behavior. Direct Debrid recoverable player errors and buffer timeouts now try a fresh source-profile refresh before replaying the same direct URL, and resolver-backed Debrid retry explicitly keeps direct passthrough disabled. Added diagnostic coverage for `stall_warning` and `direct_debrid_refresh_started`. `:app:compileDebugKotlin` and focused Debrid passthrough/repository unit tests passed; long-run device playback QA is still required for final behavioral closure.
- Added debug-only local Playback Diagnostics Recorder hooks for manual Debrid playback testing. Recorder stays local/JSONL-only, is gated by `BuildConfig.DEBUG` plus `.enabled` marker file, captures source/player/retry/track events with redacted URL/header fields, and does not change playback behavior, retry policy, or cached/direct semantics.
- Debrid Continue Watching identity alignment completed in `PlayerHistoryManager.kt`. Debrid CW now uses canonical watched-state-compatible keys for Debrid movie/episode progress entries, removes raw info-hash fallback leakage, and keeps IPTV Continue Watching paths unchanged. Completion-threshold watched-state cleanup and CW removal now share the same hardened Debrid identity contract.
- Manual Mark Watched/Unwatched completed and QA passed (UI added to VOD, Movie Detail, and Series episodes). Auto watched detection remains working. Episode Browser manual switch watched save and Auto Next Episode watched save remain working. Movie and episode playback still works. Source picker focus scrolling and BACK navigation return from PlayerActivity remain fully working. Debrid-specific manual watched/unwatched verification remains pending unless explicitly tested.
- Fixed Episode Browser manual episode switch watched-state gap. Added a "save-before-switch" call to `historyManager.recordPlaybackHistoryIfNeeded()` in `PlayerActivity.onEpisodeSelected()` so the current episode identity is committed before switching to the newly selected episode. Auto Next Episode and manual BACK watched paths remain working.
- Completed full Phase 2 QA verification of the automatic watched detection logic. A local JVM unit test (`PlayerHistoryManagerTest`) was created to deterministically verify all threshold conditions (<5% ignoring, 5%-89% normal tracking, ≥90% completion, and time-remaining completion) without relying on unreliable adb remote seek. Full regression checks (BACK navigation, source picker, playback, no log leakage) passed manually on device 192.168.0.84. NOTE: Debrid identity tracking during real-world Debrid playback paths requires future manual verification, and UI badges/actions are NOT yet implemented.
- Added Phase 1 canonical `watched_state` Room storage foundation for Movies and Episodes. This is storage-only: `PlayerActivity`, `PlayerHistoryManager`, Continue Watching, source picker, and playback behavior are not wired or changed yet.
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
- 2026-05-31: Debug-only Playback Diagnostics Recorder implemented and smoke verified on `192.168.0.21:5555`. `:app:compileDebugKotlin` and `:app:assembleDebug` passed; refreshed APK installed on `.21`. Without marker file, no session JSONL was created. With `/sdcard/Android/data/com.debridxtream.tv/files/playback-diagnostics/.enabled`, a real Debrid movie source-picker playback wrote JSONL events for `source_list_loaded`, `source_selected`, `playback_launch`, `player_initialize_started`, `media_item_built`, `player_prepare_called`, `track_discovery`, `track_selected`, `first_frame_rendered`, and `player_state_changed READY`. Export via `adb pull` succeeded. Redaction scan found no raw URLs, magnets, long hashes, bearer/basic auth, token params, Real-Debrid URLs, or secret header values. Direct-readiness/dead-link content QA remains a manual follow-up case.
- 2026-05-31: Replaced Media3 stock two-step audio/subtitle dialogs with one-press TV single-choice dialogs inside `PlayerTrackManager.kt` only. D-pad focus movement does not change tracks; center OK on a row applies the existing Media3 override and closes the dialog; Back dismisses without a new selection. Audio keeps `Auto`; subtitles keep `Off`, `Auto`, and available tracks. Playback, resolver, controller XML, and PlayerActivity dialog lifecycle were not changed. `:app:compileDebugKotlin` and `:app:assembleDebug` passed after stopping a stuck Gradle daemon and rerunning. Refreshed APK installed on `192.168.0.84:5555` and `192.168.0.21:5555`. Manual IPTV/Debrid audio, subtitle, Off, Auto, and Back-cancel QA remains pending.
- 2026-05-31: Cinematic reference-match controller polish completed in the single active runtime path. The Media3 `DefaultTimeBar` remains `exo_progress`; existing action IDs remain intact; working utility icons are compact above the timeline; transport actions remain centered below it; and the layout now restores the reference-style clock, poster/title metadata, 46dp white circular play/pause button, red scrubber, and truthful source-quality pill. The duplicate visible globe language alias was removed because it opened the same audio chooser as the waveform action; its bound ID remains hidden for Activity compatibility. Seekbar `DPAD_UP` now enters the visible waveform audio action instead of looping focus back to the seekbar; seekbar `DPAD_DOWN` still targets visible play/pause. Custom play/pause visibility now follows Media3 `playWhenReady`, and READY VOD playback re-arms normal controller auto-hide. `:app:compileDebugKotlin` and `:app:assembleDebug` passed after stopping stuck Gradle daemons and rerunning. Refreshed APK installed on `192.168.0.84:5555` and `192.168.0.21:5555`. User manual QA confirmed correct initial Pause icon, single-press pause behavior, startup controller auto-hide, and the corrected audio/subtitle D-pad route. Controller issue closed.
- 2026-05-30: Debrid Continue Watching identity alignment completed and verified. `PlayerHistoryManager.kt` now routes Debrid CW IDs through the canonical watched-state-compatible identity path, preserving stable TMDB/IMDb keys, using hardened title/year fallback keys when stable IDs are absent, and preventing raw info-hashes from becoming durable CW identifiers. `:app:compileDebugKotlin` and `:app:assembleDebug` passed, and APK installation completed on `192.168.0.21:5555` and `192.168.0.84:5555`. IPTV Continue Watching, PlayerActivity lifecycle binding, source picker routing, and playback launch contracts were not changed by this alignment pass.
- 2026-05-30: Manual Mark Watched/Unwatched QA passed. Verified that manual override behavior works as expected without breaking playback. Auto Next Episode watched save, Episode Browser manual switch watched save, and BACK return from PlayerActivity still work correctly. Debrid-specific manual watched/unwatched verification remains pending.
- 2026-05-30: Phase 2 Automatic Watched Detection QA fully verified. Since UI adb automation for mid-progress playback was unreliable, verification was performed deterministically using a JVM unit test (`PlayerHistoryManagerTest`) that passed perfectly. Regression checks (BACK nav, source picker UI, playback load, zero log leaks) were manually verified via adb on 192.168.0.84.
- 2026-05-29: Root cause of `UninitializedPropertyAccessException` in `SourceSelectionBottomSheet` (triggered on PlayerActivity BACK return) fixed by moving source UI rendering inside `onViewCreated` or using a `pendingSources` check. Manual QA confirmed PlayerActivity BACK no longer crashes or returns Home, but correctly resumes the `MovieDetailActivity`/`SeriesDetailActivity` source picker flow. Source picker focus scrolling and playback remain functional.
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
- 2026-05-31: Phase 1 debrid-agnostic direct addon/proxy HTTP `500-599` guard verified and closed. Existing successful direct playback remained intact: AIO series direct reached `first_frame_rendered` and `STATE_READY`, a second post-READY AIO launch reached READY with `retryCount=0`, StreamThru series direct reached READY, and StreamThru movie direct reached READY. On `192.168.0.21:5555`, an AIO direct source produced `500 -> retry 1 -> 500 -> terminal_failure -> return_to_sources(autoNext=false)` with the same URL fingerprint and `stremio` effective header profile. Existing HTTP `404` fast-fail remained zero-retry. Refreshed APK was also installed and tested on `192.168.0.84:5555`.
- 2026-05-31: Direct addon/proxy session-ranking positive qualification tightened in `PlayerActivity.kt` only. First frame alone no longer records a success preference: movies require first frame plus at least `1800000ms`, and episode/series playback requires first frame plus at least `300000ms`. Failure demotion hooks for terminal `404`, repeated same-context `500-599`, and repeated same-context no-first-frame timeout remain unchanged. Build passed (`:app:compileDebugKotlin`, `:app:assembleDebug`), refreshed APK installed on `192.168.0.84:5555`, and device QA confirmed `From` S4E1 AIO READY at `3239952ms`, a StreamThru short payload READY at only `30000ms` rejected by the threshold, and movie direct READY at `6329376ms`. Picker counts remained stable.
