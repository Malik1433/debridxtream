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
| ~~P9~~ | ✅ **DONE (e76fad3)** — `PlayerXRayController` (toggle + animation + metadata/cast rendering + CastAdapter/CastViewHolder), `PlayerPipController` (capability + params + enter only). `hideUiForPiP`/`showUiForNormalMode` stay on the Activity by design — they orchestrate 8 screen collaborators. 4198→4108. | ~90 | LOW-MED |
| ~~P10~~ | ✅ **DONE (e5e3ecc)** — `PlayerReconnectManager`: the rolling 4-per-60s budget, the delayed "RECONNECTING… (n/4)" banner (via a `ReconnectBannerHost` the Activity implements) and `maybeUpdateNetworkQuality`. **Recovery itself stayed put** — watchdog baseline is read inside `handleTimeout`, network callbacks call `attemptNetworkRecovery`, and Phase 2 is still BLOCKED. New `PlayerReconnectBudgetTest` (4 tests). 4108→3987. | ~120 | MED |
| ~~P11~~ | ✅ **DONE (e9bcf78)** — split into TWO files by responsibility: `PlayerLoaderUi` (cinematic overlay, its 4 views + both animators, `release()` replaces the onDestroy animator pokes) and `PlayerControlChrome` (updatePlayPauseVisibility / setupInteractiveAnimations / updateVolumeIcon, state-free). 3987→3854. | ~220 | MED |
| ~~P12~~ | ✅ **DONE (66363a5)** — split: `PlayerHandoffFrames` (captureVideoFrame + showAdoptedCoverFrame) and `PlayerExitResults` (3 pure result-Intent builders + `LiveReturnChannel`). **`performBackExit` / `handBackSharedPlayerIfNeeded` stayed** — they sequence the capture→hand-back→finish landmine and drive player/listeners/stall/history; a coordinator would need ~10 callbacks. New `PlayerExitResultsTest` (4 tests). 3854→3793. | ~60 | MED-HIGH |
| ~~P13~~ | ✅ **DONE (dc1eb20)** — `PlayerSeriesBrowsing`: the browser view decision (was inlined twice), the season-title fallback (three places), the **playlist-hijack guard** (`shouldPlaylistDrivePlayback` — the "select NL/DE → same file" fix, now named + tested) and two small helpers. Episode SWITCHING stayed (it drives history/debrid-resolve/initializePlayer). New `PlayerSeriesBrowsingTest` (6 tests). 3793→3787 — this one was de-duplication, not line count. | ~60 | MED-HIGH |
| ~~P14~~ | ✅ **DONE (455d7b3)** — `PlayerDebridSourceState`: `AddonProxyFailureTracker` (replaces 3 loose last-context fields), pure `addonProxyContextKey`, `canFreshResolveDirectDebrid(+DebridLookupIds)`, `debridSeriesLookupId`. Resolution orchestration + the 9-field source profile stayed (collapsing those fields into one identity object is a behaviour-shaped change). New `PlayerDebridSourceStateTest` (8 tests). 3787→3775. | ~70 | HIGH |
| ~~P15~~ | ✅ **DONE (245f116)** — `PlayerStallDetector` (PROGRESSING/IDLE/WARNING/STALLED) + `VideoFreezeDetector`. **Detection only — every reaction stayed** (bounded tunneling-off, live re-prepare, handlePlaybackError). Five loose fields gone. New `PlayerStallDetectorTest` (10 tests). 3784→3773. | ~150 | HIGH |
| ~~P16~~ | ✅ **DONE (d6c1f59)** — `PlayerErrorClassification`: `isTerminalHttpForNonLive` (the dead-listing fail-fast fix), `isRateLimitCoolOff`, `maxRetriesFor`. **handlePlaybackError keeps every reaction.** New `PlayerErrorClassificationTest` (7 tests). 3773→3766. | ~400 | HIGH |
| ~~P17~~ | ✅ **DONE (ade3a27)** — `PlayerKeyRouting`: `canZapNow` + `OpenPlayerSurfaces` (the live-zap guard was copy-pasted across FOUR branches — now one, keyed by `zapDirectionFor`), `isControllerTriggerKey`, `vodBackAction`. dispatchKeyEvent itself stayed. New `PlayerKeyRoutingTest` (7 tests). 3775→3771 — the win is four duplicated guards collapsing into one. | ~60 | HIGH |
| ~~P18~~ | ✅ **DONE (de0303e)** — scoped to the two self-contained pieces: `PlayerZapDebouncer` (LP-B-1 — one connect for the LAST target) and `PlayerEpgOverlay` (legacy strip: mode/pin/auto-hide + `isEpgOverlayPinnedUp`, which was written out 3×). **tuneToZapChannel's ordering, performSeamlessSwitch, TsExtractor flags, LiveConfiguration, AudioTrack recovery and the shared handoff were NOT touched** — all four landmines are outside the diff. New `PlayerEpgOverlayTest` (8 tests). 3771→3784. | ~90 | VERY HIGH |
| P19 | `PlayerFactory` — split `initializePlayer` (ExoPlayer build, codec selector, media item, track overrides, frame-rate match) + slim `onCreate` into setup* delegations | ~800 | HIGHEST — do LAST |

