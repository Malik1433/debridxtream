# Video Player Ultra-Deep Audit Report

> **Re-Audit v2 (2026-07-04)** — after P0/P1/OOM fixes, a second three-agent deep audit graded the player against Netflix/Stremio/TiviMate. See the "Re-Audit v2 Scorecard" section at the bottom of this document. **Verdict: not yet Netflix/Stremio level — core engine B-, IPTV live C, Debrid delivery D+.** The debrid rate-limiter cost, missing auto-fallback, live-stream disk caching, and zap latency are the main separators.
>
> **Final Audit v3 (2026-07-04, post Phase A/B/C + freeze fix)** — line-by-line verification of every shipped fix (all present and correct) plus on-device evidence (crash-free on the previously-OOMing 1.5GB stick; zap ~1.28s measured; video-freeze watchdog fired and recovered in live logs; Belgium-channel freeze root-caused to the Phase C TS extractor flags and reverted same-day, device-verified). **Verdict: Core engine B+, IPTV live B-, Debrid B — solid commercial mid-tier, daily-driver quality. Remaining gap to full Netflix/Stremio parity is narrow and known:** (1) startup preload/parallel-prepare architecture, (2) zap pre-buffering pool (in progress in a parallel session), (3) TorBox pipeline (in progress in a parallel session), (4) runtime ABR feedback loop, (5) detail-page pre-resolution (deferred product decision — RD account pollution risk).

**Date:** 2026-07-03
**Scope:** Full playback pipeline — ExoPlayer/Media3 core engine, network/streaming delivery layer, UI/lifecycle/track management
**App:** DebridXtream IPTV (Android TV / Fire TV), Media3 1.5.0
**Benchmark:** International-standard smooth playback (Netflix / Plex / Stremio on Fire TV)

**Overall score: ~6.5/10** — buffering tuning and TV UX are above average; startup latency, missing frame-rate matching, and lifecycle race conditions are the main gaps.

---

## 1. Strengths (jahan hum acche hain)

| # | Strength | Location |
|---|----------|----------|
| 1 | Per-content buffer tuning — separate Live/VOD/Debrid profiles, network-quality aware (FAST/MODERATE/SLOW), low-RAM device detection | `PlayerBufferConfigFactory.kt` |
| 2 | Hardware tunneling enabled for Fire TV A/V sync | `PlayerActivity.kt:1709` |
| 3 | Gapless episode switch — same player instance reused, Surface re-bind deliberately avoided (race-safe) | `PlayerActivity.kt:791-836` |
| 4 | Stall detection with device-aware tolerance (12-15s thresholds, strike system) | `PlayerActivity.kt:1979-2016` |
| 5 | `BehindLiveWindowException` recovery for live streams | `PlayerActivity.kt:1897-1899` |
| 6 | Comprehensive TV D-pad handling + smart focus routing; PiP; `configChanges` prevents player restart | `PlayerActivity.kt:2235-2390`, `AndroidManifest.xml` |
| 7 | Track language persistence (audio/subtitle auto save/restore) + Android TV audio-sink decoder-flush workaround | `PlayerTrackManager.kt` |
| 8 | Terminal HTTP error detection (401/403/404/410/429/451) aborts useless retries | `PlayerActivity.kt:1914-1919` |
| 9 | SimpleCache 500MB LRU for IPTV; RAM-only path for high-bitrate Debrid | `PlayerCacheManager.kt`, `PlayerActivity.kt:1688-1696` |
| 10 | Real-Debrid rate limiter + exponential backoff on unrestrict calls | `RealDebridRateLimiter.kt`, `DebridPlaybackRepository.kt:474-514` |

### Measured buffer values

```
LIVE (MODERATE network):  min=15s,  max=60s,  start=2s,    rebuffer=3s
LIVE (SLOW):              min=20s,  max=70s,  start=3s,    rebuffer=5s
VOD Debrid:               min=30s,  max=60s,  start=2.5s,  rebuffer=5s
VOD IPTV (FAST):          min=12s,  max=60s,  start=1.5s,  rebuffer=3s
HLS live (low-RAM):       min=15s,  max=25s   (capped)
targetBufferBytes:        128MB (Debrid), default (others)
```

---

## 2. Weaknesses

### P0 — Critical (freeze / stall / crash)

> **Post-audit verification note:** items 1-3 were re-verified against the actual call sites during fix implementation; two initial findings turned out to be false positives (see Status column). Items 4-6 were confirmed and fixed on 2026-07-03.

