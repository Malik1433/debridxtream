# Live Playback Audit — Master Plan (Diagnose Only)

**Date:** 2026-07-12
**Mode:** Diagnose Only — **zero code changes made**. This is a mapping/planning document.
**Scope:** The **Live TV playback pipeline only** (not VOD, not UI-only). Target quality bar: TiviMate-level stability.
**Engine:** androidx.media3 **1.5.0** (exoplayer, -hls, -ui, -common, -datasource-okhttp, -extractor) + jellyfin `media3-ffmpeg-decoder:1.5.0+1`.
**Method:** 4 parallel read-only audit clusters (A Engine/Buffer/ABR/Protocols · B Error/Resilience/Zapping · C Resume/Threading/Lifecycle/PiP · D Input/CC/Crash-reporting/Security/QoE/Fragmentation), each verified against media3 best practices and this repo's own documented Live constraints. Findings cross-deduplicated below.
**ID convention:** `LP-<cluster>-<n>` (e.g. `LP-B-1`). Severity `P0`–`P4`. For each **P0/P1** a one-line fix approach is given (diagnosis only — not implemented).

**Files audited:** `player/stabilized/PlayerActivity.kt` (3786 ln), `PlayerViewModel.kt`, `PlayerBufferConfigFactory.kt`, `LiveSharedPlayer.kt`, `PlaybackSource.kt`, `PlayerHistoryManager.kt`, `PlayerTrackManager.kt`, `LivePlayerOsdManager.kt`, `LiveSurfChannelAdapter.kt`/`LiveSurfCategoryAdapter.kt`/`LiveOnAirAdapter.kt`, `ui/live/PreviewPlayerPanel.kt`, `ui/live/guide/LiveTvGuideFragment.kt`/`LiveTvGuideViewModel.kt`, `data/prefs/WatchHistoryPreferences.kt`/`SettingsPreferences.kt`, `di/AppModule.kt`, `util/GlobalCrashHandler.kt`, `debug/PlaybackDiagnosticsRecorder.kt`, `app/build.gradle`.

---

## 0. Severity summary

| Severity | Count | IDs |
|---|---|---|
| **P0** | 0 | — (no release-build credential leak; no guaranteed crash/data-loss found on the live path) |
| **P1** | 1 | LP-B-1 |
| **P2** | 6 | **LP-ADOPT** (=LP-A-1 + LP-B-2), LP-C-1, LP-D-1, LP-D-2, LP-D-3 |
| **P3** | 10 | LP-A-2, LP-A-3, LP-B-3, LP-B-4, LP-B-5, LP-C-2, LP-C-3, LP-D-4, LP-D-5, LP-D-7 |
| **P4** | 5 | LP-A-4, LP-A-5, LP-B-6, LP-C-4, LP-D-6 |

> **Do-not-touch (protected, verified CORRECT):** the raw-TS `LiveConfiguration` restriction to M3U8/MPD only (`PlayerActivity.kt:1164-1177`) and the deliberate *non*-setting of `TsExtractor.FLAG_ALLOW_NON_IDR_KEYFRAMES`/`FLAG_DETECT_ACCESS_UNITS` (`:2153-2155`). Both directly guard the device-verified all-`.ts`-channels freeze/seek-loop regressions. **No fix in this plan may re-introduce either.** See memory [[ts-extractor-flags-freeze-regression]], [[liveconfiguration-ts-freeze-postmortem]].

---

## 1. P0 — None

No P0 found. Explicitly verified clean:
- **No credential leak on the live playback path.** The seamless-switch log redacts via `SensitiveLogRedactor.describeUrl` (`PlayerActivity.kt:1121`); a targeted grep for raw `$url/$streamUrl/$newUrl/$currentUrl/$token/$headers` inside `Log.*` across the whole `player/` package returned **no matches**; diagnostics store only header *presence* booleans, never values. (Two P3 residual leak *vectors* exist only in DEBUG builds / uncaught-crash path — LP-D-4, LP-D-5.)
- No guaranteed crash or data-loss path in live init/zap/teardown (init is try/caught; teardown is leak-safe).