## Realistic target
**Target: under 600 lines** — the detekt `LargeClass` threshold, so the file actually CLEARS the
guardrail instead of staying grandfathered in the baseline. Extracting P6-P18 lands the Activity around
1200-1500; P19 is what closes the gap, and to reach <600 it must split `initializePlayer` + `onCreate`
across **two** delegates (e.g. `PlayerFactory` for the ExoPlayer build and a `PlayerScreenCoordinator`
for onCreate wiring), not one. <500 is not a realistic goal for an Activity and is not the bar here.

## Per-phase definition of done
1. Extract verbatim into a `player/stabilized/Player*.kt` delegate (constructor takes only what it needs).
2. `compileDebugKotlin` + full `:app:testDebugUnitTest` green; add unit tests for the delegate where it is
   now testable.
3. **Device QA matrix on a real Fire TV — every phase**: live TS channel (zap ×5), live HLS, VOD (IPTV),
   VOD (debrid), series episode + next-episode, resume-from-Continue-Watching, back/exit, PiP.
   Re-check the four landmines above.
4. Commit + push per phase; one phase per commit so any regression is bisectable.
5. **Refresh the detekt baseline (see below) and confirm its total count did NOT grow.**

## Interaction with the detekt guardrail (added 2026-07-22)

detekt baseline IDs are keyed `Rule:FileName.kt$Class$signature`. Moving an already-baselined method
into a new delegate therefore changes its ID, and detekt reports it as a **new** violation even though
the code is byte-identical. Phases P11 and P13-P19 each move at least one baselined method, so this
will fire — it is expected, not a regression:

| Phase | Baselined methods it relocates |
|---|---|
| P11 | bindModernMetadata |
| P13 | observeSeriesPlaylistState, playNextEpisode, showEpisodeBrowser |
| P14 | observeDebridResolutionState, refreshDirectDebridSourceFromMetadata, switchToMovieSource |
| P15 | checkVideoRenderProgress |
| P16 | handlePlaybackError, handleTimeout |
| P17 | dispatchKeyEvent |
| P18 | renderOverlay |
| P19 | initializePlayer, onCreate |

**Correct handling — do NOT "fix" the long method while moving it.** Splitting a 400-line playback
method in the same commit as relocating it destroys the verbatim/zero-behaviour-change property that
makes these phases safe to ship. Instead:

1. Move verbatim; let detekt fail.
2. `./gradlew :app:detektBaseline` to re-key the moved entries.
3. **Verify the total did not grow:** `grep -c "<ID>" config/detekt/detekt-baseline.xml` must be **<=**
   the pre-phase count (it was **300** at setup). Same count = a pure relocation. Higher = you added new
   debt: investigate before committing.
