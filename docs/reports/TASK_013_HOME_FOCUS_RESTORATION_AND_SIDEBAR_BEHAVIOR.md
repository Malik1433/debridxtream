# TASK 013  Home Focus Restoration and Sidebar Behavior

## 1. Summary
Improved Home focus/navigation behavior with explicit focus memory for hero, movies, series, and sidebar. Sidebar active state now remains separate from temporary focus, DPAD_RIGHT/LEFT uses deterministic restore rules, and vertical movement between hero and content rows is handled locally for unsafe boundaries.

## 2. Files Modified
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/SidebarAdapter.kt`
- `docs/reports/TASK_013_HOME_FOCUS_RESTORATION_AND_SIDEBAR_BEHAVIOR.md`

## 3. Focus Behavior Implemented

### Sidebar active vs focused state
- `activeNavItemId` remains the persistent active/root item for Home.
- `lastFocusedSidebarItemId` tracks the sidebar item that most recently received focus, including the pinned Settings item.
- Focus movement over sidebar items no longer changes the active item.
- `SidebarAdapter` now exposes `setActiveItemId()` so active visuals are controlled separately from click/focus movement.

### DPAD_RIGHT behavior
- Sidebar and pinned Settings DPAD_RIGHT now route through `restoreContentFocusFromSidebar()`.
- Fallback order is remembered movie/series card, remembered hero, first movies card, first series card, hero button, then staying on sidebar.
- A local pending-focus guard prevents held DPAD_RIGHT from stacking competing focus posts.

### DPAD_LEFT behavior
- First item in movies or series returns to the last focused sidebar item, then active Home, then sidebar RecyclerView.
- Non-first row cards are left to normal horizontal RecyclerView movement.
- Hero buttons DPAD_LEFT return to the remembered sidebar target.

### DPAD_UP/DOWN behavior
- Hero DPAD_DOWN moves to remembered/first movies, then remembered/first series.
- Movies DPAD_UP moves to hero when available.
- Movies DPAD_DOWN moves to series at the nearest clamped index.
- Series DPAD_UP moves to movies at the nearest clamped index, then hero.
- Series DPAD_DOWN is consumed so focus does not disappear outside Home content.

### Content focus memory
- Added `HomeContentFocusArea` with `HERO`, `MOVIES`, and `SERIES`.
- Movies and series track separate last indices.
- Content memory updates only when hero/buttons or row cards gain focus.
- Sidebar focus does not overwrite content memory.

### Sidebar focus memory
- Sidebar rows report focus to Home without changing active selection.
- The pinned Settings item participates in last-sidebar-focus memory with a local sentinel id.

### Sidebar animation/rebind changes
- `SidebarAdapter.setExpanded()` no longer calls `notifyDataSetChanged()`.
- Attached sidebar holders are updated in place for expanded/collapsed label layout.
- This reduces RecyclerView rebind churn during sidebar focus expansion/collapse without redesigning the sidebar.

## 4. Validation
Commands run:

- `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console plain`
  - Result: PASS.
- `.\gradlew.bat assembleDebug --no-daemon --console plain`
  - Result: PASS.
- `.\gradlew.bat testDebugUnitTest --no-daemon --console plain`
  - Result: PASS.

## 5. Manual QA Result
Device availability:
- `adb devices` showed `192.168.0.84:5555`.

Performed:
- Installed debug APK to `192.168.0.84:5555`: PASS.
- Launched `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`: command completed.
- Captured UI hierarchy successfully.

Limited:
- The launched device state was Login, not Home, so Home-specific DPAD paths could not be manually exercised on device in this run.
- Empty-row fallback cases were not forced because this task did not alter repositories, sync, or test data controls.

## 6. Remaining Risks
- Home focus behavior should still receive real device QA on an authenticated/synced account state.
- Full backstack/root navigation cleanup remains outside this Home-local focus task.
- Adapter data stability, stable IDs for content rows, DiffUtil/ListAdapter, empty/loading/error state handling, and reload behavior remain deferred.
- Visual polish and premium Home modernization remain deferred until focus/data stability is proven.

## 7. Recommended Next Task
TASK 014  Home Adapter/Data Stability