---

## 2. P1 — Fix first

### LP-B-1 — Channel zapping has no debounce and no key-autorepeat filter; the reconnect budget is reset per zap so nothing throttles the storm
- **Evidence:** `PlayerActivity.kt:3338-3342` maps `KEYCODE_CHANNEL_UP/DOWN`, `PAGE_UP/DOWN`, `DPAD_UP/DOWN` straight to `zapChannel(...)` inside `ACTION_DOWN` (`:3285`) with **no `event.repeatCount == 0` guard**. `zapChannel`→`tuneToZapChannel` (`:983`) calls `resetReconnectBudget()` (`:990`) + `retryCount = 0` (`:992`) on every zap, then `performSeamlessSwitch` unconditionally runs `stop(); clearMediaItems(); setMediaItem(); prepare()` (`:1131-1142`). No `isSwitching`/time debounce at entry.
- **Impact (LIVE):** Holding CHANNEL/DPAD-up (autorepeat) or spamming zap fires a burst of socket close/open cycles at the provider. Because each zap wipes `reconnectAttemptTimestamps`, the `MAX_RECONNECTS_PER_WINDOW=4/60s` WAF-protection budget **never engages for user zaps** — the exact path to a 429 / IP throttle / "channels won't load" session on the `max_connections=1` WAF Xtream servers this app targets. TiviMate debounces the actual tune ~300–500 ms after the last press; this does not.
- **Fix approach:** On a zap key, update index/OSD immediately but `postDelayed` the `performSeamlessSwitch` ~350 ms, cancelling any prior pending switch; ignore `event.repeatCount > 0` for the zap keycodes. (Wire this together with LP-B-3 so the captured `reqId` makes the latest-wins guard live.)
- **Protected → QA:** [TOUCHES PROTECTED] Live zapping (`zapRequestId` latest-wins) → mandatory hold-to-surf + rapid-spam zap regression on device, plus confirm single-channel provider does not 429.

---

## 3. P2 — High-impact reliability / observability

### LP-ADOPT — Warm-adopted guide-preview player bypasses the entire tuned live engine *and* its error policy (consolidates LP-A-1 + LP-B-2)
Two clusters found this independently from different angles; same root cause, two impact facets.
- **Evidence:** `ui/live/PreviewPlayerPanel.kt:112-122` builds the preview `ExoPlayer` with **only** `.setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource))` — no `LoadControl`, no `DefaultRenderersFactory`, no bandwidth meter, no track selector, no audio attributes, **no `setLoadErrorHandlingPolicy(playbackLoadErrorPolicy)`**. `LiveTvGuideFragment.kt:716` offers that instance to `LiveSharedPlayer`; `PlayerActivity.kt:2093-2107` adopts it and keeps it as `player` for the whole session (every zap reuses it via `performSeamlessSwitch`). The tuned engine (`PlayerActivity.kt:2116-2242`: low-RAM buffer caps + `setPrioritizeTimeOverSizeThresholds(false)`, `EXTENSION_RENDERER_MODE_ON` ffmpeg fallback, audio focus, `WAKE_MODE_NETWORK`, tunneling, SLOW-net 1080p cap) and the **429 Retry-After cool-off** (`playbackLoadErrorPolicy`, `:148-172`) are applied only on the *cold* init branch — reached only *after* a playback error forces a rebuild.
- **Impact (LIVE):** The most common entry into live (tap a channel in the guide) runs the whole session on media3-default `LoadControl` — defeating the low-RAM OOM guard on 1–1.5 GB Fire TV sticks — with **no ffmpeg fallback** (channels needing SW audio EAC3/DTS/AC4 play with *no audio* when adopted but fine when cold), no audio focus, and **no 429/WAF mitigation**. Warm vs cold live behaviour diverges in stability, quality, and audio.
- **Fix approach:** Build the preview player through the *same* factory used by `PlayerActivity` (shared `LoadControl` + `RenderersFactory` + `playbackLoadErrorPolicy` + audio config), OR on adopt, reconfigure the error policy and reject/rebuild if the incoming player wasn't live-tuned.
- **Protected → QA:** [TOUCHES PROTECTED] Live warm-zap hand-off + `max_connections=1` single-connection rule → guide→fullscreen on a SW-audio channel (verify audio), low-RAM OOM soak, 429 behaviour. See [[warm-zap-feature-status]].

