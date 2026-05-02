# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**DebridXtreamIPTV** — Android TV (Leanback) IPTV / VOD client (Kotlin, MVVM + Hilt, Room, Media3) that talks to Xtream Codes providers and integrates Debrid sources (e.g. MediaFusion). Single Gradle module: `:app`. Root project name: `DebridXtreamIPTV`.

There is also a separate `web-dashboard/` subproject (React 19 + Vite + TypeScript + Firebase) — it is **not** part of the Gradle build and has its own `package.json`. Don't conflate the two.

### Identifiers (these differ — note carefully)
- **Java/Kotlin namespace:** `com.tvonnet.debridxtreamiptv`
- **Installed `applicationId`:** `com.debridxtream.tv` (so `adb install -r` updates the existing TV app)
- Launching MainActivity: `adb shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`

### Toolchain
- AGP 8.7.1, Kotlin 1.9.25, Hilt 2.51.1, Java/JVM target 11
- compileSdk/targetSdk 35, minSdk 21
- Compose enabled alongside ViewBinding (mixed)
- `local.properties` must define `TMDB_API_KEY=...` — exposed as `BuildConfig.TMDB_API_KEY`. Without it the field is empty string and TMDB-dependent features will silently fail.

## Build, test, run

Use the Gradle wrapper. On Windows shells (this is a Windows repo) use `gradlew.bat`; the AGENTS.md working agreement prefers `--no-daemon`:

```powershell
# Mandatory verification before handing off any code change (per AGENTS.md):
.\gradlew.bat :app:compileDebugKotlin --no-daemon          # minimum
.\gradlew.bat :app:testDebugUnitTest --no-daemon           # preferred

# Common
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug          # installs onto connected ADB device/emulator
.\gradlew.bat lint                  # lint.abortOnError = false, so non-blocking
.\gradlew.bat clean

# Single test class / single test method
.\gradlew.bat :app:testDebugUnitTest --tests "com.tvonnet.debridxtreamiptv.core.featureflag.FeatureFlagManagerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*FeatureFlagManagerTest.someMethodName"
```

`build_install.bat` is a one-shot helper that runs `assembleDebug` then `installDebug`.

When stating completion, **explicitly list which Gradle tasks were run and whether they passed** (AGENTS.md rule).

## Architecture (the parts that span files)

### Two coexisting engines for Series (Sidecar / V2 pattern)
This is the most important thing to internalize before touching Series code. The codebase deliberately runs an old engine and a new engine side-by-side, gated by a runtime flag:

- **Legacy V1** lives under the layer-style packages: `data/`, `ui/series/`, `data/repository/XtreamRepository.kt`, etc.
- **V2 ("Native Rescue Stack")** lives under `features/seriesv2/{data,domain,ui,worker}` — Paging 3 + `RemoteMediator`, Room as single source of truth, `StateFlow`, custom `NetworkResult` sealed class, `SeriesCachePruningWorker` for the 500MB storage cap.
- The router is `MainActivity` + `core/featureflag/FeatureFlagManager` (singleton, mem cache + SharedPreferences fallback, `isSeriesV2Enabled`).

Rules enforced by `docs/architecture.md` for this split:
1. New Series code goes in `features/seriesv2/...` — do **not** add to legacy folders.
2. V2 classes carry a `V2` suffix (`SeriesDetailFragmentV2`, `XtreamSeriesRepositoryV2`, `SeriesRemoteMediatorV2`, `EpisodeEntityV2`, etc.). Use log tags like `"SeriesDebugV2"` distinct from legacy `"SeriesDebug"` so crash reports trace back to the right engine.
3. V2 features must not import V1 UI packages. Shared code goes in `core/`.
4. New code uses `StateFlow` (not `LiveData`) and the MVI-Lite flow: UI Event → ViewModel → private `MutableStateFlow` → exposed immutable `StateFlow<UiState>`. UI never calls Repository directly.
5. Errors in V2 use the `NetworkResult` sealed class + `safeApiCall` wrapper (must distinguish `HttpError(code)` from `NetworkError`); V1 still uses Toast-style handling.

