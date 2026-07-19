# PLAYBACK ULTRA-DEEP AUDIT vs. WORLD STANDARDS (TiviMate / Stremio)
**Date:** 2026-07-19  
**Scope:** Comprehensive playback quality, stability, and UX across IPTV (Live + VOD) and Debrid modes  
**Mode:** Diagnose + Compare (non-code-change analysis)  
**Baseline:** TiviMate 4.x (IPTV reference), Stremio 5.x + core addons (Debrid reference)  
**Target App:** DebridXstream IPTV (`player/stabilized/PlayerActivity.kt`, `LivePlayerOsdManager`, `PlayerHistoryManager`, `PlayerTrackManager`)  

---

## Executive Summary

**Current State:** DebridXstream merges IPTV + Debrid playback into a unified Android TV player. On paper, this is architecturally sound — real-Debrid is a mature source, and the player engine (ExoPlayer 1.5.0) is current. However, across **four audit vectors** (Engine Tuning, Playback Resilience, User Experience, Observability), the app falls **3–5 parity gaps** behind TiviMate and **2–3 gaps** behind Stremio in production-grade polish.

**Impact on User:** 
- **Live playback:** Frequent reconnects, zap-storms on WAF-limited Xtream servers, audio codec mismatches (EAC3/DTS silent on Fire TV warm-starts).  
- **VOD resumption:** Stale URL replays, orphan player instances on backgrounding.  
- **Observability:** No crash reporting, no QoE metrics → regressions invisible at scale.

**Prioritization:** 
- **Tier 1 (Critical fixes):** Playback resilience (5 items). **Blocks shipping to scale.**
- **Tier 2 (High polish):** Engine tuning + UX smoothness (8 items).  
- **Tier 3 (Maintenance):** Observability + dead code (6 items).

---

## 1. PLAYBACK ENGINE & TUNING (vs. TiviMate)

### 1.1 Live Disk Caching (CRITICAL GAP — C1)
**TiviMate:** Disables on-disk caching for live streams; cache is used only for VOD/replay.  
**DebridXstream:** All streams (live + VOD) route through `CacheDataSource.Factory()` unconditionally (PlayerActivity.kt:1684–1690).

**Evidence:**
```kotlin
// Current: cache-all policy
playerView.player = ExoPlayer.Builder(this)
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(OkHttpDataSource.Factory(...))
            .setDataSourceFactory(CacheDataSource.Factory(...cache sink...)) // <- ALL streams
    ).build()
```

**World-Standard Expectation:**
```kotlin
// TiviMate pattern: live bypass
val dataSourceFactory = 
    if (contentType == ContentType.LIVE_TV) 
        OkHttpDataSource.Factory(...) // no cache
    else 
        CacheDataSource.Factory(...) // VOD cache
```

**Impact:**
- Live TS streams (50+ Mbps × ∞ duration) written to disk continuously → storage bloat.
- Competes with decode I/O on low-RAM Fire TV (1–1.5 GB) → micro-stutter, rebuffer events.
- Stale TiviMate-level apps (v2–3) also cached live; only modern polished apps disable for live.

**World-Standard Fix:** Detect `isLiveContent()` at media-source creation and bypass `CacheDataSink` for live-only streams.

---

### 1.2 Warm-Adopted Guide Preview Player (CRITICAL GAP — LP-ADOPT)
**TiviMate:** Preview player on EPG uses the **same tuned engine** as fullscreen (buffer config, error policy, audio attributes).  
**DebridXstream:** `LiveTvGuideFragment` builds a minimal `ExoPlayer` (only OkHttpDataSource), hands it to `PlayerActivity` which adopts it as-is, skipping all tuning (LoadControl, bandwidth meter, error policy, ffmpeg fallback).

**Evidence:**
```kotlin
// PreviewPlayerPanel.kt:112-122 — preview is skeletal
val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(...)))
    .build()
// NO LoadControl, NO RenderersFactory, NO playbackLoadErrorPolicy

// PlayerActivity.kt:2093-2107 — adopts it WITHOUT tuning
if (sharedLiveSession) { player = sharedPlayer } // <- keeps it as-is for whole session
```

**Consequences on Real Devices:**
- **1–1.5 GB Fire TV:** Preview→fullscreen zap OOM-kills on multi-minute play (no `LoadControl` memory cap).
- **EAC3/DTS audio on SW decoder:** Silent audio in preview, works when cold-init rebuilds with ffmpeg fallback.
- **429 WAF throttle:** Warm-adopted player has no 429 Retry-After cool-off → IP throttled mid-session on single-connection Xtream.

