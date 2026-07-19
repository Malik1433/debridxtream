# LOADING & SYNC PIPELINE — ULTRA-DEEP AUDIT (2026-07-19)

**Mode:** Diagnose-only, 4 parallel investigators (startup / sync engine / browse+DB / images+jank).
**Bar:** cold start → usable home <3s; cached content instant (SWR); category open <500ms; detail <1s; 60fps scroll on 1GB Fire TV.
**Verdict:** The app misses the <3s bar primarily due to **ST-1 + ST-2** (main-thread catalog parse + no cache-first home), amplified by launch-time network contention and un-indexed DB scans. 52 findings: **5 P0 · 13 P1 · 19 P2 · 15 P3**.

Numbering: ST=startup, SY=sync engine, B=browse/DB, IMG=images/jank. Full evidence lives in the per-lane agent outputs; this file is the consolidated index.

---

## P0 — The "loading issues" themselves

| ID | Finding | Where |
|----|---------|-------|
| **ST-1** | Full IPTV catalog JSON (multi-MB `iptv_cache.json`) Gson-parsed **on the MAIN thread** during Home load — seconds of freeze/ANR risk at the exact "loading" moment. `readCache()` has no dispatcher hop; two Home-path callers on Main. | HomeViewModel.kt:72,99 → XtreamRepository.kt:1495 → CacheHelper.kt:37-84 |
| **ST-2** | Home renders **nothing** until the whole pipeline finishes (2 sequential TMDB calls + per-item CW enrichment w/ 5s sub-timeouts + recent-live), single atomic emission, `withTimeoutOrNull(12s)`; **no skeleton**; on timeout hero+top10 dropped entirely. No stale-while-revalidate. | HomeViewModel.kt:131-237, HomeFragment.kt:245 |
| **IMG-1** | Global Glide `PREFER_ARGB_8888` + `disallowHardwareConfig()` — every poster 4B/px on Java heap, 2× memory vs RGB_565, hardware bitmaps forfeited. `largeHeap=true` masks it with longer GC pauses. | util/AppGlideModule.kt:40-41 |
| **IMG-2** | Debrid screens reload the **full-screen backdrop on every focus change** (8MB decode per D-pad step, 600ms crossfade, no debounce). VodFragment's correct 300ms debounce was never propagated. | DebridFragment.kt:166,474; DebridSeeAllActivity.kt:64,161; DebridDiscoverActivity.kt:126,491 |
| **IMG-3** | Fixed 32MB memory cache vs ~8MB/slide hero rotating every 6-7s → hero evicts all row posters continuously → posters visibly re-fade on scroll-back. | AppGlideModule.kt:33-34; HomeFragment.kt:76-79; StremioHeroManager.kt:56-64 |

## P1 — High impact