4. Commit the refreshed baseline **with** the phase, so the two always move together.

Reducing those long methods is worthwhile, but as its own separate, separately-QA'd commit *after* the
relocation has shipped and been device-verified.

## Note on the line target vs the guardrail
Target is **<600** (detekt `LargeClass`). Owner decision 2026-07-22: match the guardrail exactly rather
than settle for a grandfathered entry — so P19 splits `initializePlayer` + `onCreate` across two
delegates. When PlayerActivity drops under 600, remove its `LargeClass` entry from the baseline.

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
- **P9 — DONE 2026-07-22, commit `e76fad3`, pushed.** Device QA on .64: debrid episode playback +
  subtitles, BACK exit through the rewritten X-Ray-visibility path, live TS full player (first frame,
  ~2.8–3.1 Mbps), no FATAL; `:app:detekt` green. **Two honest gaps:** the X-Ray toggle sits inside a
  `visibility="gone"` FrameLayout in `custom_player_control_view.xml`, so the panel is unreachable
  through the UI (before and after this change); and PiP long-press ran without crashing but the system
  did not visibly enter PiP — params/API are verbatim, so treat PiP as *unverified*, not regressed.
- **Two dormant features found while decomposing** (both pre-existing, owner's call whether to wire up or
  delete): `debugEnabled` has no setter (P8), and the X-Ray toggle is in a gone container (P9).
- **Next: P10 (`PlayerReconnectManager`, ~200 lines, MED — needs real live QA) — NOT approved.**
- **P10 — DONE 2026-07-22, commit `e5e3ecc`, pushed.** Device QA on .64: a dead |8k| channel drove the
  real path — logcat "Reconnect attempt 1/4" → "2/4" with the "RECONNECTING… (1/4)" pill rendered on
  screen, then a clean exit; a healthy live TS channel still plays afterwards (first frame, ~2.8–3.4
  Mbps); debrid episode playback unaffected; no FATAL. Detekt flagged two issues in the new file
  (LongParameterList, ImplicitDefaultLocale) — **fixed, not baselined**.
- **Next: P11 (`PlayerLoaderUi`, ~250 lines, MED, UI only) — NOT approved.**
- **P11 — DONE 2026-07-22, commit `e9bcf78`, pushed.** Device QA on .64: loader renders identically
  (backdrop + cleaned title + sweeping segment, screenshot), hides on first frame, pause icon and the
  focus ring/scale animation still correct, live TS channel plays afterwards (~2.5–3.7 Mbps), no FATAL.
  No unit tests — view/animator code; device QA is the real check.
- **Next: P12 (`PlayerExitCoordinator`, ~200 lines, MED-HIGH — the shared-player handoff order is a
  landmine) — NOT approved.**
- **P12 — DONE 2026-07-22, commit `66363a5`, pushed.** Device QA on .64 covered the landmine end to end:
  guide preview → Watch → fullscreen adopts the shared player and renders → BACK → the mini preview is
  still playing the same channel with its EPG intact, no black frame, no crash. Return-to-sources intents
  are covered by unit tests instead (they need a real source failure to trigger on device). Detekt
  flagged LongParameterList + SwallowedException on the new files — **fixed, not baselined**.
- **Next: P13 (`PlayerSeriesController`, ~250 lines, MED-HIGH) — NOT approved.**
- **P13 — DONE 2026-07-22, commit `dc1eb20`, pushed.** Device QA on .64: debrid series playback drove the
  real playlist path — UP NEXT card with the correct next episode and thumbnail, countdown auto-advanced
  to S1:E3, played in 4K, no FATAL. The browser PANEL itself was not visually opened this pass (D-pad kept
  landing on the seek bar); its rendering is unit-tested and the UP NEXT card proves the data path.
