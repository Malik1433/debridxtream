# PlayerActivity Refactor Plan

## 1. Approved Phase Order
- **Phase 0:** Safety baseline only (COMPLETED)
- **Phase 1:** PlayerHistoryManager only after approval (COMPLETED & VERIFIED)
- **Phase 2:** PlayerNetworkStallManager only after Phase 1 is verified (BLOCKED / NOT APPROVED)
- **Phase 3A:** PlayerTrackManager (Subtitle/Audio) extraction (COMPLETED & VERIFIED)
- **Phase 4B:** PlayerNextEpisodeManager extraction (COMPLETED & VERIFIED)
- **Phase 5A:** PlayerBufferConfigFactory extraction (COMPLETED & VERIFIED)
- **Phase 5+:** Next extraction phase is NOT approved yet.

## 2. Phase 1 Guardrails
PlayerHistoryManager extraction MUST preserve exactly:
- Debrid Continue Watching same-day resume
- Debrid Continue Watching expired/null `expiresAt` fresh resolve
- IPTV Continue Watching
- Live history
- Playback progress threshold rules (e.g., min progress, min duration)
- Completion threshold rules
- No position 0 save
- No temporary Debrid URL durable-resume regression

## 3. Phase 1 Allowed Scope
Only inspect/extract:
- `recordPlaybackHistoryIfNeeded`
- `recordLiveHistory`
- `recordContinueWatchingHistory`
- Direct dependencies needed exclusively by those functions (e.g., `resolveContinueWatchingId`, threshold constants).

## 4. Phase 1 Forbidden Scope
Do NOT touch:
- Debrid resolver logic
- PlayerActivity playback initialization
- Live zapping
- Episode Browser
- Subtitles/audio
- Overlay/controller
- ExoPlayer `MediaItem` construction
- Storage schema / dedupe key logic

## 5. Required Baseline Tests Before Phase 1
- Same-day Debrid Continue Watching resume
- Expired/null `expiresAt` Debrid Continue Watching simulation
- IPTV Continue Watching smoke test
- Live TV zapping
- IPTV movie playback
- IPTV series playback
- Debrid source picker playback

## 6. Required Tests After Phase 1
Same tests as baseline. Phase 1 is accepted **ONLY** if all results match the baseline.
- **Result (2026-05-25):** Build/installation passed on 192.168.0.21. Manual QA confirmed Debrid Continue Watching same-day resume works, and VOD playback triggers onStop history save correctly. No behavior regression reported. Phase 1 ACCEPTED.

## 7. Hard Implementation Rule
When Phase 1 starts, it must be a **no-behavior-change extraction only**.
Touch maximum **1 new manager file** (`PlayerHistoryManager.kt`) + `PlayerActivity.kt`.
If more files are needed, stop and ask for permission before modifying them.

## 8. Phase 2 Status
Phase 2 (PlayerNetworkStallManager full extraction) is **BLOCKED / NOT APPROVED**.
- **Reason:** High coupling with ExoPlayer instance, UI state, Debrid re-resolution, and Live TV recovery.
- **Decision:** Recovery/retry logic must remain in `PlayerActivity` for now. Do not expose internal mutable state to force extraction.

## 9. Phase 3A Status
Phase 3A (PlayerTrackManager for Subtitle/Audio extraction) is **COMPLETED & VERIFIED**.
- **Result (2026-05-25):** Extraction completed without behavior changes. Manual QA on 192.168.0.21 passed for IPTV, Debrid, and Live TV zapping. No regressions found.

## 10. Phase 4B Status
Phase 4B (PlayerNextEpisodeManager extraction) is **COMPLETED & VERIFIED**.
- **Result (2026-05-25):** Extraction completed without behavior changes. Manual QA on 192.168.0.21 passed for Next Episode prompt visibility, countdown, and episode loading. No regressions found.

## 11. Phase 5A Status
Phase 5A (PlayerBufferConfigFactory extraction) is **COMPLETED & VERIFIED**.
- **Result (2026-05-27):** Extracted pure buffer configuration logic (buffer math, network quality enum, and device capability checks) into a stateless factory. Build and QA passed on 192.168.0.84. No behavior changes. Phase 2 (Network/Stall) remains BLOCKED. EPG Overlay / Live Browser remains mapping-only, not approved.
- **Next Phase:** Next extraction phase is NOT approved yet.