| # | Issue | Location | Impact | Status |
|---|-------|----------|--------|--------|
| 1 | Addon proxy readiness HEAD probe was not main-safe inside the repository (all current call sites did wrap it in `Dispatchers.IO`, so no actual UI freeze — it is a sequential latency step, not a main-thread block) | `DebridPlaybackRepository.kt:117-174` | Sequential pre-play latency (up to 5s timeout) | ✅ Hardened — probe now wrapped in `withContext(Dispatchers.IO)` inside the repository |
| 2 | ~~`runBlocking` token refresh on hot path~~ **False positive** — `refreshTokenBlocking()` is only called from OkHttp `Authenticator.authenticate()`, which always runs on a background network thread; blocking there is the standard OkHttp pattern | `DebridAccountRepository.kt:27` | None | ❎ No change needed |
| 3 | ~~`runBlocking` SimpleCache init blocks first play~~ **Mostly mitigated already** — `MainApplication.onCreate` pre-warms the cache on `Dispatchers.IO` at app start; the `runBlocking` wrapper was pointless (it blocked the calling thread anyway) | `PlayerCacheManager.kt:23`, `MainApplication.kt:28` | Negligible in practice | ✅ Cleaned up — misleading `runBlocking` wrapper removed |
| 4 | **Retry Handler never cancelled** — after HOME press (`onStop` → `releasePlayer`), the pending retry runnable still fired and re-created a player with `playWhenReady=true` → background playback. (`initializePlayer` guards `isFinishing/isDestroyed`, so destroy-crash was already prevented, but the stopped-activity case was real) | `PlayerActivity.kt:1961, 2093` | Background audio/network after leaving player | ✅ Fixed — dedicated `retryHandler` + `removeCallbacksAndMessages(null)` in `releasePlayer()` |
| 5 | Player listener + debugListener never explicitly removed in `releasePlayer()` | `PlayerActivity.kt:1743, 1849` | Stale callbacks / leak vector | ✅ Fixed — both listeners stored and removed before `release()` |
| 6 | Debrid magnet polling: fixed 2s × 30 attempts — cached torrents (the common case) waited a full 2s before the first status check | `DebridPlaybackRepository.kt:395-460` | +1.5-3.5s on cached torrents; slow feedback overall | ✅ Fixed — exponential intervals 500ms → 1s → 2s → 4s cap (18 attempts, ~63s budget unchanged) |

### P1 — High (smoothness gap vs international standard)

| # | Issue | Detail | Location |
|---|-------|--------|----------|
| 7 | **No frame-rate matching** (verified absent codebase-wide) — no `Display.Mode` switching / `Surface.setFrameRate()`. 23.976/24fps film content judders on 60Hz panels. Standard requirement for Fire TV media apps | `PlayerActivity.kt` |
| 8 | **No `setAudioAttributes` / audio focus** (verified absent) — player ignores calls/notifications, no ducking | `initializePlayer()` |
| 9 | **No `setWakeMode(C.WAKE_MODE_NETWORK)`** (verified absent) — streams can die in doze/screen-off | `initializePlayer()` |
| 10 | **No pre-resolution / resolved-link caching** — every play click runs the full debrid chain (add magnet → poll → select → unrestrict); no pre-fetch on detail page | `PlaybackResolver.kt`, `DebridPlaybackRepository.kt` |
| 11 | **No custom `LoadErrorHandlingPolicy`** — Media3 defaults treat 5xx (transient) same as 4xx (permanent) | `PlayerActivity.kt` |
| 12 | **Linear retry backoff** (1s, 2s, 3s…) — no exponential growth, no jitter, no circuit breaker | `PlayerActivity.kt:1961` |
| 13 | **NetworkQualityManager output never feeds the player** — speed test runs but result is not linked to ABR or runtime buffer adaptation | `NetworkQualityManager.kt` |
| 14 | **128MB `targetBufferBytes` unconditional for Debrid** — OOM risk on 1GB Fire TV Stick | `PlayerBufferConfigFactory.kt:37-45` |
| 15 | **`startPositionMs` reset bug** — reset after STATE_READY; seamless episode switch can lose resume position | `PlayerActivity.kt:1756` |
| 16 | **No Media3 DataSource timeouts configured** — stalled high-bitrate stream feels like an indefinite freeze | `PlayerActivity.kt:1670-1682` |

### P2 — Medium (polish / best-practice gaps)

