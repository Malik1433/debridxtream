# Audit Master Fix Plan — Phases 1–6 Consolidation

**Date:** 2026-06-12  
**Mode:** Mapping Only — planning document, zero code changes made.  
**Sources:** AUDIT_PHASE1_PLAYER.md (25 findings) · AUDIT_PHASE2_LIVE.md (22) · AUDIT_PHASE3_DEBRID.md (21) · AUDIT_PHASE4_SERIES_VOD_CW.md (23) · AUDIT_PHASE5_DPAD_UI.md (~34) · AUDIT_PHASE6_APPWIDE.md (49).  
**ID convention:** `P<phase>-<finding>` (e.g., `P6-C1` = Phase 6 finding C1). Plan entries are `FIX-P0-1`, `FIX-P1-1`, batch `B-…`.  
**Modes:** *Simple Bug* = isolated, low regression surface. *Risky Bug* = shared-spine or multi-file. *Regression* = touches a protected behavior → mandatory device QA per the module report checklist.

**Global rules for all fix prompts (from DO_NOT_REPEAT / module reports):**
- Any fix marked **[TOUCHES PROTECTED]** must regress the named behavior on device: Live zapping (`zapRequestId` latest-zap-wins), Episode Browser current-series binding, Debrid playback/resolver contract, Continue Watching resume, Home focus/sidebar rules.
- PlayerActivity fixes are **in-place edits only** — extractions stay blocked (see Deferred D2).
- No two fix prompts may run in parallel if they touch the same file (see Conflict Map §6).
- Never log stream URLs, tokens, magnets, hashes, usernames, or passwords while fixing.

---

## 1. P0 — SECURITY / DATA (fix first, individually)

### FIX-P0-1 — P5-C1: Raw credentialed live stream URL logged via `Log.e` ✅ DONE 2026-06-12 (see RECENT_CHANGES.md)
- Recent Live click logs `item.streamUrl` raw (Xtream URLs embed username/password). Route through `SensitiveLogRedactor.describeUrl` like the adjacent CW handler, or delete the log.
- **Files:** `ui/home/HomeNavigationRouter.kt` (~line 412)
- **Protected:** No (log-only change; do not alter click routing) — but file is CW-protected territory: log-line-only edit.
- **Blast radius:** 1 file, 1 line. Isolated.
- **Deps:** None. Do first.
- **Mode:** Simple Bug.

### FIX-P0-2 — P5-H1: Server URL + username logged on Search open
- `initializeRepository()` logs `serverUrl=$serverUrl, username=$username` at debug level. Remove or redact.
- **Files:** `ui/search/SearchFragment.kt` (~line 158)
- **Protected:** No.
- **Blast radius:** 1 file, 1 line. Isolated.
- **Deps:** None. Note SearchFragment is also touched by FIX-P1-4 / FIX-P2-20 — do this log fix first, standalone.
- **Mode:** Simple Bug.

### FIX-P0-3 — P5-M11: Login toasts show raw exception messages
- `performLogin` toasts `exceptionOrNull()?.message` / `e.message` verbatim — transport exceptions can embed the credentialed request URL. Replace with stable user-facing messages (LOGIN_MODULE_REPORT rule).
- **Files:** `ui/LoginFragment.kt` (~lines 272–278)
- **Protected:** No.
- **Blast radius:** 1 file. Isolated.
- **Deps:** None.
- **Mode:** Simple Bug.