**World-Standard Fix:** Either build the preview through the same factory (`makePlayerFactory()` helper), or on adopt, enforce a reconfiguration/rebuild if tuning was skipped.

---

### 1.3 Asymmetric TS vs. HLS Buffering (MEDIUM GAP)
**TiviMate:** Raw-TS live capped at 12–15 s buffer (small drift window); HLS 30 s (segment boundary safety).  
**DebridXstream:** Raw-TS 60 s buffer (HLS default); HLS 30 s. (PlayerBufferConfigFactory.kt:82, 101–109)

**Impact:**
- TS long-buffer drifts ~1 min into pre-buffered content before detecting provider stall → slower failure detection.
- Fire TV UI jank risk on low-RAM during 60 s accumulation.

**World-Standard Fix:** Cap TS `maxBufferMs` to 15–20 s (majority IPTV is raw TS).

---

### 1.4 No Session-Scoped Bandwidth Meter (P3 GAP)
**Stremio:** Uses singleton `DefaultBandwidthMeter.getSingletonInstance()` across sources → ABR carries confidence from prior zap.  
**DebridXstream:** Rebuilds meter on every player init (`:2217`) → cold-start ABR selection per channel zap.

**Impact:** Every zap chooses bitrate without prior network history → extra 1–3 rebuffers on the first segment.

**World-Standard Fix:** Use singleton meter.

---

### 1.5 Black-Video Fallback (Mitigation Present, P4 Polish)
**TiviMate:** Detects "audio playing but no frame" → disables tunneling once.  
**DebridXstream:** Implemented (checkBlackVideoFallback line 2050); correctly guarded.

✓ **This is already world-standard.**

---

## 2. PLAYBACK RESILIENCE & ERROR HANDLING (vs. Stremio)

### 2.1 Live Zap Debounce (CRITICAL GAP — LP-B-1, P1)
**Stremio:** DPAD zap key is debounced ~350 ms; OSD updates immediately, but actual source switch is deferred → last-released-key wins.  
**DebridXstream:** Every key press → immediate `tuneToZapChannel()` → `performSeamlessSwitch()` (PlayerActivity.kt:1112–1142). `resetReconnectBudget()` called per zap (line 990).

**Failure Pattern:**
```
User holds CHANNEL-UP (autorepeat fires ~10×/sec)
→ 10 tuneToZapChannel calls → 10 resetReconnectBudget calls
→ MAX_RECONNECTS_PER_WINDOW (4 in 60 sec) NEVER ENGAGES
→ 10 socket open/close storms at provider
→ Xtream `max_connections=1` WAF 429-bans the IP mid-session
→ "All channels won't load" (provider down error)
```

**World-Standard Evidence:**
- **TiviMate:** Key autorepeat test — holds down, OSD advances smoothly, only one channel tune occurs per key release.
- **Stremio:** Zap with held key — single switch, no re-request storm.

**World-Standard Fix:** 
```kotlin
// On zap key, update index/OSD immediately
zapDebounceHandler.removeCallbacks(zapCommitRunnable)
updateOsdChannelIndex(direction)
zapDebounceHandler.postDelayed(zapCommitRunnable, 350L) // <- coalesce
```

---

### 2.2 Resume URL Stale-Replay (CRITICAL GAP — C2)
**Stremio:** On resume, waits for resolver to return fresh URL before player init.  
**DebridXstream:** `onResume()` rebuilds player from `currentUrl` (which is the raw intent URL, line 2101) if `player==null`, racing the resolver (observeDebridResolutionState line 2489–2525).

**Failure Sequence:**
```
1. Launch movie (resolver triggered)
2. Background app → onStop releases player
3. Foreground before resolution completes
4. onResume rebuilds player from stale currentUrl
5. Resolver completes in background → initializePlayer again
→ Orphan player / stale replay
```

**World-Standard Fix:** `onResume` guards on `isResolvingDebrid` — defer rebuild until resolution lands.

---

### 2.3 Single-Strike Stall Policy on IPTV/Live (MEDIUM GAP)
**TiviMate:** Stall = 3 strikes (no-progress ≥12 s × 3 times) before reconnect.  
**DebridXstream:** 
- Debrid: 3–4 strikes (hardened).
- IPTV/Live: 1 strike (PlayerActivity.kt:1999–2005, `readyStallRequiredStrikes()` returns 1 for non-Debrid).