### LP-C-1 — Entering PiP pauses live video; leaving releases the player (PiP has no continuity)
- **Evidence:** `onPause()` (`PlayerActivity.kt:2934`) calls `player?.pause()` and `onStop()` (`:2935`) calls `releasePlayer("on_stop")`, neither guarded on `isInPictureInPictureMode` (contrast the OSD code which *does* guard PiP at `:3241`, `:3444`).
- **Impact (LIVE):** On PiP-capable devices, live freezes the instant it enters PiP and the window dies on backgrounding — PiP effectively non-functional. **Mitigated on the primary target:** Fire TV doesn't report `FEATURE_PICTURE_IN_PICTURE`, so `supportsPictureInPicture()` (`:3427`) is false there; this bites PiP-capable Android TV / handheld builds only.
- **Fix approach (deferred-eligible):** Skip `player?.pause()` when `isInPictureInPictureMode`; keep the player alive across `onStop` while in PiP, releasing only in `onDestroy`. (Same lifecycle change would also give "instant on return" for live — see §7 note.)

### LP-D-1 — No remote crash/ANR reporting instrumented anywhere
- **Evidence:** Repo-wide grep `Crashlytics|Firebase|Sentry|bugsnag` → no crash SDK. `app/build.gradle:186-188` pulls `firebase-firestore-ktx` + `firebase-analytics-ktx` (Magic-Link login) but **not** `firebase-crashlytics`. Only crash path is the local `util/GlobalCrashHandler.kt` (counts + restarts). No ANR watchdog. `onPlayerError` records only to the debug-gated local `PlaybackDiagnosticsRecorder`.
- **Impact (LIVE):** Field crashes/ANRs on the live player (decoder-init, audio-sink faults, TS freezes) are invisible remotely — a device-/OS-specific regression at scale cannot be detected.
- **Fix approach:** Add a lightweight crash/ANR reporter in `Application`, tagging live-vs-VOD + media3 `errorCode`; keep GlobalCrashHandler's restart.

### LP-D-2 — Zero QoE / playback analytics (no media3 `AnalyticsListener`)
- **Evidence:** Repo-wide grep `AnalyticsListener|addAnalyticsListener|droppedFrames|onDroppedFrames` → **no matches**; `FirebaseAnalytics|logEvent` in `app/src/main/java` → **no files** (declared analytics dep is dead). No TTFF, rebuffer-ratio, bitrate-switch, or error-rate metric emitted.
- **Impact (LIVE):** The exact QoE signals needed for a TiviMate-level bar (time-to-first-frame on zap, rebuffer frequency per channel, error rate) cannot be measured.
- **Fix approach:** Attach an `AnalyticsListener` on the ExoPlayer at `PlayerActivity.kt:2220`, forwarding TTFF, `onDroppedVideoFrames`, `onBandwidthEstimate`, and player-error counts to a sink. (Note: also close the frame-drop blind spot from LP-A "surfaced".)

### LP-D-3 — Live surf drawer loses D-pad focus on EPG refresh (`notifyDataSetChanged`)
- **Evidence:** `LiveSurfChannelAdapter.submit(...)` calls `notifyDataSetChanged()` (`LiveSurfChannelAdapter.kt:47`) with no `setHasStableIds(true)`, and is re-invoked while the drawer is open and a row focused by `LivePlayerOsdManager.setSurfEpg` (`:368`), `setZapState` (`:547`), `setFavorites` (`:577`) — EPG snapshots update asynchronously during playback.
- **Impact (LIVE):** A background EPG snapshot rebinds every row and drops/resets D-pad focus (often to position 0) mid-navigation — unstable channel selection vs TiviMate's stable list focus.
- **Fix approach:** `setHasStableIds(true)` + `getItemId=streamId.hashCode` and move `submit` to DiffUtil so focus survives EPG refreshes.

