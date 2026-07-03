# DIAG — Live Issue: Debrid CW slow resume

**Date:** 2026-06-12 · **Mode:** Diagnose Only · **Code changes:** NONE · **Temporary timing logs added:** NONE (all durations below are derived from hard constants in code; see table)

User report: resuming a Debrid item from Continue Watching takes very long to load before playback starts.

---

## 1. Exact code path (Home click → first frame)

Two variants, decided at click time. All slow work happens **after** PlayerActivity launches (spinner lives in PlayerActivity; Home side is negligible).

**Phase 0 — Home side (fast, <100 ms):**
1. `ui/home/HomeNavigationRouter.kt:181-209` — `onContinueWatchingItemClick` computes `isDebrid`, `hasResolutionInfo`, `canFreshResolveDirectDebrid`, `expired`; prefs read; detail screen skipped for debrid.
2. `HomeNavigationRouter.kt:224` — for direct-debrid items the saved `streamUrl` is **deliberately discarded** (`""`), forcing a fresh resolve (per Debrid Direct Resume Rule — by design).

**Phase 1 — PlayerActivity onCreate routing:**
3. `player/stabilized/PlayerActivity.kt:457-461` — `isExpired` is **forced true for every debrid CW resume** (no `expiresAt` extra → `isDebrid && (isDebridUrlMissing || isDebridHistoryResume)`). The fast path `initializePlayer(streamUrl)` at line 666 is unreachable for debrid resumes.
4. `PlayerActivity.kt:656-661` — fork: **Variant A** (direct-debrid) → `refreshDirectDebridSourceFromMetadata("expired_direct_resume")`; **Variant B** (RD torrent w/ infoHash) → `viewModel.reResolveDebridUrl(...)` with `isExpired = true` hard-coded (`PlayerViewModel.kt:728`).

**Phase 2A — Variant A: full source re-discovery (dominant everyday cost, est. 8–25 s):**
5. `PlayerViewModel.kt:455-507` → `UnifiedSourceProvider.getMovieSources` / `getSeriesEpisodeSources`, sequential await, under `withTimeout(30_000)` (`UnifiedSourceProvider.kt:69,142`).
6. `UnifiedSourceProvider.kt:152-163` (movie) — **TMDB "health check" `searchMovies("Inception")` runs sequentially before any source fetch**; result feeds only a log line. One wasted network RTT every resume.
7. `UnifiedSourceProvider.kt:472-487` (episode) — TMDB `getSeriesDetails` for TMDB→IMDb id mapping, sequential, uncached, every resume.
8. `UnifiedSourceProvider.kt:281-303 / 494-511` — configured Stremio addons fetched as a **sequential stage before** the parallel block (internally parallel, but serial w.r.t. Torrentio/MediaFusion/dynamic at lines 311-350 / 517-555).
9. `UnifiedSourceProvider.kt:412/576 → 1305-1330` — `verifyRealDebridCacheStatuses`: up to **10 hashes** (`MAX_CACHE_VERIFICATION_HASHES`, line 71), **serialized via `Semaphore(1)`** (line 1314), each behind `awaitPermit()` at **1200 ms** pacing (`RealDebridRateLimiter.kt:83`), capped by `withTimeout(20_000)` (line 1316). Worst case **~12 s of deliberate pacing** — on the resume critical path even though the continuity picker (`PlayerViewModel.kt:624-641`) only needs the one matching stream.
10. `PlayerViewModel.kt:553-599` — direct playable URL + `directPreferred` → instant Success passthrough; otherwise falls into the Phase 2B resolver chain.

