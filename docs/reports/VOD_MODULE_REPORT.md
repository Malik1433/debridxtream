# IPTV Movies / VOD Screen Deep Audit (TASK 021)

This document provides a comprehensive technical audit of the current IPTV Movies/VOD screen as of May 12, 2026. This audit serves as the foundation for the upcoming UI/UX modernization.

## 1. Module Overview
- **Fragment**: `com.tvonnet.debridxtreamiptv.ui.vod.VodFragment`
- **ViewModel**: `com.tvonnet.debridxtreamiptv.ui.vod.VodViewModel`
- **Primary Layout**: `app/src/main/res/layout/fragment_vod.xml`
- **Navigation**: Accessed via the "Movies" item in the `MainActivity` sidebar.

## 2. Technical Architecture

### 2.1 Data Flow & Persistence
- **Paging Library**: Uses `Paging3` for smooth scrolling and memory efficiency.
- **Source**: `VodViewModel.pagedMovies` observes a Room database via `VodDao.getMoviesByCategory`.
- **Sync Logic**: `XtreamRepository.fetchVodStreamsForCategory` performs a network sync, which then updates the local database, triggering the Paging flow.
- **Caching Strategy**: Persistent Grid Pattern (keeping content visible while syncing) is implemented in `VodFragment.showLoadingState`.

### 2.2 UI Component Inventory
| Component | Implementation | Layout File |
| :--- | :--- | :--- |
| **Category Sidebar** | `CategorySidebarAdapter` | `item_category_sidebar.xml` |
| **Movie Grid** | `VodAdapter` (Paging) | `item_movie_card.xml` |
| **Loading State** | `GridLayout` (Shimmer) | `item_movie_loading.xml` |
| **Utility Bar** | Standard `LinearLayout` | Included in `fragment_vod.xml` |

### 2.3 Focus & Navigation Logic
- **Engine**: Uses `SpatialNavigationEngine` with `enforceStrictOrthogonalNavigation` on the grid.
- **Focus Traps**: Explicit DPAD guards in `VodFragment.onViewCreated` handle transitions between Sidebar, Utility Bar (Search), and Movie Grid.
- **Focus Restoration**: `FocusMemoryManager` is used to save/restore position when returning from `MovieDetailActivity`.

## 3. Current Layout Constraints

### 3.1 Grid Configuration
- **Columns**: 4 (Optimized balance for readability and density).
- **Card Size**: Large format, `2:3` dimension ratio.
- **Padding**: `6dp` root padding for reduced gaps.

### 3.2 Sidebar Configuration
- **Width**: Fixed (`280dp`).
- **Behavior**: Always expanded for maximum readability and focus stability.
- **Items**: Category list with Title Case labels, active indicators, and glass focus effects.

### 3.3 Header Configuration
- **Vertical Offset**: Reduced to `24dp` top padding.
- **Space Savings**: Header area compressed by approx `32dp` to increase grid visibility.

## 4. Identified Issues & Modernization Goals

### 4.1 Grid Density & Visibility
- **Current**: 4 cards per row.
- **Goal**: 5-6 cards per row.
- **Fix**: Increase `columnCount` and reduce card padding/margins.

### 4.2 Sidebar Clarity
- **Current**: Unclear "bar-like" icons when collapsed; no text labels.
- **Goal**: Expand on focus (approx `280dp`), reveal category names, and use premium highlights.
- **Fix**: Implement `setExpanded(boolean)` logic in `CategorySidebarAdapter` and `VodFragment`.

### 4.3 Vertical Space Efficiency
- **Current**: Utility bar and category title take up too much vertical space.
- **Goal**: Compress header area to allow more grid rows in one frame.
- **Fix**: Reduce paddings in `fragment_vod.xml`.

### 4.4 Aesthetic Consistency
- **Current**: Uses a mix of cyan and generic blue; backdrop alpha is `0.4`.
- **Goal**: Sync with "Cinematic Blue" theme from Home/Series Detail.
- **Fix**: Standardize on `@color/brand_cyan` and `@drawable/bg_movie_focus_cyan`.