- No `LiveConfiguration` (target offset, live catch-up speed adjustment) for live streams
- No MediaCodec **async queueing** (`forceEnableMediaCodecAsynchronousQueueing`) — decode starvation risk on weak SoCs
- No `maxVideoSize` constraint — 4K track selectable on 1080p device (decoder stress)
- `CaptioningManager` ignored — system subtitle style settings not applied
- No back-buffer (`setBackBufferDurationMs`) — backward seek re-downloads
- `PreviewPlayerPanel` creates a **second ExoPlayer** — codec contention with main player on low-RAM TVs (`PreviewPlayerPanel.kt:116-122`)
- No `SeekParameters.CLOSEST_SYNC` — slower seeks than necessary
- Episode browser controller not cleaned up in `onDestroy` (`PlayerActivity.kt:1134`)
- **Security:** cleartext traffic globally allowed; Xtream credentials embedded in stream URLs (leak via logs/crash reports); no cert pinning for the Real-Debrid API

### P3 — Feature parity (nice-to-have)

- Seek preview thumbnails, playback speed control, subtitle timing offset, forced-subtitle auto-selection

---

## 3. Latency budget analysis (time-to-first-frame, Debrid VOD)

| Stage | Cost before fixes | After P0 fixes / achievable |
|-------|-------------------|------------------------------|
| Proxy readiness HEAD probe (sequential, pre-play) | up to 5s (timeout) | unchanged — could overlap with player init (P1) |
| Rate limiter inter-call delay (1.2s × ~4 calls) | 5-10s | 2-3s possible with batching (P1) |
| Magnet poll interval | 2-60s (first check after 2s) | 0.5-8s typical (exponential intervals — fixed) |
| Full re-resolution on every play (no link cache) | full chain every time | 15-min resolved-URL cache would make replays instant (P1) |
| **Typical cached-torrent start** | **~8-15s** | **~5-10s now; ~3-5s with P1 pre-resolution** |

Commercial players (Netflix/Prime on Fire TV) target < 2s to first frame; a debrid app realistically targets 3-8s for cached torrents.

---

## 4. Fix roadmap

### Phase 1 — P0 (✅ done, 2026-07-03)
1. ✅ Readiness HEAD probe made main-safe inside repository (`withContext(Dispatchers.IO)`)
2. ❎ Token refresh — false positive, no change needed (standard OkHttp Authenticator pattern)
3. ✅ Pointless `runBlocking` removed from `PlayerCacheManager` (pre-warm already existed in `MainApplication`)
4. ✅ Dedicated `retryHandler` + cancellation in `releasePlayer()` (stops background re-init after HOME/stop)
5. ✅ Both player listeners explicitly removed in `releasePlayer()`
6. ✅ Exponential magnet-poll intervals (500ms → 1s → 2s → 4s cap, same ~63s total budget)

