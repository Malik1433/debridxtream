# App Success Patterns

Established engineering and UI patterns that have proven stable and performant in the DebridXtream codebase.

## 1. Persistent Grid Pattern
- **Definition:** Keep the current RecyclerView content visible (dimmed) while a background sync or category refresh is happening.
- **Benefits:** Prevents "empty screen flicker" and maintains focus context for the user.
- **Implementation:** Set `rv.alpha = 0.4f` and show a centered `ProgressBar` overlay.

## 2. Shared Coordinate Space (Overlays)
- **Definition:** Place loading indicators, empty states, and content grids inside the same `FrameLayout` or `ConstraintLayout` container.
- **Benefits:** Guarantees that overlays are centered relative to the content area, ignoring sidebars or headers.
- **Implementation:** 
    ```xml
    <FrameLayout>
        <RecyclerView android:id="@+id/grid" />
        <LinearLayout android:id="@+id/loading" android:layout_gravity="center" />
    </FrameLayout>
    ```

## 3. Atomic Category Switching
- **Definition:** Using a `isSwitchingCategory` flag in the UI State combined with a small delay before clearing the loading state.
- **Benefits:** Synchronizes the slow emission of Room/Paging3 data with the rapid state change of a ViewModel.
- **Implementation:** Use `kotlinx.coroutines.delay(1000)` after a successful repository fetch before setting `isLoading = false`.

## 4. Lumina Sidebar Overlay
- **Definition:** Sidebar expands over the content area rather than pushing it.
- **Benefits:** Maintains grid item size and aspect ratio during focus transitions.
- **Implementation:** Sidebar uses high elevation (`20dp`) and animates its `layout_width` via `ValueAnimator`.
## 5. Shared Source-Gated Overlays
- **Definition:** Using a single Activity/Fragment for multiple content sources (IPTV vs Debrid) but gating specific UI overlays based on content type or source metadata.
- **Benefits:** Reduces code duplication and ensures a consistent UX across different backend sources.
- **Implementation:** In `dispatchKeyEvent`, use helper methods like `isSeriesEpisodePlayback()` to route keys to specific overlay controllers.
