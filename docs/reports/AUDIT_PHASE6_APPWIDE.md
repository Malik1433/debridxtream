# Phase 6 Audit — App-Wide: Memory Leaks, ANR Risk, Threading, Lifecycle, Startup

**Date:** 2026-06-12  
**Mode:** Diagnose Only — read-only, no code changes  
**Method:** 3-lane parallel swarm audit (Lane A: Application/DI/Glide/manifest · Lane B: prefs/cache/utility classes · Lane C: network layer/workers/receivers), lead-aggregated and deduplicated against Phases 1–5.  
**Scope:** `MainApplication`, Hilt modules, `AndroidManifest.xml`, Glide module, all `data/prefs` + `data/cache` classes, `PlayerCacheManager`, `EpgCache`, `VoiceSearchManager`, `GlobalConfig`/`GlobalCrashHandler`/`MemoryManager`/`PerformanceMonitor`, `NetworkMonitor`/`NetworkQualityManager`, Xtream Retrofit client + interceptors, all workers/schedulers.  
**Excluded (covered by prior phases):** Player internals (Phase 1), Live/EPG UI (Phase 2), Debrid resolver/auth internals (Phase 3), Series/VOD/CW module internals (Phase 4), Home/Search/Settings/Login UI (Phase 5).  
**Security:** No tokens, stream URLs, magnets, hashes, or credentials are reproduced in this report.

---

## Summary Table