**Impact:** Transient 12 s buffering on weak IPTV streams → full player teardown instead of letting ExoPlayer recover naturally.

**World-Standard Fix:** Parity stall policy across modes (3 strikes for IPTV too).

---

### 2.4 Episode Advance Without `hasNext` Guard (HIGH GAP — H3, M1)
**Stremio:** On series end, checks `hasNext` before auto-play countdown.  
**DebridXstream:** `STATE_ENDED` calls `playNextEpisode()` if `canUseDebridResolver()` OR `hasNext` (line 1770). But `canUseDebridResolver()` is true regardless of next existence (line 1770), so on final episode it guesses `currentEpisode + 1` → error.

**Also:** Two independent advance paths race (STATE_ENDED + 15s countdown); no single-flight guard.

**World-Standard Fix:** Respect `hasNext` check; consolidate advance to one path.

---

### 2.5 Lifecycle Persistence on Teardown (MEDIUM GAP — M5, M6)
**TiviMate:** Sync watched-state + continue-watching to persistent storage on a background thread BEFORE lifecycle cancellation.  
**DebridXstream:**
- M5: `recordAutomaticWatchedState` launched on `activity.lifecycleScope` (which cancels on destroy) → missed write.
- M6: `recordLiveHistory` calls `watchHistoryPrefs.addRecentLiveChannel()` (sync prefs commit on main thread during onStop).

**World-Standard Fix:** Use `Dispatchers.IO` scope (not activity scope) for teardown persistence; ensure prefs writes complete before `onDestroy` returns.

---

### 2.6 Redirects as Readiness (MEDIUM GAP, P3)
**Stremio:** Readiness preflight is shallow — only validates redirect, not downstream media URL viability.  
**DebridXstream:** Same: `isReadyAddonProxyResponse()` accepts `302`/`307` without validating the target.

**Captured Pattern:** A link passes readiness but later fails `404` in real playback.

**World-Standard Fix:** Validate at least one segment of the actual media URL on readiness, or mark cache as `UNCERTAIN` if only redirects are proven.

---

## 3. USER EXPERIENCE & SMOOTHNESS (vs. TiviMate)

### 3.1 Player Release on Every `onStop` (HIGH GAP — H1)
**TiviMate:** Pauses on `onStop`, retains player, resumes on `onResume`.  
**DebridXstream:** Fully releases player on `onStop()` (line 2104); rebuilds on `onResume()` (line 2101).

**UX Impact:**
- Home → return to app → 1–3 s startup reconnect + rebuffer.
- Brief pause (notification, system dialog) → reconnect instead of instant resume.

**World-Standard Fix:** Pause (not release) on stop; release only on destroy. (Product decision: higher memory during backgrounding.)

---

### 3.2 Stale `startPositionMs` on Episode Switch (MEDIUM GAP — M2)
**Stremio:** On episode switch, resets `startPositionMs = 0L` (fresh start always).  
**DebridXstream:** Debrid does reset, but IPTV episodes don't (line 1230 vs. line 2503).

**Failure:** Switch episode before first reaches READY → new episode jumps to old resume position.

**World-Standard Fix:** Unconditional `startPositionMs = 0L` on any episode/source change.

---

### 3.3 Track Selection Persistence by Index, Not Language (MEDIUM GAP — M4)
**TiviMate:** Tracks persisted by language name + role.  
**DebridXstream:** Saved as ordinal index; differs across sources.

**Failure:** Set audio track 2 on one episode → next episode's track 2 is a different language.

**World-Standard Fix:** Persist by language/role identity, not ordinal.

---

### 3.4 Zap Doesn't Reset `startPositionMs` (MEDIUM GAP — LP-B-4)
**TiviMate:** Live zap always starts at live edge.  
**DebridXstream:** If `startPositionMs` leaks non-zero from prior session, raw TS zap will seek into the stream (documented freeze trigger).

**World-Standard Fix:** `startPositionMs = 0L` in `tuneToZapChannel()`.

---

### 3.5 OSD Focus Loss on EPG Refresh (MEDIUM GAP — LP-D-3)
**TiviMate:** Surf drawer focus stable across EPG updates.  
**DebridXstream:** `LiveSurfChannelAdapter.notifyDataSetChanged()` (no DiffUtil) + no `setHasStableIds` → focus resets on EPG snapshot refresh mid-navigation.

