# App Failed Patterns

Patterns that have caused bugs, crashes, or poor UX in this project.

## 1. Hardcoded UI Offsets
- **Avoid:** `android:layout_marginStart="144dp"` to align an element with a grid that starts "somewhere over there".
- **Reason:** Breaks on different screen sizes (720p vs 1080p vs 4K) and dynamic sidebar widths.

## 2. Premature Empty State
- **Avoid:** Checking `itemCount == 0` immediately when a category is selected.
- **Reason:** Paging data takes time to invalidate and fetch from the database. This leads to a "No series available" flash before cards appear.
- **Fix:** Always check `!viewModel.isSwitchingCategory` before showing the empty state.

## 3. Adapter-Driven UI Updates
- **Avoid:** Updating Fragment-level views (like Headers or Backdrops) from inside `onBindViewHolder`.
- **Reason:** Tight coupling and inconsistent updates during fast scrolling or view recycling.
- **Fix:** Use `addOnChildAttachStateChangeListener` on the RecyclerView in the Fragment.

## 4. Unconstrained Shimmer Animations
- **Avoid:** Running infinite animations on views that might be `GONE` or hidden via `alpha=0` but still attached to the window.
- **Reason:** Unnecessary CPU/GPU usage on low-end TV devices.
- **Fix:** Always call `clearAnimation()` when hiding a loading view.
## 5. Duplicate Playback Activities
- **Avoid:** Creating `DebridPlayerActivity` vs `IptvPlayerActivity`.
- **Reason:** Leads to fragmented player logic, mismatched key behaviors, and maintenance overhead for common overlays (subtitles, audio tracks).
- **Fix:** Use a single `PlayerActivity` with source-aware controllers.
