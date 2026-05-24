# Recent Changes

Track only the last 10 important changes. Newest first. Keep this file compact for regression debugging.

Date: 2026-05-24
Module: Debrid / Player / Continue Watching
Issue fixed: Debrid Continue Watching resume no longer replays saved null-expiry history URLs as safe direct playback. Player resume now treats Debrid history with missing `expiresAt` as refresh-required, fresh-resolves hash/magnet entries, and allows freshly fetched direct addon results to pass through.
Files changed: `PlayerActivity.kt`
Regression risk: Debrid Continue Watching movie/series resume, direct addon resume, resolver-backed hash/magnet resume, normal Debrid source picker playback.
QA result: `:app:compileDebugKotlin` and `:app:assembleDebug` passed. Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`; app PIDs confirmed. Content-specific manual Debrid/Continue Watching regression remains required.
Follow-up QA: 2026-05-24 expired/null `expiresAt` Debrid movie Continue Watching resume passed on `192.168.0.21:5555`. Home routed the expired item to Player, PlayerActivity triggered Debrid refresh instead of direct stale URL initialization, `PlaybackResolver` reported success, and playback started without a false Real-Debrid config error.

Date: 2026-05-23
Module: Player
Issue fixed: Phantom audio playing in the background after returning from the player due to `Handler` postDelayed recreating `ExoPlayer` instances after the activity finished.
Files changed: `PlayerActivity.kt`
Regression risk: ExoPlayer initialization, network retry delay logic, and stream switching logic.
QA result: `:app:assembleDebug` passed. `isFinishing || isDestroyed` checks securely prevent recreation.

Date: 2026-05-23
Module: Live TV
Issue fixed: Channel list focus could get stuck or jump back to the category sidebar because focus-return paths targeted the category RecyclerView container instead of a bound category child item.
Files changed: `LiveFragment.kt`
Regression risk: Category navigation, channel item D-pad movement, fullscreen return focus, Live zapping.
QA result: `:app:assembleDebug` passed. Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`. Manual TV D-pad regression still required.

Date: 2026-05-23
Module: Player / Live TV
Issue fixed: Fast Live TV zapping race condition causing video/metadata mismatch. Fixed by using latest-zap-wins logic with `zapRequestId` and `streamId`.
Files changed: `PlayerActivity.kt`, `PlayerViewModel.kt`
Regression risk: Live zapping speed, fullscreen return, async EPG loading.
QA result: Fast zapping in both directions passed on target TVs. Video and UI stay in sync.

Date: 2026-05-23
Module: Debrid / Player
Issue fixed: Debrid playback "Real Debrid config missing" regression caused by Player/Episode Browser changes has been resolved.
Files changed: `PlayerViewModel.kt`
Regression risk: Direct Debrid/addon HTTP streams passing through Real-Debrid config validation instead of playing directly.
QA result: Debrid Movie source click works. Debrid Series source click works. IPTV and Live TV continue working.

Date: 2026-05-23
Module: Player / Series
Issue fixed: Stale Episode Browser could show episodes from a previous series/season/source.
Files changed: `PlayerViewModel.kt`, `SeriesPlaylistManager.kt`, `PlayerBrowserManager.kt`
Regression risk: Episode Browser, Next Episode, Debrid Series, IPTV Series.
QA result: compile, assemble, install/launch, crash scan, and manual IPTV/Debrid Series episode-browser QA passed on both target TVs.

Date: 2026-05-23
Module: Player / Live TV
Issue fixed: Live TV EPG zapping initialization restored during Player creation.
Files changed: `PlayerActivity.kt`, `PlayerViewModel.kt`, Player Live overlay/zapping path
Regression risk: Live zapping, fullscreen return, EPG overlay, D-pad channel switching.
QA result: build/device smoke previously passed; rerun Live regression when touching this path.

Date: 2026-05-23
Module: Player
Issue fixed: Controller seekbar `DPAD_UP` now reaches audio/subtitle/language controls instead of Play/Pause.
Files changed: `PlayerKeyDispatcher.kt`, `custom_player_control_view.xml`
Regression risk: VOD/Series controller focus and track selection.
QA result: compile/build verified in Player report; manual controller QA required for future edits.

Date: 2026-05-23
Module: Player / IPTV Series
Issue fixed: IPTV Series playlist loading rejects stream URLs as episode IDs and avoids episode-zero fallback.
Files changed: `PlayerBrowserManager.kt`, `SeriesPlaylistManager.kt`, Player intent/playlist state path
Regression risk: IPTV Series Episode Browser, Continue Watching episode resume, Next Episode.
QA result: compile, assemble, install/launch, and manual IPTV Series QA passed on both target TVs.

Date: 2026-05-22
Module: Companion / Security
Issue fixed: Companion LAN access, PIN validation, URL handling, and sensitive logging hardened.
Files changed: Companion config/server and settings/security paths.
Regression risk: Companion setup, Firestore/device-code sync, IPTV credential ingestion.
QA result: recent build/device verification passed; manual payload round-trip remains tracked in companion QA report.

Date: 2026-05-22
Module: Home
Issue fixed: Home focus/sidebar/teardown stability consolidated into manager-based flow.
Files changed: `HomeFragment.kt`, `HomeFocusManager.kt`, `HomeKeyRoutingManager.kt`, `HomeNavigationRouter.kt`, `HomeSidebarManager.kt`
Regression risk: Home row focus, sidebar return, Continue Watching actions.
QA result: build and real-device Home verification completed in Home reports.

### Phase 2: Memory & Lifecycle Cleanup
- **SeriesFragment.kt**: Nullified RecyclerView adapters in onDestroyView() to fix massive memory leaks resulting from retained PagingData and ViewHolders.
- **LiveFragment.kt**, **VodFragment.kt**, **SeriesFragment.kt**: Added if (!isAdded) return@post guards inside async focus restorations (post and postDelayed) to fix crash patterns and phantom focus states when rapidly switching tabs during UI delays.
- **Verification**: Conducted adb monkey stress test (--pct-syskeys 0 -v 500) with zero memory or lifecycle crashes observed.


### Phase 3: Paging3 Buffer Expansion
- **LiveViewModel.kt**, **SeriesViewModel.kt**, **VodViewModel.kt**: Increased Paging3 pageSize from 20 to 60, and prefetchDistance from 5 to 20. This prevents loading jitter and stuttering when rapidly scrolling through long lists via D-pad, ensuring a seamless user experience.
- **Verification**: Built ssembleDebug successfully. Ran ADB monkey test (--pct-syskeys 0 -v 500) on device 192.168.0.84:5555 with 0 crashes.