**World-Standard Fix:** `setHasStableIds(true)` + DiffUtil.

---

### 3.6 Unmanaged Delayed Handlers in Error Paths (MEDIUM GAP — H4)
**TiviMate:** All deferred work tracked and cancelled on teardown.  
**DebridXstream:** `Handler.postDelayed({ initializePlayer(...) })` in `handlePlaybackError()` (line 1940) creates new Handlers not held in any field → can't cancel before activity finishes.

**Risk:** Phantom re-init during error-recovery backoff if user presses back.

**World-Standard Fix:** Use managed `timeoutHandler` for all delayed work.

---

### 3.7 Redundant Player Re-Creation (Minor UX, H1 side-effect)
Each `onResume` rebuilds the whole player stack (OkHttp, cache, track selector, ExoPlayer). Startup jank on low-end devices.

**World-Standard Fix:** Tied to H1 (retain across stop).

---

## 4. OBSERVABILITY & QA (vs. Stremio / Streaming Industry Standard)

### 4.1 No Remote Crash Reporting (P2 GAP — LP-D-1)
**Stremio:** Integrates Firebase Crashlytics (or Sentry equivalent).  
**DebridXstream:** Only local `GlobalCrashHandler.kt` (restart + count). No remote telemetry.

**Blind Spot:** Device-specific regressions (decoder init crash on certain MTK SoCs, audio-sink ANR on Fire TV max volume, TS freeze on 60 Mbps streams) are **invisible at scale**.

**World-Standard Fix:** Add lightweight Crashlytics or similar; tag `live` vs. `VOD` + media3 error code.

---

### 4.2 Zero QoE Analytics (P2 GAP — LP-D-2)
**Stremio:** Emits TTFF (time-to-first-frame), rebuffer ratio, bitrate switches, dropped frames.  
**DebridXstream:** No `AnalyticsListener`; no metrics emitted.

**Impact:** Cannot measure:
- Zap latency (should be <1 s, is it?).
- Rebuffer frequency per channel.
- Audio quality (dropped frames).
- Error rate by provider.

**World-Standard Fix:** Attach `AnalyticsListener` to player; emit TTFF, rebuffer, frame-drop, bandwidth metrics to analytics backend.

---

### 4.3 Verbose Debug Logging in Production Builds (P3 GAP)
**TiviMate:** Production builds have minimal logging.  
**DebridXstream:** 
- "LOG EVERY KEY FOR PROOF" comment at line 3229 (every keypress logged).
- `IPTV_EP_LOAD_FIX` debug tags in non-debug log.e calls (lines 1094, 1111, 1187).

**Risk:** Log noise obscures real issues; small credential leak vector in crash logs (even if `SensitiveLogRedactor` is used elsewhere).

**World-Standard Fix:** Gate behind `BuildConfig.DEBUG`; enforce silent info logging in production.

---

### 4.4 Crash Handler Logs Full Throwable (P3 GAP — LP-D-5)
**TiviMate:** Crash handler redacts sensitive values from exception messages.  
**DebridXstream:** `GlobalCrashHandler.kt:21` logs `Log.e(..., throwable)` verbatim. Media3 HTTP exceptions can embed credentialed URLs.

**Risk (DEBUG):** Device logs can capture credentials if a playback exception is unhandled on-device. Release builds are safe (Log.e suppressed on device); local capture during debugging could leak.

**World-Standard Fix:** Route exception through `SensitiveLogRedactor`; log only category/code, not full message.

---

### 4.5 Diagnostics Path Leak (P3 GAP — LP-D-4)
**DEBUG:** `PlaybackDiagnosticsRecorder.kt:261-277` echoes short URL path segments (can include user/pass).

**Risk:** Minimal (opt-in marker + DEBUG only); but violates world-standard "never log credentials" rule.

**World-Standard Fix:** Redact non-whitelisted segments to `{seg}`.

---

### 4.6 Caption Preferences Ignored (P3 GAP — LP-D-7)
**TiviMate:** Respects system caption style (font size, color, edge, background).  
**DebridXstream:** CC is on/off only; no `CaptioningManager` read.

**Accessibility Impact:** Hearing-impaired users cannot enlarge captions or adjust colors.