| ID | Severity | Area | Short description |
|----|----------|------|-------------------|
| C1 | Critical | EPG network | `getEpg` missing `@Streaming` — Retrofit buffers the entire XMLTV document in heap before the "streaming" parser runs `[TOUCHES PROTECTED]` |
| C2 | Critical | Network clients | New `OkHttpClient` + new 50MB `okhttp3.Cache` on the **same directory** per `initialize()` call — DiskLruCache corruption risk; ~9–10 distinct OkHttp clients app-wide |
| C3 | Critical | Crash handling | `GlobalCrashHandler` restart loop: async counter write killed before flush + 15s counter reset → potential infinite restart cycle; platform crash reporting swallowed |
| H1 | High | Glide config | ARGB_8888 + `disallowHardwareConfig()` + fixed 32MB LRU; `DeviceProfile` low-RAM sizing exists but is never used by the image pipeline |
| H2 | High | Manifest | `CAMERA` permission without `uses-feature required="false"` implicitly filters the app off camera-less devices (every Fire TV); `MANAGE_EXTERNAL_STORAGE` also declared |
| H3 | High | MemoryManager | 1Hz polling loop started at login, never stopped; `LiveFragment` leaked via never-removed emergency callbacks; unsynchronized callback sets iterated cross-thread; `System.gc()` under pressure `[TOUCHES PROTECTED]` |
| H4 | High | PlayerCacheManager | Static `SimpleCache` retains the first PlayerActivity via `StandaloneDatabaseProvider(this)` — full Activity leak for process lifetime `[TOUCHES PROTECTED]` |
| H5 | High | PlayerCacheManager | `releaseCache()` has zero callers; cache-dir deletion (Settings clear-cache or OS storage purge) breaks the live locked cache with no recovery until process death `[TOUCHES PROTECTED]` |
| H6 | High | CacheHelper | Blocking Gson parse/serialize of up to 50MB exposed with no dispatcher guard; reachable from main-dispatcher suspend callers → direct ANR path |
| H7 | High | CacheHelper | Static `inMemoryCache` pins the entire parsed playlist object graph in heap indefinitely; no `onTrimMemory` hook, no TTL |
| H8 | High | EPG pipeline | `epgDao.clearAll()` runs **before** parse — any fetch/parse failure leaves the guide empty until the next full successful sync `[TOUCHES PROTECTED]` |
| H9 | High | Workers | `DebridAuthRefreshScheduler.schedule()` has zero call sites — background Debrid token refresh never happens; worker also double-fires the refresh call when run |
| H10 | High | Workers | `ExistingPeriodicWorkPolicy.KEEP` — user changes to EPG sync interval/constraints are silently ignored until auto-sync is toggled off/on `[TOUCHES PROTECTED]` |
| H11 | High | EpgParser | Detached `CoroutineScope(Dispatchers.IO).launch` + `join()` swallows all exceptions including the deliberate memory-pressure abort — partial sync reported as success; orphaned parse survives caller cancellation `[TOUCHES PROTECTED]` |
| H12 | High | EpgParser | `initialize()` registers a new MemoryManager pressure callback on every repository init (login + every worker run) — unbounded callback accumulation |
| H13 | High | WatchHistoryPreferences | Class-side closure of Phase 4 H2: every read is a synchronous load → Gson parse → per-item URL-heal with 2 log builds/item → dedupe → possible write-back-from-getter pipeline on the calling thread `[TOUCHES PROTECTED]` |
| M1 | Medium | GlobalConfig | Mutable global credentials without `@Volatile` — cross-thread visibility race → broken posters after process restore; 2 log lines per image bind including server address |
| M2 | Medium | Glide | 500MB image cache in **internal** storage on 8GB devices; `registerComponents` builds a second untracked OkHttpClient |
| M3 | Medium | GlideUtils | Hard-coded phone-class decode sizes (400×600) while `DeviceProfile` prescribes 320×480 for low-RAM; `GlideLifecycleObserver.onDestroy` foot-gun (destroys app-wide RequestManager if ever wired) |
| M4 | Medium | WatchHistoryPreferences | All mutations are unsynchronized read-modify-write over the whole JSON blob — concurrent player save vs. Home dedupe write-back loses data `[TOUCHES PROTECTED]` |
| M5 | Medium | DebridPreferences | EncryptedSharedPreferences first-touch Keystore cost (100–600ms) on whatever thread touches it first; `create()` can throw post-restore with no try/catch → hard crash until app data cleared |
| M6 | Medium | CredentialsPreferences | Constructor does migration write + `Settings.Secure` binder call on every construction; constructed ~20+ times on the main thread (Hilt singleton exists but is bypassed everywhere) |
| M7 | Medium | CacheManager | `LruCache.sizeOf` counts 1KB flat per list element — the "10MB" memory cache can really hold 30–50MB |
| M8 | Medium | EpgCache | `preloadEpgData` launches one uncapped coroutine per channel — floods Dispatchers.IO with hundreds of Room queries at Live-screen open |
| M9 | Medium | VoiceSearchManager | Zombie singleton: `destroy()` nulls the recognizer but not `INSTANCE` — voice search silently dead after first MainActivity destruction |
| M10 | Medium | EpgSyncWorker | All failure paths return `Result.success()` — configured exponential backoff is unreachable; transient failure = no guide for a full interval `[TOUCHES PROTECTED]` |
| M11 | Medium | CacheInterceptor | Forces `Cache-Control: public, max-age=300` on every response — pushes the XMLTV body through the 50MB disk cache, persists credentialed URLs in the cache index, makes empty EPG bodies servable for 5 min; catch block re-proceeds after cancellation `[TOUCHES PROTECTED]` |
| M12 | Medium | XtreamRepository | `fetchAndSaveEpg` catches `CancellationException` and converts it to `Result.Error` — breaks cooperative cancellation |
| M13 | Medium | NetworkQualityManager | Blocking `execute()` not cancellation-aware (runs to 60s timeout after caller cancels), response leaked on mid-stream exception, swallows `CancellationException` (class side; caller frequency = Phase 5 M7) |
| M14 | Medium | Workers | Periodic EPG sync is only ever scheduled from the Settings screen — fresh installs with default "auto-sync: on" never enqueue background sync at all `[TOUCHES PROTECTED]` |
| M15 | Medium | SeriesSyncWorker | Unconditional `Result.retry()` with no `runAttemptCount` cap — permanently failing series retries forever |
| M16 | Medium | PlayerCacheManager | Fixed 500MB media-cache budget in `cacheDir` on devices with often <1GB free — invites the OS storage purge that triggers H5 |
| m1 | Minor | MainApplication | `GlobalScope.launch` 15s crash-counter reset — structurally tolerable but is the load-bearing half of C3 |
| m2 | Minor | DI | `XtreamRepository` constructs its own `CacheHelper` bypassing the `@Singleton` provider — two instances |
| m3 | Minor | WatchHistoryPreferences | `Log.e` on a normal hot path; `isFavorite()` parses the full favorites JSON per call (O(n·parse) in list UIs) |
| m4 | Minor | Debrid/TorBox prefs | Token read = AES-GCM decrypt + Gson parse on every intercepted HTTP request; TorBox repeats the unguarded EncryptedSharedPreferences create pattern |
| m5 | Minor | SettingsPreferences | Per-content-hash track-selection keys grow unboundedly; prefs file held in RAM and rewritten whole on each apply |
| m6 | Minor | CacheManager | `getAllChannels` dead path with unchecked cast; key never populated |
| m7 | Minor | EpgCache | `shutdown()` has zero callers; `inFlightRefresh` check-then-act race allows duplicate refresh jobs (crash-free, duplicate work only) |
| m8 | Minor | VoiceSearchManager | `EXTRA_LANGUAGE` passed a `Locale` object instead of a BCP-47 String — language hint likely ignored |
| m9 | Minor | ContentEnricher | ~30 regexes compiled per call; currently zero callers (dead code) — flag before it gets wired into torrent-name parsing |
| m10 | Minor | NetworkMonitor | Cold `callbackFlow` (no `shareIn`) would register one system NetworkCallback per collector if ever adopted; currently zero collectors; duplicate `@Provides` alongside `@Inject` constructor |
| m11 | Minor | EpgSyncScheduler | `isSyncScheduled`/`getLastSyncTime` block with `ListenableFuture.get()` (dead code today); `getLastSyncTime` reads an output key the worker never writes |
| m12 | Minor | EpgSyncWorker | Writes `last_sync_time` freshness even for a 0-program success (DB-level self-heal in `ensureEpgData` contains the damage) |
| m13 | Minor | PlayerCacheManager | First `getCache` call on the main thread during player setup blocks on SimpleCache index load — worst-case jank after unclean shutdown |
| P1 | Polish | PerformanceMonitor | Non-atomic StateFlow read-modify-write (`_metrics.value = current.copy(...)`) — lost-update race; metrics wrong, not leaky |
| P2 | Polish | CacheManager | Expired Room rows re-wrapped with fresh 24h/7d TTLs — stale data masquerades as fresh for a full cycle |
| P3 | Polish | FavoritesCache | Two StateFlows set non-atomically in `updateCache` — momentary list/ID-set inconsistency |
| P4 | Polish | Manifest | `usesCleartextTraffic="true"` redundant alongside `networkSecurityConfig` (config wins on API 24+) |

---

## Critical Findings

### C1 — EPG Fetch Buffers the Entire XMLTV Document in Heap (Missing `@Streaming`) `[TOUCHES PROTECTED]`

**Files:** `data/remote/XtreamApiService.kt` ~79–83; `data/repository/XtreamRepository.kt` ~1617–1648

**Root cause:** `@GET("xmltv.php") suspend fun getEpg(...): Response<ResponseBody>` carries no `@Streaming` annotation. Retrofit therefore reads the complete response body into an in-memory okio buffer before returning. The downstream chain (`charStream().buffered()` → `EpgParser.parseStream`, a true XmlPullParser streaming parse with batched Room inserts) is genuinely streaming — but it streams from a buffer that already holds the whole document. App Failed Pattern #10 ("never pre-process huge XMLTV in memory") is violated one layer below the parser it was written to protect.