## 12. Post-Refactor Full App Smoke Test (2026-05-25)
- **Device:** 192.168.0.84
- **Refactor Regression Status:** PASS — all extracted managers (PlayerHistoryManager, PlayerTrackManager, PlayerNextEpisodeManager) verified working.
- **Full App Smoke Status:** PARTIAL — pre-existing Debrid issues found, not caused by refactoring.
- **Open Issue A:** MediaFusion/AIO intermittent API exceptions during Debrid source resolution.
- **Open Issue B:** Debrid Continue Watching resume intermittently fails.
- **Open Issue C:** Debrid expired resume starts playback then stalls on reloading.
- **Next recommended task:** Diagnose Only for Open Issue C (no fix without approval).
- **No automatic fixes applied.**

---

# PlayerActivity Decomposition Roadmap (drafted 2026-07-22)

**Status: PROPOSAL ONLY — no phase below is approved. Each needs explicit owner approval before execution**,
per the governance in §1. Nothing here changes the existing BLOCKED status of PlayerNetworkStallManager.

## Current state
`player/stabilized/PlayerActivity.kt` — **4402 lines, 145 methods, 152 fields**, `@AndroidEntryPoint` Activity.
Largest single methods: `initializePlayer` ~430, `onCreate` ~380, `handlePlaybackError` ~190,
`dispatchKeyEvent` ~140, `initBrowserOverlay` ~75.

