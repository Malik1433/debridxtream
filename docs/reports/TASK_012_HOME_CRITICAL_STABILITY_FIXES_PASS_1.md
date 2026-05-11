# TASK 012  Home Critical Stability Fixes Pass 1

## 1. Summary
Implemented the first critical Home Screen stability fixes from TASK 011. The pass stayed focused on deterministic focus fallback, safer content focus restoration, sidebar adapter crash prevention, non-dead hero actions, local navigation guarding, and view cleanup on destruction.

No XML, Login, PlayerActivity, repository, database, Gradle, theme, Live, VOD, Series, Debrid, Search, Settings, Favorites, or EPG behavior was modified.

## 2. Files Modified
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/SidebarAdapter.kt`
- `docs/reports/TASK_012_HOME_CRITICAL_STABILITY_FIXES_PASS_1.md`

## 3. Fixes Implemented

### Initial focus fallback
Added lifecycle-scoped initial focus state and a single HomeFragment focus policy. The fallback order is now:
1. remembered content focus when valid,
2. first movie card,
3. first series card,
4. hero Watch button only when a current hero item exists,
5. active/Home sidebar item,
6. sidebar RecyclerView.

The helper avoids repeated focus loops, avoids recursive delayed retries, and does not mark the initial policy complete while loading fallback focus is still provisional. It also avoids accepting default hero button focus as final initial focus when content rows are available.

### restoreContentFocus hardening
`restoreContentFocus()` now returns a Boolean and clamps remembered row/item indices against adapter item counts. It chooses the remembered row when available, otherwise falls back to the first available movie or series row. It scrolls to the target position, posts one layout-safe focus attempt, then falls back to the first attached child or sidebar.

### Sidebar NO_POSITION guard
`SidebarAdapter` now uses `bindingAdapterPosition` and ignores clicks when the holder reports `RecyclerView.NO_POSITION`. This prevents invalid `selectItem(-1)` calls during adapter/layout transitions.

### Hero button behavior/focus safety
Home now stores the current hero item. `PLAY NOW` routes through the existing featured-item click path and `MORE INFO` opens the existing movie or series detail activity for TMDB items. Unsupported or unavailable hero actions show a short `Not available` Toast instead of leaving focus on dead controls.

### onDestroyView cleanup
Home now clears adapters from the Home RecyclerViews, removes listeners from touched views where safe, cancels button/settings animations, clears the current hero item, and resets view-lifecycle focus flags in `onDestroyView()`.

### Minimal navigation guard
Home sidebar navigation now ignores duplicate rapid navigation while a Home-originated transaction is already in progress, and skips transactions when the fragment is not added or the FragmentManager state is saved. Broader sidebar/backstack behavior remains deferred.

## 4. Validation
Commands run:

- `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console plain`
  - Result: PASS.
- `.\gradlew.bat assembleDebug --no-daemon --console plain`
  - Result: PASS.
- `.\gradlew.bat testDebugUnitTest --no-daemon --console plain`
  - Result: PASS.

Notes:
- An earlier sandboxed Gradle attempt failed while downloading Gradle with `java.net.SocketException: Permission denied: getsockopt`; the same command was rerun with approved Gradle permissions.
- One parallel validation run produced a transient KAPT output access error for `:app:kaptGenerateStubsDebugKotlin` while another Gradle build was running. A subsequent sequential `:app:compileDebugKotlin` passed, so this was treated as a parallel Gradle/KAPT workspace conflict rather than a Home code failure.

## 5. Manual QA Result
Device availability:
- `adb devices` found `192.168.0.21:5555` and `192.168.0.84:5555`.

Performed:
- Installed debug APK on `192.168.0.21:5555`: PASS.
- Launched `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`: command completed.
- Captured a Home UI hierarchy before the final focus-order adjustment and confirmed Home content rendered with visible focus.

Limited:
- After the final focus-order adjustment, repeated hierarchy dumps were intercepted by the Fire TV screensaver/settings overlay even after wake/start commands, so final on-device focus hierarchy could not be reliably captured.
- Empty movie row, empty series row, and both-empty states were not manually forced because this task did not modify repositories, sync, or test data controls.

## 6. Remaining Risks
- Full DPAD boundary behavior, sidebar active-vs-focused semantics, and remembered content return from sidebar should be handled in TASK 013.
- Adapter modernization, stable IDs, DiffUtil/ListAdapter, empty/loading/error row states, and reload behavior remain deferred to TASK 014.
- Visual modernization, clipping polish, spacing hierarchy, and premium TV UI work remain deferred to TASK 015.
- Broader MainActivity/sidebar backstack cleanup was intentionally not expanded in this critical pass.

## 7. Recommended Next Task
TASK 013  Home Focus Restoration and Sidebar Behavior