- **Next: P14 (`PlayerDebridSourceManager`, ~300 lines, HIGH) — NOT approved.**
- **P14 — DONE 2026-07-22, commit `455d7b3`, pushed.** Device QA on .64: debrid series episode plays
  through the rewritten direct-debrid path (S1:E3 with subtitles), no FATAL. The repeated-failure
  branches need a flaky proxy to reproduce, so they are unit-tested rather than claimed device-verified.
- **Remaining: P15 (stall monitor — the previously BLOCKED Phase 2 scope, needs explicit re-approval),
  P16 (error recovery), P17 (input), P18 (live), P19 (initializePlayer/onCreate). None approved.**
- **P17 — DONE 2026-07-22, commit `ade3a27`, pushed.** Device QA on .64: single DPAD_UP zapped once; a
  LONG-PRESS DPAD_DOWN produced exactly ONE "Performing seamless switch" (auto-repeat filter holds),
  playback healthy after; VOD two-step BACK verified (OK → controller, BACK → hide, BACK → exit; an extra
  BACK was consumed by the Up Next prompt, its own earlier branch). No FATAL.
- **Phase order note:** P15 and P16 are the recovery/stall scope that §8 BLOCKED — they need explicit
  owner re-approval, so P17 was taken first. Remaining: **P15, P16, P18 (live, VERY HIGH), P19 (last)**.
- **P18 — DONE 2026-07-22, commit `de0303e`, pushed.** Device QA on .64: guide → Watch → fullscreen adopts
  and renders (~2.5–3.2 Mbps); LONG-PRESS DPAD_DOWN produced exactly ONE "Performing seamless switch"
  (debounce survives the extraction); playback healthy after; BACK handed the player back and the guide's
  mini preview kept playing with EPG intact; no FATAL. The legacy EPG strip only runs when the cinematic
  Live OSD is absent (it is active on this device) — unit-tested rather than device-claimed.
- **Remaining: P15 + P16 (the BLOCKED recovery/stall scope — explicit re-approval required) and P19
  (`initializePlayer` + `onCreate`, ~800 lines, do LAST).**

## §8 UNBLOCKED — 2026-07-22
The owner explicitly re-approved the stall/recovery scope that §8 had BLOCKED since
2026-05. Both phases were still scoped to **decision logic only**; every reaction that
touches the four landmines stayed in `PlayerActivity`.
- **P15 — DONE, commit `245f116`, pushed.** Device QA on .64: debrid episode played and
  auto-advanced (871 sources resolved); live TS at ~2.7–3.3 Mbps; the freeze detector fired for real
  and the Activity's bounded tunneling-off recovery ran, exactly as this device behaved before; no false
  stall during a long buffering stretch on a weak 2.4 GHz link (a buffering player is IDLE, not stalled).
- **P16 — DONE, commit `d6c1f59`, pushed.** Device QA on .64: dead |8k| channel → "Reconnect attempt
  1/4" → "2/4" → clean exit (unchanged); healthy channel then played at ~2.7–3.3 Mbps. The 429 cool-off
  branch needs a rate-limiting provider, so it is unit-tested only.
- **Only P19 remains** (`initializePlayer` ~430 + `onCreate` ~380) — NOT approved, and it should stay last.

---

# Route to ≤600 lines (Option B, owner-chosen 2026-07-22)

Measured reality after P6–P18 + P19a: **PlayerActivity 3665 lines**, 122 methods.
The mass is not in the logic that has been extracted so far — it is in a handful of giants
plus a long tail of wiring:

| Block | Lines |
|---|---|
| `onCreate` | 378 |
| `handlePlaybackError` (reactions) | 179 |
| `dispatchKeyEvent` (routing shell) | 133 |
| `handleTimeout` | 94 |
| `initBrowserOverlay` | 77 |
| listener objects (`playerListener` etc.) | ~200 |
| observers / `setupLiveOsd` / `switchToMovieSource` / `showEpisodeBrowser` / metadata bind / `buildMediaItem` | ~450 |
| field declarations + companion + imports | ~350 |