| ID | Finding | Where |
|----|---------|-------|
| ST-3 | `repository.initialize()` on main, runs twice per Home open, rebuilds Retrofit/OkHttp/disk-cache each time | MainActivity.kt:112; HomeFragment.kt:277; XtreamRetrofitClient.kt:31-38 |
| ST-4 | Xtream `login` network call on **every** HomeFragment view creation (expiry chip) — extra roundtrip, panel rate-limit risk | HomeFragment.kt:356-366 |
| ST-5 | Full home reload (whole ST-2 pipeline) on every `onResume` | HomeFragment.kt:209-212 |
| SY-1 | Initial sync: network failure coerced to empty → sync reports **SUCCESS with zero catalogs**, no auto-retry ("No categories found") | XtreamRepository.kt:304-336, 718-721 |
| SY-2 | Initial sync sequential chain, no per-stage timeout (raw 30s each) → up to ~90-180s stuck on sync screen | XtreamRepository.kt:304-335 |
| SY-3 | Series category fetch unbounded + dead-category fallback runs **full-catalog** `fetchAllSeries()` (3×30s strategies) on the interactive path | XtreamRepository.kt:1151, 2273-2315 |
| B-1 | **Zero indices** on channels / vod_v2 / series_v2 — every category open = full table scan (×2 on Live cache-miss) | ChannelEntity.kt:12, VodEntity.kt:11, SeriesEntity.kt:11 |
| B-2 | Sorted grid paging: `ORDER BY CAST(added AS INTEGER)` (never indexable) → every page = full scan + sort of the whole table on All Movies/Series | VodViewModel.kt:174-189; SeriesViewModel.kt:230-253 |
| B-3 | MOST_EPISODES chip: 2 correlated COUNT subqueries per row over both episode tables inside the PagingSource | SeriesViewModel.kt:246-250 |
| B-4 | `runBlocking { episodeDao.getCountForSeries() }` ×2 on main thread on every series detail open — exists only to feed a leftover debug Log.d | XtreamSeriesRepositoryV2.kt:408,428 |
| B-5 | Series grid episode-count refresh → `notifyDataSetChanged()` on view-create AND every onResume → full poster reload flash (VOD's payload-rebind fix never mirrored) | SeriesFragment.kt:1018-1030,166,1054 |
| IMG-4 | `transform(RoundedCorners)` **replaces** `transform(CenterCrop)` (Glide semantics) — main grid posters ship uncropped | GlideUtils.kt:276-288 |
| IMG-5 | `.thumbnail(0.25f)` on every card = double decode even on disk-cache hits | GlideUtils.kt:50,73,101,157 |
| IMG-6 | FocusGlint: disables clipping up the whole parent chain permanently + allocates a LinearGradient **per animation frame** + no-op alpha animators on every sibling per focus step | utils/FocusGlintHelper.kt:108-137,229-247 |
| IMG-7 | `resolveIconUrl`: regex compiled per call + log strings built per call, and runs **twice** per poster bind | GlobalConfig.kt:22-49; GlideUtils.kt:238-249 |

## P2 (19)

- **ST-6** 1MB Cloudflare speed test at launch (contends with everything) — MainActivity.kt:152. **ST-7** EPG XMLTV kick at onCreate overlaps first home load — MainActivity.kt:123. **ST-8** prefs first-touch + WatchHistory Gson parse/rewrite on main at launch — MainActivity.kt:65,89; WatchHistoryPreferences.kt:45-53. **ST-9** SpeechRecognizer built on main in onCreate — VoiceSearchManager.kt:50.
- **SY-4** launch network contention (index sync + EPG + first browse race on max_connections=1). **SY-5** full-dump index sync materializes 50k+ items in memory (heap spike). **SY-6** janitor pruning worker **never scheduled** — DB grows unbounded, RetentionPolicy dead. **SY-8** every category fetch rewrites entire legacy JSON cache file non-atomically (corruption risk + I/O amplification). **SY-9** per-category in-memory VOD cache unbounded (getMovieSources pulls up to 10 full categories).
- **B-6** `getSeriesEpisodeCounts` UNION full-scan on every Series-tab resume. **B-7** favorite toggle re-runs full category pipeline (network + delete/insert). **B-8** category open always delete+inserts the full category even when unchanged. **B-9** Live paging is fake (full category in memory) + un-debounced search query flow. **B-10** EPG search LIKE-scans ~289k rows. **B-11** movie-detail TMDB enrichment: 4 sequential searches, no timeout, no cache (mitigated: page interactive from args). **B-12** VodFragment watched/progress collectors lack `repeatOnLifecycle` — keep re-querying while backgrounded under the player.
- **IMG-9** failed poster URLs re-attempted on every rebind (no negative cache). **IMG-10** per-request anonymous RequestListener + log-string allocations. **IMG-13** no RecyclerViewPreloader on grids. **IMG-14** most grids don't Glide.clear() in onViewRecycled.

## P3 (15)

SY-7 search index insert-only (dead titles accumulate) · SY-10 no Room freshness gate per category (session-only) · SY-11 stale cache re-labelled fresh, no background revalidation · SY-12 sync progress % stage-lagged · ST-10 redundant readCache in collect condition · B-13 sibling LIKE scan per detail open · B-14 dead DAOs + superseded VodPagingAdapter · B-15 sidebar notifyDataSetChanged · B-16 series_v2_core unindexed (small today) · IMG-8 per-bind drawable allocations (Stremio rows) · IMG-11 Top10 wrong placeholder asset · IMG-12 GlideLifecycleObserver landmine (dead) · + minor logging/format churn items.

---

## Verified CLEAN (don't chase)

- Application.onCreate lean; licensing gate **non-blocking** (cached decision, async Firestore); UpdateManager async; windowBackground set (no white flash).
- Prior fixes intact: series detail 15s/10s timeout bounds + deferred warm; EPG generational replace + heap-pressure GC-and-continue.
- Sync-storm mutexes correct everywhere; WorkManager hygiene good; browse is DB-first (no blank-screen window on category replace).
- VOD grid: real Paging3 + payload rebinds + RecyclerView hygiene; global search debounced+capped; watched_state/epg tables indexed.
- Glide disk cache config sane; StremioFonts async; VodFragment backdrop debounce correct (the pattern to copy).

## Cross-cutting themes → fix phases

1. **Phase L1 — kill the P0s (target: home <3s, instant cached content):**
   ST-1 (IO-hop readCache), ST-2 (two-phase emission: cache-first + background enrich, parallel TMDB), IMG-1 (RGB_565/hardware bitmaps), IMG-2 (copy the 300ms debounce), IMG-3 (hero override ~960×540 + cache sizing).
2. **Phase L2 — DB & grid speed:** B-1/B-2 indices + `added` as INTEGER (migration), B-4 delete runBlocking logs, B-5 payload rebind mirror, IMG-4 combined transform, IMG-5 drop thumbnail(), IMG-6 glint fixes, SY-1/SY-2/SY-3 sync bounding + honest failure.
3. **Phase L3 — contention & hygiene:** ST-3..ST-9, SY-4..SY-9 (janitor schedule!, atomic cache write), B-6..B-12, IMG-7/9/10/13/14.
4. **Phase L4 — P3 sweep.**

**Regression guards for the fix phase:** don't break DB-first browse (no blank windows), the documented EPG/TS/zap protections, series 15s/10s bounds, or the payload-rebind pattern. Migrations must preserve data (indices are additive).