**Code path:** `EpgSyncWorker.doWork` → `XtreamRepository.fetchAndSaveEpg` → `apiService.getEpg(...)` → full-body buffer → streaming parse of in-memory data.

**Reproduction path:** Configure a provider whose XMLTV is 100MB+ (common for full-lineup providers) → trigger EPG sync (background worker or Settings → Sync EPG Now) → process OOM-kills on 1–1.5GB-heap Fire TV hardware. Background death is silent; foreground sync crashes the app. All the memory-pressure machinery inside `fetchAndSaveEpg`/`EpgParser` runs after the buffer already exists, so it cannot help.

**Fix direction (not applied):** add `@Streaming` to `getEpg` and exclude the endpoint from the HTTP cache (see M11).

---

### C2 — New OkHttpClient + New 50MB Cache on the Same Directory Per `initialize()`

**Files:** `data/remote/XtreamRetrofitClient.kt` ~31–67; `data/repository/XtreamRepository.kt` ~162–183; `worker/EpgSyncWorker.kt` ~76

**Root cause:** `XtreamRetrofitClient.create()` builds a fresh `OkHttpClient` and a fresh `Cache(File(cacheDir, "http_cache"), 50MB)` on every invocation. `XtreamRepository.initialize()` calls it unconditionally, and `EpgSyncWorker` calls `repository.initialize(...)` on **every run** instead of the existing `ensureInitialized()`. Login + each periodic worker run each mint a new client. OkHttp explicitly forbids two `Cache` instances over one directory — concurrent DiskLruCache journal access corrupts the cache and can crash. Each client also carries its own connection pool and dispatcher executor. Across the app there are ~9–10 distinct `OkHttpClient` builds (AppModule singleton, XtreamRetrofitClient×N, TMDB + TorBox clients in `data/debrid/di/DebridModule.kt` ~55–93, Glide's own client in `AppGlideModule` — see M2) where 1–2 shared clients with `newBuilder()` variants would suffice.

**Reproduction path:** Log in (client #1 over `http_cache`) → wait for a periodic EPG worker run (client #2 over the same directory) → both clients perform cached Xtream calls concurrently → DiskLruCache journal contention; corruption manifests as cache crashes/evictions under load.

**Risk:** crash/corruption plus multiplied thread + socket footprint on 1GB devices.

---

### C3 — Crash Handler Restart Loop With Broken Counter Persistence

**Files:** `util/GlobalCrashHandler.kt` ~20–49; `MainApplication.kt` ~22–26

**Root cause (three interacting defects):**
1. The crash counter is written with `apply()` (async) immediately before `Process.killProcess(...)` — the write can die with the process, so `startup_crash_count` may never increment and the `count >= 3` recovery gate may never trip. This must be `commit()`.
2. `MainApplication.onCreate` resets the counter to 0 after `delay(15000)` (via the app's only `GlobalScope.launch` — m1). Any deterministic crash occurring later than 15s after launch cycles: launch → reset → crash → count=1 → restart → reset → crash → … The threshold is never reached; `RecoveryActivity` is never offered; the app restarts **forever**.
3. Every uncaught exception unconditionally `startActivity(MainActivity, NEW_TASK|CLEAR_TASK)` then kills the process — the platform's default crash handler (and its telemetry) only runs on the fallback catch path, so crashes are swallowed to a single `Log.e`.

**Reproduction path:** Introduce any deterministic exception thrown >15s after cold start (e.g., on opening a specific detail screen) → infinite restart loop; the only escape is force-stop from system settings.

**Risk:** A restart loop repeatedly pays full cold-start cost (Hilt graph, Room open, Glide init) on low-RAM hardware — device appears frozen; crash telemetry is lost.

---

## High Findings

### H1 — Glide Configured for High-RAM Phones, Not Low-RAM TV

**File:** `util/AppGlideModule.kt` ~32–47

`PREFER_ARGB_8888` (2× memory per bitmap vs RGB_565) + `disallowHardwareConfig()` (all bitmaps on the Java heap instead of GPU-backed) + a hard-coded 32MB `LruResourceCache` regardless of device class. No `MemorySizeCalculator`, no `isLowRamDevice` branch, no bitmap-pool sizing. `DeviceProfile.kt` (~9–51) already implements low-RAM detection and smaller poster sizes — and the Glide module ignores it.

**Code path:** any poster grid → `GlideUtils.loadMoviePoster` → defaults from `applyOptions` → ARGB_8888 software bitmaps fill 32MB on devices whose `memoryClass` is ~112–160MB.

**Risk:** poster-heavy screens drive the process into the MemoryManager CRITICAL band (which responds with `System.gc()` — H3), then OOM kills on 1GB sticks.

### H2 — CAMERA Permission Implicitly Filters the App Off Fire TV

**File:** `AndroidManifest.xml` ~line 14

`<uses-permission android:name="android.permission.CAMERA"/>` with no `<uses-feature android:name="android.hardware.camera" android:required="false"/>` implicitly marks the camera feature **required**, filtering the app from camera-less devices — every Fire TV — in store device targeting. `RECORD_AUDIO` similarly implies microphone. `MANAGE_EXTERNAL_STORAGE` (~line 18, `tools:ignore="ScopedStorage"`) is a near-automatic store-review rejection and is not plausibly needed by an IPTV client.

**Reproduction path:** store-console device catalog check; sideloaded installs are unaffected, which is why this hasn't been observed locally.

### H3 — MemoryManager: Never-Stopped 1Hz Poll + Fragment-Leaking Callback Registry `[TOUCHES PROTECTED]`

**Files:** `utils/memory/MemoryManager.kt` ~56–60, 83–123, 226–248, 283, 332–337; `XtreamRepository.kt` ~176; `ui/live/LiveFragment.kt` ~334

1. `XtreamRepository.initialize()` → `memoryManager.startMonitoring()` → `while(isActive) { checkMemoryPressure(); delay(1000) }` on a detached `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `stopMonitoring()`/`cleanup()` have **zero external callers** — the loop wakes a core every second for process lifetime, including during 4K playback.
2. `LiveFragment` registers an anonymous `EmergencyCleanupCallback` capturing the fragment's views; `removeEmergencyCleanupCallback` is **never called anywhere**. Every Live-tab visit adds another retained, destroyed fragment view tree to the singleton's set — a leak that compounds the very pressure the class manages.
3. `cleanupCallbacks`/`emergencyCallbacks` are plain `mutableSetOf()` mutated on the UI thread and iterated on `Dispatchers.Default` → `ConcurrentModificationException` risk exactly at memory-critical moments; the shared `ActivityManager.MemoryInfo` instance is also written concurrently.
4. `triggerEmergencyCleanup` → `System.gc()` — explicit full GC at the worst moment; multi-frame stalls on low-end ARM.

**Reproduction path:** Login → open Live → back → open Live (repeat ×5) → heap-dump shows 5 retained LiveFragment view trees in `MemoryManager.emergencyCallbacks`; CPU profiler shows the steady 1Hz wake.

### H4 — PlayerCacheManager Pins the First PlayerActivity in Memory `[TOUCHES PROTECTED]`

**File:** `util/PlayerCacheManager.kt` ~20–29; caller `player/stabilized/PlayerActivity.kt` ~1685

`getCache(context)` is called as `getCache(this)` from PlayerActivity. Inside, `StandaloneDatabaseProvider(context)` (a `SQLiteOpenHelper`) stores that Activity context for its lifetime inside the never-released static `SimpleCache`. The first PlayerActivity ever opened is retained — view hierarchy, surfaces, adapters — for the rest of the process.

**Reproduction path:** Cold start → play anything → exit player → heap dump: PlayerActivity retained via `PlayerCacheManager.cache → StandaloneDatabaseProvider.context`.

**Fix direction:** use `context.applicationContext` inside `getCache` for both the cache dir and the database provider. (No change made — protected player spine; Phase 1 covers PlayerActivity internals, this is the utility-class side.)

### H5 — `releaseCache()` Never Called: Cache-Dir Deletion Breaks the Live Cache Unrecoverably `[TOUCHES PROTECTED]`

**File:** `util/PlayerCacheManager.kt` ~18, 23, 34–37; interaction with Phase 5 M10's caller (`SettingsFragment` deletes `cacheDir`) and routine Fire OS storage purges

The media cache lives in `cacheDir/media_cache`; `releaseCache()` has zero callers. When Settings clear-cache or the OS purges `cacheDir` (routine on Fire TV under storage pressure — which the 500MB budget, M16, invites), the directory is deleted under a live, locked SimpleCache: (a) the in-memory index references deleted files → `Cache.CacheException` churn during playback; (b) the static `cache` stays non-null so a healthy instance is never re-created until process death; (c) SimpleCache's static folder-lock registry still marks the path locked, so any future second construction path would throw `IllegalStateException` — the latent double-instantiation crash, currently gated only by the synchronized singleton being the sole constructor (verified: the only `SimpleCache(` in the codebase).

**Reproduction path:** Play a Debrid VOD → while playing, Settings → "Clear Cached TV Data" → Clear → observe cache write errors in logcat and degraded buffering; cache stays broken for every subsequent playback this process.

### H6 — CacheHelper: Blocking Up-to-50MB Gson API With No Dispatcher Guard

**File:** `data/cache/CacheHelper.kt` ~16–80, 102; callers `XtreamRepository.kt` ~1879–1899

`readCache()` parses a file allowed to reach 50MB (`MAX_CACHE_SIZE_KB = 51200`) synchronously on the calling thread; `writeCache()` serializes the full `IptvCache` the same way. No `withContext(IO)` inside the class, and repository callers like `getLiveStreamById`/`getVodById` are plain suspend functions that inherit the caller's dispatcher — for `viewModelScope` callers that is `Dispatchers.Main`.

**Reproduction path:** Cold start (in-memory snapshot empty) → click a favorited live channel → `getLiveStreamById` → `cacheHelper.readCache()` parses tens of MB of JSON on the main thread → ANR on Fire TV class CPUs.

### H7 — CacheHelper Static Snapshot Pins the Entire Parsed Playlist in Heap

**File:** `data/cache/CacheHelper.kt` ~24, 62, 105

`@Volatile private var inMemoryCache: IptvCache?` in the companion is set on every read/write and cleared only by explicit `clearCache`/`clearMemorySnapshot`. A 50MB JSON file parses to a substantially larger object graph (all live + VOD + series lists), held statically with no `onTrimMemory` hook and no TTL — and partially duplicated by `XtreamRepository`'s own `memoryCache` field (~1449, 1881–1882).

**Risk:** this single static can dominate the heap on 1GB devices and directly compounds H1's Glide pressure.

### H8 — `clearAll()` Before Parse: Failed Sync Wipes a Good Guide `[TOUCHES PROTECTED]`

**File:** `XtreamRepository.kt` ~1645 (within `fetchAndSaveEpg`)

The EPG table is cleared before streaming insert begins. If fetch/parse aborts (memory-pressure `CancellationException` at ~1656/1681, network drop, OOM), the catch paths (~1709–1720) return an error with the DB already emptied or partial.

**Reproduction path:** Start an EPG sync on a flaky connection → kill connectivity mid-parse → guide is empty until the next fully successful sync (6–24h away given M10/M14).

**Fix direction:** parse-then-swap, or rely on the existing `clearOldPrograms` pass (~1698) instead of pre-clearing.

### H9 — Debrid Auth Refresh Worker Is Never Scheduled

**Files:** `worker/DebridAuthRefreshScheduler.kt` (whole file), `worker/DebridAuthRefreshWorker.kt` ~39–44

Project-wide search finds zero call sites for `DebridAuthRefreshScheduler.schedule` — background token refresh never runs. Typical TV usage (app not opened for days) lets the Debrid session lapse, producing playback auth failures (refresh internals = Phase 3; the missing wire-up is the Phase 6 finding). Secondary defect: the worker's blank-token guard itself invokes `refreshAuthStateIfNeeded(...)` inside the condition and the `when` body calls it again — two network refresh attempts per run once it is ever scheduled.

### H10 — EPG Settings Changes Silently Ignored (`KEEP` Policy) `[TOUCHES PROTECTED]`

**Files:** `worker/EpgSyncScheduler.kt` ~57–61; `ui/settings/SettingsViewModel.kt` ~141–153

`enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)`: changing the sync interval or constraints keeps the originally enqueued request. The new cadence only applies after toggling auto-sync off (cancel) and on again.

**Reproduction path:** Settings → EPG Sync Interval: change 6h → 24h → inspect WorkManager (`adb shell dumpsys jobscheduler` or WorkManager inspector): the 6h periodic request is unchanged.

### H11 — EpgParser Detached Scope Swallows the Memory-Pressure Abort — Partial Sync Reported as Success `[TOUCHES PROTECTED]`

**File:** `data/epg/EpgParser.kt` ~90–202; interaction `XtreamRepository.kt` ~1656, 1681

`parseStream` runs in `parsingJob = CoroutineScope(Dispatchers.IO).launch { ... }` then `parsingJob?.join()`:
1. All exceptions — including the deliberate `CancellationException` the repository throws from `onProgram` to abort under memory pressure — are caught inside the launch (~185–192) and logged. `parseStream` returns the partial count normally; `fetchAndSaveEpg` inserts the final batch, writes `last_sync_time`, and returns `Result.Success(partialCount)`. **A memory-pressure abort is recorded as a successful sync.**
2. `parsingJob` is unsynchronized shared state on a singleton — a concurrent parse clobbers the reference, so the pressure callback cancels only the newest job.
3. Caller cancellation does not propagate into the detached scope — the parse keeps consuming CPU/heap after the worker is stopped.

**Reproduction path:** Sync a large XMLTV on a 1GB device until the pressure abort fires → Settings shows a fresh successful sync; the guide silently covers only part of the lineup.

### H12 — EpgParser Leaks a MemoryManager Callback Per Repository Init

**Files:** `EpgParser.kt` ~25–35; `XtreamRepository.kt` ~173; `MemoryManager.kt` ~226–228

`EpgParser.initialize()` calls `addMemoryPressureCallback` with no dedup and no removal; `XtreamRepository.initialize()` runs it on login **and** `EpgSyncWorker` re-initializes on every run (see C2). Identical anonymous callbacks accumulate for process lifetime and are iterated on every pressure event.

### H13 — WatchHistoryPreferences Read Pipeline (Class-Side Closure of Phase 4 H2) `[TOUCHES PROTECTED]`

**File:** `data/prefs/WatchHistoryPreferences.kt` ~19–68, 125–143

Every `getContinueWatchingList()` executes, on the calling thread: first-access synchronous load of the whole `watch_history` XML (which embeds the CW list, favorites, and recent-live as JSON strings) → Gson parse of the full list → a per-item `GlobalConfig.resolveIconUrl` healing pass with **two log-string builds per item** → `dedupeContinueWatching` → and, if dedupe shrank the list, a write-back from inside the getter (`toJson` + `apply()`). The class is also constructed ad-hoc at 6+ sites (fragment click handlers, an adapter bind path at `EpisodesAdapterV2.kt:94`, PlayerActivity, Settings) despite a Hilt provider existing at `AppModule.kt:73`. Caller-side main-thread usage is Phase 4 H2; this is the design that makes every caller pay.

---

## Medium Findings

### M1 — GlobalConfig: Non-Volatile Global Credentials + Per-Bind Logging
**File:** `util/GlobalConfig.kt` ~8–50. Plain `var baseUrl/username/password` written on the main thread (Login/MainActivity/Repository) and read on Glide model-loader threads via `resolveIconUrl` with no `@Volatile`/synchronization — a worker thread can see stale empty values → raw relative paths → broken posters until restart. Deterministic after process death + non-login restore (incl. C3's restart path): `GlobalConfig` reinitializes empty while saved state resumes a content screen. `resolveIconUrl` also executes two `Log.d/w` calls per image bind including the server address (200+ lines per poster grid); `GlideUtils.addErrorListener` logs full model URLs. It does **not** retain Context/Activity/View — credentials only.

### M2 — Glide: 500MB Internal Disk Cache + Second Untracked OkHttpClient
**File:** `util/AppGlideModule.kt` ~36–37, 54–72. `InternalCacheDiskCacheFactory(..., 500MB)` in internal storage on 8GB-total devices materially contributes to Fire OS low-storage pressure (which also triggers H5). `registerComponents` builds its own `OkHttpClient` separate from the DI singleton — extra dispatcher/pool footprint (counts toward C2's client inventory).

### M3 — GlideUtils Ignores DeviceProfile; GlideLifecycleObserver Foot-Gun
**Files:** `util/GlideUtils.kt` ~30–33, 171, 312–325; `DeviceProfile.kt` ~42–50. Hard-coded 300/400×600 sizes while `DeviceProfile.posterSize()` etc. prescribe 220/320×480 for low-RAM — the low-RAM work exists but is dead in the poster path (≈2.3× the prescribed pixel count). `GlideLifecycleObserver.onDestroy` manually calls `Glide.with(context).onDestroy()` — if ever wired with an application context it destroys the app-wide RequestManager (`IllegalStateException` on all subsequent loads). Zero current call sites — flagged so it is not wired up later. `clearCache` spawns a raw untracked `Thread {}`.

### M4 — WatchHistoryPreferences: Unsynchronized Read-Modify-Write `[TOUCHES PROTECTED]`
**File:** `data/prefs/WatchHistoryPreferences.kt` ~19–68, 159–179, 202–218. `saveContinueWatchingItem`, both `removeContinueWatchingItem` overloads, favorites and recent-live mutations all do get → parse → mutate → serialize → `apply()` with no lock, from PlayerActivity, fragments, and adapters concurrently. Two interleaved writers last-write-wins on the whole JSON blob. **Reproduction:** pause playback (player saves CW) at the moment Home's `getContinueWatchingList()` performs its dedupe write-back — one write is lost (lost progress or resurrected deleted item).

### M5 — DebridPreferences: Keystore First-Touch Cost + Uncaught Create Failure
**File:** `data/prefs/DebridPreferences.kt` ~29–41. The `by lazy` EncryptedSharedPreferences creation performs MasterKey/Keystore work (100–600ms on low-end Fire TV firmware) on whichever thread touches it first — often main (`getPlayerEngine()` during player setup). `EncryptedSharedPreferences.create` can throw (`KeyStoreException`/`AEADBadTagException` after backup-restore or keystore corruption) with no try/catch → hard crash at first Debrid access, unrecoverable without clearing app data.

### M6 — CredentialsPreferences: Constructor I/O ×20+ Main-Thread Construction Sites
**Files:** `data/prefs/CredentialsPreferences.kt` ~10–15; `data/prefs/IdentityPreferences.kt` ~13–77. Every construction runs two pref reads, an unconditional `migrateFromLegacy` `apply()`, `getOrCreateInstallInstanceId()`, and `ensureDeviceSignalHash()` → `Settings.Secure.getString` (a binder round-trip) + possible SHA-256 + write. Constructed imperatively at 20+ sites (MainActivity, HomeFragment, SearchFragment, LiveFragment, detail activities, ViewModels) — overwhelmingly on the main thread — while a Hilt `@Singleton` provider (`AppModule.kt:55`) is bypassed everywhere.

### M7 — CacheManager: Fictional 10MB Budget
**File:** `data/cache/CacheManager.kt` ~47–55. `sizeOf` counts 1KB flat per element; real retained size of an `XtreamStream` is multiple KB — the "10MB" LruCache can hold 30–50MB. Eviction works; the bound is miscalibrated for low-RAM TV.

### M8 — EpgCache: Uncapped Per-Channel Preload Fan-Out
**File:** `util/EpgCache.kt` ~130–157; caller `LiveFragment.kt:876`. `channelIds.forEach { launch { ... } }` floods `Dispatchers.IO` with one coroutine + Room query per channel — hundreds simultaneous at Live-screen open on large lineups; memory/DB-contention spike exactly at screen-open time.

### M9 — VoiceSearchManager: Zombie Singleton After First MainActivity Destruction
**File:** `util/VoiceSearchManager.kt` ~38, 50–141, 160, 185–191. `getInstance` correctly stores `applicationContext` (no Activity leak) and `destroy()` is called from MainActivity.onDestroy — but `INSTANCE` is not cleared and the recognizer is only created in `init`. After MainActivity recreation (back-out + relaunch without process death), `getInstance` returns the gutted singleton; `startVoiceSearch` hits `speechRecognizer ?: return false` and voice search silently fails forever. **Reproduction:** open app → BACK until exit (Activity destroyed, process alive) → relaunch from launcher → press mic key: nothing.

### M10 — EpgSyncWorker Returns `success()` on Every Failure `[TOUCHES PROTECTED]`
**File:** `worker/EpgSyncWorker.kt` ~109–136. All failure paths return `Result.success()` (comment: avoid retry spam), so the exponential backoff configured in `EpgSyncScheduler` (~50–53) is unreachable. A transient failure means no guide refresh until the next full interval (6–24h). `Result.retry()` with the existing backoff is the spam-safe mechanism.

### M11 — CacheInterceptor Force-Caches Everything `[TOUCHES PROTECTED]`
**File:** `data/remote/interceptor/CacheInterceptor.kt` ~64–85. Strips `Pragma` and overwrites `Cache-Control: public, max-age=300` on every successful Xtream response: (a) the giant XMLTV body is pushed through the 50MB disk cache → pure eviction churn + flash I/O each sync; (b) Xtream URLs carry account credentials as query parameters — forcing `public` cacheability persists those URLs in the disk-cache index (data-at-rest exposure; not quoted here); (c) a successful-but-empty EPG body becomes servable for 5 minutes (brushes DO_NOT_REPEAT "never cache empty EPG"; the DB-level `totalPrograms == 0` self-heal contains it, but a forced refresh within 5 min can be fed the cached empty body); (d) the catch block calls `chain.proceed()` a second time even after cancellation-induced IOExceptions.

### M12 — `fetchAndSaveEpg` Converts `CancellationException` to `Result.Error`
**File:** `XtreamRepository.kt` ~1709–1712. Catching `CancellationException` without rethrow breaks cooperative cancellation — a cancelled `viewModelScope` caller (e.g., `EpgViewModel.fetchEpg` ~32) continues into result handling instead of unwinding.

### M13 — NetworkQualityManager: Non-Cancellable Blocking Call + Response Leak
**File:** `data/network/NetworkQualityManager.kt` ~33–84. Downloads a fixed 1MB per invocation via blocking `newCall(...).execute()` inside `withContext(IO)` — OkHttp `execute()` ignores coroutine cancellation, so a cancelled caller leaves the download running up to the 60s read timeout at the worst time (app startup). If `readAll` throws mid-stream the response is never closed (no `use {}`) → leaked pool connection. `catch (e: Exception)` swallows `CancellationException` → `UNKNOWN`. (Caller frequency is Phase 5 M7; this is the class-side design.)

### M14 — Periodic EPG Sync Only Scheduled From the Settings Screen `[TOUCHES PROTECTED]`
**Files:** `worker/EpgSyncController.kt`; sole call sites `SettingsViewModel.kt` ~145, 152. No Application/startup/login path schedules the periodic sync. Default pref is `enabled=true`, but a fresh install that never visits EPG settings never enqueues the work — Settings shows "Auto EPG Sync: on" while no background sync exists. (Verified intact: repo-level single-flight via `epgSyncMutex` ~1597 — DO_NOT_REPEAT satisfied; periodic + immediate unique names can still queue a back-to-back double full fetch, each paying H8's clear + full download.)

### M15 — SeriesSyncWorker Retries Forever
**Files:** `worker/SeriesSyncWorker.kt` ~20–30; `worker/SeriesSyncScheduler.kt`. Any error → `Result.retry()` with no `runAttemptCount` cap: a permanently failing series (deleted upstream, 404) re-downloads on schedule indefinitely while CONNECTED. Per-series unique names with `KEEP` are correct (no duplicate storms) — impact bounded per-run, unbounded in time.

### M16 — PlayerCacheManager 500MB Budget on Low-Storage Devices `[TOUCHES PROTECTED]`
**File:** `util/PlayerCacheManager.kt` ~18. `MAX_CACHE_SIZE = 500MB` in `cacheDir` with no storage-availability check. Fire TV sticks commonly have <1GB free — the budget practically guarantees the OS low-storage cache purge fires mid-playback, triggering H5's unrecoverable-stale-cache failure mode.

---

## Minor Findings

- **m1 — MainApplication GlobalScope** (`MainApplication.kt` ~22–26): the app's only `GlobalScope.launch` (grep-verified); 15s delayed crash-counter reset. Structurally tolerable as process-lifetime work, but it is the load-bearing half of C3 and should be removed/redesigned with it (timestamp-based crash windows). `onCreate` is otherwise admirably lean; WorkManager default initializer is correctly removed in the manifest with on-demand `Configuration.Provider`.
- **m2 — Duplicate CacheHelper** (`AppModule.kt` ~44–48; `XtreamRepository.kt` ~128): repository constructs `CacheHelper(context)` itself, bypassing the `@Singleton` provider — two instances; in-memory snapshot state (H7) is shared only because it's static.
- **m3 — WatchHistoryPreferences hot-path logging** (`~221`): `Log.e` for the normal `getRecentLiveChannelsList` path; `isFavorite()` (~191–193) parses the entire favorites JSON for one boolean — O(n·parse) when called per-item in list UIs.
- **m4 — Per-request token decrypt** (`DebridPreferences.kt` ~43–58): `getAccessToken()` does an AES-GCM decrypt + Gson parse on **every** intercepted HTTP request (it is the auth interceptor's token provider); during scraping that is dozens of parallel crypto/parse cycles. `TorBoxPreferences` (~14–26) repeats the unguarded EncryptedSharedPreferences creation pattern (see M5).
- **m5 — SettingsPreferences unbounded keys** (~63–78): `last_audio_idx_<hash>` / `series_audio_idx_<id>` keys are created per content ever played and never deleted; the whole prefs file lives in RAM and is rewritten on each `apply()`.
- **m6 — CacheManager dead path** (~221–242): `getAllChannels` reads a key no code ever writes, with an unchecked cast that would mis-cast if it ever were populated.
- **m7 — EpgCache lifecycle** (~30, 40, 82–124, 209–213): `shutdown()` has zero callers; `cacheScope` lives forever (acceptable for a process-lifetime cache, but a hung repository call holds its Job indefinitely); `inFlightRefresh` check-then-act race permits duplicate refresh jobs (duplicate work only — `ConcurrentHashMap` keeps it crash-free). Static `repository` reference verified application-scoped — no Activity leak.
- **m8 — VoiceSearchManager locale extra** (~72): `EXTRA_LANGUAGE` passed a `Locale` object where recognizers expect a BCP-47 String — hint likely ignored (device default usually coincides).
- **m9 — ContentEnricher** (~60–71): ~30 `Regex` instances compiled per `parse()` call; currently **zero callers** (dead code). Flagged so the per-call compilation is fixed before it gets wired into torrent-name parsing (hundreds of names per source query).
- **m10 — NetworkMonitor** (~45–96): `callbackFlow` correctly unregisters in `awaitClose` — no leak today and zero collectors (verified: no connectivity-triggered retry storms exist app-wide). But the flow is cold; each future collector would register its own system `NetworkCallback` (Android caps at 100 → `TooManyRequestsException`). Needs `shareIn` before adoption. Duplicate `@Provides` (`AppModule.kt` ~225) alongside the `@Inject` constructor — harmless redundancy.
- **m11 — EpgSyncScheduler blockers** (~108–126): `isSyncScheduled`/`getLastSyncTime` use `ListenableFuture.get()` (ANR if ever called from main; currently dead code). `getLastSyncTime` reads output key `sync_time` which the worker never writes — would always return 0.
- **m12 — Freshness on empty sync** (`EpgSyncWorker.kt` ~96–105): `last_sync_time` written even for a 0-program success; `ensureEpgData`'s `totalPrograms == 0` check (~1737) self-heals at the DB layer, leaving only a misleading "last synced" timestamp.
- **m13 — PlayerCacheManager first-use block** (caller `PlayerActivity.kt` ~1685): first `getCache` on the main thread blocks on SimpleCache SQLite index load — worst case (large index after unclean shutdown) contributes to player-open jank.

---

## Polish Findings

- **P1 — PerformanceMonitor lost updates** (`utils/PerformanceMonitor.kt` ~117–137): `_metrics.value = current.copy(...)` read-modify-write race under concurrent `measureOperation` calls — should be `_metrics.update {}`. No polling, no retention — metrics wrong, not leaky.
- **P2 — Stale-as-fresh re-wrap** (`CacheManager.kt` ~93–111, 274–288): expired Room rows are promoted into the memory cache with brand-new 24h/7d TTLs (acknowledged inline) — stale data masquerades as fresh for a full cycle.
- **P3 — FavoritesCache non-atomic pair** (~39–43): two StateFlows set sequentially; a reader between assignments sees an inconsistent list/ID-set pair. Harmless for current usage; otherwise the class is a model citizen (injected singleton, no Context, bounded, cleared on logout).
- **P4 — Manifest cosmetics:** `usesCleartextTraffic="true"` is redundant alongside `networkSecurityConfig` (config wins on API 24+).

---

## Cross-Phase Pointer Closure

### Pointer 1 (Phase 1): WatchHistoryPreferences — **CLOSED**
Confirmed real on the class side. Every read is a synchronous load → full-list Gson parse → per-item URL-heal (2 log builds/item) → dedupe → possible write-back-from-getter pipeline with no dispatcher guard (H13), and every mutation is an unsynchronized read-modify-write over the whole JSON blob (M4). Static state is limited to the suppression map already reported as Phase 4 M7 (completeness note: entries whose keys are never re-saved persist past their 60s TTL). No Context/Activity/View retention; no coroutine scopes or Handlers. Duplicate ad-hoc construction at 6+ sites despite an existing Hilt provider.

### Pointer 2 (Phase 1): PlayerCacheManager — **CLOSED**
The classic double-`SimpleCache` crash is currently mitigated: the `@Synchronized` singleton is the only construction site in the codebase. The real defects are the Activity-context leak through `StandaloneDatabaseProvider(this)` (H4), the never-called `releaseCache()` whose absence turns any cache-dir deletion — including Phase 5 M10's Settings action and routine Fire OS storage purges invited by the 500MB budget (M16) — into an unrecoverable stale-locked-cache state (H5), and a main-thread first-use index-load block (m13).

### Pointer 3 (Phase 2): EpgSyncWorker / `parse(String)` callers — **CLOSED**
**Verdict: `EpgParser.parse(xmlContent: String)` has zero production callers — the String path is dead code.** The live chain is `EpgSyncWorker.doWork` → `XtreamRepository.fetchAndSaveEpg` (mutex-guarded, IO) → `XtreamApiService.getEpg` → `charStream().buffered()` → `EpgParser.parseStream` (true XmlPullParser streaming, batched inserts). However, the whole-document-in-memory hazard the pointer was probing **is real one layer lower**: the missing `@Streaming` annotation makes Retrofit buffer the complete XMLTV body in heap before the streaming parse begins (C1), and `CacheInterceptor` additionally forces the same body through the 50MB disk cache (M11). For 100MB+ XMLTV the OOM risk is Critical and currently unmitigated despite the genuinely streaming parser. Recommend deleting `parse(String)`/`parseInputStream` to prevent regression alongside the C1 fix.

---

## Verified Clean (for the record)

- `MainApplication.onCreate` is minimal (crash-handler install + one launch); WorkManager default initializer correctly removed (`tools:node="remove"`) with on-demand `Configuration.Provider`; all Hilt providers are lazy with no provision-time I/O — cold-start cost is **not** concentrated in the Application/DI layer.
- Manifest: `android:banner` + `LEANBACK_LAUNCHER` + leanback feature `required="false"` correct; `PlayerActivity` `singleTop` + full `configChanges` + PiP appropriate; all non-launcher activities `exported="false"`; no `largeHeap`; `allowBackup="false"`.
- No `BroadcastReceiver`s or `Service`s exist anywhere in source or manifest. No `HttpLoggingInterceptor` exists — no logging-level violations on credentialed clients.
- PlayerActivity's `NetworkCallback` is correctly paired (register `onStart` / unregister `onStop`, double-registration guarded, recovery gated) — no callback storm; no connectivity-triggered retry storms app-wide (NetworkMonitor has zero collectors).
- EPG repo-level single-flight (`epgSyncMutex`) confirmed intact — DO_NOT_REPEAT "no concurrent EPG sync" satisfied at the execution layer.
- `HomePreferences` (callbackFlow with `awaitClose` unregister), `FavoritesCache`, `CachedData`, `ThemeHelper` — clean.
- `VoiceSearchManager` does **not** retain an Activity (`applicationContext` stored); `EpgCache`'s static repository reference is application-scoped.

**Security observation (routed to SECURITY_AUDIT.md scope, not a Phase 6 finding):** `CredentialsPreferences` stores provider credentials in plaintext SharedPreferences while the Debrid/TorBox classes use encrypted storage; `CacheInterceptor` persists credentialed URLs in the HTTP disk cache index (M11b).

---

## Cross-Reference Map

| Phase 6 ID | Related prior finding / rule |
|-----------|------------------------------|
| C1, M11, H8, H11 | App Failed Pattern #10 (huge XMLTV in memory); DO_NOT_REPEAT EPG rules; Phase 2 EPG UI findings |
| C3, m1 | — (new area: crash handling) |
| H1, M2, M3 | Compounds Phase 4 H1/M2 (TMDB artwork enrichment) memory pressure |
| H3 | LIVE_MODULE protected scope (LiveFragment leak); App Failed Pattern #4 family |
| H4, H5, M16, m13 | Phase 1 player spine (protected — utility-class side only) |
| H13, M4, m3 | Phase 4 H2 (caller side), Phase 4 M7 (suppression map) |
| M1 | Phase 5 C1/H1 family (sensitive-adjacent logging — different files) |
| M6 | Phase 5 m3 / Phase 4 m3 (imperative preference construction — app-wide scale confirmed) |
| M10, M14, H10 | DO_NOT_REPEAT LiveTV EPG sync rules |
| M13 | Phase 5 M7 (caller side) |

---

*Audit complete. No code was modified. Phases 1–6 of the read-only diagnose series are now complete; all cross-phase pointers are closed.*
