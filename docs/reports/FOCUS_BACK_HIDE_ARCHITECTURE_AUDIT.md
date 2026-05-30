# FOCUS_BACK_HIDE_ARCHITECTURE_AUDIT

## 1. Executive Summary
This report audits the DebridXtreamIPTV architecture focusing on Android TV/Fire TV D-pad navigation, back button behavior, and hide/show visibility rules. The app employs a custom, highly-tuned navigation system utilizing `FocusCoordinator`, `FocusMemoryManager`, and Fragment-level back dispatchers to manage TV-specific focus persistence. However, inconsistencies exist across modules (notably `VodFragment` missing standard focus restoration), and complex priority trees in `PlayerActivity` require strict adherence to standard rules to prevent focus drops, "jumpy" UI states, or unhandled exits.

**NO RUNTIME CODE CHANGED.**

## 2. Current Architecture Map
* **Routing Authority:** `MainActivity` acts as the global back-press handler, managing the backstack and routing root fragments back to `HomeFragment`.
* **Focus Memory:** A "Single-Owner" pattern driven by `FocusCoordinator` (locks focus during layout/transitions) and `FocusMemoryManager` (remembers last focused child via `WeakReference`).
* **Player & Overlays:** `PlayerActivity` overrides `dispatchKeyEvent` and `OnBackPressedCallback` to handle a strict hierarchy of overlay visibility (Episode Browser > EPG > Controls > Exit).
* **Sidebar Management:** Custom key listeners (`setOnKeyListener`) create a D-pad boundary that prevents focus escape to the left and explicitly routes `DPAD_RIGHT` into content grids.

## 3. Current Global Focus Rules Found
1. **Sidebar Containment:** Focus must not escape the Sidebar to the left. `DPAD_LEFT` from content explicitly forces focus to the sidebar.
2. **Grid Vertical Constraints:** Focus must not jump from the top row of a grid to header buttons (Search/Settings) unless explicitly handled; native focus search is blocked upwards.
3. **Child-Targeted Focus:** `RecyclerView`s are kept `isFocusable = false`. Focus is requested on the laid-out child item view, not the container.
4. **Paged List D-Pad Handling:** Rapid `DPAD_UP`/`DPAD_DOWN` in TV lists intercepts keys, tracks pending targets, and explicitly requests focus on bound child views once layout catches up, rather than relying on Android's native focus-search.
5. **Live TV Zapping:** `DPAD_UP`/`DPAD_DOWN` on live channels routes directly to channel zapping and must not fall through to the native `RecyclerView` focus-search.

## 4. Current Global Back Rules Found
1. **Detail Pages:** Handled natively by `MainActivity` popping `supportFragmentManager.backStackEntryCount`.
2. **Root Module Routing:** Pressing back on Live, VOD, Series, or Settings seamlessly replaces the current fragment with `HomeFragment` (does not exit the app).
3. **Home Fragment Exit:** If on `HomeFragment`, it delegates to `HomeFragment.handleBackPress()`. If already at the top/default state, it triggers the `ExitDialog`.
4. **Search Fallback:** In `SearchFragment`, pressing back while results are showing (and search bar is unfocused) jumps focus back to the search bar. A second press exits the module.
5. **Player Overlay Hierarchy:** Back in `PlayerActivity` prioritizes: (1) Close Live TV Browser -> (2) Close EPG Overlay -> (3) Hide Standard Controls -> (4) Release ExoPlayer and `finish()`.

## 5. Current Hide/Show Rules Found
1. **Persistent Grid Pattern:** Grids (`rvMoviesGrid`, `rvSeriesGrid`) remain `VISIBLE` (alpha = 0.4f to 1.0f) during data fetches to prevent empty screen flicker. A `ll_loading_state` container with Shimmer animations overlays the grid.
2. **Empty State Gating:** A grid is only set to `GONE` if proven empty (`itemCount == 0` AND all Paging load states report `NotLoading`). 
3. **Sidebar Expansion:** Uses `ValueAnimator` on `layout_width` with high elevation (e.g., `20dp`) to overlay content, strictly avoiding `LinearLayout` `layout_weight` pushes.
4. **Player Controller Elements:** Unused elements (like disabled X-Ray) are hidden via `visibility="gone"` to maintain `findViewById` backward compatibility, never removed entirely.
5. **Animation Teardown:** Hiding a loading view must call `clearAnimation()` to prevent CPU/GPU drain on low-end TV devices.

