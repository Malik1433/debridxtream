# DebridXtreamIPTV

Android TV (Leanback) IPTV / VOD client written in Kotlin. Talks to Xtream Codes providers for live TV, movies, and series; integrates Debrid sources (e.g. MediaFusion / Real-Debrid) for higher-quality VOD streaming.

Single Gradle module: `:app`. There's a separate `web-dashboard/` companion project (React + Vite + TypeScript + Firebase) used for phone-pair credential setup — it's **not** part of the Gradle build.

---

## Identifiers (note carefully — these differ on purpose)

| | Value |
|---|---|
| Java/Kotlin namespace | `com.tvonnet.debridxtreamiptv` |
| Installed `applicationId` | `com.debridxtream.tv` |
| Launcher activity | `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity` |

`adb install -r` updates the existing TV app because the `applicationId` matches what's on the device.

---

## Toolchain

- **AGP** 8.7.1 · **Kotlin** 1.9.25 · **Hilt** 2.51.1
- Java/Kotlin target: **JVM 11**
- `compileSdk` / `targetSdk`: **35** · `minSdk`: **21**
- Compose enabled alongside ViewBinding (mixed)
- `local.properties` must define `TMDB_API_KEY=...` — exposed as `BuildConfig.TMDB_API_KEY`. Without it, TMDB-dependent features silently fail.

---

## Build · install · run

This is a Windows-first repo; use `gradlew.bat`. Per [AGENTS.md](AGENTS.md), prefer `--no-daemon` and verify before handing off.

```powershell
# Mandatory verification before claiming a change is done:
.\gradlew.bat :app:compileDebugKotlin --no-daemon          # minimum
.\gradlew.bat :app:testDebugUnitTest    --no-daemon        # preferred

# Common
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug          # installs onto connected ADB device/emulator
.\gradlew.bat lint                  # lint.abortOnError = false, non-blocking
.\gradlew.bat clean

# Single test class / single test method
.\gradlew.bat :app:testDebugUnitTest --tests "com.tvonnet.debridxtreamiptv.core.featureflag.FeatureFlagManagerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*FeatureFlagManagerTest.someMethodName"

# Launch on an attached device/emulator
adb shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity
```

`build_install.bat` is a one-shot helper that runs `assembleDebug` then `installDebug`.

---

## Architecture (the parts that span files)

### Sidecar / V2 pattern for Series

The most important thing to internalize before touching Series code. The codebase intentionally runs an old engine and a new engine side-by-side, gated by a runtime flag:

- **Legacy V1** lives under the layer-style packages (`data/`, `ui/series/`, etc.).
- **V2 ("Native Rescue Stack")** lives under `features/seriesv2/{data,domain,ui,worker}` — Paging 3 + `RemoteMediator`, Room as single source of truth, `StateFlow`, custom `NetworkResult` sealed class, `SeriesCachePruningWorker` for the 500MB storage cap.
- The router is `MainActivity` + `core/featureflag/FeatureFlagManager` (singleton, mem cache + SharedPreferences fallback, `isSeriesV2Enabled`).

Rules (from [docs/architecture.md](docs/architecture.md)):

1. New Series code goes in `features/seriesv2/...` — do **not** add to legacy folders.
2. V2 classes carry a `V2` suffix (`SeriesDetailFragmentV2`, `XtreamSeriesRepositoryV2`, `EpisodeEntityV2`, …). Use log tags like `"SeriesDebugV2"` distinct from legacy `"SeriesDebug"`.
3. V2 features must not import V1 UI packages. Shared code goes in `core/`.
4. New code uses `StateFlow` (not `LiveData`) and the MVI-Lite flow: `UI Event → ViewModel → private MutableStateFlow → exposed immutable StateFlow<UiState>`. UI never calls Repository directly.
5. Errors in V2 use the `NetworkResult` sealed class + `safeApiCall` wrapper (must distinguish `HttpError(code)` from `NetworkError`).

### Data layer

- **Room DB**: [data/local/AppDatabase.kt](app/src/main/java/com/tvonnet/debridxtreamiptv/data/local/AppDatabase.kt), version 10, migrations in `DatabaseMigrations.kt`. Entities mix legacy (`ChannelEntity`, `VodEntity`, `SeriesEntity`, `SeasonEntity`, `EpisodeEntity`, `FavoriteEntity`, `EpgEntity`, `SearchHistoryEntity`, `CategoryEntity`) and V2 (`SeriesEntityV2`, `EpisodeEntityV2`). **Any schema change requires a migration** — the DB is shared between V1 and V2.
- **Network**: Retrofit + OkHttp ([data/remote/](app/src/main/java/com/tvonnet/debridxtreamiptv/data/remote/)). Xtream responses are sometimes flaky JSON — see `XtreamResponseParser.kt` and the custom adapters/interceptors.
- **Debrid integration**: [data/debrid/](app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/) (e.g. `MediaFusionFetcher`).
- **Multi-level caching**: Memory → Room (TTL ~7 days) → HTTP cache → Network. `data/cache/CachedData` and `CachePruningWorker` / `SeriesCachePruningWorker` enforce the 500MB storage cap.