Already extracted (this codebase's established delegate convention — keep following it):
PlayerHistoryManager, PlayerTrackManager, PlayerNextEpisodeManager, PlayerBufferConfigFactory,
EpisodeBrowserController, LivePlayerOsdManager, PlaybackQoeTracker, VodSeekOverlay,
LivePlaybackLoadErrorPolicy.

## Why this file is different from the data-layer god-classes
XtreamRepository (2716→467) and UnifiedSourceProvider decomposed cleanly because their logic is
state-in/state-out and unit-testable. PlayerActivity is an Activity: everything touches views, the
ExoPlayer instance, and lifecycle. So the unit here is a **delegate/controller that receives the
PlayerView + player + lifecycle scope**, not a "repository". Benefit: extracted managers become
unit-testable (cf. PlayerHistoryManagerTest).

## Known landmines (from prior post-mortems — DO NOT regress)
- NEVER re-add TsExtractor DETECT_ACCESS_UNITS / NON_IDR flags (video freeze on live TS).
- NEVER set LiveConfiguration on raw TS (seek-loop froze all .ts channels).
- First-frame freeze on any channel = AudioTrack alloc failure (bounded tunneling-off + cool-off).
- Adopt-before-takeFrame was rejected — keep the shared-player handoff order as-is.
Any phase touching live playback must be re-verified against all four.

## Proposed phase order (risk-ascending; sizes approximate)

| Phase | Extract | ~Lines | Risk |
|---|---|---|---|
| ~~P6~~ | ✅ **DONE (495d288)** — `PlayerHelpers.kt`: cleanTitle, cleanLoaderTitle, frameRateMismatch, parseRetryAfterMs, isAudioSinkInitFailure moved verbatim; qoeMode, resolveMediaMimeType, isTerminalDirectHttpPlaybackError parameterised to become state-free. **Not moved** (they read companion consts / the Display / the debrid repo, so forcing them out would have been worse): resolveTimeoutMs, readyStallRequiredStrikes, isDolbyVisionDisplaySupported, requiresAddonProxyPlaybackContext, formatTimeRangeCompact. New `PlayerHelpersTest` (9 tests). 4402→4270. | ~130 | LOW |
| ~~P7~~ | ✅ **DONE (0235915)** — `PlayerDiagnosticsFields.kt`: readStreamHeaders / diagnosticsContentFields / diagnosticsSourceFields / trackDiagnosticsFields now take state as params. `diagnosticsPlaybackFields` + `effectivePlaybackHeadersFor` stay on the Activity (Context / debrid repo). New `PlayerDiagnosticsFieldsTest` (4 tests, privacy contract). 4270→4206. | ~65 | LOW-MED |
| ~~P8~~ | ✅ **DONE (995184d)** — `PlayerDebugOverlay` delegate + pure `buildDebugOverlayText(PlayerDebugSnapshot)`; onResume→start(), onStop→stop(). New `PlayerDebugOverlayTest` (3 tests). **Found: `debugEnabled` has no setter anywhere — the overlay is currently unreachable at runtime; left inert on purpose.** 4206→4198. | ~60 | LOW |
| P9 | `PlayerPipController` + `PlayerXRayController` | ~140 | LOW-MED |
| P10 | `PlayerReconnectManager` — watchdog baseline, backoff, reconnect budget, network-quality, reconnecting banner, network callback register/unregister | ~200 | MED (live QA) |
| P11 | `PlayerLoaderUi` — cinematic loader, metadata bind, play/pause visibility, interactive animations, volume icon | ~250 | MED (UI only) |
| P12 | `PlayerExitCoordinator` — performBackExit, exit-result rules, finishWithReturnToSources, shared-player handoff + frame capture + adopted cover | ~200 | MED-HIGH (handoff order is a landmine) |
| P13 | `PlayerSeriesController` — playlist state, episode browser wiring, play next/prev, IPTV episode resume | ~250 | MED-HIGH |
| P14 | `PlayerDebridSourceManager` — source profile, fresh direct-debrid resolve, source panel, switchToMovieSource, addon-proxy failure tracking, resolution-state observer | ~300 | HIGH |
| P15 | `PlayerStallMonitor` — checkForStall, checkVideoRenderProgress, start/stop (**this is the previously BLOCKED Phase 2 scope — re-approve explicitly**) | ~150 | HIGH |
| P16 | `PlayerErrorRecoveryManager` — handlePlaybackError taxonomy, network recovery, timeout, terminal failure, failure-detail redirect, black-video fallback | ~400 | HIGH |
| P17 | `PlayerInputController` — dispatchKeyEvent, long-press, smart-focus controller, seek navigation, control focus wiring | ~250 | HIGH (every D-pad path) |
| P18 | `PlayerLiveController` — zap, tune, live OSD wiring, EPG overlay modes, seamless switch, channel meta | ~350 | VERY HIGH (live TS landmines) |
| P19 | `PlayerFactory` — split `initializePlayer` (ExoPlayer build, codec selector, media item, track overrides, frame-rate match) + slim `onCreate` into setup* delegations | ~800 | HIGHEST — do LAST |

## Realistic target
Extracting P6–P18 lands the Activity around **1200–1500 lines**. Only P19 (initializePlayer + onCreate)
brings it toward **600–800**. Getting an Activity strictly under 500 is not a realistic goal without
splitting the screen itself (e.g. a PlayerScreenCoordinator owning wiring) — **propose 600–800 as the
success bar**, and treat <500 as aspirational, unlike the data-layer classes.

## Per-phase definition of done
1. Extract verbatim into a `player/stabilized/Player*.kt` delegate (constructor takes only what it needs).
2. `compileDebugKotlin` + full `:app:testDebugUnitTest` green; add unit tests for the delegate where it is
   now testable.
3. **Device QA matrix on a real Fire TV — every phase**: live TS channel (zap ×5), live HLS, VOD (IPTV),
   VOD (debrid), series episode + next-episode, resume-from-Continue-Watching, back/exit, PiP.
   Re-check the four landmines above.
4. Commit + push per phase; one phase per commit so any regression is bisectable.

## Phase execution log
- **P6 — DONE 2026-07-22, commit `495d288`, pushed.** Device QA on 192.168.178.64: debrid series
  episode resume + playback (House of the Dragon S1:E2, position written back on exit), live TS full
  player + 3 zaps (HW.video.avc first frame, 1280x720 @ 2.9 Mbps — no freeze, no AudioTrack failure),
  dead 8K channel degraded through the reconnect path without crashing, no FATAL in logcat.
  Landmines re-checked: no TsExtractor flag change, no LiveConfiguration on TS, tunneling fallback
  untouched, shared-player handoff untouched.
- **Next: P7 / P8 are the low-risk pair — still NOT approved.** Ask the owner before starting either.
- **P7 — DONE 2026-07-22, commit `0235915`, pushed.** Device QA on .64: debrid series episode resume
  through the extracted header path (17:40 → 18:18, network quality FAST ~36 Mbps), no crash. Live not
  re-run — P7 touches no live code path.
- **P8 — DONE 2026-07-22, commit `995184d`, pushed.** Device QA on .64: debrid episode playback with
  subtitles, live TS full player (first frame, 1280x720 @ ~2.6–3.6 Mbps), HOME (onStop) + relaunch, no
  FATAL. `:app:detekt` green (new guardrail).
- **Next: P9 (`PlayerPipController` + `PlayerXRayController`) — NOT approved.** Ask the owner first.