**Phase 2B — Variant B: RD resolve chain (est. 4–8 s happy path; 60–96 s+ worst case):**
11. `PlaybackResolver.kt:61-106` — outer loop 2 attempts; terminal failures abort.
12. `DebridPlaybackRepository.kt:359-367` — **cooldown gates**: prior 429/AUTH → 120 s global/per-source lockout; NOT_CACHED → 5 min; LEGAL → 30 min (`RealDebridRateLimiter.kt:84-86`). Active cooldown = instant failure (fast error, not slow — opposite symptom, same subsystem).
13. `DebridPlaybackRepository.kt:369-393` — auth refresh (possible network) + `awaitPermit()` (≤1200 ms) + `addMagnet`.
14. `DebridPlaybackRepository.kt:396-460` — **poll loop**: 30 attempts × (`awaitPermit` ≥1200 ms + `getTorrentInfo` + `delay(2000)`) ≈ **60–96 s worst case**; episode-not-found triggers delete + re-add and **resets `attempts = 0`** once (lines 415-430), nearly doubling the budget. Even the cached happy path costs **~4–8 s of pacing alone**.
15. `DebridPlaybackRepository.kt:474-514` — `unrestrictLinkWithRetry`: 3 attempts × (1200 ms permit + network) + backoff 1 s/2 s ≈ 7–10 s worst case.

**Phase 3 — Player start (both variants):**
16. `PlayerActivity.kt:2489-2515` — resolution Success → `initializePlayer(url)`; `PlayerActivity.kt:1651-1733` — ExoPlayer prepare + seek; buffer watchdog 25 s / 35 s (MediaFusion) (`PlayerActivity.kt:273-274`). No readiness probe on the resume path.
17. Failure recovery (`PlayerActivity.kt:1905-1940, 2045-2072`) can re-enter the entire Phase 2 pipeline — a fresh-resolved URL that 403s restarts everything (compounding).

---

## 2. Which on-file findings explain the delay

| Candidate | Verdict | Mapped FIX |
|---|---|---|
| **P4-H1** (sequential CW enrichment vs 12 s home timeout) | **Not the click-to-frame delay.** Pre-click only: slows the CW row appearing on Home. Contributes only if the user's clock starts at app open. | FIX-P2-8 |
| **P3-H4** (AUTH_REQUIRED → 120 s cooldown loop) + Issue C chain (no `expiresAt` validation; 60 s poll with generic failure) | **Explains the "very long" outliers** (~2 min repeating stalls / 60 s dead waits) when token/link auth is broken — episodic, not the everyday case. | FIX-P2-19 |
| **P1-C2** (onResume rebuilds on stale `currentUrl` mid-resolve) | **Situational only** — requires backgrounding mid-resolve. Note: on the plain CW-click path the stale URL is never tried first (`isExpired` forced true), so no try-fail-double here. | FIX-P1-7 |
| P3-M6/M7/m2 | Correctness (wrong episode / position bleed / missing CW records), **not latency**. | FIX-P3-DB-4 |

**Dominant everyday cause = NEW, unmapped (see §3).** The by-design fresh-resolve (Debrid Direct Resume Rule) sets a latency floor, but the implementation inflates it 2–4×.

## 3. NEW unmapped causes (proposed finding ID: **P3-N1**, file `UnifiedSourceProvider.kt` + resume routing)

Every Debrid CW resume re-runs the **entire source-discovery pipeline** to re-pick a source the user already chose, with three pure latency adders on the critical path:

- **N1a** — TMDB health-check `searchMovies("Inception")` sequential before source fetch, log-only result (`UnifiedSourceProvider.kt:152-163`).
- **N1b** — Stremio addon stage runs serially **before** the Torrentio/MediaFusion/dynamic parallel block instead of inside it (`UnifiedSourceProvider.kt:281-303 / 494-511`).
- **N1c** — RD instant-availability verification of up to 10 hashes, fully serialized at 1.2 s/permit (~12 s), although resume continuity only needs the single matching source (`UnifiedSourceProvider.kt:1305-1330`).
- **N1d** (Variant B) — `isExpired` hard-forced true on every resume (`PlayerActivity.kt:457-461`, `PlayerViewModel.kt:728`) means even a still-valid stored RD link always pays the full add-magnet→poll→unrestrict chain (≥4–8 s pacing minimum). Largely mandated by the Direct Resume rules for *direct addon* URLs; whether RD-unrestricted links could carry a real `expiresAt` and skip the chain is a design question for the fix, not assumed here.