### Background work & companion server

- **WorkManager** is initialised via Hilt (`App` implements `Configuration.Provider`); the default `androidx.startup` initializer is disabled in the manifest. Add workers via `HiltWorkerFactory`.
- [network/CompanionConfigServer.kt](app/src/main/java/com/tvonnet/debridxtreamiptv/network/CompanionConfigServer.kt) runs an embedded **Ktor CIO server** in-app for companion-device pairing. `RemotePairingManager` orchestrates it.
- Firebase Firestore + Analytics are wired in (`google-services.json` present) for the Magic Link / pairing flow.

### Player

[player/PlayerActivity.kt](app/src/main/java/com/tvonnet/debridxtreamiptv/player/PlayerActivity.kt) uses Media3 ExoPlayer (`media3-exoplayer`, `-hls`, `-ui`, `-datasource-okhttp`, `-extractor`). PiP is enabled. Migration from legacy ExoPlayer is documented in [EXOPLAYER_MEDIA3_MIGRATION_NOTES.md](docs/) (or older root-level note if still present).

### UI

- TV-first: declares `android.software.leanback` and registers `LEANBACK_LAUNCHER`.
- Most screens are `Fragment` + ViewBinding under `ui/{home,live,vod,series,search,settings,favorites,debrid,...}`. Movie Detail uses Compose ([ui/compose/MovieDetailScreen.kt](app/src/main/java/com/tvonnet/debridxtreamiptv/ui/compose/MovieDetailScreen.kt)) — the two stacks coexist.
- **Cinematic redesign** (gold + ink palette, glass cards, gold pill on active sidebar item) is wired across Login, Initial Sync, Companion Setup, Movie Detail, Series Detail, Home + Sidebar, Movies/Series category sidebars, and Player chrome. Design tokens live in `res/values/{colors,dimens,styles}_cinematic.xml` + `res/drawable/cin_*.xml`. The cinematic sidebar uses hardcoded nav items in [cin_view_sidebar.xml](app/src/main/res/layout/cin_view_sidebar.xml) instead of the legacy `SidebarAdapter` RecyclerView.

---

## Conventions worth knowing

- **Verification before handoff is mandatory** ([AGENTS.md](AGENTS.md)). Don't claim work done without naming the Gradle tasks you ran.
- Don't add `LiveData` to new code — use `StateFlow` with `repeatOnLifecycle` / `flowWithLifecycle` in fragments.
- Don't add new files under `data/` for Series features — they go under `features/seriesv2/`.
- `applicationId` and `namespace` differ on purpose; don't "fix" them.
- `lint.abortOnError = false` and `checkReleaseBuilds = false` are intentional.
- Cinematic resources are namespaced `cin_*` / `Cin.` — global find for that prefix surfaces every cinematic touchpoint.
- Hand-off to Claude design (the redesign tool) requires **giving it the cinematic design tokens** (`colors_cinematic.xml`, `dimens_cinematic.xml`, `styles_cinematic.xml`, the `cin_*` drawables) so its output stays in the same visual language. See [CLAUDE.md](CLAUDE.md) for the full hand-off pattern.

---

## Layout of the repo

```
app/                                    Single Gradle module — the Android TV app
  src/main/java/com/tvonnet/debridxtreamiptv/
    core/featureflag/                   FeatureFlagManager (V1/V2 router)
    data/                               Legacy V1 data layer (Room + Retrofit + Debrid)
    features/seriesv2/                  V2 "Native Rescue" engine (sidecar)
    network/                            Embedded Ktor server for phone pairing
    player/                             Media3 PlayerActivity
    ui/                                 Fragments + Activities (TV-first)
  src/main/res/
    layout/                             cin_*, fragment_*_cinematic, legacy variants
    drawable/                           cin_* design system + legacy icons/cards
    values/                             colors_cinematic, dimens_cinematic, styles_cinematic
docs/                                   Authoritative architecture + dev guides
  architecture.md
  development-guide.md
  design/README_REDESIGN.md             Original Claude-design hand-off notes
web-dashboard/                          React + Vite companion (separate package.json)
DebridXtre/                             Cursor agent definitions (not part of build)
```

Historical session notes (`IMPLEMENTATION_ROADMAP.md`, `BEST_PRACTICES_ANALYSIS.md`, `*_COMPLETE.md`, etc.) at the repo root are planning archives, not authoritative specs. The load-bearing docs are [docs/architecture.md](docs/architecture.md), [docs/development-guide.md](docs/development-guide.md), [AGENTS.md](AGENTS.md), and [CLAUDE.md](CLAUDE.md).

---

## License

Internal / proprietary — see project owner.
