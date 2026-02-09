# Deep Search Report — DebridXtreamIPTV (debxtrem)

Date: 2025-12-14

## Scope

- Code: `app/src/main/**` (Android TV app)
- Build config: `build.gradle`, `app/build.gradle`
- Excluded from deep scan: `build/**`, `.gradle/**`, `backups/**` (historical copies + logs)

## Project Snapshot (What you have)

- Platform: Android / Android TV
- Language: Kotlin
- UI: Fragments + RecyclerView + some Jetpack Compose screens
- Architecture: MVVM-ish (ViewModels + Repository), Hilt DI
- Data: Retrofit/OkHttp for Xtream API, Room for caching, Paging3 for lists
- Playback: Media3 (ExoPlayer)
- Background work: WorkManager (EPG + series sync)
- Inputs supported: Xtream Codes API (`player_api.php`) + XMLTV (`xmltv.php`)

## Key Issues Found (High impact)

### 1) Live TV “loading” pressure from background prefetching

**Where:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveViewModel.kt`

**Problem:** The Live screen was prefetching per-category channel counts by fetching full channel lists for many categories (and also doing an additional full fetch on category select). On real IPTV providers, this can create:

- Very long “loading” times / UI waiting
- Burst network traffic + server throttling
- High memory pressure (large lists) + GC churn

**Fix applied:** Removed category-count prefetch and removed the redundant full-category fetch on selection; Live paging now drives the network/cache work.

### 2) Hardcoded TMDB key and key logging

**Where:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/TmdbRemoteDataSource.kt`

**Problem:** A TMDB API key was hardcoded and partially logged. This is a leak risk (even partial logs help attackers) and makes key rotation difficult.

**Fix applied:** Uses `BuildConfig.TMDB_API_KEY` (already wired in `app/build.gradle`) and returns a clear error if missing. Removed key logging and removed the hardcoded key.

**Action needed:** Put this into `local.properties`:

```
TMDB_API_KEY=your_key_here
```

## Medium/Low Issues Found (Still worth fixing)

### Error handling inconsistency (can look like loading issues)

- `BasePagingSource` defaults to treating errors as empty lists (VOD/Series). This can silently hide real API failures and present as “no data” instead of “error + retry”.
- Live uses strict error propagation (good). Consider aligning other sections to show errors when the DB is empty and the network fails.

### Credentials + tokens stored as plain preferences

- Xtream credentials and Real-Debrid auth state are stored via preference helpers.
- You already depend on `androidx.security:security-crypto`; consider moving credentials/tokens to `EncryptedSharedPreferences` (or Jetpack Security Crypto wrappers) for better at-rest protection.

### Paging strategy for Live is “full fetch then slice”

- `ChannelPagingSource` fetches the entire category list once (`fetchLiveStreamsForCategoryStrict`) and then pages by slicing the in-memory list.
- This is simple, but for categories with thousands of channels it can still feel “stuck loading” because the first fetch is big.
- Long-term best practice: mirror what VOD does (Room-driven paging) or use a `RemoteMediator` + database-backed paging for Live.

## Comparison: Best IPTV Apps (A-Z, alphabetical)

This compares common “top” IPTV player apps vs your current app’s direction (Xtream + Android TV focus + caching + Media3).

- **GSE Smart IPTV**: Strong playlist support (M3U + Xtream), EPG, lots of settings; more phone/tablet oriented than TV-first UI.
- **iMPlayer (Android TV)**: Very TV-optimized; strong EPG grid, multi-playlist, catch-up support, fast navigation.
- **IPTV Smarters (Android/iOS/TV)**: Popular “all-in-one” UX, multi-screen, catch-up (provider dependent), playlist manager.
- **IPTVX / iPlayTV (tvOS)**: Premium Apple TV players; excellent EPG/timeline UX, modern design, playlist management.
- **Kodi + PVR IPTV Simple Client**: Extremely flexible; powerful addons; heavier setup and not as “appliance-like”.
- **OTT Navigator (Android)**: Very strong browsing/search, EPG, filtering; lots of power-user features.
- **Perfect Player (Android)**: Lightweight, simple, fast; UI looks dated but performs well.
- **Sparkle TV (Android TV)**: Modern TV UI, EPG-first, stable playback, good playlist handling.
- **Televizo (Android)**: Clean UI, multi-playlist, solid EPG; good balance of features/performance.
- **TiviMate (Android TV)**: Gold standard on Android TV: fast, polished EPG grid, multi-playlist, catch-up, excellent remote UX.
- **XCIPTV Player (Android)**: Similar market to Smarters; decent features; UI/maintainability varies by build.