---

## 4. P3 — Medium

| ID | Finding | Evidence | One-line direction |
|---|---|---|---|
| LP-A-2 | Bandwidth estimate reset every zap (ABR cold-start each channel) | `PlayerActivity.kt:2217` `DefaultBandwidthMeter.Builder(this).build()` per init instead of `getSingletonInstance` | Use the singleton meter so ABR carries the estimate across zaps/reconnects |
| LP-A-3 | Raw-TS live max buffer left at 60 s (HLS-only 30 s cap) | `PlayerBufferConfigFactory.kt:82,101-109` — 30 s cap gated to `isHls` branch only | Cap TS live `maxBufferMs` ~20–30 s (majority IPTV case; reduces drift + memory) |
| LP-B-3 | `currentZapRequestId` latest-wins guard is inert as wired | `PlayerActivity.kt:1112` check; only caller passing a real id (`:1016`) runs synchronously right after `++` (`:984-985`); all others pass `-1L` | Make the debounced switch (LP-B-1) capture `reqId` and compare at execution — activates this guard |
| LP-B-4 | `tuneToZapChannel` never zeroes `startPositionMs`; a leaked non-zero would `seekTo` raw TS (documented freeze trigger) | `:983-1017` resets retry/reconnect but not `startPositionMs`; `:1145-1146` seeks if `>0` | Set `startPositionMs = 0L` at top of `tuneToZapChannel` (live always starts at edge) |
| LP-B-5 | Zap category-switch coroutines have no cancellation — rapid category changes race | `PlayerViewModel.kt:1120,1230-1271` launch on IO, set `_zapState` on completion, no Job handle | Hold a single `zapLoadJob`, cancel before relaunch (last-*requested* wins) |
| LP-C-2 | Redundant main-thread history serialization on exit (Gson encode ×3–4 per exit) | `recordPlaybackHistoryIfNeeded` has no LIVE idempotency guard (`PlayerHistoryManager.kt:51-59`); called from `:3129`, `:2935`, `:3106`, `:40-42`; each does Gson encode/decode on UI thread (`WatchHistoryPreferences.kt:253-268`) | Add `hasRecordedLiveHistory` latch and/or move `addRecentLiveChannel` to `Dispatchers.IO` |
| LP-C-3 | Two state collectors without `repeatOnLifecycle` run while stopped | `PlayerActivity.kt:3206` (browserState), `:3559` (debridResolutionState) — raw `lifecycleScope.launch{collect{}}` vs correctly-scoped OSD collectors (`:1040-1067`) | Wrap both in `repeatOnLifecycle(STARTED)` |
| LP-D-4 | Xtream user/pass can appear verbatim in DEBUG diagnostics `pathShape` | `debug/PlaybackDiagnosticsRecorder.kt:261-277` `else -> segment` echoes short non-hex segments; live URL is `/user/pass/id.ts`. Gated on `BuildConfig.DEBUG` + opt-in marker (no release leak) | Redact every non-whitelisted path segment to `{seg}` |
| LP-D-5 | `GlobalCrashHandler` logs full throwable to `Log.e` (cred leak on crash) | `util/GlobalCrashHandler.kt:21` `Log.e(..., throwable.message, throwable)` — media3 HTTP exceptions embed the credentialed URL; `Log.e` not suppressed on device; only on genuinely uncaught crashes | Route message through `SensitiveLogRedactor`; log `describeException`, not the raw throwable |
| LP-D-7 | Live CC ignores system caption style (no `CaptioningManager`/`CaptionStyleCompat`) | CC *is* wired (`LivePlayerOsdManager.kt:874-904`, correct override/toggle) but grep `CaptioningManager\|CaptionStyleCompat\|SubtitleView` → no matches — user font/size/color/edge prefs never applied | Read `CaptioningManager`, apply `CaptionStyleCompat` + font scale to `playerView.subtitleView` (accessibility) |

---

## 5. P4 — Minor / polish

