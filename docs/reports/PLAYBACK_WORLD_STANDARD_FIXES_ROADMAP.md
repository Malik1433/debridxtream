# Playback World-Standard Fixes — Quick Action Roadmap
**Date:** 2026-07-19  
**Comparing Against:** TiviMate 4.7+ (IPTV), Stremio 5.4+ (Debrid)  

---

## Critical Issues (Block Production Scale) — 9 Hours

### 1. **Zap Debounce + WAF Protection** (2h)
**Problem:** User holds CHANNEL-UP → 10 zaps/sec → WAF 429-bans IP on single-connection Xtream.  
**Fix:** Debounce tune 350 ms; ignore key autorepeat; maintain reconnect budget.  
**Risk:** Medium (zap QA mandatory).  
**Files:** `PlayerActivity.kt` (~30 lines touched).

### 2. **Disable Live Disk Caching** (1h)
**Problem:** Live TS streams write 50+ Mbps to disk endlessly → stutter on 1 GB Fire TV.  
**Fix:** Bypass `CacheDataSource` for live; use cache only for VOD.  
**Risk:** Low.  
**Files:** `PlayerActivity.kt:1684–1690` (conditional cache factory).

### 3. **Warm Guide-Preview Tuning** (3h)
**Problem:** EPG preview player is skeletal; OOMs after 5–10 min on low-RAM Fire TV; no EAC3/DTS audio.  
**Fix:** Build preview through same tuned engine (LoadControl caps, ffmpeg fallback, 429 policy).  
**Risk:** Medium (warm hand-off QA; audio codec coverage).  
**Files:** `PreviewPlayerPanel.kt`, `LiveTvGuideFragment.kt`, `PlayerActivity.kt` (shared factory refactor).

### 4. **Resume URL Stale-Replay Fix** (1h)
**Problem:** `onResume` rebuilds player from stale URL before resolver completes → orphan player.  
**Fix:** Guard `onResume` on `!isResolvingDebrid`.  
**Risk:** Low.  
**Files:** `PlayerActivity.kt:2101–2102` (2 lines).

### 5. **Episode Advance Continuity** (2h)
**Problem:** End-of-series triggers advance to non-existent episode; two paths can race (STATE_ENDED + countdown).  
**Fix:** Guard on `hasNext`; consolidate advance to one path.  
**Risk:** High (episode continuity QA).  
**Files:** `PlayerActivity.kt`, `PlayerNextEpisodeManager.kt`.

---

## High-Impact Polish (Smoothness + Reliability) — 12 Hours

### 6. **Player Retention on Stop** (4h)
**Problem:** Every Home→return reconnects (1–3 s delay).  
**Fix:** Pause (not release) on `onStop`; release only in `onDestroy`.  
**Files:** `PlayerActivity.kt` (lifecycle refactor).

### 7. **Crash + QoE Reporting** (4h)
**Problem:** Device crashes/ANRs invisible; no metrics (TTFF, rebuffer, frame drops).  
**Fix:** Add Firebase Crashlytics + `AnalyticsListener`.  
**Files:** `build.gradle` (deps), `Application.kt`, `PlayerActivity.kt` (instrumentation).

### 8. **Stall Strike Parity** (1h)
**Problem:** IPTV reconnects after single 12 s stall; Debrid gets 3–4 strikes (asymmetric).  
**Fix:** Parity: 3 strikes all modes.  
**Files:** `PlayerActivity.kt:1999–2005` (~1 line).

### 9. **Bandwidth Meter Singleton** (1h)
**Problem:** ABR cold-starts on every zap (no confidence from prior channels).  
**Fix:** Use singleton meter.  
**Files:** `PlayerActivity.kt:2217`.

### 10. **Teardown Persistence** (2h)
**Problem:** Watched-state IO launched on cancelling lifecycle; continues writes block main thread on exit.  
**Fix:** Async IO scope; unblock main thread.  
**Files:** `PlayerHistoryManager.kt`, `WatchHistoryPreferences.kt`.

---

## Maintenance (Code Health + Compliance) — 4 Hours

### 11. **Remove Dead Code** (1h)
- Dead methods: `playUrl()`, `showLanguageSelection()`, `showVideoSelection()`, `showSupportQr()`.
- Dead overlays: `vodInfoOverlay`, `debugEnabled`.

### 12. **Security Logging** (1h)
- Redact crash handler throwable (LP-D-5).
- Redact diagnostics path segments (LP-D-4).

### 13. **Accessibility: Caption Customization** (1h)
- Read `CaptioningManager`; apply system caption style.

### 14. **TS Buffer Cap** (0.5h)
- Cap raw-TS live at 15–20 s (not 60 s).

---

## Test Plan (Device QA — Fire TV 4K Max + Cube)

### Tier 1 Regressions
- [ ] Rapid zap (hold DPAD-UP); confirm no 429 ban on single-connection provider.
- [ ] EPG→fullscreen on SW-audio channel (EAC3/DTS); verify audio intact.
- [ ] Low-RAM soak (30 min play on 1 GB device); confirm no OOM.
- [ ] Debrid resume during URL resolution; confirm no stale replay.
- [ ] End of series; confirm no ghost advance.

### Tier 2 Regressions
- [ ] Home→return; measure resume latency (<200 ms, not 1–3 s).
- [ ] Verify crash reporter + QoE metrics hit backend.
- [ ] IPTV stall recovery (3 strikes before reconnect).

### Tier 3 Regressions
- [ ] Caption customization on system setting change.
- [ ] Dead code removal (no missing reference errors).

---

## Timeline & Dependencies

| Sprint | Items | Days | Risk |
|--------|-------|------|------|
| **Sprint 1 (Tier 1)** | 1–5 (Critical) | 2–3 | **High** — WAF + episode QA mandatory |
| **Sprint 2 (Tier 2)** | 6–10 (Polish) | 3 | Medium — no provider/platform risk |
| **Sprint 3 (Tier 3)** | 11–14 (Maintenance) | 1 | Low — internal only |

**Total:** 25 hours (3+ person-days). **Ship Gate:** All Tier 1 + device QA passed.

---

## Impact Summary

| Before | After (All Tiers) |
|--------|-------------------|
| WAF 429 bans on zap | ✓ Debounced; budget protected |
| Low-RAM OOM crashes | ✓ Tuned buffer; live-cache disabled |
| Silent audio (EAC3/DTS) | ✓ ffmpeg fallback on warm-adopt |
| Stale resume replays | ✓ Resolver guard |
| Series ghost advance | ✓ hasNext guard; single-path logic |
| Home→return 1–3 s delay | ✓ Player retention; <200 ms |
| No crash visibility | ✓ Crashlytics + QoE metrics |
| IPTV jank on weak streams | ✓ 3-strike parity; smooth recovery |
| No observability | ✓ TTFF + rebuffer + frame-drop telemetry |
| Hearing-impaired no CC control | ✓ Caption customization |

**Result:** World-standard playback quality on par with **TiviMate (IPTV)** and **Stremio (Debrid)**.

---

*See full audit: `PLAYBACK_ULTRA_DEEP_AUDIT_WORLD_STANDARDS_2026-07-19.md`*