### What your app already does well vs these

- TV-first navigation + focus handling in key places
- Room + caching strategy (great foundation for speed/offline)
- Media3 playback and debrid integrations (not common in classic IPTV-only players)
- Worker-based background syncing (EPG/series)

### Biggest gaps vs top tier IPTV apps

- **Playlist management**: multi-playlist, switching profiles, import/export, M3U support (currently Xtream-only)
- **EPG UX**: full grid/timeline polish, fast scrolling, instant preview, “now/next” overlays
- **Catch-up/timeshift**: using `tv_archive` / `tv_archive_duration` when provider supports it
- **Playback controls**: buffering presets, retry strategy, external player, audio/subtitle track UX, aspect ratio controls
- **Reliability UX**: explicit error states for VOD/Series when DB empty + network fails (avoid silent empty)
- **Performance on huge lists**: database-backed paging for Live (avoid “fetch all then show”)

## Suggested Roadmap (Most valuable first)

### Quick wins (1–2 days)

- Keep the Live prefetch removal (already done).
- Add clearer “no internet / auth failed / provider error” states for VOD + Series when DB empty.
- Encrypted storage for Xtream credentials and RD tokens.

### Medium (1–2 weeks)

- Implement Catch-up if provider exposes archive flags + URLs.
- Add multi-profile + multi-playlist support (even if still Xtream-only at first).
- Improve playback settings UI (buffering presets, track selection, subtitle UX).

### Longer-term (2–6+ weeks)

- Move Live channel paging to Room (or `RemoteMediator`) for instant first paint and stable memory usage.
- EPG grid overhaul (TiviMate-style timeline UX), including fast seek + “now/next” overlays.

## Recent Fixes (2025-12-14)

### Live TV focus guard (remote UX)

**Issue:** At the top of the Live channel list, repeated UP navigation could escape to the sidebar/back button.

**Fix applied:** In `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt`, consume DPAD_UP when the focused channel position is `0` so focus stays in the list.

### Search results focus visibility (border + zoom)

**Issue:** After searching, navigation worked but the focused item was not visually obvious (no reliable border/zoom).

**Fix applied:**

- `app/src/main/res/layout/item_search_result.xml`: focus ring moved to a foreground drawable + padding + `blocksDescendants` so focus stays on the item root.
- `app/src/main/res/drawable/search_result_focus_highlight.xml`: updated to stroke-only so it can be used as an overlay without covering content.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchFragment.kt`: DPAD_DOWN from the search box now clears focus and moves focus into results.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchLiveAdapter.kt`: increased focus scale to `1.08`.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchVodAdapter.kt`: increased focus scale to `1.08`.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchSeriesAdapter.kt`: increased focus scale to `1.08`.
- `app/src/main/res/color/search_result_stroke_color.xml`: stateful stroke colors (focused/selected/activated) for a very reliable focus border.
- `app/src/main/res/layout/item_search_result.xml`: card uses `strokeColor` + `duplicateParentState` so the border is visible even if OEM focus overlay behavior differs.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchLiveAdapter.kt`: toggles `selected/activated` on focus.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchVodAdapter.kt`: toggles `selected/activated` on focus.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchSeriesAdapter.kt`: toggles `selected/activated` on focus.

### Verification (mandatory)

- `.\gradlew.bat :app:compileDebugKotlin --no-daemon` (pass)
- `.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.tvonnet.debridxtreamiptv.ui.search.SearchViewModelTest"` (pass)
- `.\gradlew.bat :app:installDebug --no-daemon` + launched on `192.168.0.54:5555` (pass)