## Staged plan (each step: verbatim move, one commit, device QA, detekt, push)
| Step | Extract | Est. after |
|---|---|---|
| ~~P19a~~ ✅ `db99bec` | `PlayerEngineFactory` — ExoPlayer construction | 3665 |
| P19b | `PlayerLaunchArgs` (intent parsing) + `bindViews()` + `setup*()` — slim `onCreate` | ~3350 |
| P19c | `PlayerEventListener` over a `PlayerEventHost` interface — the listener objects | ~3150 |
| P20 | `PlayerRecoveryCoordinator` — handlePlaybackError reactions + handleTimeout (classification already extracted in P16) | ~2850 |
| P21 | `PlayerInputRouter` — the dispatchKeyEvent shell (rules already extracted in P17) | ~2700 |
| P22 | `PlayerLiveTuner` — tuneToZapChannel + performSeamlessSwitch + live OSD setup | ~2450 |
| P23 | `PlayerDebridCoordinator` — source panel, switchToMovieSource, resolution observers | ~2200 |
| P24 | `PlayerViewModelBinder` — every lifecycleScope collector | ~1950 |
| P25 | `PlayerMetadataBinder` + browser overlay wiring | ~1700 |
| P26 | field/companion sweep: constants and state move to the collaborator that owns them | ~1400 |

**Honest end state: ~1400 lines, not 600.** Getting the Activity itself to ≤600 needs the
screen split (a `PlayerScreenCoordinator` owning the delegates, or separate Live/VOD player
screens) — that is a structural change with a real regression surface, and it needs its own
plan + approval. Every file CREATED by the steps above stays ≤600 by design.

## Session-state route progress (2026-07-22, evening)
- **P19b — DONE `6e3f4ff`**: `PlayerTransportButtons` (controller buttons + volume via `TransportActions`). 3665→3584.
- **P19c — DONE `dd611fe`**: `PlayerChannelBrowser` (+`ChannelBrowserHost`; tuning stays in the Activity). 3584→3539.
- **S1 — DONE `0258e7e`**: `PlayerSessionState` — all ~63 mutable screen vars in one object; the Activity
  declares each as `by session::field`, so every call site (Activity + collaborators) compiled unchanged.
  This is the enabler: new controllers take the session directly, no more 20-callback host interfaces.

### C1 `PlayerRecoveryController` — DONE (3539→3244, controller 453 lines)
Moved verbatim: `handlePlaybackError` (all reactions), `handleTimeout`, `attemptNetworkRecovery`,
`handleInitializationError`, `retryBackoffDelayMs`, the network callback register/unregister +
`connectivityManager`/`networkCallback` (fully owned now). Constructor `(activity, session, host,
timeoutHandler, retryHandler, timeoutRunnable)`.

Design decisions that departed from the sketch above (all to keep the move truly verbatim):
- **The two Handlers + `timeoutRunnable` stay owned by the Activity**, not the controller — they are
  armed/cancelled from ~30 non-recovery sites (initializePlayer, the lifecycle callbacks, releasePlayer).
  The controller holds the SAME instances (passed in), so both sides post/cancel the same work and every
  one of those 30 sites compiled unchanged. Only `timeoutRunnable`'s body flipped to `recovery.handleTimeout()`.
- **Session state via delegated props on the controller** (`by session::field`, same idiom as the Activity)
  + a `player` bridge property (get/set → host) → the moved bodies are byte-identical; only 4 mechanical
  edits: consts → `PlayerActivity.X` (bumped 6 to `internal`), `record(this,`→`record(activity,`,
  `viewModel.reResolveDebridUrl`/`settingsPreferences.isAutoReconnectEnabled()` → host forwarders.