| ID | Finding | Evidence | Note |
|---|---|---|---|
| LP-A-4 | `targetLiveOffset` never set for HLS/DASH live | `PlayerActivity.kt:1171-1176` sets only min/max speed; no `setTargetOffsetMs` | Latency defaults to manifest offset (~15–30 s); tune only if latency is a goal |
| LP-A-5 | Dead `DefaultExtractorsFactory`/`TsExtractor` imports; no explicit LL-HLS opt-in | `PlayerActivity.kt:77-78` imports referenced only in comments; HLS uses `DefaultMediaSourceFactory` defaults | Remove dead imports; LL-HLS low priority (rare in IPTV) |
| LP-B-6 | Latent NPE in software-audio track fixup | `PlayerActivity.kt:2406` `... ?: player?.trackSelectionParameters!!` throws if `player` null when `onTracksChanged` fires | Guard with `val p = player ?: return` |
| LP-C-4 | Hand-off frame Bitmap can linger in `LiveSharedPlayer` singleton if adopted without `takeFrame()` | `LiveSharedPlayer.kt:63-75` `adopt()` clears player/url/id but not `frame`; only `takeFrame`/`release` clear it. Bounded (≤1 full-screen bitmap) | Defensively clear `frame = null` inside `adopt()` |
| LP-D-6 | `dispatchKeyEvent` logs every keypress ("LOG EVERY KEY FOR PROOF") | `PlayerActivity.kt:3229-3230` Log.d per key (suppressed on device) | Gate behind `BuildConfig.DEBUG` or remove |

---

## 6. Per-area coverage checklist (verifiable — every scoped area accounted for)

**Player Engine & Config** — LP-ADOPT (adopted-path bypass), LP-A-2 (meter), LP-A-3 (TS buffer). ✔ *Clean:* media3 1.5.0 current, no deprecated API; HLS LoadControl values sane (min 15 s/max ≤30 s/BFP 1–3 s/back-buffer 0); `DefaultTrackSelector`+`AdaptiveTrackSelection` with param-based (not fixed-track) caps; `setEnableDecoderFallback(true)`+`EXTENSION_RENDERER_MODE_ON` on cold path; SurfaceView correct.

**Stream Handling & Protocols** — LP-A-4 (targetLiveOffset), LP-A-5 (LL-HLS absent). ✔ *Clean/CORRECT:* `resolveMediaMimeType` type inference; **raw-TS `LiveConfiguration` guard** and **`TsExtractor` flag omission** verified correct (protected); DRM correctly absent for IPTV.

**Buffering & Rebuffering** — LP-A-3 (TS cap). ✔ *Clean:* stall detection = position-strike + render-progress freeze detection + ENDED-reconnect + `BEHIND_LIVE_WINDOW` re-prepare, all under one reconnect budget (no storm on the *error* path).

**Error Handling & Resilience** — LP-B-6 (NPE). ✔ *Clean:* `playbackLoadErrorPolicy` fail-fast on 401/403/404/410/416/451, 429 Retry-After cool-off, capped exponential else; transient-vs-fatal classified; network-callback recovery gated; single-URL live fallback → user-facing "stream broken at provider"; init try/caught. *(Caveat: this policy is absent on the warm-adopted path — LP-ADOPT.)*

**Channel Zapping / Source Switching** — **LP-B-1 (P1)**, LP-B-3, LP-B-4, LP-B-5. ✔ *Clean:* same-ExoPlayer reuse on zap (no per-zap leak); `releasePlayer` fully tears down; `max_connections=1` respected across hand-off (one connection only).

**Resume & State Management** — *(none)*. ✔ *Clean:* live resume snaps to live edge (`maybeSnapToLiveEdge`), never a stale byte position; "Resume Last Channel" (`resume_last_live`, default off) re-tunes correctly with a full-index fallback; process-death path safe.

**Threading & Performance** — LP-C-2 (history serialize), LP-C-3 (collectors). ✔ *Clean:* no `GlobalScope`; all six Handlers cleared in `onStop`/`releasePlayer`; OSD collectors correctly `repeatOnLifecycle`-scoped; no retained-Activity leak. *(Surfaced: first `SharedPreferences` touch is a synchronous disk read on the UI thread at launch/zap — LP-C "surfaced".)*