Estimated happy-path breakdown (Variant A movie, from constants): health-check RTT (~0.3–1 s) + Stremio stage (~1–3 s) + parallel provider fetch (~2–5 s, slowest-of) + serialized cache verification (~2–12 s) + optional resolver chain (~4–8 s) ⇒ **8–25 s before `initializePlayer`**, matching the user-perceived "very long".

## 4. Recommended fix order

1. **NEW P3-N1** (needs a new master-plan entry when picked) — trim the resume critical path: drop N1a, merge N1b into the parallel block, restrict N1c to the continuity-matched hash on resume. Biggest everyday win (est. 8–25 s → ~3–6 s). Must respect: Debrid Cache Confidence Rule, Debrid Direct Freshness Split Rule, resolver contract untouched.
2. **FIX-P2-19** (P3-H4 + Issue C) — kills the 120 s cooldown loops and 60 s generic-failure stalls (the worst outliers).
3. **FIX-P1-7** (P1-C2) — backgrounding-mid-resolve race; sequence before FIX-P2-9 per plan.
4. **FIX-P2-8** (P4-H1) — pre-click Home/CW-row latency; independent, can land any time after FIX-P2-4.

## Temporary timing logs to remove

None added. If a measured on-device confirmation is wanted before picking the fix, the cheapest probe points are: `PlayerActivity.kt:656` (fork entry), `UnifiedSourceProvider.kt:142` (fetch start), `:412/576` (cache-verify start), `PlayerViewModel.kt:553` (resolve start), `PlayerActivity.kt:2493` (Success observed) — elapsed-ms only, no URLs/tokens.

*Diagnosis only — no master plan changes yet, per task instructions.*

---

# Round 2 — On-device measurement after FIX-P1-12 (2026-06-12, measured from logcat)

FIX-P1-12 (health check removed, Stremio parallelized, priority-hash verification) shipped, but a Debrid episode CW resume still measured **18.1 s click-to-sources-ready** (Spider-Noir, 16:56:16–16:56:34):

| Segment | Measured | What ran |
|---|---|---|
| Click → fetch start | 0.9 s | Home routing + PlayerActivity launch |
| Provider fetch | 5.8 s | 728 sources (604 from one of 4 Stremio addons + 124 Torrentio) |
| Cache verify + conversion | **11.5 s** | **Full 10-hash RD verification ran — the FIX-P1-12 priority-hash shortcut did NOT engage** |

Control: a movie CW resume with only 3 sources (Momacu, 17:38:17) completed in 6.3 s — so the fix works when the source count is small; the episode case exposes the real defect.

## RC-1 (NEW, root cause of both "wrong/slow source" and the dead shortcut): unstable source identity

`UnifiedSourceProvider.convertAddonStreamsToMovieSources` (`UnifiedSourceProvider.kt:854-858`):

```kotlin
val streamId = if (mediaFusionUrl != null) {
    mediaFusionUrl.hashCode().toString() + "_$index"
} else {
    (validInfoHash ?: addonStream.title.hashCode().toString()) + "_$index"
}
```

`stream_id` — the identity saved into Continue Watching as `debridStreamId` and used by `pickDebridSourceForContinuity` for the exact match — has the **list position appended** (`_$index`), and for MediaFusion is a **hashCode of a volatile signed URL**. With 728 sources whose count and order change between fetches (log shows 102→101→100→83→61→60 for the same series), the same physical source almost never reappears at the same index. Consequences, all observed:

1. **Exact continuity match is a lottery** → resume falls back to fuzzy profile scoring → may pick a different source than last time (user-reported symptom: "should play from the same source as before").
2. **FIX-P1-12's priority shortcut never engages**: `priorityInfoHash` arrives as `<hash>_42`; `normalizeInfoHash` lowercases it but the `_42` suffix means it never equals a bare hash in `allHashes` → designed fallback runs the full 10-hash × 1.2 s-paced verification (**the measured 11.5 s**).
3. For direct-play Stremio sources the RD cache verification is wasted entirely — the source plays via direct URL passthrough, not RD.

## RC-2: full multi-provider discovery still mandatory on resume