- **`RecoveryHost` kept the Activity's real method NAMES** (so review is 1:1); existing methods just gained
  `override` (private→public). New tiny overrides: `peekPlayer`/`assignPlayer`/`requestSeamlessSwitch`/
  `diagnosticsFields`/`isAutoReconnectEnabled`/`reResolveDebridUrl`/`maxRetriesConfigured`.
- detekt: no NEW violations; the 7 controller flags are the SAME giant-method exemptions relocated from
  the Activity (baseline regenerated 355→**349**, i.e. shrank — verified none genuinely new).
- **Device QA on .64 (commit `396c8f5`)**: debrid resume — CW item launched via `RESUME_PATH: DIRECT`,
  first frame rendered, progress advanced (1:25:19→1:26:16) so the resume position was honoured and saved;
  live TS zap — Sky Sports HD (XTREAM) fullscreen, four channel-up zaps each logged `Performing seamless
  switch` + a fresh `First Frame Render` with no freeze/error; clean BACK exits from both; zero FATAL all
  session. The recovery error branches (handlePlaybackError/handleTimeout) are dormant in healthy
  playback — their wiring (onStart→registerNetworkCallback, shared timeout/retry handlers, seamless-switch
  forwarding) is exercised and green; the 429/terminal branches remain unit-tested (need a failing source).

### C2 `PlayerLiveTuner` — DONE (3244→3062, tuner 277 lines)
Moved verbatim: `zapChannel` (per-keypress OSD + debounce), `tuneToZapChannel` (the single tune commit),
`tuneToZapIndex`, `toggleLiveFavorite`, `setupLiveOsd` (Live OSD + its 4 lifecycle collectors) and
`performSeamlessSwitch` (the gold-standard atomic stop→clear→prepare→seek→play).

Design:
- **Held the concrete `PlayerActivity`, not an interface.** This cluster is wired to the Activity's views
  (`playerView`, `findViewById`), `viewModel` (~12 calls), `prefs`, `epgOverlayUi`, `zapDebouncer`,
  `qoeTracker` and lifecycle — a narrow host interface would have needed ~25 methods and bought nothing.
  16 Activity members bumped `private`→`internal`; the tuner reads them through get-only forwarders +
  session delegates + a `player`/`liveOsd` bridge, so the bodies stayed byte-identical (only `findViewById`
  and the 4 `repeatOnLifecycle` calls took an `activity.` prefix — no LifecycleOwner receiver in the tuner).
- **Kept on the Activity (shared, and the LiveConfiguration landmine lives here):** `buildMediaItem`,
  `resolveTimeoutMs`, `armWatchdogBaseline`, `startStallMonitor`, `bindChannelMeta`, `showEpgOverlay`,
  `liveOsd` field. The timeout Handler + `timeoutRunnable` stay Activity-owned (same as C1) and are passed in.
- `performSeamlessSwitch`'s 8 callers (channel browser, C1 recovery via `requestSeamlessSwitch`, ENDED /
  freeze reconnect, the resume collector) now call `liveTuner.performSeamlessSwitch`.
- detekt: clean, no baseline change (none of the moved methods trip a threshold). compile + full tests green.
- **Device QA .64 (commit `b84826b`):** live TS zap — Sky Sports Cricket HD (XTREAM) fullscreen, four zaps each
  logged `Performing seamless switch` + a fresh `First Frame Render`; shared handoff — BACK returned to the
  guide with the mini preview still playing live (scoreboard advanced 106→110 runs, not frozen/black); zero
  FATAL. The four live landmines (TS flags, TS LiveConfiguration, first-frame AudioTrack, shared handoff) all
  held.

### C3 `PlayerDebridCoordinator` — DONE (3062→2946, coordinator 189 lines)
Moved verbatim: `openMovieSourcePanel` (the in-player "other sources" bottom sheet for a movie),
`switchToMovieSource` (swap to a picked source IN-PLACE — save position, re-init IPTV/direct-http/resolver-
backed) and `observeDebridResolutionState` (the single collector that turns a `DebridResolutionState` into
play / seamless-switch / terminal).