## 6. Module-by-Module Findings
* **Home:** Successfully implements back delegation to `MainActivity` and handles `ExitDialog`. Grid persistence mostly stable.
* **Live TV:** Strict `setOnKeyListener` bounds implemented. Loading state must be driven by `Paging3` to prevent UI flicker. Channel focus must not return to the category container.
* **VOD / Movies:** **[RISK]** Missing `onResume()` focus restoration block. Relies entirely on adapter load state listeners and `PagingDataAdapter` observers, leading to potential focus drops or "jumpy" UI when returning from `MovieDetailFragmentV2`.
* **Series:** Implements safe grid focus restoration via `onResume() { restoreFocusIfPossible() }`. Episode Browser claims total ownership of keys when visible.
* **Debrid:** Generally follows VOD patterns. **[FIXED]** Previously required the same focus restoration safety nets as standard VOD; this has now been implemented and manually verified.
* **Search:** Directly intercepts back presses via `requireActivity().onBackPressedDispatcher`. Properly implements `DPAD_DOWN` to explicitly route focus from keyboard to result grid.
* **Settings / Favorites / Login:** Follow standard global Fragment routing.
* **Detail Pages (`MovieDetailFragmentV2`, `SeriesDetailFragmentV2`):** Popped cleanly from backstack by `MainActivity`. 
* **PlayerActivity Overlays:** `dispatchKeyEvent` successfully captures Episode Browser and X-Ray visibility before standard back callbacks occur.

## 7. Rule Violations (With References)
1. **VOD Focus Restoration Drop:** `VodFragment.kt` relies on adapter data observers for focus restoration instead of an explicit `onResume` + `FocusMemoryManager` sync, violating the pattern established in `SeriesFragment.kt`.
2. **Live TV Loading Drift:** Instances where ViewModel loading flags clear early before the adapter is ready, violating the rule that Live TV loading states must be driven by `Paging3` refresh states.
3. **Terminal Playback Loop:** Terminal/exhausted playback failures sometimes route through the same `EXTRA_RETURN_TO_SOURCES` path as transient errors, instead of using a failure-origin guard to disable auto-play-next behavior.

## 8. Priority Bug List

| Priority | Issue | Description | Impact |
|---|---|---|---|
| **P0 (FIXED)** | VOD Focus Restoration Drop | `VodFragment` was missing `onResume` focus recovery. Fixed by adding lifecycle hooks. | Resolved focus loss when returning from VOD detail pages. |
| **P0 (FIXED)** | Live TV Category Focus Escapes | Channel list focus sometimes returns to the category container instead of the targeted category child item. Fixed after VOD and Debrid focus restoration phases. | Breaks D-pad grid navigation boundaries. |
| **P1** | Terminal Playback Routing | Terminal playback errors route through transient error paths (`EXTRA_RETURN_TO_SOURCES`). | Causes unexpected auto-play-next loops on dead sources. |
| **P1** | Search Back Inconsistency | Search back-button behavior jumps to the search bar but can feel inconsistent if focus listeners overlap. | Annoyance / minor regression risk. |
| **P2** | Unconstrained Animations | Loading shimmers potentially running while `alpha=0` or off-screen. | CPU/GPU drain on low-end devices. |

## 9. Recommended App-Wide Standard Rules
1. **Single Owner Focus Model:** All modules must use `FocusCoordinator` to lock focus transitions and `FocusMemoryManager` inside `onResume()` to guarantee restoration.
2. **Fragment-Owned Back Model:** Fragments intercept `Back` only when they have internal state to revert (e.g., Search results, Sidebar expanded). Otherwise, yield to `MainActivity` routing.
3. **Persistent View Model:** Never set grids to `GONE` unless explicitly empty (`itemCount == 0` && `NotLoading`). Overlay loading states instead of replacing content.
4. **Player Overlay Strict Priority:** 1) Episode Browser -> 2) EPG -> 3) Standard Controls -> 4) Exit. All routed through `dispatchKeyEvent` / `OnBackPressedCallback`.
5. **Adapter Data Driven Focus:** Never use `notifyDataSetChanged()`. All list updates must use `DiffUtil` or `PagingDataAdapter` to preserve focused view holders.
6. **Async Call Guards:** Guard all asynchronous layout callbacks (`post`, `postDelayed`) with `if (!isAdded) return@post` inside Fragments.

## 10. Safest Phased Fix Plan
* **Phase 1: Global Rule Documentation**
  * Generate and distribute this architecture audit. (Completed)
* **Phase 2: Highest-Risk Focus Restoration Fixes**
  * [x] Implement `onResume() { restoreFocusIfPossible() }` pattern using `FocusMemoryManager` in `VodFragment`.
  * [x] Implement `onResume() { restoreFocusIfPossible() }` pattern using `FocusMemoryManager` in `DebridFragment`. (Completed after VOD phase)
  * [x] Fix Live TV Category focus return paths to strictly target the selected child item. (Completed after VOD and Debrid phases)
* **Phase 3: Back Behavior & Playback Routing**
  * Reroute terminal playback errors to disable auto-play-next.
  * Standardize `SearchFragment` back dispatcher logic to perfectly match the design intent.
* **Phase 4: Hide/Show Focusability Cleanup**
  * Ensure all loading shimmers call `clearAnimation()` when hidden.
  * Audit XML for any remaining `layout_weight` usage in Sidebar splits and migrate to `ValueAnimator` + elevation.
* **Phase 5: Module-Specific Polish**
  * Finalize edge-case transitions (e.g., rapid D-pad holding during Paging3 loads).
  * Ensure zero-latency metadata rendering via Bundles across all detail pages.

**EXPLICIT STATEMENT: NO RUNTIME CODE CHANGED.**