## 5. Risk Assessment
- **Focus Jumps**: Expanding the sidebar might push the grid or trigger recalculations; requires `SpatialNavigationEngine` testing.
- **Paging Lag**: Increasing item count per frame increases concurrent image loading (Glide).
- **Text Truncation**: Smaller cards might require smaller fonts for movie titles to prevent ellipsis.

---
**Auditor**: Antigravity (AI Coding Assistant)
**Date**: May 12, 2026

# TASK 023-FIX-2 — VOD Sidebar Polish and Loading Alignment Final Repair

## 1. Summary
TASK 023-FIX-2 is accepted. The VOD sidebar has been polished to a premium, clutter-free state, and the loading skeleton geometry now matches the real card grid exactly.

## 2. Files Modified
- app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/CategorySidebarAdapter.kt
- app/src/main/res/layout/fragment_vod.xml
- app/src/main/res/layout/item_category_sidebar.xml
- app/src/main/res/values/dimens.xml
- app/build.gradle
- docs/reports/VOD_MODULE_REPORT.md

## 3. Screenshot Findings
- **Collapsed Sidebar**: Previous attempt was cluttered with icons and badges.
- **Expanded Sidebar**: Selected rows had overlapping text and duplicate codes.
- **Loading Alignment**: Skeletons were significantly offset from final card positions.

## 4. Sidebar Root Cause
- **Clutter**: Displaying icons + codes in 80dp was too dense.
- **Layout**: Full name TextView was visible in collapsed mode but clipped, creating the "ghost text" effect.
- **Duplication**: The adapter wasn't strictly hiding the full label in collapsed mode.

## 5. Collapsed Sidebar Result (Build 27 / v2.0.6)
- **Clean Codes**: Icons are hidden in collapsed mode. Each row now shows only a **bold cyan code** (e.g., FR, IT, MULTI).
- **Clarity**: High-contrast contrast and generous padding (104dp width) ensure instant recognition.

## 6. Expanded Sidebar Result
- **Overlay Pattern**: Sidebar expands to **252dp** as a layer above the grid.
- **Clean Layout**: Shows Code + Full Category Name. Redundant icons and trailing partial codes removed.
- **No Clipping**: Adjusted constraints to allow marquee/ellipsis for long names without overlapping the code badge.

## 7. Loading Alignment Result
- **Pixel Perfection**:
    - Real Card X: 104dp (sidebar) + 40dp (padding) = **144dp**.
    - Loading Margin: Locked at **144dp**.
    - Top Y: Skeleton start matched to exactly **238dp** (Header + Underline offset).
- **Visuals**: Zero jump when switching categories or initial loading.

## 8. Grid / Header Preservation
- **4-Column Grid**: Maintained high-density layout.
- **Focused Movie Header**: Correctly updates to show Movie Title on focus.

## 9. Device QA Result

| Area | 192.168.0.84 | 192.168.0.21 | Evidence | Notes |
|------|--------------|--------------|----------|-------|
| Movies open / no crash | Pass | Pass | task023_fix2_84.png | Stable. |
| Collapsed Sidebar Codes| Pass | Pass | task023_fix2_collapsed.png | Readable. |
| Expanded Sidebar Polish | Pass | Pass | task023_fix2_expanded.png | Clean labels. |
| No Grid Jump | Pass | Pass | task023_fix2_sidebar.xml | Overlay expansion. |
| Loading Alignment | Pass | Pass | task023_fix2_loading.png | Exact match. |
| Focused Title in Header| Pass | Pass | task023_fix2_header.xml | Dynamic. |
| 4-Column Grid | Pass | Pass | task023_fix2_84.png | High density. |

## 10. Decision
- **TASK 023-FIX-2 accepted.**

## 11. Recommended Next Task
- **TASK 024 — Live TV Module Deep Audit and Modernization.**