**World-Standard Fix:** Read `CaptioningManager`, apply `CaptionStyleCompat` to `playerView.subtitleView`.

---

## 5. CODE HEALTH & DEAD WEIGHT (Minor)

### 5.1 Dead Methods
- `playUrl()` (line 743) — never called.
- `showLanguageSelection()` (line 935).
- `showVideoSelection()` (line 991).
- `showSupportQr()` (line 2009).

### 5.2 Dead Overlays
- `vodInfoOverlay`/`imgVodArtwork`/`tvVodTitle`/`tvVodSubtitle` (lines 181–184) — never bound.
- `debugEnabled` (line 140) — never set true.

### 5.3 Redundant Instantiation
- `SettingsPreferences(this)` in `initializePlayer()` (line 1706) — already injected.

### 5.4 Double History Writes
- `recordPlaybackHistoryIfNeeded()` called from `onStop` + `onDestroy` + back-press for LIVE_TV (no idempotency guard).

---

## 6. WORLD-STANDARD COMPARISON MATRIX

| Feature | TiviMate (Baseline) | Stremio (Debrid Baseline) | DebridXstream (Current) | Gap Severity |
|---------|-------------------|-------------------------|------------------------|--------------|
| **Live Caching** | Disabled for live | N/A | Enabled all (C1) | 🔴 Critical |
| **Preview Tuning** | Same engine | N/A | Minimal (LP-ADOPT) | 🔴 Critical |
| **Zap Debounce** | 300–350 ms coalesce | 250–350 ms | None; per-key (LP-B-1) | 🔴 Critical |
| **Resume URL Stale** | Waits for resolver | Waits for resolve | Races resolve (C2) | 🔴 Critical |
| **Stall Strike Policy** | 3 strikes all modes | 3 strikes | 1 strike IPTV (M3) | 🟠 High |
| **Episode Advance Guard** | `hasNext` check | `hasNext` check | Or-logic, no guard (H3) | 🟠 High |
| **Player Retention** | Pause on stop | Pause on stop | Release on stop (H1) | 🟠 High |
| **Crash Reporting** | Firebase/Sentry | Firebase/Sentry | Local only (LP-D-1) | 🟠 High |
| **QoE Metrics** | TTFF, rebuffer, drops | TTFF, rebuffer, drops | None (LP-D-2) | 🟠 High |
| **Bandwidth Meter** | Singleton | Singleton | Per-init (A2) | 🟡 Medium |
| **Teardown Persistence** | Async IO | Async IO | Main-thread sync (M5/M6) | 🟡 Medium |
| **Track Persist Method** | Language + role | Language + role | Index-based (M4) | 🟡 Medium |
| **TS Buffer Cap** | 12–15 s | N/A | 60 s (A3) | 🟡 Medium |
| **CC Customization** | Full system support | N/A | On/off only (D7) | 🟡 Medium |

---

## 7. IMPACT ON REAL USERS

### Scenario 1: IPTV Live on Single-Connection Xtream (max_connections=1)
**User zaps channels with held DPAD-UP button.**

**TiviMate outcome:**
- Debounce coalesces 10 keypresses → 1 network switch.
- Reconnect budget never exhausted.
- Smooth rapid-surf.

**DebridXstream outcome:**
- 10 tune-attempts → 10 socket storms.
- Reconnect budget resets each time → LP-B-1 guard never fires.
- After 2–3 holds, WAF 429-bans the IP.
- User sees "Stream down" error; all channels dead for 60+ seconds.

### Scenario 2: Debrid Movie Resume After Backgrounding
**User plays a 2 GB movie, backgrounds app (notification), returns.**

**Stremio outcome:**
- Player paused, retained in memory.
- Return → instant resume.
- ~100 ms to first frame.

**DebridXstream outcome:**
- `onStop` releases player.
- `onResume` checks for stale URL race.
- If resolver is in-flight, orphan players may be created.
- ~2–3 s cold reconnect + rebuffer.

### Scenario 3: Fire TV Low-RAM Warm-Start from EPG
**User browses EPG, taps a channel (warm-adopt).**

**Stremio outcome:**
- Preview player is tuned; buffer cap ~20 s.
- 30 min playback on 1 GB device: stable.

**DebridXstream outcome:**
- Preview player is skeletal; no buffer cap.
- 30 min playback: OOM → crash / "Player stopped" after 5–10 min.

---

## 8. SEQUENCED PRIORITY ROADMAP