**Remote / D-pad Input** — LP-D-3 (surf focus loss), LP-D-6 (key log). ✔ *Clean:* zap key handling does only in-memory index work; rapid-zap double-actions guarded by request id; keys during buffering supersede rather than drop; OSD focus traps reset correctly on hide.

**Audio/Video Sync & Quality** — *(none new)*. ✔ *Clean:* tunneling offloads A/V sync to HW composer, auto-disabled on audio-sink failure and on audio-plays/video-frozen. *(Surfaced: no `onDroppedFrames` telemetry — folded into LP-D-2; PlayerTrackManager play/pause flush on live track-switch can rebuffer raw TS.)*

**Subtitles / CC** — LP-D-7 (caption style). ✔ CC wired for live with correct mid-stream toggle (no stale-override trap).

**Picture-in-Picture** — LP-C-1 (P2). Dead on Fire TV (no `FEATURE_PICTURE_IN_PICTURE`); bites PiP-capable builds.

**Crash & Error Reporting** — LP-D-1 (P2, no remote reporter), LP-D-5 (crash-log leak).

**Security & Privacy** — LP-D-4, LP-D-5 (both DEBUG/crash-only, no release leak). ✔ *Clean:* live-path URL/token logging correctly redacted; header values never logged; **no P0**.

**Analytics & Observability** — LP-D-2 (P2, no QoE).

**Device / Fragmentation** — *(none)*. ✔ *Clean:* `DeviceProfile.isLowRamDevice` drives buffer/stall sizing; SDK_INT guards on frame-rate matching (API 23) and PiP (API 26); decoder fallback covers incapable HEVC.

---

## 7. Recommended sequencing (when a fix pass is authorized — separate task)

1. **LP-B-1 (P1) + LP-B-3 + LP-B-4** — one prompt: debounce the tune, honor `repeatCount`, activate the request-id guard, zero `startPositionMs`. Highest stability ROI; protects the single-connection provider. **[Regression — zap QA mandatory].**
2. **LP-ADOPT (P2)** — unify the preview and fullscreen player construction (engine + error policy). Fixes OOM guard, SW-audio, and 429 mitigation on the warm path in one change. **[Regression — warm hand-off QA].**
3. **LP-D-1 + LP-D-2 (P2)** — add crash reporter + `AnalyticsListener` QoE together (both are Application/player-init instrumentation; also wire or remove the dead `firebase-analytics` dep).
4. **LP-D-3 (P2)** — surf-drawer stable IDs + DiffUtil.
5. **LP-D-5 + LP-D-4 (P3 security residuals)** — redact crash-handler + diagnostics segments.
6. **P3 batch** (same-file, sequence after the above): LP-A-2, LP-A-3, LP-B-5, LP-C-2, LP-C-3, LP-D-7.
7. **P4 sweep:** LP-A-4/A-5, LP-B-6, LP-C-4, LP-D-6.

> **Lifecycle note (out of scope, product decision):** `onStop` unconditionally releases the live player (`:2935`), so Home→return rebuilds cold (reconnect/rebuffer). Keeping the live player alive across `onStop` (release only in `onDestroy`) would give "instant on return" *and* fix LP-C-1's PiP release — but it changes background-resource behaviour and needs a product call before implementing. Not filed as a bug.

**Conflict map:** `PlayerActivity.kt` is touched by items 1, 2, 3, 5, 6, 7 — sequence these, never parallel. `PreviewPlayerPanel.kt` + `LiveTvGuideFragment.kt` (item 2) are isolated from the zap path (item 1) and may run in parallel with it.

---

*Diagnosis only. No code was modified in this pass. 22 findings: 0 P0 · 1 P1 · 5 P2 (6 IDs, LP-A-1/LP-B-2 consolidated as LP-ADOPT) · 10 P3 · 5 P4. Every scoped audit area is represented above with either a finding or an explicit "clean" note.*
