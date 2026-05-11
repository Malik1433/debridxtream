# TASK 014  Home Adapter/Data Stability

## 1. Summary
Stabilized the existing Home Trending Movies and Trending Series row updates without redesigning Home or adding new rows. `Top10Adapter` now uses DiffUtil and stable item IDs instead of broad full-list refreshes. `HomeViewModel` now exits loading when cache is unavailable, exposes minimal empty/error state, coalesces concurrent Home loads, and suppresses identical state re-emissions.

Continue Watching, Recently Watched, and Recent Live infrastructure was inspected. The rows were not wired in this task because Home currently has no XML/focus integration for those rows, and the Recently Watched write path is incomplete.

## 2. Files Modified
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/Top10Adapter.kt`
- `docs/reports/TASK_014_HOME_ADAPTER_DATA_STABILITY.md`

## 3. Adapter Stability Changes
`Top10Adapter` now uses `DiffUtil.calculateDiff()` inside `updateItems()` and returns immediately when the incoming list is identical to the current list. This removes the previous broad `notifyDataSetChanged()`/range refresh behavior for Top 10 rows.

Stable IDs were enabled with `setHasStableIds(true)`. IDs are derived from stable content identity instead of adapter position:
- `sourceType`
- `contentType`
- `contentId`, falling back to title only if the ID is blank

Helper APIs were added to support focus preservation:
- `getStableItemIdAt(position)`
- `findPositionByStableId(stableId)`

Existing click routing, focus callbacks, and DPAD-left boundary behavior were preserved.

## 4. Focus Preservation During Data Updates
`HomeFragment` now snapshots the currently focused Top 10 row/card before applying row updates. The snapshot includes:
- content area: Movies or Series
- stable item ID
- adapter index

After DiffUtil updates complete, Home posts one layout-safe restore attempt. If the focused stable ID still exists, focus restores to that item. If it disappeared, focus falls back to the nearest valid index in the same row. If the row becomes empty, Home falls back through the existing initial-focus policy.

The restore logic avoids stealing focus when the user still has a valid focused child after the adapter update.

## 5. Loading / Empty / Error Handling
`HomeUiState` now includes:
- `errorMessage: String?`
- `isEmpty: Boolean`

When `repository.readCache()` returns null, `HomeViewModel` no longer returns while leaving `isLoading=true`. It now emits a completed empty/error state with:
- `isLoading=false`
- empty movie/series rows
- no hero item
- `isEmpty=true`
- `errorMessage="Content is not available yet."`

Failures during Home loading now also exit loading and preserve the current rows where possible. No XML placeholder was added in this task because adding a visible empty/error panel would be a UI/layout change; existing Home focus fallback keeps the screen from becoming an unfocused blank state.

## 6. Reload / Duplicate Update Control
`HomeViewModel` now keeps a `loadHomeDataJob` and ignores duplicate load requests while a load is already active. After loading completes, it compares the next row/hero/section/empty/error state against the current state and avoids emitting identical content repeatedly.

Repository internals, sync architecture, and cache storage were not changed.

## 7. Continue Watching / Recent Watch Readiness
Existing Home adapters found:
- `ContinueWatchingAdapter`
- `RecentlyWatchedAdapter`
- `RecentLiveChannelsAdapter`

Existing models/preferences found:
- `HomeScreenModels.kt`
- `WatchHistoryPreferences`
- `HomePreferences`

`WatchHistoryPreferences` supports:
- `saveContinueWatchingItem()`
- `getContinueWatchingList()`
- `addRecentlyWatched()`
- `getRecentlyWatchedList()`
- `addRecentLiveChannel()`
- `getRecentLiveChannelsList()`

Read-only inspection showed `PlayerActivity` records Continue Watching for movie/series/episode playback and Recent Live channels for Live TV playback. Live TV recent watch persistence therefore exists. However, no production caller was found for `addRecentlyWatched()`, so Recently Watched data exists as a preference API but does not appear to be populated for VOD/series today.

The rows were not wired now because:
- `fragment_home_cinematic.xml` currently only defines hero, Trending Movies, and Trending Series.
- Adding rows would require Home XML/focus integration and row-specific DPAD memory beyond this adapter/data stability pass.
- Recently Watched needs a confirmed write path before it should be surfaced.

Recommended follow-up:
`TASK 014A  Home Continue Watching and Recent Watch Data Integration`

## 8. Validation
| Command | Result | Notes |
|---------|--------|-------|
| `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console plain` | PASS | Completed with `BUILD SUCCESSFUL`. |
| `.\gradlew.bat assembleDebug --no-daemon --console plain` | PASS | Debug APK assembled successfully. |
| `.\gradlew.bat testDebugUnitTest --no-daemon --console plain` | PASS | Retried after TASK 013C timeout; completed successfully. |
| `adb devices` | PASS | `192.168.0.21:5555` was connected and used. |
| `adb -s 192.168.0.21:5555 install -r app\build\outputs\apk\debug\app-debug.apk` | PASS | Install returned `Success`. |
| `adb -s 192.168.0.21:5555 shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity` | PASS | MainActivity launched and authenticated Home was reachable. |

## 9. Manual QA Result
| Area | Result | Evidence | Notes |
|------|--------|----------|-------|
| Startup/data load | PASS | `docs/reports/task014_start.xml` | Home opened with visible focus on the first movie card after data loaded. |
| Data refresh focus stability | PARTIAL | `docs/reports/task014_start.xml` | Normal launch/data render did not lose focus. A forced refresh was not safely triggered without changing data/cache state. |
| Sidebar/content focus regression | PASS | `docs/reports/task014_movie_restore.xml`, `docs/reports/task014_series_restore.xml` | Movie and series first-card focus restored from sidebar with DPAD_RIGHT. |
| Settings sidebar expanded regression | PASS | `docs/reports/task014_settings_expanded.xml` | Settings had focus and `sidebar_panel` bounds were `[0,0][520,1080]`, confirming expanded state. |
| Empty/failure behavior | PARTIAL | Code inspection and successful build | Cache-null path now exits loading. Device cache was not cleared or corrupted because that would risk changing tester state. |
| Continue Watching / Recent Watch | BLOCKED | Read-only inspection | Existing adapters/preferences exist, but rows were deferred because UI/focus integration and Recently Watched write path need a dedicated task. |
| Rapid D-pad stability | PASS | `docs/reports/task014_regression_final.xml` | Mixed rapid D-pad input retained visible focus. |
| Crash/logcat review | PASS | `adb logcat -d -t 1000` filtered for crash signatures | No `FATAL EXCEPTION`, `AndroidRuntime`, app ANR, `NullPointerException`, or `IllegalStateException` signatures were found. |

## 10. Bugs Remaining
- Continue Watching, Recently Watched, and Recent Live rows are still not displayed on Home.
- Recently Watched has a preference API but no confirmed production write path.
- Empty/cache-failure UI was not visually surfaced with a dedicated placeholder to avoid a layout change in this task.
- Forced empty-cache device QA was not performed because it would require changing tester app state.

## 11. Decision
TASK 014 accepted; proceed to TASK 014A.

## 12. Recommended Next Task
TASK 014A  Home Continue Watching and Recent Watch Data Integration