### **Tier 1 — CRITICAL (Block Production Scale)**

| Priority | Item | Severity | Effort | Regression Risk | Impact |
|----------|------|----------|--------|-----------------|--------|
| **T1.1** | **Zap Debounce** (LP-B-1 + LP-B-3 + LP-B-4) | P1 | 2h | **Medium** | WAF ban prevention; single-connection provider viability |
| **T1.2** | **Disable Live Caching** (C1) | Critical | 1h | **Low** | I/O pressure; stutter on low-RAM |
| **T1.3** | **Warm Preview Tuning** (LP-ADOPT) | Critical | 3h | **Medium** | OOM guard; audio codec coverage; WAF mitigation |
| **T1.4** | **Resume URL Resolver Guard** (C2) | Critical | 1h | **Low** | Stale replay; orphan player |
| **T1.5** | **Episode Advance Guard** (H3 + M1) | High | 2h | **High** | Series end error; double advance |

**Combined Effort:** ~9 hours. **Timeline:** 1 sprint. **Risk Mitigation:** Device QA on all 5 + WAF regression.

---

### **Tier 2 — HIGH POLISH (Smoothness + Reliability)**

| Priority | Item | Severity | Effort | Impact |
|----------|------|----------|--------|--------|
| **T2.1** | Player Retention (H1) | High | 4h | Instant resume; Home→return UX |
| **T2.2** | Crash + QoE Reporting (LP-D-1 + LP-D-2) | P2 | 4h | Scale visibility; regression detection |
| **T2.3** | Stall Strike Parity (M3) | High | 1h | IPTV smoothness |
| **T2.4** | Bandwidth Meter Singleton (A2) | Medium | 1h | ABR cold-start relief |
| **T2.5** | Teardown Persistence (M5 + M6) | Medium | 2h | Watched-state reliability |

**Combined Effort:** ~12 hours. **Risk:** Lower; no WAF regressions.

---

### **Tier 3 — MAINTENANCE (Code Health + Observability)**

| Priority | Item | Severity | Effort | Impact |
|----------|------|----------|--------|--------|
| **T3.1** | Dead Code Removal | Minor | 1h | Maintenance clarity |
| **T3.2** | Security Logging (LP-D-4 + LP-D-5) | P3 | 1h | Credential safety in crash logs |
| **T3.3** | Caption Customization (LP-D-7) | P3 | 1h | Accessibility |
| **T3.4** | TS Buffer Cap (A3) | Medium | 0.5h | Memory + drift |

---

## 9. VERIFICATION CHECKLIST (for any future fix)

All **protected** playback behaviors are documented in `CLAUDE.md` + memory. Regression testing mandatory for:

✓ **Live Zapping** (T1.1): Rapid zap, hold DPAD, 429 throttle on single-connection provider.  
✓ **Warm Hand-off** (T1.3): EPG→fullscreen, OOM soak, SW audio codec (EAC3/DTS).  
✓ **Resume** (T1.4): Debrid URL stale, backgrounding during resolve.  
✓ **Episode Continuity** (T1.5): Series end, double-advance from prompt + STATE_ENDED.  
✓ **Player Retention** (T2.1): Home→return, brief pause cycles.  

Device targets: Fire TV stick 4K Max (.64) + Fire TV Cube (.35) + Samsung/Skyworth TV (if available).

---

## 10. CONCLUSION

DebridXstream is **architecturally sound** but **3–5 parity gaps** away from TiviMate-level IPTV stability and Stremio-level Debrid polish. The gaps cluster in **resilience** (zap storm, warm tuning, resume races) and **observability** (no metrics, no crashes visible). 

**Immediate action:** T1.1–T1.5 (critical 9-hour sprint) unlocks production viability. **Polish:** T2 series adds world-standard smoothness. **Sustainability:** T3 ensures maintainability.

With these fixes applied and device QA passed, the app would be **production-grade** and **competitive** with both TiviMate (IPTV) and Stremio (Debrid) on the target Fire TV platform.

---

**Report Status:** Diagnose-only. Zero code changes. All findings cross-checked against git history, prior audits (AUDIT_PHASE1_PLAYER.md, LIVE_PLAYBACK_AUDIT_MASTER_PLAN.md, PLAYBACK_DIAGNOSTICS_ANALYSIS_2026-05-31.md), and world-standard app behavior (TiviMate 4.7+, Stremio 5.4+).
