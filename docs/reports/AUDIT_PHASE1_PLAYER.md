# AUDIT PHASE 1 — Player Spine / Playback Smoothness (Diagnose Only)

Status: diagnose-only audit. NO code changed. Reports NOT updated (module reports update only after fixes are confirmed).
Date: 2026-06-12
Mode: Full professional audit of the shared playback spine vs a polished IPTV app (TiviMate-level).

## Scope Inspected
- `player/stabilized/PlayerActivity.kt` (2718 lines)
- `player/stabilized/PlayerHistoryManager.kt`
- `player/stabilized/PlayerTrackManager.kt`
- `player/stabilized/PlayerNextEpisodeManager.kt`
- `player/stabilized/PlayerBufferConfigFactory.kt`

Out-of-scope files referenced as root-cause pointers only (NOT inspected, per instruction): `PlayerCacheManager`, `WatchHistoryPreferences`, `PlayerViewModel`, `DebridPlaybackRepository`, `SettingsPreferences`.

Protected behavior (NOT to be changed in any future fix): Live zapping `zapRequestId`/latest-zap-wins, Episode Browser current-series binding, Debrid playback path, Continue Watching. Items below that touch these are flagged **[TOUCHES PROTECTED]**.

Legend: each finding = ID • file:line area • symptom • root-cause hypothesis • reproduction path • risk.

---

## CRITICAL

### C1 — Every stream (including Live TV) is written to disk cache continuously
- **Where:** `PlayerActivity.initializePlayer()` lines 1684–1690.
- **Symptom:** All playback — Live TV, IPTV VOD, IPTV Series, Debrid — is wrapped in `CacheDataSource.Factory()` with a `CacheDataSink` write sink. Live continuous streams and multi-GB Debrid movies are streamed *to disk* while playing.
- **Root cause:** A single unconditional `CacheDataSource` chain is used for the media source factory regardless of `contentType`/`playbackSource`. There is no bypass for `ContentType.LIVE_TV` or for large progressive Debrid files.
- **Reproduction:** Play any Live channel for several minutes → observe continuous disk writes under the player cache dir; on a low-RAM TV box this competes with decode I/O and grows storage. Live never benefits from on-disk cache (no seek-back-into-cache value for an endless stream).
- **Risk:** High I/O pressure → micro-stutter/rebuffering on low-end TV hardware; storage growth; pointless write amplification for live. Polished IPTV players do not disk-cache live. **Smoothness + stability.**

### C2 — `onResume` re-initializes the player with a possibly-stale `currentUrl`, racing in-flight Debrid resolution
- **Where:** `onResume()` line 2101 (`if (player == null) currentUrl?.let { initializePlayer(it) }`) interacting with `onStop()` line 2104 (`releasePlayer("on_stop")`) and the Debrid resolution flow (onCreate lines 656–667; `observeDebridResolutionState` lines 2489–2525).
- **Symptom:** If the activity is stopped (player released, `player=null`) while a Debrid re-resolution is in flight (`isResolvingDebrid=true`), the next `onResume` rebuilds the player directly from `currentUrl` — which at that moment is the **stale/expired** URL captured in onCreate (line 470), bypassing the resolver. Can replay an expired direct URL or initialize before resolution returns; when resolution then completes it builds/switches again → transient orphan/duplicate init.
- **Root cause:** `onResume` has no guard for `isResolvingDebrid`, and `currentUrl` is seeded with the raw intent URL before resolution overwrites it (line 2509). `releasePlayer` on stop discards the player but not the pending-resolution state.
- **Reproduction:** Launch a Debrid Continue-Watching resume whose URL is expired (resolver triggered) → background the app (onStop releases) → foreground (onResume) before resolution completes → player builds on the expired URL.
- **Risk:** Resume failure / stuck-resolving / stale-URL replay. **[TOUCHES PROTECTED]** (Debrid playback, Continue Watching). Diagnose-only — do not modify the resolver contract.

---

## HIGH

### H1 — Player fully released on every `onStop` and rebuilt on `onResume` (redundant re-creation)
- **Where:** `onStop()` line 2104 → `releasePlayer()` lines 2106–2114; `onResume()` line 2101.
- **Symptom:** Any brief backgrounding (notification, PiP transition, system dialog that stops the activity) destroys the `ExoPlayer` and rebuilds it from scratch on return — re-running OkHttp/cache/track-selector/ExoPlayer construction and a fresh network connect. For Live this is a full channel reconnect; for VOD it re-buffers from the saved position.
- **Root cause:** Lifecycle policy releases unconditionally on stop rather than pausing and retaining for short stops. No distinction between "stopping briefly" and "finishing".
- **Reproduction:** Start playback → press Home → reopen app → observe re-buffer/reconnect and startup spinner instead of instant resume.
- **Risk:** Repeated startup delay and reconnect; worse than TiviMate which retains the surface/player across short stops. **Smoothness.** (Related to C2.)

