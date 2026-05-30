# Debrid Sidebar Focus + Route Audit

## Executive Summary
This audit reviews the current state of the Debrid section's sidebar (permanent navigation menu). The sidebar successfully manages its focus boundaries and restores focus to the main content correctly. However, half of the navigation buttons are stubbed ("Coming Soon"), and the visual layout (e.g., text sizes and icon scaling) does not fully align with modern couch-distance viewing standards or the recently integrated premium cinematic styling.

## Current Sidebar Map

| Label | View ID | Click Handler | Expected Action | Actual Action | Status | File / Line |
|---|---|---|---|---|---|---|
| **Search** | `nav_item_search` | `startActivity` | Open Search | Starts `DebridSearchActivity` | **Working** | `DebridFragment.kt`:220 |
| **Home** | `nav_item_home` | `smoothScrollToPosition` | Go to Home | Scrolls `rvDebridRows` to top | **Working** | `DebridFragment.kt`:225 |
| **Discover** | `nav_item_discover` | `startActivity` | Open Discover | Starts `DebridDiscoverActivity` | **Working** | `DebridFragment.kt`:230 |
| **Library** | `nav_item_library` | `Toast` | Open User Library | Toasts "Coming Soon" | **Broken** | `DebridFragment.kt`:237 |
| **Movies** | `nav_item_movies` | `Toast` | Open Movies section | Toasts "Coming Soon" | **Broken** | `DebridFragment.kt`:238 |
| **Series** | `nav_item_series` | `Toast` | Open Series section | Toasts "Coming Soon" | **Broken** | `DebridFragment.kt`:239 |

## Focus Issue Table

| Interaction | Behavior Found | Status |
|---|---|---|
| **Initial Focus** | `navItemHome` explicitly requests focus when Debrid is entered. | Safe |
| **Left from Cards** | Handled by `DebridRowsAdapter` triggering `onLeftBoundary`. Calls `returnToSidebar()`, returning to the last active nav item ID. | Safe |
| **Right from Sidebar** | DPAD_RIGHT on any sidebar item intercepts key event and calls `restoreFocusIfPossible()`. | Safe |
| **Up/Down in Sidebar** | Top (`nav_item_search`) sets `nextFocusUpId` to itself. Bottom (`nav_item_series`) sets `nextFocusDownId` to itself. Trapping vertical focus successfully. | Safe |
| **Back Behavior** | Not explicitly overridden. System default back applies (exits fragment). | Safe |
| **Selected/Active state** | Controlled by `SidebarFocusController`. Unfocused active item dims pill alpha to 0.2f. Focused item brightens pill to 0.6f. | Safe |
| **Hero Integration** | Hero sets `btnPrimary.isFocusable = false`. It is completely passive. | Safe |

## Visual/UI Gap Table

| UI Element | Observation | Recommendation |
|---|---|---|
| **Text Size** | `14sp` is used for sidebar labels (`item_debrid_sidebar.xml`). | Increase to `16sp` or `18sp` for better TV couch-distance readability. |
| **Icon Size** | `24dp` is standard mobile size, but can feel small on TV. | Increase to `28dp` or `32dp` to improve visual hierarchy. |
| **Micro-animations** | `SidebarFocusController` translates text slightly, scales icons by 1.06x. | Good, but could incorporate scale animations for the focus pill itself. |
| **Spacing/Padding** | `48dp` top buffer in `view_debrid_sidebar.xml`, `8dp` between items. | Adequate, though could use slight tuning for vertical centering. |
| **Integration** | Sidebar still uses basic white/muted colors instead of the premium gold/cinematic elements introduced in Phase 2. | Introduce gold highlight or cinematic gradient for the active state to unify the UI. |

## Root Causes
1. **Broken Buttons**: The routing logic for Library, Movies, and Series has not yet been implemented (they just display Toast messages).
2. **Visual Immaturity**: The sidebar layout uses hardcoded mobile-centric dimensions (`14sp` text, `24dp` icons) that lack a premium TV feel.

## Minimal Safe Fix Plan
1. **Visual Overhaul (Phase 1)**: 
   - Update `item_debrid_sidebar.xml` to increase text size to `16sp`/`18sp` and icon sizes to `28dp`/`32dp`.
   - Update `SidebarFocusController.kt` to use a more premium highlight color (e.g., gold gradient/tint) to match the cinematic rows.
2. **Implement Routes (Phase 2)**: 
   - Wire the `Library`, `Movies`, and `Series` buttons to their respective Activity or Fragment endpoints in `DebridFragment.kt`, replacing the Toast placeholders.

## Files Likely to Change Later
- `app/src/main/res/layout/item_debrid_sidebar.xml`
- `app/src/main/res/layout/view_debrid_sidebar.xml`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/SidebarFocusController.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/DebridFragment.kt`

## What Not to Touch
- Do not modify focus boundaries (`onLeftBoundary`, `restoreFocusIfPossible()`) as they are currently working and stable.
- Do not alter `PlayerActivity`, `PlaybackResolver`, or other unrelated modules.

## QA Checklist
- [x] D-pad left from first card enters sidebar
- [x] D-pad right from sidebar returns to same/nearest card
- [x] Up/down sidebar movement stable (does not escape at top/bottom)
- [x] OK on every sidebar button performs correct action
- [x] Active/focused state is visually clear and readable
- [x] Back behavior unchanged
- [x] Hero does not steal focus
- [x] Rows still work
- [x] Continue Watching still works

## 2026-05-28 Floating Rail Gutter Correction
- Runtime layout now uses an icon-only floating Debrid rail over the shared full-screen backdrop, not a full-height sidebar block.
- Corrected the main viewport start margin from `168dp` to `144dp` in `fragment_debrid.xml`.
- Rail position remains `84dp` wide with `52dp` start margin and vertical centering. On `192.168.0.84:5555`, UI bounds were rail `[104,388][272,692]`, main viewport `[288,0][1920,1080]`, backdrop `[0,0][1920,1080]`.
- Visible rail buttons remain Search and Discover only; no labels, tooltips, Movies, Series, Library, Home, or Coming Soon entries were added.
- Device QA on `192.168.0.84:5555`: Search opened, Back restored Debrid focus, Discover returned top content focus, left from first content card entered the rail, up/down stayed between Search and Discover, right returned to content, hero stayed non-focusable, and Continue Watching was present.

## 2026-05-28 Row Heading + Spacing Polish
- Floating rail XML and behavior were left unchanged.
- Row title visibility was polished in `item_debrid_row.xml`: row container clipping disabled, top padding kept at `16dp`, bottom padding set to `20dp`, title top margin set to `0dp`, and title bottom margin set to `14dp`.
- Passive hero synopsis was capped in `view_debrid_hero.xml`: synopsis top margin `16dp -> 12dp`, line spacing `4dp -> 2dp`, and max lines `3 -> 2`.
- Device QA on `192.168.0.84:5555`: Continue Watching heading visible above first cards, Trending Movies heading visible at top and after DPAD_DOWN focus, hero remained non-focusable, left/right rail focus remained stable, Continue Watching OK opened Player, Trending OK opened detail, and Back restored Debrid focus.
