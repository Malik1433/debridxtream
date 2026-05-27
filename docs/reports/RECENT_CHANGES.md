# Recent Changes

Track only the last 10 important changes. Newest first. Keep this file compact for regression debugging.

Date: 2026-05-27
Module: Player
Issue fixed: Phase 5A PlayerBufferConfigFactory extraction completed. Extracted pure buffer configuration logic (calculateSmartBuffer, network quality enum, and device capability checks) into a stateless factory.
Files changed: `PlayerActivity.kt`, `PlayerBufferConfigFactory.kt`
Regression risk: IPTV playback, Debrid playback, buffering behavior, live TV zapping, buffer config loading.
QA result: Build/install applied successfully. Manual QA passed on 192.168.0.84 with no behavior regression found. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. EPG Overlay / Live Browser remains mapping-only, not approved.

Date: 2026-05-27
Module: Player / Debrid / Continue Watching
Issue fixed: Old Debrid CW item resume failed by looping/reloading expired proxy/direct URLs instead of fresh RD resolving. Fixed by forcing resolver to use `infoHash`/`magnet` directly for old CW items instead of attempting passthrough with expired URLs. Expected behavior is that retry should use durable hash/magnet for fresh RD resolve.
Files changed: `PlayerActivity.kt`, `DebridPlaybackRepository.kt`, `UnifiedSourceProvider.kt`
Regression risk: Resume for normal Debrid items, resume for newer items, normal Debrid source resolution.
QA result: Build/install applied successfully. Old Debrid Continue Watching item resume functionality is PENDING next-day QA to confirm no stuck reloading and proper fresh resolving.

Date: 2026-05-25
Module: Player (Full App Post-Refactor Smoke Test)
Issue fixed: None. Verification-only smoke test after Phase 1, 3A, and 4B PlayerActivity extractions.
Files changed: None.
Regression risk: N/A.
QA result: Refactor regression status PASS. Full app smoke PARTIAL. Device: 192.168.0.84. IPTV movie PASS. IPTV series PASS. Live TV fullscreen PASS. Live TV fast zapping PASS. Episode Browser PASS. Next Episode prompt PASS. Subtitle/audio PASS. Back button PASS. Debrid source picker PARTIAL (MediaFusion/AIO intermittent API exceptions, StremThru PASS). Debrid CW resume INTERMITTENT. Debrid CW expired resume FAIL (starts then stalls on reloading). Debrid issues are pre-existing, not refactor regressions. Phase 2 BLOCKED. Phase 5+ NOT APPROVED.
Open issues recorded:
- Open Issue A: MediaFusion/AIO intermittent API exceptions during Debrid source resolution.
- Open Issue B: Debrid Continue Watching resume intermittently fails.
- Open Issue C: Debrid expired resume starts playback then stalls on reloading.
Next recommended task: Diagnose Only for Debrid expired resume reload/stall (no fix without approval).

Date: 2026-05-25
Module: Player / Debrid / Continue Watching
Issue fixed: Added regression test coverage proving Debrid history/direct-addon refresh does not replay stale direct AIOStreams playback URLs when direct HTTP passthrough is disabled.
Files changed: `PlayerViewModelDebridDirectPassthroughTest.kt`
Regression risk: None at runtime; test-only change.
QA result: `:app:testDebugUnitTest --tests "*PlayerViewModelDebridDirectPassthroughTest*"` passed. `:app:testDebugUnitTest --tests "*DebridResolutionManager*"` passed through the test-only alias for the current PlayerViewModel-owned resolver path. No runtime code changed.

Date: 2026-05-25
Module: Player / Subtitle / Audio
Issue fixed: Phase 3A Subtitle/Audio extraction completed. Extracted subtitle and audio logic from PlayerActivity into PlayerTrackManager.
Files changed: `PlayerActivity.kt`, `PlayerTrackManager.kt`
Regression risk: IPTV playback, Debrid playback, Subtitle/audio behavior, Live TV zapping.
QA result: Build/install completed. Manual QA passed on 192.168.0.21. No behavior regression found. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. Next extraction phase is NOT approved yet.

Date: 2026-05-25
Module: Player
Issue fixed: Phase 2 PlayerNetworkStallManager extraction mapping completed and reviewed. Full extraction is BLOCKED / NOT APPROVED due to high coupling with Player lifecycle, ExoPlayer instances, and Debrid re-resolution.
Files changed: None (Documentation updated only)
Regression risk: None (No runtime code changed).
QA result: N/A. Next step is either optional Phase 2B mapping (`PlayerStallDetector`) or skipping to lower-risk cleanup.

Date: 2026-05-25
Module: Player / History
Issue fixed: Phase 1 PlayerHistoryManager extraction completed. Refactored history-related logic out of PlayerActivity into a DefaultLifecycleObserver.
Files changed: `PlayerActivity.kt`, `PlayerHistoryManager.kt`
Regression risk: Continue Watching resume, Live TV history save, Debrid resume.
QA result: Build/installation passed on 192.168.0.21. Manual QA confirmed Debrid Continue Watching same-day resume works, and VOD playback triggers onStop history save correctly. No behavior regression reported in tested history path. Phase 2 PlayerNetworkStallManager remains NOT APPROVED / DO NOT START.

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

### Phase 4B: PlayerNextEpisodeManager Extraction
- Extracted Next Episode prompt and binge playback logic into PlayerNextEpisodeManager.kt
- Wired callbacks between PlayerActivity and the new manager
- Compilation errors fixed, clean build passed
- Installed on 192.168.0.21. Manual QA passed. Phase 4B VERIFIED / CLOSED.

### Phase 2: Memory & Lifecycle Cleanup
- **SeriesFragment.kt**: Nullified RecyclerView adapters in onDestroyView() to fix massive memory leaks resulting from retained PagingData and ViewHolders.
- **LiveFragment.kt**, **VodFragment.kt**, **SeriesFragment.kt**: Added if (!isAdded) return@post guards inside async focus restorations (post and postDelayed) to fix crash patterns and phantom focus states when rapidly switching tabs during UI delays.
- **Verification**: Conducted adb monkey stress test (--pct-syskeys 0 -v 500) with zero memory or lifecycle crashes observed.

### Phase 3: Paging3 Buffer Expansion
- **LiveViewModel.kt**, **SeriesViewModel.kt**, **VodViewModel.kt**: Increased Paging3 pageSize from 20 to 60, and prefetchDistance from 5 to 20. This prevents loading jitter and stuttering when rapidly scrolling through long lists via D-pad, ensuring a seamless user experience.
- **Verification**: Built assembleDebug successfully. Ran ADB monkey test (--pct-syskeys 0 -v 500) on device 192.168.0.84:5555 with 0 crashes.