When asked to change something Series-related, first decide whether it belongs in V1 or V2 and check `FeatureFlagManager` callers to see how the routing reaches that code path.

### Data layer
- Room DB: `data/local/AppDatabase.kt`, **version 10**, migrations in `data/local/migrations/DatabaseMigrations.kt`. Entities are a mix of legacy (`ChannelEntity`, `VodEntity`, `SeriesEntity`, `SeasonEntity`, `EpisodeEntity`, `FavoriteEntity`, `EpgEntity`, `SearchHistoryEntity`, `CategoryEntity`) and V2 (`SeriesEntityV2`, `EpisodeEntityV2`). **Any schema change requires a migration** — the DB is shared between V1 and V2.
- Network: Retrofit + OkHttp, `data/remote/XtreamApiService.kt` and `XtreamRetrofitClient.kt`. Xtream responses are sometimes flaky JSON — see `XtreamResponseParser.kt` and the custom adapters/interceptors under `data/remote/`.
- Debrid integration: `data/debrid/{api,model,repository,source,util}` (e.g. `MediaFusionFetcher`).
- Multi-level caching strategy: Memory → Room (TTL ~7 days) → HTTP cache → Network. `data/cache/CachedData` and `CachePruningWorker`/`SeriesCachePruningWorker` enforce the storage cap.

### Background work & companion server
- WorkManager is initialized via Hilt (`App` implements `Configuration.Provider`); the default `androidx.startup` initializer is disabled in `AndroidManifest.xml`. Always go through `HiltWorkerFactory` when adding workers.
- `network/CompanionConfigServer.kt` runs an embedded **Ktor CIO server** in-app for companion-device pairing — `network/RemotePairingManager.kt` orchestrates it. This is why Ktor is on the classpath despite this being an Android client.
- Firebase Firestore + Analytics are wired in (`google-services.json` present) for the Magic Link / pairing flow.

### Player
- `player/PlayerActivity` uses Media3 ExoPlayer (`media3-exoplayer`, `-hls`, `-ui`, `-datasource-okhttp`, `-extractor`). PiP is enabled (`supportsPictureInPicture="true"`). When changing player code, prefer Media3 APIs — the migration away from legacy ExoPlayer is documented in `EXOPLAYER_MEDIA3_MIGRATION_NOTES.md`.

### UI
- TV-first: declares `android.software.leanback` and registers `LEANBACK_LAUNCHER` intent filter. Many screens are `Fragment` + ViewBinding under `ui/{home,live,vod,series,search,settings,favorites,debrid,...}`. New screens may use Compose (`ui/compose/`) — both stacks coexist.

## Conventions worth knowing
- **Verification before handoff is mandatory** (AGENTS.md). Don't claim work done without naming the Gradle tasks you ran.
- Don't add `LiveData` to new code — use `StateFlow` with `repeatOnLifecycle` / `flowWithLifecycle` in fragments.
- Don't add new files under `data/` for Series features — they go under `features/seriesv2/`.
- The `applicationId` and `namespace` differ on purpose; don't "fix" them.
- `lint.abortOnError = false` and `checkReleaseBuilds = false` are intentional — don't tighten without asking.
- Many root-level `*.md` files (e.g. `IMPLEMENTATION_ROADMAP.md`, `NEXT_SESSION_START_HERE.md`, `BEST_PRACTICES_ANALYSIS.md`, `QA_IMPROVEMENTS_APPLIED.md`) are historical session notes / planning docs, not authoritative specs — `docs/architecture.md`, `docs/development-guide.md`, and `AGENTS.md` are the load-bearing ones.
- `.windsurfrules`, `.cursor/rules/design.mdc`, and the older copy of `CLAUDE.md` were boilerplate from the "SuperDesign" VS Code extension and have nothing to do with this project — ignore them when reasoning about conventions.