### H2 — Heavy synchronous work on the main thread inside `initializePlayer` (cold-start jank/ANR risk)
- **Where:** `initializePlayer()` lines 1685–1718: `PlayerCacheManager.getCache(this)` (called twice, lines 1685 & 1687), `new SettingsPreferences(this)` line 1706, plus `OkHttpDataSource.Factory`, `DefaultTrackSelector`, `ExoPlayer.Builder(...).build()` — all before the first frame, all on the UI thread.
- **Root cause:** Cache initialization (`PlayerCacheManager.getCache`, pointer-only) scans/locks the on-disk cache directory; on first call this is disk I/O on the main thread during onCreate. `SettingsPreferences` is re-constructed despite an injected instance already existing (field `settingsPreferences`).
- **Reproduction:** Cold launch into Player on a low-end box → measurable delay from `onCreate` to first frame; `getCache` first-call disk scan blocks the UI thread.
- **Risk:** Startup delay; potential ANR if the cache dir is large/locked. **Startup smoothness.** Root cause partly in `PlayerCacheManager` (pointer; not modified here).

### H3 — `STATE_ENDED` for resolver-backed Debrid always calls `playNextEpisode()`, ignoring `hasNext`
- **Where:** `initializePlayer()` listener, `onPlaybackStateChanged` STATE_ENDED, lines 1766–1776: condition `if (canUseDebridResolver() || state?.hasNext == true)`. `playNextEpisode()` Debrid branch lines 1423–1450 falls back to `currentEpisode + 1`.
- **Symptom:** When a resolver-backed (non-direct) Debrid episode reaches the end, `canUseDebridResolver()` is true regardless of whether a next episode exists, so it always tries to advance. On the **last** episode this guesses `currentEpisode + 1` and attempts to resolve a non-existent episode → failure/error surface at series end.
- **Root cause:** End-of-stream advance is gated on resolver capability, not on `SeriesPlaylistState.hasNext`. This is the documented anti-pattern (APP_FAILED_PATTERNS #34 / DO_NOT_REPEAT "Series Controller Next Rule" / "Guessing Next Episode Continuity").
- **Reproduction:** Play the final episode of a Debrid series to completion → app tries to resolve a next episode that doesn't exist.
- **Risk:** Wrong/dead advance at end of series. **[TOUCHES PROTECTED]** (Debrid playback, episode continuity).

### H4 — Untracked `Handler.postDelayed` retry/finish runnables are never cancelled on teardown
- **Where:** `handlePlaybackError()` line 1940 (`Handler(Looper.getMainLooper()).postDelayed({ ... initializePlayer ... }, retryCount*1000)`); `handleTimeout()` line 2072 (delayed reinit, 2000ms); `handleTerminalPlaybackFailure()` line 2457 (delayed `finish()`, 3000ms).
- **Symptom:** These create *new* `Handler` instances whose delayed runnables capture the activity and are not held in any field, so `onStop`'s `timeoutHandler.removeCallbacks` cannot cancel them. If the activity finishes during the delay window (up to 5s), the runnable still fires after teardown.
- **Root cause:** Ad-hoc Handler allocation instead of the managed `timeoutHandler`. `initializePlayer` guards `isFinishing||isDestroyed` (so no crash), but the pattern is a phantom-reinit window and a leak of the captured activity for the delay duration. This is the residual of the "phantom audio after delayed retry" class noted in PLAYER_MODULE_REPORT.
- **Reproduction:** Trigger a playback error → press BACK during the retry backoff → delayed runnable still executes (no-op due to guard, but unmanaged).
- **Risk:** Phantom re-init attempts / short-lived activity leak; fragile reliance on the isFinishing guard.

### H5 — `observeDebridResolutionState` and `initBrowserOverlay` collect WITHOUT `repeatOnLifecycle`
- **Where:** `observeDebridResolutionState()` line 2490 (`lifecycleScope.launch { viewModel.debridResolutionState.collect { ... } }`) and `initBrowserOverlay()` collect line 2192–2211. Compare with `observeSeriesPlaylistState`/`observeOverlayState`/`observeZapState` which correctly use `repeatOnLifecycle(STARTED)`.
- **Symptom:** Debrid resolution Success can call `initializePlayer`/`performSeamlessSwitch` (lines 2510–2514) while the activity is stopped (after `onStop` already released the player), creating an orphan player in the background.
- **Root cause:** Collection runs for the whole `lifecycleScope` lifetime instead of being lifecycle-bound to STARTED. Only the `isFinishing||isDestroyed` guard protects init — a "stopped but not finishing" state is unguarded.
- **Reproduction:** Start a Debrid resolve → background app (onStop) → resolution completes in background → `initializePlayer` runs while stopped.
- **Risk:** Orphan/duplicate player while stopped. **[TOUCHES PROTECTED]** (Debrid playback).

---

## MEDIUM

### M1 — Double next-episode advance race (auto-play countdown vs STATE_ENDED)
- **Where:** `PlayerNextEpisodeManager.checkNextEpisodePrompt` (1s poll) → 15s countdown → `playNextEpisode()` (NextEp lines 85–161); and `PlayerActivity` STATE_ENDED → `playNextEpisode()` (line 1770).
- **Symptom:** If the credits/tail are short, the prompt's 15s countdown and the real `STATE_ENDED` can both fire `playNextEpisode()` near-simultaneously → two advance/resolve calls → skipped episode or double resolve.
- **Root cause:** Two independent advance triggers with no shared single-flight guard. STATE_ENDED is suppressed from `finish()` while the prompt is visible (line 1775) but is NOT suppressed from calling `playNextEpisode()`.
- **Reproduction:** Play an episode whose remaining time after the 0.97/45s prompt threshold is < ~15s → countdown completes around the same time the stream ends.
- **Risk:** Episode skip / double resolve. **[TOUCHES PROTECTED]** (episode continuity).

### M2 — IPTV episode switch does not reset `startPositionMs` (Debrid path does)
- **Where:** `playSeriesEpisode()` lines 1327–1342 and `onEpisodeSelected()` IPTV branch line 1230. Compare Debrid `DebridResolutionState.Success` which resets `startPositionMs = 0L` when `!isSame` (line 2503).
- **Symptom:** When switching IPTV episodes via the Episode Browser before the previous episode cleared `startPositionMs` (it is cleared to 0 only after READY + >1000ms, line 1748), the new episode `performSeamlessSwitch` seeks to the *previous* resume position (line 838).
- **Root cause:** No `startPositionMs = 0L` reset on the IPTV episode-switch path.
- **Reproduction:** Resume an IPTV episode mid-way → quickly open Episode Browser and pick another episode before the first reaches READY → new episode jumps to the old position.
- **Risk:** Wrong seek/resume glitch on IPTV series. **[TOUCHES PROTECTED]** (Episode Browser binding — diagnose only).

### M3 — IPTV/Live non-Debrid stall = single 12s strike → synthesized error → full release+reinit
- **Where:** `readyStallRequiredStrikes()` lines 1997–2005 (non-Debrid returns `1` strike, or `LOW_RAM_STALL_STRIKES=2`); `checkForStall()` lines 1958–1995 synthesizes `ERROR_CODE_REMOTE_ERROR` → `handlePlaybackError` → release + reinit.
- **Symptom:** A single 12s no-progress window on IPTV VOD/Live forces a full player teardown/rebuild rather than allowing ExoPlayer to rebuffer naturally. Debrid was hardened to multi-strike (3/4) but IPTV/Live keep single-strike.
- **Root cause:** Asymmetric strike policy; IPTV/Live retained the aggressive single-strike behavior.
- **Reproduction:** Play a weak IPTV stream that pauses ~12s at a segment boundary → forced reload instead of organic recovery.
- **Risk:** Unnecessary reloads/visible reconnect on transiently slow IPTV/Live streams. **Smoothness.**

### M4 — Index-based audio/subtitle persistence can map to the wrong track across sources
- **Where:** `PlayerTrackManager.captureManualTrackSelection` lines 195–249 and `applyTrackIndexOverrides` lines 251–315 (positional `sAIdx`/`sTIdx` saved per infohash/series).
- **Symptom:** Saved selections are positional track indices, not language/identity. A different episode/source with a different track ordering re-applies the saved index to a different track → wrong audio/subtitle auto-selected.
- **Root cause:** Persistence keyed on ordinal index rather than language/role. (The live dialog selection itself correctly uses `TrackSelectionOverride`; only the persisted re-apply is ordinal.)
- **Reproduction:** Set audio to track #2 on one episode → next episode from a different source has audio tracks in a different order → index #2 selects a different language.
- **Risk:** Wrong track on episode/source change. **[TOUCHES PROTECTED]** (Debrid source-language; diagnose only).

### M5 — Automatic watched-state persistence launched on `activity.lifecycleScope` during `onDestroy`
- **Where:** `PlayerHistoryManager.recordAutomaticWatchedState` line 160 (`activity.lifecycleScope.launch(Dispatchers.IO){ ... upsert ... }`), invoked from `onDestroy(owner)` line 40 and `PlayerActivity.onDestroy` line 2124.
- **Symptom:** The IO upsert is launched on the activity's `lifecycleScope`, which is cancelled as the activity is destroyed → the watched-state write can be cancelled before it completes.
- **Root cause:** Teardown-time persistence tied to a scope that is being cancelled. Continue-Watching prefs save is synchronous (so it lands), but the Room watched-state upsert may not.
- **Reproduction:** Finish an episode and immediately exit so `onDestroy` fires → race between scope cancellation and the IO write.
- **Risk:** Occasionally lost automatic watched-state. **[TOUCHES PROTECTED]** (Continue Watching / watched-state).

### M6 — Synchronous SharedPreferences writes on the main thread during teardown
- **Where:** `PlayerHistoryManager.recordLiveHistory` line 76 (`watchHistoryPrefs.addRecentLiveChannel`) and `saveContinueWatchingIfAllowed` line 357 (`watchHistoryPrefs.saveContinueWatchingItem`), reached from `onStop`/`onDestroy`/onBackPressed (all main thread). Root cause in `WatchHistoryPreferences` (pointer-only).
- **Symptom:** If those prefs writes serialize JSON and `commit()` synchronously, they block the UI thread during exit/zapping.
- **Reproduction:** Exit playback with a large Continue-Watching list → potential frame drop on teardown.
- **Risk:** Exit/zap jank. Needs `WatchHistoryPreferences` review (out of scope here).

### M7 — `onPause` without `onStop` leaves player paused with no resume
- **Where:** `onPause()` line 2103 (`player?.pause()`); `onResume()` line 2101 only re-inits when `player == null`, otherwise just `startStallMonitor()` — never calls `player.play()`.
- **Symptom:** A pause-without-stop cycle (e.g., entering/exiting multi-window or certain PiP transitions where the player is retained) returns to a paused player that does not resume.
- **Root cause:** `onResume` does not restore `playWhenReady` for the retained-player path.
- **Reproduction:** Trigger an onPause→onResume transition that does not pass through onStop while the player survives → playback stays paused.
- **Risk:** Playback appears frozen after returning from a transient pause. (Track dialogs do NOT trigger onPause, so this is limited to system pause transitions.)

### M8 — `attemptNetworkRecovery` uses raw `player.release()` and skips `releasePlayer` housekeeping
- **Where:** `attemptNetworkRecovery()` lines 1843–1857 (`playerSnapshot.release(); player = null; initializePlayer(url)`).
- **Symptom:** Recovery releases the player directly without `playerView.player = null`, without diagnostics, and without history finalize, then reinitializes. Brief stale binding of a released player to `playerView` before reinit rebinds.
- **Root cause:** Bypasses the centralized `releasePlayer()` path.
- **Reproduction:** Network drop + restore on a VOD stream → recovery path runs.
- **Risk:** Inconsistent teardown; possible surface flicker on reconnect. Low-frequency.

---

## MINOR

### Mi1 — Dead methods never called
- `playUrl()` (line 743), `showLanguageSelection()` (line 935), `showVideoSelection()` (line 991), `showSupportQr()` (line 2009) have no callers (verified by in-file search). `playUrl` is the only reader of the `isSwitching` early-return, so `isSwitching` (line 137) is effectively vestigial as a guard.
- **Risk:** Maintenance confusion; divergent unused code paths.

### Mi2 — Dead/unused VOD overlay + debug overlay
- `vodInfoOverlay`/`imgVodArtwork`/`tvVodTitle`/`tvVodSubtitle` (lines 181–184) are never assigned; `setVodOverlayVisible`/`updateVodOverlayVisibility`/`hideUiForPiP` operate on a null overlay (no-ops). `debugEnabled` (line 140) is never set true → entire debug-overlay path (`updateDebugOverlay`, `debugOverlayRunnable`, `debugListener`, `layoutDebugOverlay`/`tvDebugInfo`) is dead.
- **Risk:** Dead weight; misleading "active" code.

### Mi3 — Redundant `SettingsPreferences` instantiation
- `initializePlayer` line 1706 constructs `SettingsPreferences(this)` though the injected `settingsPreferences` field exists.
- **Risk:** Extra allocation/possible prefs read per init.

### Mi4 — Single unsupported audio group has no fallback selection
- `onTracksChanged` software-audio fallback (lines 1805–1818) only overrides when `audioGroups.size > 1`. A single unsupported audio group (size == 1) gets no override → potential silent audio if the renderer can't handle it.
- **Risk:** Edge-case no-audio on exotic single-track codecs.

### Mi5 — Subtitle "loading" toast can persist
- `PlayerTrackManager.showSubtitleSelection` lines 112–123 shows "subtitles loading" when no text tracks yet. If a sidecar subtitle never loads (bad URL), repeated opens keep saying loading with no resolution.
- **Risk:** Confusing UX on broken sidecar subtitles.

### Mi6 — LIVE_TV history re-recorded multiple times per teardown
- `recordPlaybackHistoryIfNeeded` (History lines 44–57) guards VOD with `hasRecordedHistory` but LIVE_TV is unguarded; `onStop` + `onDestroy` + back-press each re-write recent-live.
- **Risk:** Redundant prefs writes (compounds M6).

---

## POLISH

### P1 — Verbose debug logging in hot paths
- `dispatchKeyEvent` logs every key (line 2216, comment "LOG EVERY KEY FOR PROOF"). Numerous `Log.d`/`Log.e` leftovers (`IPTV_EP_LOAD_FIX` used at `Log.e` for non-errors, lines 1094/1111/1187; `SWITCH_DEBUG`, `EP_BROWSER_FOCUS_FIX`, `PLAYER_SEEK_FOCUS`).
- **Risk:** Log spam; minor per-key overhead. Confirm none print sensitive values (URLs already routed through `SensitiveLogRedactor`).

### P2 — A/V tuning choices
- Tunneling explicitly disabled (`setTunnelingEnabled(false)`, line 1701) — tunneled playback can improve A/V sync/power on TV. No `setHandleAudioBecomingNoisy`. Both are deliberate-but-worth-revisiting for TV polish.

### P3 — Fragile MIME heuristic
- `resolveMediaMimeType` `.ts` branch checks `sourceText.contains(".ts ")` with a trailing space (line 913); only `cleanUrl.endsWith(".ts")` reliably catches it.
- **Risk:** Mislabeled container in rare metadata-only cases.

### P4 — Inconsistent retry backoff
- Player-error retry uses `retryCount * 1000L` (line 1940); buffer-timeout retry uses a fixed `2000ms` (line 2072).
- **Risk:** Cosmetic inconsistency in recovery cadence.

---

## Cross-Cutting Themes (for fix sequencing later — NOT fixing now)
1. **Caching policy** (C1, H2): a single unconditional `CacheDataSource` chain drives both the live-disk-write problem and the main-thread cache-init cost. Highest smoothness leverage.
2. **Lifecycle/recreation** (C2, H1, H4, H5, M7, M8): release-on-stop + rebuild-on-resume + unmanaged delayed handlers + non-lifecycle-bound collects together create the redundant-recreation and race surface.
3. **Episode continuity** (H3, M1, M2): three independent advance/seek paths (STATE_ENDED, countdown auto-play, browser switch) lack a shared guard and consistent `startPositionMs`/`hasNext` discipline.
4. **Persistence-on-teardown** (M5, M6, Mi6): main-thread prefs writes + cancellable IO scope at destroy.
5. **Dead code** (Mi1, Mi2, Mi3): safe to prune; reduces audit/maintenance noise.

## QA / Verification notes for any future fix
- Every item above is reproducible via the listed code path. No build was run (diagnose-only).
- Future fixes touching **[TOUCHES PROTECTED]** items (C2, H3, H5, M1, M2, M4, M5) MUST regress: Live zapping (`zapRequestId`), Episode Browser series binding, Debrid movie/series playback, Continue Watching resume — per PLAYER_MODULE_REPORT QA checklist.
- No tokens, stream URLs, magnets, hashes, or credentials are reproduced in this report.