Even with a stable identity, resume re-fetches all providers (5.8 s measured) to re-pick one known source. Bounded by the slowest of 4 Stremio addons. Architecture cost — the Debrid Direct Resume Rule mandates a fresh resolve, but only from the *saved source's own provider*, not all of them. Larger redesign; separate decision.

## RC-3 (NEW, needs caller ID): full re-fetch loop during playback

`getSeriesEpisodeSources` for tt14379784 fired **20+ times in ~9 minutes** (17:58:44–18:07:05, intervals 10–30 s), each a full provider fetch, intermittently re-paying ~10 s verification when the 60 s UNKNOWN TTL lapsed (gaps at 18:02:57→18:03:07, 18:06:03→18:06:13, 18:07:12→18:07:23). The only in-player caller is `loadNextDebridEpisode`, reached from `refreshDirectDebridSourceFromMetadata` (resume/stall/error recovery), auto-next, and episode-browser selection. The trigger reason is logged under tag `PlayerActivity` ("Direct Debrid refresh requested: <reason>") which the Round-1 capture filtered out — Round-2 capture now includes it. Suspects: playback-error/timeout retry loop re-entering full discovery, or episode browsing. **Do not fix until the reason codes are captured.**

## Recommended fix order (Round 2)

1. **RC-1a (small, surgical):** make continuity matching identity-stable without changing displayed IDs — in `pickDebridSourceForContinuity` and the `priorityInfoHash` path, strip the `_<index>` suffix and compare on the bare infoHash. Restores "same source as before" AND activates the 1-hash verification (11.5 s → ~1.2 s). Files: `PlayerViewModel.kt`, `UnifiedSourceProvider.kt`.
2. **RC-3:** reproduce once with the Round-2 capture, read the refresh reason codes, then map to the right fix entry.
3. **RC-2:** provider-scoped resume fetch — bigger design change, decide after 1+2 land.

---

# Round 3 — RC-1a shipped + verified; RC-3 identified; NEW CW-write bug (2026-06-12)

**RC-1a result (measured):** episode CW resume 18.1 s → **~1.1 s** (Spider-Noir, 18:34:15.451 click → 18:34:16.529 playback switch); movie resume **0.66 s** (Momacu). Same source re-picked. RC-1a is DONE (logged in RECENT_CHANGES.md).

**RC-3 identified:** refresh reason = `player_error_direct_refresh` (PlayerActivity), firing every ~20 s on a flaky direct source (18:23:39–18:25:16, 5 cycles). The playing URL repeatedly errors; each error re-runs full source discovery and "seamless switch" to a new URL that errors again. Proper fix direction: after N failures of the same source/family, fail over to the next ready source instead of re-resolving the same one; respects Debrid Auto-Skip Rule (terminal per-source failures only). Not yet mapped to a plan entry.

**NEW finding (CW-WRITE-1): new content never reaches Continue Watching.** Two mechanisms in `PlayerHistoryManager.kt`:
- **Flag trap (bug):** `recordPlaybackHistoryIfNeeded` set `hasRecordedHistory = true` even when `recordContinueWatchingHistory` bailed early (player null / duration unset / < 60 s) — any premature call (PiP, episode switch, BACK-exit ordering, error windows) permanently blocked the real onStop/onDestroy save for the session. Observed: `continueWatching size=2` constant across a session where new content was played (its playback was inside the RC-3 error loop, maximizing premature-call windows).
- **5% tiny-progress rule (too strict alone):** `MIN_PROGRESS_RATIO = 0.05` means a 2 h movie needs 6+ min watched before saving; below it the CW entry is actively REMOVED.

**Fix shipped (same day):** flag now latches only when a save/remove decision actually ran with valid duration (`recordContinueWatchingHistory` returns Boolean); added `MIN_PROGRESS_ABSOLUTE_MS = 90_000` floor — 90 s of absolute watch time qualifies regardless of ratio. Files: `PlayerHistoryManager.kt`.

**Remaining for "Stremio/Flix-level" resume:** RC-3 failover, then RC-2 provider-scoped resume fetch.