Design:
- Same concrete-`PlayerActivity` pattern as C2. **The debrid identity fields are all in `PlayerSessionState`
  now (S1)** — which is exactly why `applyDebridSourceProfile`/`currentDebridSourceProfile` (flagged in P14 as
  un-extractable while they were 9 loose `var`s) are now trivially session-backed. `applyDebridSourceProfile`
  is called through the Activity; `currentDebridSourceProfile` **stays** on the Activity (6 non-C3 callers).
- One helper added on the Activity — `resetNextEpisodeStateIfInitialized()` — because the moved observer's
  `::nextEpisodeManager.isInitialized` lateinit check can't be expressed from outside the class. 4 members
  bumped `private`→`internal` (`loaderUi`, `bindModernMetadata`, `showCinematicLoader`, `applyDebridSourceProfile`).
- detekt: the 3 flags (`switchToMovieSource` Cyclomatic, the creds `ComplexCondition`, `bindModernMetadata`
  re-keyed by private→internal) are all pre-existing exemptions relocated/re-keyed; baseline regenerated
  349→**348** (shrank). compile + full tests green.
- **Device QA .64 (commit `558da02`):** a debrid CW movie played cleanly (First Frame Render), so the coordinator
  is constructed at player setup and its resolution collector is wired with no crash. The Sources bottom sheet
  itself couldn't be driven blind (the fullscreen player is a secure surface — screencap is white, same as the
  Live case); `switchToMovieSource` is verbatim + compile/test-covered.

### C4 `PlayerSeriesController` — DONE (2946→2686, controller 345 lines)
Moved verbatim: `observeSeriesPlaylistState` + `setupEpisodeBrowser` + `showEpisodeBrowser` +
`onEpisodeSelected` (the episode-browser overlay + the seriesPlaylist collector, incl. the P13 playlist-
hijack guard `shouldPlaylistDrivePlayback`), plus the switch commit points `playSeriesEpisode` /
`playNextEpisode` / `playPreviousEpisode` and `resolveIptvEpisodeResumeMs` (the IPTV resume lookup with the
latest-wins `episodeSwitchRequestId` guard).

Design:
- Same concrete-`PlayerActivity` pattern. **Kept on the Activity** the thin cross-cutting wrappers that have
  non-series callers: `debridSeriesLookupId` (3 ext callers incl. the SeriesDetail intent + C1's
  `refreshDirectDebridSourceFromMetadata`), `currentDebridSourceProfile` (6 callers),
  `currentIptvEpisodeIdForPlaylist`, `isSeriesEpisodePlayback`, `bindModernMetadata` (C6). The controller
  reaches them through `activity`.
- `episodeBrowserController` (read from dispatchKeyEvent + PiP, i.e. C6 territory) stays on the Activity and is
  driven through a get/set bridge; one more Activity helper added (`hideNextEpisodePromptIfInitialized`) for the
  lateinit-guarded `nextEpisodeManager.hidePrompt()`.
- detekt: the 2 flags (`playNextEpisode` Cyclomatic, `showEpisodeBrowser` NestedBlockDepth) are pre-existing
  exemptions relocated; baseline regenerated 348→**348** (net 0). compile + full tests green.
- **Device QA .64 (commit `<c4>`):** a debrid series episode (Elite Force S1E1) resumed and played cleanly
  (RESUME_PATH DIRECT type=EPISODE → Stremio scoped-resume 9 streams → First Frame Render), so the series
  controller + its playlist collector wire up at setup with no crash. Next-episode / episode-browser switching
  is verbatim + compile/test-covered but not blind-drivable on the secure player surface (same as P13's note).

Then C5 UI binder, C6 input shell, F1 sweep → ~600.