### Phase 2 — P1 (✅ done, 2026-07-03)
- ✅ **Frame-rate matching** — on STATE_READY the display switches to a refresh rate that is an integer multiple of the content frame rate (`preferredDisplayModeId`); skipped for live TV (mode switch blanks the screen while zapping) and skipped when the current mode is already judder-free
- ✅ **Audio & power** — `setAudioAttributes(USAGE_MEDIA/MOVIE, handleAudioFocus=true)`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_NETWORK)`
- ✅ **Custom `LoadErrorHandlingPolicy`** — fail fast on 401/403/404/410/416/451, exponential backoff with jitter (1s→8s + 0-400ms) for transient errors; app-level retry backoff also changed from linear to exponential+jitter
- ✅ **Resolved-URL cache (15-min TTL)** — replays/episode restarts skip the full add-magnet→poll→unrestrict chain; retries (`isExpired`/attempt>1) always bypass the cache so a dead link is never re-served
- ⏸️ **Pre-resolution on detail-page open — deliberately deferred**: auto-resolving on browse would add torrents to the user's Real-Debrid account for content they may never play, and the 1.2s/call rate limiter means background resolution could trigger 429 cooldowns that hurt actual playback. Needs a product decision first.
- ✅ **RAM-scaled `targetBufferBytes`** — Debrid: low-RAM 48MB / ≤2.5GB 96MB / else 128MB
- ✅ **`startPositionMs` race fixed** — the READY-state reset no longer clears the resume target while a debrid re-resolve is in flight

### Phase 3 — P2/P3 (backlog)
- LiveConfiguration, MediaCodec async queueing, maxVideoSize cap, CaptioningManager, back-buffer, preview player pooling, security hardening (HTTPS segmentation, cert pinning), seek thumbnails, speed control

---

## 5. Verification checklist

- [ ] `.\gradlew.bat assembleDebug` passes
- [ ] `.\gradlew.bat testDebugUnitTest` passes
- [ ] Fire TV device test: cold-start-to-first-frame measured before/after (debrid VOD + live IPTV)
- [ ] Back-press during retry window → no crash
- [ ] Episode auto-play switch retains resume position
- [ ] Live channel zap latency unchanged or better

---

# Re-Audit v2 Scorecard (2026-07-04) — vs Netflix / Stremio / TiviMate

## Verdict: NOT yet Netflix/Stremio level. Solid mid-tier, with a clear path to parity.

| Area | Grade | Netflix/Stremio gap |
|------|-------|---------------------|
| Core engine (rendering, buffering, errors) | **B-** | Buffering strategy B (RAM-scaled, solid); missing decoder fallback, SeekParameters, bandwidth-based resolution capping |
| IPTV live path | **C** | Zap ~1-3s vs TiviMate <300ms (no pre-buffering/codec pool); live streams wrongly written to 500MB disk cache; no LiveConfiguration |
| Debrid delivery | **D+** | Click-to-play 8-15s vs Stremio+RD 3-5s; rate limiter (1.2s/call × 5-8 calls) is the kingpin cost; no auto-fallback on source failure; no next-episode pre-resolve |

## Verified strengths (match commercial grade)
- Hardware tunneling + frame-rate matching (v2 confirmed the new frame-rate code is mathematically correct)
- RAM-scaled buffers + low-RAM hard byte caps (OOM fix verified correct)
- Retry/backoff stack (LoadErrorHandlingPolicy + app-level exponential+jitter) graded A/B+
- Gapless episode switch, track-language persistence, TV D-pad UX

## Corrections to agent claims (lead review)
- "15s min buffer = 15s black screen on zap" — **wrong**: playback starts at `startPlaybackMs` (1-2s), not `minBufferMs`; real zap cost is codec re-init + TS demux + ~2s start buffer.
- "eMMC death in 83 minutes" — hyperbole, but live-TS disk caching genuinely is pointless I/O churn with zero reuse value; bypassing cache for LIVE_TV is correct and trivial.
- "Remove the rate limiter" — **rejected**: 1.2s spacing protects the user's RD account from 429 bans. Correct fix is fewer calls (skip redundant getTorrentInfo, batch checks), not removing protection.
- TorBox "vaporware" — overstated; TorBox sources play via addon/direct links, but a full RD-equivalent playback pipeline (own unrestrict/poll) indeed does not exist.

## Ranked remaining gaps (user-visible impact)

### Debrid (biggest gap to Stremio)
1. **No auto-fallback on source failure** — user manually returns + taps next source; should auto-try next ranked source
2. **Rate limiter call-count** — 5-8 RD calls × 1.2s per resolution; reduce call count (cached-check fast path)
3. **No next-episode pre-resolve during the 15s countdown** — binge-watching pays 5-10s per episode
4. **Source dedup missing** — same infoHash shown twice (Torrentio + Comet); no codec surfaced (REMUX vs HEVC)
5. **Cache-key truncation collision risk** (magnet fallback path, 120 chars) — use full extracted hash

### IPTV live
6. **Live streams written to disk cache** — zero reuse value, I/O churn; bypass CacheDataSource for LIVE_TV (one-line)
7. **Zap latency structural** — no adjacent-channel pre-buffering / codec warm pool (TiviMate technique; 3-5 day effort)
8. **No TsExtractor tuning** — FLAG_ALLOW_NON_IDR_KEYFRAMES / FLAG_DETECT_ACCESS_UNITS not set (+300-500ms per zap)
9. **No LiveConfiguration / jump-to-live** — pause on live, resume lags behind edge with no "LIVE" button
10. **25s timeout too patient for live** — dead stream detection should be ~12s

### Core engine
11. **No decoder fallback** — H.265 hardware decoder failure has no H.264/software fallback path (`enableDecoderFallback`)
12. **No SeekParameters.CLOSEST_SYNC** — seeks slower than needed
13. **No bandwidth-aware resolution cap** — 4K picked even on 5Mbps network
14. **No back-buffer** — backward seek re-downloads
15. Minor: captureRunnable not cleared in releasePlayer (1.5s-lived activity ref; cosmetic)

## Path to parity (recommended order)
- **Phase A (quick wins, ~1 day):** live cache bypass, live timeout 12s, SeekParameters.CLOSEST_SYNC, enableDecoderFallback, back-buffer 15s for VOD, cache-key full-hash fix, captureRunnable cleanup, preview-player release on zap
- **Phase B (high value, 2-4 days):** debrid auto-fallback to next ranked source; next-episode pre-resolve during countdown; RD call-count reduction; source dedup by infoHash + codec surfacing
- **Phase C (structural, 1-2 weeks):** zap pre-buffering/codec pool (TiviMate parity), LiveConfiguration + jump-to-live, bandwidth meter + resolution capping, TorBox full playback pipeline