### FIX-P0-4 — P5 minor "Search error surfaces raw `e.message`" (+ same pattern in SettingsFragment catch toasts)
- `SearchViewModel.performSearch` catch renders `"Search failed: ${e.message}"` into the UI; same raw-exception pattern exists in Settings toasts (covered functionally under FIX-P3-UI-2's string fixes). Replace with generic message here.
- **Files:** `ui/search/SearchViewModel.kt` (~line 180)
- **Protected:** No. **Blast radius:** 1 file. **Deps:** None. **Mode:** Simple Bug.

**P0 security note carried into P2:** P6-M11's data-at-rest component (CacheInterceptor persists credentialed URLs into the HTTP disk cache index) is fixed in FIX-P2-6 — sequenced after FIX-P1-1 because both edit the same caching surface. P6-M1's per-image-bind server-address logging is fixed in FIX-P3-AW-1.

---

## 2. P1 — CRASH / OOM CRITICALS (individually, one prompt each, in this order)

Ordering rationale: hard crashes first (OOM, restart loop, cache corruption), then ANR/meltdown-class user impact, then correctness criticals, with higher-fix-risk protected items placed after the safe wins.

### FIX-P1-1 — P6-C1: Missing `@Streaming` — entire XMLTV buffered in heap (OOM kill)
- Add `@Streaming` to `getEpg`; bypass the HTTP cache for this endpoint (coordinate with FIX-P2-6).
- **Files:** `data/remote/XtreamApiService.kt` (~79–83); possibly a no-cache header/marker honored in `data/remote/interceptor/CacheInterceptor.kt`
- **Protected:** [TOUCHES PROTECTED] EPG sync pipeline (DO_NOT_REPEAT LiveTV EPG rules) → QA: one full EPG sync on a large XMLTV provider; verify guide populates and memory stays bounded.
- **Blast radius:** 1–2 files. Isolated annotation change; downstream chain already streams.
- **Deps:** Before FIX-P2-6 (CacheInterceptor) and before FIX-P2-5 (EPG sync reliability batch).
- **Mode:** Simple Bug.

### FIX-P1-2 — P6-C3: Crash-handler infinite restart loop
- `commit()` (not `apply()`) for the crash counter before process kill; replace the 15s reset (`P6-m1` GlobalScope block — removed as part of this fix) with timestamp-window crash counting; invoke the platform default handler so crash telemetry isn't swallowed.
- **Files:** `util/GlobalCrashHandler.kt` (~20–49), `MainApplication.kt` (~22–26)
- **Protected:** No.
- **Blast radius:** 2 files. Isolated subsystem.
- **Deps:** None. Absorbs P6-m1 (do not schedule separately).
- **Mode:** Simple Bug.

### FIX-P1-3 — P6-C2: New OkHttpClient + new 50MB Cache over the same directory per `initialize()`
- Make the Xtream client/cache a process singleton (rebuild only on base-URL change); `EpgSyncWorker` must use `ensureInitialized()` instead of `initialize()`.
- **Files:** `data/remote/XtreamRetrofitClient.kt` (~31–67), `data/repository/XtreamRepository.kt` (~162–183), `worker/EpgSyncWorker.kt` (~76)
- **Protected:** [TOUCHES PROTECTED] shared data spine (XtreamRepository init path feeds login, Home, Live, EPG) → QA: login, Home load, Live list, manual EPG sync.
- **Blast radius:** 3 files, shared spine. Medium risk.
- **Deps:** **Must land before** FIX-P2-12 (P3-H1 Stremio client reuse), FIX-P2-1 (Glide client consolidation part of P6-M2), and any P6-m5 pool review.
- **Mode:** Risky Bug.

### FIX-P1-4 — P5-C2: Search results have zero RecyclerView recycling
- Replace the `ScrollView` + four `wrap_content` grids with a recycling structure (single concatenated/sectioned RecyclerView, or fixed-height nested RVs) so large result sets don't inflate every ViewHolder.
- **Files:** `res/layout/fragment_search.xml`, `ui/search/SearchFragment.kt`
- **Protected:** No (but preserve Search→detail handoff and source-type identity per SEARCH_MODULE_REPORT).
- **Blast radius:** 2 files but a structural layout rework. Isolated to Search.
- **Deps:** Do **before** FIX-P2-20 (P5-H7 focus restore) and Search medium fixes — same files.
- **Mode:** Risky Bug.

### FIX-P1-5 — P2-C1: Per-channel EPG update triggers O(N²) snapshot rescans
- Replace `notifyEpgUpdated`'s full snapshot rescan with a channelKey→position index; bound warmup to the viewport.
- **Files:** `ui/live/LiveFragment.kt` (~482–548), `util/EpgCache.kt` (~110, 145)
- **Protected:** [TOUCHES PROTECTED] Live list/focus rules → QA: Live scroll, category switch, EPG text appears on visible rows.
- **Blast radius:** 2 files, Live module spine.
- **Deps:** Do **before** B-LV1/FIX-P2-7 (P2-H1+M8 — same files, same theme; the audit notes the index "dissolves C1/H1/M8 together," so FIX-P2-7 may collapse into this prompt if scope stays controlled).
- **Mode:** Risky Bug.

### FIX-P1-6 — P1-C1: All streams (incl. Live) unconditionally written to disk cache
- Gate `CacheDataSource` writes by content type (no disk-cache writes for Live; keep VOD/Debrid policy explicit).
- **Files:** `player/stabilized/PlayerActivity.kt` (`initializePlayer()` ~1684–1690)
- **Protected:** [TOUCHES PROTECTED] player spine — QA: full matrix per Player Rule 6 (IPTV Series, Debrid Series, Live TV, VOD, Debrid Movie) + zapping regression.
- **Blast radius:** 1 file but the shared player. High caution.
- **Deps:** Coordinate with FIX-P2-3 (PlayerCacheManager batch — same subsystem, different files). Do before FIX-P2-15 (P2-H3 zap-storm), which the audit says shares this root.
- **Mode:** Regression.

### FIX-P1-7 — P1-C2: onResume rebuilds player from stale `currentUrl`, racing in-flight Debrid resolution
- Guard onResume re-init against an in-flight resolution; do **not** modify the resolver contract (explicit audit constraint).
- **Files:** `player/stabilized/PlayerActivity.kt` (onResume ~2101, onStop ~2104, onCreate 656–667, observeDebridResolutionState 2489–2525)
- **Protected:** [TOUCHES PROTECTED] Debrid playback + Continue Watching → QA: Debrid movie/series start, home-button + resume mid-resolve, CW resume.
- **Blast radius:** 1 file, player spine. Highest-risk critical — scheduled after the isolated wins.
- **Deps:** Related to B-PL-LIFE (FIX-P2-9: P1-H1/H4/H5) — same file/theme; sequence FIX-P1-7 first, then the batch (or one combined Regression prompt if the fixer prefers — never parallel).
- **Mode:** Regression.

### FIX-P1-8 — P3-C1: TMDB "Inception" health-check fires on every movie source fetch
- Remove or cache the discarded health-check call.
- **Files:** `data/debrid/repository/UnifiedSourceProvider.kt` (~153–163)
- **Protected:** No.
- **Blast radius:** 1 file. Isolated.
- **Deps:** None (other UnifiedSourceProvider items — P3-H2/M1/M2/M9 — sequence after; same file).
- **Mode:** Simple Bug.

### FIX-P1-9 — P3-C2: `pickVideoLink()` size tiebreaker ascending — smallest file wins
- Flip the `thenBy` to descending size on score tie.
- **Files:** `data/debrid/repository/DebridPlaybackRepository.kt`
- **Protected:** [TOUCHES PROTECTED] Debrid file selection (Issue B chain; DO_NOT_REPEAT episode-continuity rule) → QA: Debrid movie + series episode resolve picks the expected file; CW resume.
- **Blast radius:** 1 file, 1 expression — but part of the Issue B compound chain. 
- **Deps:** Issue B closure also needs FIX-P3-DB-4 (P3-M6/M7/m2). DMM stays blocked until the full chain lands (Deferred D1).
- **Mode:** Regression.

### FIX-P1-10 — P4-C1: IPTV watched badges never show in in-player Episode Browser
- Replace `isDebridEpisode()` gating in the browser's internal adapter with the same `WatchedIdentityBuilder.iptvEpisode()` path `EpisodesAdapterV2` already uses.
- **Files:** `player/stabilized/EpisodeBrowserController.kt`
- **Protected:** [TOUCHES PROTECTED] Episode Browser (binding fix + DPAD ownership must not change) → QA: IPTV series badges, Debrid series badges, browser DPAD/BACK ownership, current-episode highlight.
- **Blast radius:** 1 file. 
- **Deps:** Do **before** FIX-P3-SV-5 (P4-M4/m2 — same file).
- **Mode:** Regression.

### FIX-P1-11 — P4-C2: `_selectedSeason` not persisted — process death resets to Season 1
- Back `_selectedSeason` with the already-injected `SavedStateHandle`.
- **Files:** `features/seriesv2/ui/SeriesDetailViewModelV2.kt`
- **Protected:** No (do not fall back to episode 0 — DO_NOT_REPEAT).
- **Blast radius:** 1 file. Isolated.
- **Deps:** Pairs naturally with FIX-P3-SV-4 (P4-M9 fragment-side state) — sequence, same module.
- **Mode:** Simple Bug.

---

## 3. P2 — HIGH-IMPACT PERFORMANCE / RELIABILITY

Batches only where same files + same root-cause theme; everything else individual.

### Batches

**FIX-P2-1 — B-GLIDE: Glide low-RAM configuration** — P6-H1 (ARGB_8888 + software bitmaps + fixed 32MB) + P6-M2 (500MB internal disk cache; second OkHttpClient) + P6-M3 (GlideUtils ignores DeviceProfile; GlideLifecycleObserver foot-gun)
- **Files:** `util/AppGlideModule.kt`, `util/GlideUtils.kt` (+ read `util/DeviceProfile.kt`)
- **Protected:** No. **Blast radius:** 2 files, app-wide visual surface → smoke-test posters on Home/VOD/Series/Live.
- **Deps:** OkHttp-client consolidation part **after** FIX-P1-3. 
- **Mode:** Risky Bug.

**FIX-P2-2 — B-CACHEHELPER: blocking 50MB Gson API** — P6-H6 (no dispatcher guard → main-thread ANR) + P6-H7 (static `inMemoryCache` heap pin) + P6-m2 (duplicate instance bypassing DI)
- **Files:** `data/cache/CacheHelper.kt`, `data/repository/XtreamRepository.kt` (caller dispatch + duplicate instance)
- **Protected:** [TOUCHES PROTECTED] shared data spine → QA: cold-start favorites click, VOD/Live by-id lookups.
- **Deps:** After FIX-P1-3 (same XtreamRepository region). **Mode:** Risky Bug.

**FIX-P2-3 — B-PLAYERCACHE: PlayerCacheManager lifecycle** — P6-H4 (Activity leak via `StandaloneDatabaseProvider(this)`) + P6-H5 (`releaseCache()` never called / cache-dir deletion breaks live cache) + P6-M16 (500MB budget, no storage check) + P6-m13 (main-thread first-use index load)
- **Files:** `util/PlayerCacheManager.kt`, `player/stabilized/PlayerActivity.kt` (call-site context + release wiring)
- **Protected:** [TOUCHES PROTECTED] player spine → full 5-source playback QA.
- **Deps:** Coordinate with FIX-P1-6 (same subsystem; sequence, never parallel). FIX-P3-UI-5 (P5-M10 settings cache delete) after this so `releaseCache()` exists to call.
- **Mode:** Regression.

**FIX-P2-4 — B-WHP: WatchHistoryPreferences pipeline** — P6-H13 (read = parse/heal/log/dedupe/maybe-write on calling thread) + P6-M4 (unsynchronized read-modify-write) + P6-m3 (hot-path `Log.e`, O(n·parse) `isFavorite`) + P4-M7 (static suppression-map growth)
- **Files:** `data/prefs/WatchHistoryPreferences.kt`
- **Protected:** [TOUCHES PROTECTED] Continue Watching storage → QA: CW save/resume/clear across IPTV+Debrid, favorites, recent live.
- **Deps:** Before FIX-P3-PL-3 (P1-M5/M6/Mi6 caller side) and FIX-P3-SV-1 (P4-M1 key collision). Phase 4 H2 caller fix rides in FIX-P2-8.
- **Mode:** Regression.

**FIX-P2-5 — B-EPGSYNC: EPG sync reliability** — P6-H8 (`clearAll()` before parse) + P6-H10 (`KEEP` ignores settings changes) + P6-M10 (`Result.success()` on failure kills backoff) + P6-M14 (periodic sync never scheduled outside Settings)
- **Files:** `data/repository/XtreamRepository.kt` (fetchAndSaveEpg), `worker/EpgSyncWorker.kt`, `worker/EpgSyncScheduler.kt`, `worker/EpgSyncController.kt` (+ one startup call site)
- **Protected:** [TOUCHES PROTECTED] DO_NOT_REPEAT EPG rules (single-flight must remain; no double sync paths) → QA: manual sync, interval change takes effect, failure does not wipe guide.
- **Deps:** After FIX-P1-1 and FIX-P1-3 (same files). P6-M12/m12 follow in FIX-P3-AW-5.
- **Mode:** Risky Bug.

**FIX-P2-6 — B-CACHEINT: CacheInterceptor correctness + data-at-rest** — P6-M11 (force-caches XMLTV + credentialed URLs `public`; empty-EPG cacheable; double `proceed()` on cancellation)
- **Files:** `data/remote/interceptor/CacheInterceptor.kt`
- **Protected:** [TOUCHES PROTECTED] "never cache empty EPG" rule. Security component (credentialed URLs at rest) — treat with P0 urgency once FIX-P1-1 lands.
- **Deps:** After FIX-P1-1. **Mode:** Simple Bug (1 file).

**FIX-P2-7 — B-LIVESCROLL: EPG-on-scroll coupling** — P2-H1 (bind-time EPG fetch storm) + P2-M8 (six hot-path `snapshot()` allocations)
- **Files:** `ui/live/ChannelPagingAdapter.kt`, `ui/live/LiveFragment.kt`, `util/EpgCache.kt`
- **Protected:** [TOUCHES PROTECTED] Live focus/zapping adjacency → Live scroll/zap QA.
- **Deps:** After FIX-P1-5 (same files/theme — audit says these dissolve together; may merge into one prompt).
- **Mode:** Risky Bug.

**FIX-P2-8 — B-HOMELOAD: Home load cost** — P4-H1 (sequential CW enrichment vs 12s timeout) + P4-H2 (SharedPreferences+Gson on Main) + P4-H3 (TMDB trending reload every onResume)
- **Files:** `ui/home/HomeViewModel.kt`, `ui/home/HomeFragment.kt` (onResume)
- **Protected:** [TOUCHES PROTECTED] Home rows/CW row → QA: cold Home load, CW posters, return-from-section refresh behavior.
- **Deps:** After FIX-P2-4 (WHP API may change). P4-M2 (artwork matching) follows in FIX-P3-SV-2 (same file — sequence).
- **Mode:** Risky Bug.

**FIX-P2-9 — B-PL-LIFE: Player lifecycle/recreation** — P1-H1 (full release/rebuild every onStop/onResume) + P1-H4 (untracked `postDelayed` runnables) + P1-H5 (collects without `repeatOnLifecycle`)
- **Files:** `player/stabilized/PlayerActivity.kt`
- **Protected:** [TOUCHES PROTECTED] Debrid playback (H5), zapping, player spine → full 5-source QA + zapping.
- **Deps:** After FIX-P1-7 (same file/theme). P1-M7/M8 follow in FIX-P3-PL-2.
- **Mode:** Regression.

**FIX-P2-10 — B-EPGPARSER: EpgParser hardening** — P2-H2 (per-programme Calendar/Regex allocation) + P2-M5 ≡ P6-H11 (detached scope swallows abort; partial sync = success) + P2-M6 (malformed timestamps → "now") + P6-H12 (MemoryManager callback leak per init)
- **Files:** `data/epg/EpgParser.kt` (+ callback dedup touchpoint in `XtreamRepository.initialize` / `utils/memory/MemoryManager.kt` registration)
- **Protected:** [TOUCHES PROTECTED] EPG pipeline → QA: full sync on large XMLTV; abort under pressure must report failure, not success.
- **Deps:** After FIX-P1-1. Retire dead `parse(String)`/`parseInputStream` here (closes P2-M7; also listed in dead-code batch — do once). P2-Mi5 (lateinit guard) rides along (same file, trivial).
- **Mode:** Risky Bug.

**FIX-P2-11 — B-HOMEFOCUS: initial/empty-row focus logic** — P5-H4 (clearing last item of focused row drops focus) + P5-H8 (initial focus targets bottom CW row → cold start scrolled past hero; **needs a product decision**: CW-first focus vs hero-first layout)
- **Files:** `ui/home/HomeFocusManager.kt` (+ possibly row order in `res/layout/fragment_home_cinematic.xml` if the decision goes layout-side)
- **Protected:** [TOUCHES PROTECTED] Home focus rules + CW clear flow → QA: cold focus, CW clear-last-item, sidebar return.
- **Deps:** P5-m2 (sidebar-RIGHT always consumed) rides along (same file/theme).
- **Mode:** Regression.

### Individual P2 items

| Entry | Finding | One-line | Files | Protected → QA | Blast | Deps | Mode |
|---|---|---|---|---|---|---|---|
| FIX-P2-12 | P3-H1 | Stremio manifest re-fetched every request; isolated OkHttpClient | `data/debrid/source/StremioAddonFetcher.kt` | No | 1 file | After FIX-P1-3 | Simple Bug |
| FIX-P2-13 | P3-H2 | Sequential registry loading blocks scrapers | `data/debrid/repository/UnifiedSourceProvider.kt` | No | 1 file | After FIX-P1-8 (same file) | Simple Bug |
| FIX-P2-14 | P1-H2 | Heavy synchronous main-thread work in `initializePlayer` | `player/stabilized/PlayerActivity.kt` | [PROTECTED] player spine → 5-source QA | 1 file, spine | After FIX-P2-3 (root partly in PlayerCacheManager) | Regression |
| FIX-P2-15 | P2-H3 | Held D-pad surf storms full player rebuilds (debounce) | `player/stabilized/PlayerActivity.kt` (dispatchKeyEvent/zap path) | [PROTECTED] Live zapping (`zapRequestId`) → zap QA mandatory | 1 file, spine | After FIX-P1-6 + FIX-P2-9 (shared root) | Regression |
| FIX-P2-16 | P1-H3 | STATE_ENDED always advances; guesses episode+1 at series end | `player/stabilized/PlayerActivity.kt` (~1766–1776, 1423–1450) | [PROTECTED] episode continuity (`hasNext` gate) → series-end QA both IPTV+Debrid | 1 file, spine | Before FIX-P3-PL-1 (P1-M1/M2 same theme) | Regression |
| FIX-P2-17 | P2-H4 | Browser category switch blocks list on full EPG enrichment | `player/stabilized/PlayerViewModel.kt` (~1095–1132) | No | 1 file | None | Simple Bug |
| FIX-P2-18 | P3-H3 | Debrid routing by label string; unknown providers fall to IPTV | `ui/vod/MovieDetailActivity.kt` | [PROTECTED] Debrid identity rules → Debrid movie launch QA | 1 file | Before FIX-P3-DB-2 (M3 same file) | Regression |
| FIX-P2-19 | P3-H4 | AUTH_REQUIRED → 120s cooldown loop instead of re-auth (Issue C) | `data/debrid/repository/RealDebridRateLimiter.kt`, `DebridPlaybackRepository.kt` (refreshAuthStateIfNeeded; include unnumbered Issue-C `expiresAt` validation in `PlaybackResolver.kt`) | [PROTECTED] Debrid error-handling rules → expired-token resume QA | 2–3 files | Upstream of Phase 1 PA-09-class symptoms; coordinate with FIX-P1-7 | Regression |
| FIX-P2-20 | P5-H7 | Search steals focus back to input on every view re-creation | `ui/search/SearchFragment.kt` | No | 1 file | After FIX-P1-4 (same files); P5-M12 rides along | Simple Bug |
| FIX-P2-21 | P3-H5 | `sanitize()` silently drops all direct HTTP URL streams | `data/debrid/repository/AddonCatalogRepository.kt` (~303) | [PROTECTED] Debrid direct-addon playback rule → direct-URL source QA | 1 file | None | Regression |
| FIX-P2-22 | P4-H4 | "Watch Now" plays first paged episode, ignores CW resume | `features/seriesv2/ui/SeriesDetailFragmentV2.kt` | [PROTECTED] CW series metadata rule → resume QA | 1 file | Before FIX-P3-SV-3 (same file) | Regression |
| FIX-P2-23 | P4-H5 | Any watched-key change fires full episode-list rebind | `features/seriesv2/ui/SeriesDetailFragmentV2.kt` (+ scoping query in VM/DAO) | [PROTECTED] watched-state flow → badge QA | 1–2 files | Sequence with FIX-P2-22 (same file) | Risky Bug |
| FIX-P2-24 | P5-H2 | Home focus memory lost on every section return; BACK builds new HomeFragment | `ui/home/HomeFocusManager.kt` (state relocation), `ui/home/HomeFragment.kt`, `ui/MainActivity.kt` (onBackPressed) | [PROTECTED] Home focus restoration → full Home focus QA checklist | 3 files, Home spine | After FIX-P2-11 (same files) | Regression |
| FIX-P2-25 | P5-H3 | Voice query always spawns duplicate SearchFragment (`return@forEach`) | `ui/MainActivity.kt` (~157–177) | No | 1 file | None | Simple Bug |
| FIX-P2-26 | P5-H5 | Hero backdrop Glide reload on every card focus (debounce ~250–300ms) | `ui/home/HomeHeroManager.kt` (+ caller wiring in HomeFragment adapters) | [PROTECTED] Home rows → hero/focus QA | 1–2 files | P5-M2 + P5-P4 follow in FIX-P3-UI-1 (same file) | Risky Bug |
| FIX-P2-27 | P5-H6 | Login disables all focusables; nothing refocused on failure | `ui/LoginFragment.kt` | No | 1 file | After FIX-P0-3 (same file) | Simple Bug |
| FIX-P2-28 | P6-H2 | Manifest: CAMERA implies required feature; MANAGE_EXTERNAL_STORAGE | `app/src/main/AndroidManifest.xml` | No | 1 file | None | Simple Bug |
| FIX-P2-29 | P6-H3 | MemoryManager: never-stopped 1Hz poll; LiveFragment leak; unsynchronized sets; `System.gc()` | `utils/memory/MemoryManager.kt`, `ui/live/LiveFragment.kt` (~334 unregister), `data/repository/XtreamRepository.kt` (start/stop wiring) | [TOUCHES PROTECTED] Live module → Live open/close ×5 leak check | 3 files | Sequence vs FIX-P1-5/P2-7 (LiveFragment) and FIX-P2-10 (callback registration) | Risky Bug |
| FIX-P2-30 | P6-H9 | Debrid auth refresh worker never scheduled; double refresh call in worker | `worker/DebridAuthRefreshScheduler.kt` (+ one startup/login call site), `worker/DebridAuthRefreshWorker.kt` (~39–44) | [PROTECTED] Debrid auth → token-refresh QA after idle | 2–3 files | None | Regression |

---

## 4. P3 — MEDIUM BATCHES (per module)

**Player (PlayerActivity & managers — all sequenced after P1/P2 player work, never parallel):**
- FIX-P3-PL-1 — P1-M1 + P1-M2 (auto-advance double-fire race; IPTV episode switch keeps stale `startPositionMs`). Files: `PlayerNextEpisodeManager.kt`, `PlayerActivity.kt`. [PROTECTED: episode continuity, Episode Browser]. Regression. Also absorbs P4-M8 (prompt won't re-show after seek-back — same file `PlayerNextEpisodeManager.kt`, same theme).
- FIX-P3-PL-2 — P1-M3 (Live single-strike stall policy) + P1-M7 + P1-M8 (onPause/resume play-state; raw `release()` bypass). Files: `PlayerActivity.kt`. [PROTECTED: player spine]. Regression.
- FIX-P3-PL-3 — P1-M5 + P1-M6 + P1-Mi6 (watched-write on dying scope; sync prefs writes on teardown; Live history re-record). Files: `player/stabilized/PlayerHistoryManager.kt`, `PlayerActivity.kt` (onDestroy). [PROTECTED: CW/watched-state]. Deps: after FIX-P2-4. Regression.
- FIX-P3-PL-4 — P1-M4 (index-based track persistence). Files: `player/stabilized/PlayerTrackManager.kt`. [PROTECTED: Debrid source-language rule]. Regression.

**Live:**
- FIX-P3-LV-1 — P2-M1 + P2-M2 (per-bind allocation churn; oversized RV caches). Files: `ChannelPagingViewHolder/Adapter`, `BrowserChannelAdapter.kt`, `LiveFragment.kt`. Deps: after FIX-P2-7. Risky Bug.
- FIX-P3-LV-2 — P2-M3 (empty-state flash guard; APP_FAILED_PATTERNS #2). Files: `LiveFragment.kt`. Simple Bug.
- FIX-P3-LV-3 — P2-M4 (sidebar DiffUtil instead of positional notify). Files: `SidebarCategoryAdapter.kt`, `LiveFragment.kt`. Simple Bug.
- FIX-P3-LV-4 — P2-M9 (+P2-P3 cosmetic facet) (EPG progress never ticks). Files: `ChannelPagingViewHolder.kt`, `PreviewPlayerPanel.kt`; overlay part in `PlayerActivity.renderOverlay` → sequence with player work. Risky Bug.

**Debrid:**
- FIX-P3-DB-1 — P3-M1 + P3-M2 + P3-M9 (availabilityCache unbounded; Semaphore(1) serialization; Log.e noise). Files: `UnifiedSourceProvider.kt`. Deps: after FIX-P2-13. Simple Bug.
- FIX-P3-DB-2 — P3-M3 (proxy classification single source of truth — fix in BOTH `DebridPlaybackRepository.kt` and the duplicated local copy in `MovieDetailActivity.kt`). [PROTECTED: Direct Proxy Readiness rule]. QA gate: self-hosted proxy manual QA remains open (Deferred D5). Regression.
- FIX-P3-DB-3 — P3-M4 + P3-M5 + P3-m1 (duplicate first-open fetch; uncancelled HEAD probes; no existing-sheet guard). Files: `MovieDetailActivity.kt`, `SeriesDetailActivity.kt`. Deps: after FIX-P2-18. Risky Bug.
- FIX-P3-DB-4 — P3-M6 + P3-M7 + P3-m2 (**Issue B closure batch**: episode context on history resume; year discriminator; base32 over-rejection). Files: `MovieDetailActivity.kt`, `player/stabilized/PlayerHistoryManager.kt`, `data/local/WatchedIdentityBuilder.kt`. [PROTECTED: Debrid CW resume]. Deps: with FIX-P1-9 completes Issue B → unblocks DMM (D1). Regression.
- FIX-P3-DB-5 — P3-M8 (parallelize movie/TV search). Files: `AddonCatalogRepository.kt`. Simple Bug.
- FIX-P3-DB-6 — P3-M10 (parallelize per-season TMDB fetch). Files: `SeriesDetailActivity.kt`. Simple Bug.

**Series/VOD/CW:**
- FIX-P3-SV-1 — P4-M1 (CW key collision Debrid seriesId vs tmdbId). Files: `WatchHistoryPreferences.kt` key derivation + `HomeNavigationRouter.kt`/`SeriesDetailActivity.kt` field population. [PROTECTED: CW identity]. Deps: after FIX-P2-4. Regression.
- FIX-P3-SV-2 — P4-M2 + P4-M3 (TMDB artwork year discrimination; stableId includes enriched title). Files: `HomeViewModel.kt`, `ContinueWatchingAdapter.kt`. Deps: after FIX-P2-8. Risky Bug.
- FIX-P3-SV-3 — P4-M5 + P4-M6 + P4-M9 (init collect never cancels; focus-restore race after submitData; focus/season not in onSaveInstanceState). Files: `SeriesDetailViewModelV2.kt`, `SeriesDetailFragmentV2.kt`. Deps: after FIX-P1-11/P2-22/P2-23. Risky Bug.
- FIX-P3-SV-4 — P4-M10 (Debrid source caches lost on process death). Files: `SeriesDetailActivity.kt`. Risky Bug.
- FIX-P3-SV-5 — P4-M4 + P4-m2 (double `notifyDataSetChanged` in `show()`; per-bind single-row DB reads). Files: `EpisodeBrowserController.kt`. [PROTECTED: Episode Browser]. Deps: after FIX-P1-10. Regression.
- FIX-P3-SV-6 — P4-M11 (long-press leaked `CoroutineScope`). Files: `EpisodesAdapterV2.kt`. Simple Bug.

**Home/Search/Settings/Login (UI):**
- FIX-P3-UI-1 — P5-M1 + P5-M2 (dual OnKeyListener ownership on row children; hero LEFT skips PLAY NOW — consolidate key routing to one owner). Files: `Top10Adapter.kt`, `HomeKeyRoutingManager.kt`, `HomeHeroManager.kt`. [PROTECTED: Home D-pad rules]. Deps: after FIX-P2-26. Regression.
- FIX-P3-UI-2 — P5-M4 + P5-M5 + P5-M6 (toggle stale capture/lambda DiffUtil; category highlight desync; escaped `\${}` toast strings). Files: `SettingsDetailAdapter.kt`, `SettingsFragment.kt`. Simple Bug.
- FIX-P3-UI-3 — P5-M7 + P6-M13 (speed-test gating + class-side cancellation/`use{}`/leak). Files: `MainActivity.kt`, `data/network/NetworkQualityManager.kt`. Simple Bug.
- FIX-P3-UI-4 — P5-M8 (sidebar width animation → overlay/translation approach). Files: `utils/SidebarFocusHelper.kt` (+ Home layout insets). [PROTECTED: sidebar expand/collapse behavior]. Regression.
- FIX-P3-UI-5 — P5-M10 (cache delete off-main; call `PlayerCacheManager.releaseCache()` first). Files: `SettingsFragment.kt`. Deps: **after FIX-P2-3**. Simple Bug.
- FIX-P3-UI-6 — P5-M9 (InitialSync unguarded commit; retry focus). Files: `ui/InitialSyncFragment.kt`. Simple Bug.
- FIX-P3-UI-7 — P5-M3 (search job cancellation / stale results). Files: `ui/search/SearchViewModel.kt`. Deps: after FIX-P0-4. Simple Bug.

**App-wide:**
- FIX-P3-AW-1 — P6-M1 (GlobalConfig `@Volatile` + remove per-bind logging incl. GlideUtils listener logging). Files: `util/GlobalConfig.kt`, `util/GlideUtils.kt`. Deps: sequence with FIX-P2-1 (GlideUtils). Simple Bug.
- FIX-P3-AW-2 — P6-M5 + P6-m4 (EncryptedSharedPreferences guarded creation + off-main first touch; cached token field; TorBox same pattern). Files: `data/prefs/DebridPreferences.kt`, `data/prefs/TorBoxPreferences.kt`. Risky Bug (Debrid auth QA).
- FIX-P3-AW-3 — P6-M6 (CredentialsPreferences: use the Hilt singleton; move migration off constructor). Files: `data/prefs/CredentialsPreferences.kt`, `data/prefs/IdentityPreferences.kt` (+ mechanical call-site swaps ~20 files — mechanical, low logic risk). Risky Bug (blast radius wide but mechanical).
- FIX-P3-AW-4 — P6-M7 (CacheManager realistic `sizeOf`) + P6-P2 (stale-as-fresh re-wrap — same file, take together). Files: `data/cache/CacheManager.kt`. Simple Bug.
- FIX-P3-AW-5 — P6-M12 + P6-m12 (rethrow CancellationException; don't stamp freshness on empty sync). Files: `XtreamRepository.kt`, `EpgSyncWorker.kt`. Deps: after FIX-P2-5. Simple Bug.
- FIX-P3-AW-6 — P6-M8 + P6-m7 (EpgCache: cap preload concurrency; in-flight dedupe). Files: `util/EpgCache.kt`. Deps: sequence vs FIX-P1-5/P2-7 (same file). Simple Bug.
- FIX-P3-AW-7 — P6-M9 + P6-m8 (VoiceSearchManager zombie singleton; BCP-47 language extra). Files: `util/VoiceSearchManager.kt`. Simple Bug.
- FIX-P3-AW-8 — P6-M15 (SeriesSyncWorker retry cap). Files: `worker/SeriesSyncWorker.kt`. Simple Bug.

---

## 5. P4 — MINOR / POLISH CLEANUP BATCHES

- **FIX-P4-1 — Dead-code removal (one batch):** P1-Mi1 + P1-Mi2 + P1-Mi3 (PlayerActivity dead methods/views/redundant prefs — in-place deletion only, no extraction); P2-Mi1 (dead Live adapters + unused field); EpgParser `parse(String)`/`parseInputStream` removal if not already done in FIX-P2-10 (closes P2-M7); P6-m6 (CacheManager dead path); P6-m9 (ContentEnricher — delete or precompile regexes); P6-m11 (EpgSyncScheduler `.get()` dead helpers); P5-m7 (Version dead action row). Sequence the PlayerActivity portion after all player fixes. Mode: Simple Bug.
- **FIX-P4-2 — Log-hygiene batch:** P1-P1 (hot-path key logging — verify SensitiveLogRedactor coverage); P2-Mi3 (EpgCache hit/miss per bind); P4-m4 (Log.e-as-debug across HomeViewModel/ContinueWatchingAdapter/SeriesDetailViewModelV2/WatchHistoryPreferences — remainder after FIX-P2-4); P5-m4 (Search keystroke spam + HomeFragment lifecycle Log.e). Mode: Simple Bug. Sequence after the functional fixes in the same files.
- **FIX-P4-3 — Small-UX minors batch (per file, no cross-module merging):** P1-Mi4 (audio fallback), P1-Mi5 (subtitle toast timeout), P2-Mi2 (favorites double-update), P2-Mi4 (EpgCache stale purge cadence), P4-m1 (favorites placeholder toast honesty), P4-m3 (imperative prefs in MovieDetailFragmentV2 — fold into FIX-P3-AW-3 call-site sweep), P5-m1 (voice keys swallowed when logged out), P5-m2b (voice query double-fire), P5-m3 (EPG result placeholder metadata), P5-m5 (px vs dp box stroke), P5-m6/m8-table (requestFocusOnFirstItem listener lifetime), P6-m5 (SettingsPreferences key growth cap), P6-m10 (NetworkMonitor `shareIn` before adoption + duplicate provider), P6-m13 already in FIX-P2-3, P4-P1 (VodFragment badge blink), P2-Mi5 (EpgParser lateinit guard — rides FIX-P2-10).
- **FIX-P4-4 — Polish batch:** P1-P3 (MIME heuristic), P1-P4 (retry backoff consistency), P2-P1 (shared UA constant), P2-P2 (precompute badges), P5-P1 (login entrance focus on invisible view), P5-P2 (search overscan margins — rides FIX-P1-4 layout rework), P5-P3 (sidebar animation stacking), P5-P4 (hero listener re-registration — rides FIX-P2-26), P6-P1 (PerformanceMonitor `update{}`), P6-P3 (FavoritesCache atomic pair), P6-P4 (manifest cleartext redundancy — rides FIX-P2-28).

---

## 6. CONFLICT MAP — same-file findings (sequence, never parallel)

| File | Plan entries touching it (fix in this order) |
|---|---|
| `player/stabilized/PlayerActivity.kt` | FIX-P1-6 → FIX-P1-7 → FIX-P2-9 → FIX-P2-14 → FIX-P2-15 → FIX-P2-16 → FIX-P2-3 (call-site) → FIX-P3-PL-1/2/3 → FIX-P3-LV-4 (overlay part) → FIX-P4-1/2 |
| `util/PlayerCacheManager.kt` | FIX-P2-3 only (single owner) — coordinate with FIX-P1-6 |
| `data/prefs/WatchHistoryPreferences.kt` | FIX-P2-4 → FIX-P3-SV-1 → FIX-P4-2 |
| `data/repository/XtreamRepository.kt` | FIX-P1-3 → FIX-P2-2 → FIX-P2-5 → FIX-P2-10 (init callback) → FIX-P2-29 (monitor wiring) → FIX-P3-AW-5 |
| `worker/EpgSyncWorker.kt` / `EpgSyncScheduler.kt` / `EpgSyncController.kt` | FIX-P1-3 → FIX-P2-5 → FIX-P3-AW-5 → FIX-P4-1 (m11) |
| `data/epg/EpgParser.kt` | FIX-P2-10 → FIX-P4-1 (String-path removal if deferred) |
| `data/remote/interceptor/CacheInterceptor.kt` | FIX-P1-1 (exclusion hook) → FIX-P2-6 |
| `ui/live/LiveFragment.kt` | FIX-P1-5 → FIX-P2-7 → FIX-P2-29 (callback unregister) → FIX-P3-LV-1/2/3 → FIX-P4-3 |
| `util/EpgCache.kt` | FIX-P1-5 → FIX-P2-7 → FIX-P3-AW-6 → FIX-P4-2/3 |
| `data/debrid/repository/UnifiedSourceProvider.kt` | FIX-P1-8 → FIX-P2-13 → FIX-P3-DB-1 |
| `data/debrid/repository/DebridPlaybackRepository.kt` | FIX-P1-9 → FIX-P2-19 → FIX-P3-DB-2 |
| `ui/vod/MovieDetailActivity.kt` | FIX-P2-18 → FIX-P3-DB-2 → FIX-P3-DB-3 → FIX-P3-DB-4 |
| `ui/series/SeriesDetailActivity.kt` | FIX-P3-DB-3 → FIX-P3-DB-6 → FIX-P3-SV-1 (field population) → FIX-P3-SV-4 |
| `features/seriesv2/ui/SeriesDetailFragmentV2.kt` | FIX-P2-22 → FIX-P2-23 → FIX-P3-SV-3 |
| `features/seriesv2/ui/SeriesDetailViewModelV2.kt` | FIX-P1-11 → FIX-P3-SV-3 |
| `player/stabilized/EpisodeBrowserController.kt` | FIX-P1-10 → FIX-P3-SV-5 |
| `player/stabilized/PlayerHistoryManager.kt` | FIX-P3-PL-3 → FIX-P3-DB-4 (M7) — or combine; never parallel |
| `player/stabilized/PlayerNextEpisodeManager.kt` | FIX-P3-PL-1 only (absorbs P4-M8) |
| `ui/home/HomeViewModel.kt` | FIX-P2-8 → FIX-P3-SV-2 → FIX-P4-2 |
| `ui/home/HomeFocusManager.kt` / `HomeFragment.kt` | FIX-P2-11 → FIX-P2-24 → FIX-P4-2 |
| `ui/home/HomeHeroManager.kt` / `Top10Adapter.kt` / `HomeKeyRoutingManager.kt` | FIX-P2-26 → FIX-P3-UI-1 → FIX-P4-4 |
| `ui/home/HomeNavigationRouter.kt` | FIX-P0-1 → FIX-P3-SV-1 (field population) |
| `ui/MainActivity.kt` | FIX-P2-25 → FIX-P2-24 (onBackPressed) → FIX-P3-UI-3 → FIX-P4-3 (m1) |
| `ui/search/SearchFragment.kt` + `fragment_search.xml` | FIX-P0-2 → FIX-P1-4 → FIX-P2-20 → FIX-P4-3 |
| `ui/search/SearchViewModel.kt` | FIX-P0-4 → FIX-P3-UI-7 |
| `ui/LoginFragment.kt` | FIX-P0-3 → FIX-P2-27 → FIX-P4-3/4 |
| `ui/settings/SettingsFragment.kt` | FIX-P3-UI-2 → FIX-P3-UI-5 |
| `util/AppGlideModule.kt` / `util/GlideUtils.kt` | FIX-P2-1 → FIX-P3-AW-1 |
| `utils/memory/MemoryManager.kt` | FIX-P2-29 → (registration API used by FIX-P2-10) — land FIX-P2-29 first or coordinate |
| `data/cache/CacheHelper.kt` | FIX-P2-2 only |
| `AndroidManifest.xml` | FIX-P2-28 (+P6-P4 rides) |

**Cross-phase duplicate:** P2-M5 and P6-H11 are the same defect (EpgParser detached scope) — fixed once in FIX-P2-10.

---

## 7. DEFERRED (explicitly not fixed now)

| ID | Item | Reason |
|---|---|---|
| D1 | **DMM implementation** | Explicitly blocked on Issue B (FIX-P1-9 + FIX-P3-DB-4) and Issue C (FIX-P2-19 + FIX-P1-7) landing **and passing device QA**. No DMM code exists; do not start until both chains are closed. |
| D2 | **PlayerActivity extractions/refactors** | Blocked per standing rule — all player fixes in this plan are in-place edits. Do not propose extraction in any fix prompt. |
| D3 | **P3-m3** — rate-limiter cooldown state in-memory only | Audit marks it explicitly acceptable. No action. |
| D4 | **P1-P2** — tunneling disabled / no handleAudioBecomingNoisy | Deliberate design choice; revisit only with dedicated device-QA bandwidth, not as a bug fix. |
| D5 | **Self-hosted addon proxy manual QA** (Phase 4 "readiness alignment" / acceptance gate for FIX-P3-DB-2) | Code fix is planned (FIX-P3-DB-2), but the readiness claim stays open until manual QA on a self-hosted proxy passes. |
| D6 | **P3-m4** — stale `DebridResolutionManager.kt` reference in DEBRID_MODULE_REPORT | Documentation-only; module reports are frozen until fixes land — correct it during the next DEBRID_MODULE_REPORT update. |
| D7 | **P6-m1** — MainApplication GlobalScope reset | Not a separate fix — absorbed into FIX-P1-2 by design. |
| D8 | **P5-H8 product decision** | The fix entry exists (FIX-P2-11) but requires a user decision first: keep CW-first focus and move the CW row up, or keep layout and make focus hero-first. Ask before implementing. |
| D9 | **GlideLifecycleObserver foot-gun** (part of P6-M3) | Zero call sites today; FIX-P2-1 should delete or guard it — listed here so it is not "wired up later" by mistake. |

---

## 8. COVERAGE CHECKLIST

Every finding from all six phases mapped exactly once (criticals/highs to a P-list entry; mediums/minors/polish to their batch; deferrals noted).

**Phase 1 (25):** C1→FIX-P1-6 · C2→FIX-P1-7 · H1→FIX-P2-9 · H2→FIX-P2-14 · H3→FIX-P2-16 · H4→FIX-P2-9 · H5→FIX-P2-9 · M1→FIX-P3-PL-1 · M2→FIX-P3-PL-1 · M3→FIX-P3-PL-2 · M4→FIX-P3-PL-4 · M5→FIX-P3-PL-3 · M6→FIX-P3-PL-3 · M7→FIX-P3-PL-2 · M8→FIX-P3-PL-2 · Mi1→FIX-P4-1 · Mi2→FIX-P4-1 · Mi3→FIX-P4-1 · Mi4→FIX-P4-3 · Mi5→FIX-P4-3 · Mi6→FIX-P3-PL-3 · P1→FIX-P4-2 · P2→D4 · P3→FIX-P4-4 · P4→FIX-P4-4 ✔

**Phase 2 (22):** C1→FIX-P1-5 · H1→FIX-P2-7 · H2→FIX-P2-10 · H3→FIX-P2-15 · H4→FIX-P2-17 · M1→FIX-P3-LV-1 · M2→FIX-P3-LV-1 · M3→FIX-P3-LV-2 · M4→FIX-P3-LV-3 · M5→FIX-P2-10 (≡P6-H11) · M6→FIX-P2-10 · M7→FIX-P2-10/FIX-P4-1 (dead-code removal; resolved by Phase 6 closure) · M8→FIX-P2-7 · M9→FIX-P3-LV-4 · Mi1→FIX-P4-1 · Mi2→FIX-P4-3 · Mi3→FIX-P4-2 · Mi4→FIX-P4-3 · Mi5→FIX-P2-10 (ride-along) · P1→FIX-P4-4 · P2→FIX-P4-4 · P3→FIX-P3-LV-4 (cosmetic facet) ✔

**Phase 3 (21):** C1→FIX-P1-8 · C2→FIX-P1-9 · H1→FIX-P2-12 · H2→FIX-P2-13 · H3→FIX-P2-18 · H4→FIX-P2-19 · H5→FIX-P2-21 · M1→FIX-P3-DB-1 · M2→FIX-P3-DB-1 · M3→FIX-P3-DB-2 · M4→FIX-P3-DB-3 · M5→FIX-P3-DB-3 · M6→FIX-P3-DB-4 · M7→FIX-P3-DB-4 · M8→FIX-P3-DB-5 · M9→FIX-P3-DB-1 · M10→FIX-P3-DB-6 · m1→FIX-P3-DB-3 · m2→FIX-P3-DB-4 · m3→D3 · m4→D6 ✔

**Phase 4 (23):** C1→FIX-P1-10 · C2→FIX-P1-11 · H1→FIX-P2-8 · H2→FIX-P2-8 · H3→FIX-P2-8 · H4→FIX-P2-22 · H5→FIX-P2-23 · M1→FIX-P3-SV-1 · M2→FIX-P3-SV-2 · M3→FIX-P3-SV-2 · M4→FIX-P3-SV-5 · M5→FIX-P3-SV-3 · M6→FIX-P3-SV-3 · M7→FIX-P2-4 · M8→FIX-P3-PL-1 · M9→FIX-P3-SV-3 · M10→FIX-P3-SV-4 · M11→FIX-P3-SV-6 · m1→FIX-P4-3 · m2→FIX-P3-SV-5 · m3→FIX-P3-AW-3 (call-site sweep) · m4→FIX-P4-2 · P1→FIX-P4-3 ✔

**Phase 5 (Criticals/Highs + table-and-detail minor union — the Phase 5 report has a known numbering drift among minors; both enumerations are covered):** C1→FIX-P0-1 · C2→FIX-P1-4 · H1→FIX-P0-2 · H2→FIX-P2-24 · H3→FIX-P2-25 · H4→FIX-P2-11 · H5→FIX-P2-26 · H6→FIX-P2-27 · H7→FIX-P2-20 · H8→FIX-P2-11 (+D8 decision) · M1→FIX-P3-UI-1 · M2→FIX-P3-UI-1 · M3→FIX-P3-UI-7 · M4→FIX-P3-UI-2 · M5→FIX-P3-UI-2 · M6→FIX-P3-UI-2 · M7→FIX-P3-UI-3 · M8→FIX-P3-UI-4 · M9→FIX-P3-UI-6 · M10→FIX-P3-UI-5 · M11→FIX-P0-3 · M12→FIX-P2-20 (ride-along) · m1(voice keys)→FIX-P4-3 · m2(sidebar-RIGHT consumed)→FIX-P2-11 (ride-along) · m2b/m3(voice double-fire)→FIX-P4-3 · m3/m4(EPG placeholder)→FIX-P4-3 · m4/m5(log spam)→FIX-P4-2 · m5/m6(px stroke)→FIX-P4-3 · m6/m8(focus-listener lifetime)→FIX-P4-3 · m7(Version row)→FIX-P4-1 · m8(raw e.message)→FIX-P0-4 · P1→FIX-P4-4 · P2→FIX-P4-4 (rides FIX-P1-4) · P3→FIX-P4-4 · P4→FIX-P4-4 (rides FIX-P2-26) ✔

**Phase 6 (49):** C1→FIX-P1-1 · C2→FIX-P1-3 · C3→FIX-P1-2 · H1→FIX-P2-1 · H2→FIX-P2-28 · H3→FIX-P2-29 · H4→FIX-P2-3 · H5→FIX-P2-3 · H6→FIX-P2-2 · H7→FIX-P2-2 · H8→FIX-P2-5 · H9→FIX-P2-30 · H10→FIX-P2-5 · H11→FIX-P2-10 (≡P2-M5) · H12→FIX-P2-10 · H13→FIX-P2-4 · M1→FIX-P3-AW-1 · M2→FIX-P2-1 · M3→FIX-P2-1 (+D9) · M4→FIX-P2-4 · M5→FIX-P3-AW-2 · M6→FIX-P3-AW-3 · M7→FIX-P3-AW-4 · M8→FIX-P3-AW-6 · M9→FIX-P3-AW-7 · M10→FIX-P2-5 · M11→FIX-P2-6 · M12→FIX-P3-AW-5 · M13→FIX-P3-UI-3 · M14→FIX-P2-5 · M15→FIX-P3-AW-8 · M16→FIX-P2-3 · m1→D7 (absorbed by FIX-P1-2) · m2→FIX-P2-2 · m3→FIX-P2-4 · m4→FIX-P3-AW-2 · m5→FIX-P4-3 · m6→FIX-P4-1 · m7→FIX-P3-AW-6 · m8→FIX-P3-AW-7 · m9→FIX-P4-1 · m10→FIX-P4-3 · m11→FIX-P4-1 · m12→FIX-P3-AW-5 · m13→FIX-P2-3 · P1→FIX-P4-4 · P2→FIX-P3-AW-4 (ride-along) · P3→FIX-P4-4 · P4→FIX-P4-4 (rides FIX-P2-28) ✔

**Critical/High audit:** 12 criticals → 1 in P0 (P5-C1) + 11 in P1 ✔. 40 highs → 1 in P0 (P5-H1) + 39 across P2 entries, with P6-H11 deduplicated against P2-M5 (one fix, FIX-P2-10) ✔. No Critical/High appears twice; none unassigned.

---

*Planning document only. No code was modified. Recommended execution: P0 (4 prompts) → P1 (11 prompts, in order) → P2 (batches first where they unblock: FIX-P2-3/4/5 early) → P3 per module → P4 sweeps. Always check the Conflict Map before launching any prompt in parallel with another.*
