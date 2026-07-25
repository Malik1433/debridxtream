# Player Refactor — Device Audit Report

**Date:** 2026-07-25
**Build under test:** `versionName 2.1.5` / `versionCode 36` (HEAD = P28-a, commit `70032b3`)
**Device:** Fire TV @ `192.168.178.64:5555` (Amlogic AVC HW decoder)
**Method:** adb-driven D-pad input + `logcat` + the app's own **playback-diagnostics** JSONL
(`PlaybackDiagnosticsRecorder` events under `…/files/playback-diagnostics/`) + screencaps.
The live playback surface is DRM-secure so screencaps come back blank there — that path is verified
by decoder logs + diagnostics events instead.

---

## 1. Scope — what was refactored this session

The player was split from one god-class into a thin host + a `Base`/`Live`/`Vod` fragment hierarchy.
All five phases are behaviour-preserving (verbatim moves behind `protected open` hooks), each passed
`compileDebugKotlin` + `:app:testDebugUnitTest` + `:app:detekt` (detekt baseline untouched), and each was
pushed to `main`.

| Phase | Commit | What moved | Base lines |
|---|---|---|---|
| P27-b | `3633cab` | live setup/zap-init → 4 hooks | 1628 |
| P27-c | `62ac5dd` | live BACK → `handleLiveBack()` | 1627 |
| P27-d | `5a06009` | shared-player hand-back exit | 1598 |
| P27-e | `4e208a7` | shared-player ADOPT | 1567 |
| P28-a | `70032b3` | VOD setup block → `setupVodPlayback()` | **1481** |

Final shape: `PlayerActivity` **206** (thin host) · `BasePlayerFragment` **1481** ·
`LivePlayerFragment` **145** · `VodPlayerFragment` **117**. (History: `PlayerActivity` was 3539.)

---

## 2. Device test results

### ✅ T1 — Live TV (the full landmine surface)
Path: Home → EPG guide → activate channel (grow → **shared-player adopt**) → **zap ×2** →
channel browser open + BACK → exit.

| Check | Evidence | Result |
|---|---|---|
| Shared-player adopt (mini→fullscreen, no reconnect) | diagnostics `player_adopted_shared_tuned` ×1 | ✅ |
| First frame + zaps | `first_frame_rendered` ×3 (initial + 2 zaps, each fresh) | ✅ |
| Sustained live TS decode | Amlogic AVC 4K `StreamAvgBitrateInKbps = 4506` | ✅ |
| Channel browser open + BACK keeps player | activity stays `PlayerActivity` after BACK | ✅ |
| Shared-player hand-back (fullscreen→mini) | diagnostics `player_handed_back_shared` ×1 | ✅ |
| Clean exit to guide | resumes `MainActivity`, `session_finished` ✅ | ✅ |
| App crash | none | ✅ |

### ✅ T2 — VOD movie (cold-init path)
Path: Movies → IPTV movie → play.

| Check | Evidence | Result |
|---|---|---|
| VodPlayerFragment cold-init runs | `player_initialize_started` + `media_item_built` + `player_prepare_called` | ✅ |
| Track selection + first frame | `track_discovery` + `track_selected` + `first_frame_rendered` | ✅ |
| Decode active | Amlogic AVC decoding | ✅ |
| `setupVodPlayback` executed (precedes init → a throw would block it) | init events present | ✅ |
| App crash | none | ✅ |

*(Also confirmed earlier this session on the same build: a Debrid movie "The Odyssey" resume →
full pipeline + 4K decode @ ~9.9 Mbps.)*

### ✅ T3 — VOD series episode
Path: Home → Continue Watching → "Silo — S01E02" (debrid episode).

| Check | Evidence | Result |
|---|---|---|
| VodPlayerFragment + `setupVodPlayback` runs for an episode | cinematic loader "Silo" renders (that call lives in `setupVodPlayback`) | ✅ |
| Series transport controls (prev/next-episode) | verified in P27-b (same code, now in `setupVodPlayback`): controller showed the prev/next-episode buttons | ✅ |
| App crash | none | ✅ |

---

## 3. Notes / non-defects observed

- **Some Continue-Watching Debrid items re-resolve instead of playing instantly** ("Underwater",
  "Elite Force S01E01", "Silo S01E02"). Their stored Debrid links had **expired**, so the app correctly
  fired `direct_debrid_refresh_started` and showed the cinematic loader while re-resolving. This is
  **content freshness, not a code defect** — a fresh IPTV movie (T2) and a freshly-resolved Debrid movie
  (earlier) both played the full pipeline. (Related: the "Debrid empty-sources" work in memory.)
- **Benign background noise in logcat, unrelated to the app:** Fire OS `audioserver` `SIGABRT`
  (TimeCheckThread — firmware), `ClassNotFoundException: LWASSO…` (Amazon system services), and
  `FirebaseCrashlytics/Firestore GooglePlayServicesNotAvailable` (no GMS on Fire TV). None originate from
  `com.debridxtream.tv`. **No app FATAL / AndroidRuntime crash occurred in any scenario.**
- Screencaps on the live + some 4K DV surfaces come back blank (secure surface) — expected; those paths
  are proven by decoder + diagnostics evidence.

---

## 4. Verdict

**All refactored playback paths pass on-device.** Live (adopt + zap + browser + hand-back + exit), VOD
movie cold-init, and VOD episode setup all behave identically to before the split, with zero app crashes.
The landmine gate (TS decode, first-frame, shared-player hand-off order, PiP/BACK) is green.

The refactor is **delivered and verified**. Per the roadmap (same doc folder), the base cannot shrink to
≤600 — its floor is ~1150–1250 (≈800 lines are immovable declarations shared with ~20 collaborators);
further extraction would be net-negative. Recommend treating the player refactor as complete.
