# TASK 011  Home Screen Deep Audit

## 1. Executive Summary

Home is functional but not yet stable enough for the target Android TV quality bar. The current screen is a single `HomeFragment` hosted by `MainActivity`, using `fragment_home_cinematic.xml`, a left sidebar, a hero background, and two horizontal RecyclerView rows: Trending Movies and Trending Series.

The biggest risks are not crashes in normal startup, but Android TV navigation reliability: manual focus restoration can fail when target ViewHolders are not attached, sidebar navigation adds root sections to the back stack, hero buttons are focusable but have no click actions, and repeated data reloads can rebind focused rows without DiffUtil or stable IDs. Several older Home adapters and models remain in the package but are not wired into the active cinematic layout, increasing maintenance risk.

## 2. Files Inspected

Kotlin:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/MainActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/InitialSyncFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/LoginFragment.kt` (read only for login-to-sync-to-Home dependency)
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/SidebarAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/Top10Adapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/FeaturedAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/ContinueWatchingAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/FavoritesAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/NewAddedAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/RecentLiveChannelsAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/RecentlyWatchedAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeSampleData.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFavoriteMapper.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/model/HomeScreenModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/HomePreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/utils/SidebarFocusHelper.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/util/FocusEffects.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/network/NetworkQualityManager.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepository.kt` (read only for cache/repository dependency)
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/TmdbRemoteDataSource.kt` (read only for Home data dependency)

XML/resources:

- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/fragment_home_cinematic.xml`
- `app/src/main/res/layout/view_home_sidebar.xml`
- `app/src/main/res/layout/item_top_10_card.xml`
- `app/src/main/res/layout/item_sidebar_nav.xml`
- `app/src/main/res/layout/item_featured_card.xml`
- `app/src/main/res/layout/item_continue_watching_card.xml`
- `app/src/main/res/layout/item_favorite_card.xml`
- `app/src/main/res/layout/item_new_added_card.xml`
- `app/src/main/res/layout/item_recent_live_card.xml`
- `app/src/main/res/layout/item_home_section_row.xml`
- `app/src/main/res/layout/item_hero_card.xml`
- `app/src/main/res/layout/item_hero_banner.xml`
- `app/src/main/res/drawable/selector_sidebar_item.xml`
- `app/src/main/res/drawable/bg_sidebar_nav_active.xml`
- `app/src/main/res/drawable/bg_sidebar_nav_focused.xml`
- `app/src/main/res/drawable/bg_sidebar_nav_default.xml`
- `app/src/main/res/drawable/bg_card_focus_cyan.xml`
- `app/src/main/res/drawable/sidebar_alive_bg.xml`
- `app/src/main/res/drawable/shadow_sidebar_right.xml`
- `app/src/main/res/drawable/vignette_lumina.xml`
- `app/src/main/res/animator/sidebar_item_animator.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/dimens_cinematic.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/colors_stitch.xml`
- `app/src/main/res/values/colors_cinematic.xml`
- `app/src/main/res/values/colors_tv_modern.xml`
- `app/src/main/res/values/strings.xml`

## 3. Current Home Architecture

`MainActivity` is the host Activity. Its layout is a full-screen `FrameLayout` named `content_container` (`activity_main.xml` lines 3-6). On authenticated cold start, `MainActivity` initializes `XtreamRepository` and `GlobalConfig`, then replaces `content_container` with `HomeFragment` (`MainActivity.kt` lines 81-93).

After login, `LoginFragment` navigates to `InitialSyncFragment` (`LoginFragment.kt` line 295). `InitialSyncFragment` starts initial sync and replaces the container with `HomeFragment` after success, guarded by `hasNavigated` (`InitialSyncFragment.kt` lines 36, 83-90, 125-130).

The active Home class is `HomeFragment`. It inflates `fragment_home_cinematic.xml` (`HomeFragment.kt` lines 75-81). The active layout is mixed static and RecyclerView based: static hero area and two horizontal RecyclerViews (`rv_top_10_movies`, `rv_top_10_series`) inside a `NestedScrollView`, plus a vertical sidebar RecyclerView included from `view_home_sidebar.xml`.

`HomeFragment` owns both navigation and rendering setup. It also directly initializes credentials/repository (`HomeFragment.kt` lines 87-90, 125-131), even though `MainActivity` already initializes the repository on authenticated startup. Home uses `HomeViewModel` with `StateFlow`, `viewModelScope`, repository sync progress, preferences, and TMDB remote calls (`HomeViewModel.kt` lines 35-44, 50-69, 72-132).

Home depends directly on IPTV/VOD/Series/Debrid concepts:

- Sidebar routes to Live, Movies/VOD, Series, Debrid, Search, and Settings (`HomeFragment.kt` lines 199-217, 393-406).
- Top 10 fallback uses cached VOD and Series (`HomeViewModel.kt` lines 91-105).
- Click routing opens `MovieDetailActivity`, `SeriesDetailActivity`, or `PlayerActivity` depending on source/content type (`HomeFragment.kt` lines 409-487).

Legacy or duplicate code paths exist. The current cinematic fragment only wires `Top10Adapter` and `SidebarAdapter`, but older adapters remain: `FeaturedAdapter`, `ContinueWatchingAdapter`, `FavoritesAdapter`, `NewAddedAdapter`, `RecentLiveChannelsAdapter`, and `RecentlyWatchedAdapter`. `HomeUiState.sections` and `HomeSection` also remain but are populated as an empty list (`HomeViewModel.kt` lines 19-31, 120-130).

## 4. Layout Structure

Root layout is `CoordinatorLayout` with full-screen hero `ImageView`, full-screen overlay view, then a `ConstraintLayout` shell (`fragment_home_cinematic.xml` lines 2-30). Sidebar is anchored left with fixed `@dimen/sidebar_width_expanded`, and main content starts to the right of `sidebar_panel` (`fragment_home_cinematic.xml` lines 33-47, 225-232).

Main content is a `NestedScrollView` with vertical `LinearLayout`, static hero, and two row sections (`fragment_home_cinematic.xml` lines 33-222). The rows are horizontal RecyclerViews with `wrap_content` height and `clipChildren=false` (`fragment_home_cinematic.xml` lines 169-180, 206-217). The hero title is 48sp and constrained to 70 percent width; description is 16sp and 60 percent width (`fragment_home_cinematic.xml` lines 62-96).

Sidebar structure is a `ConstraintLayout` with header, divider, vertical `rv_sidebar`, divider, and pinned Settings include (`view_home_sidebar.xml` lines 2-14, 16-93). Sidebar list rows are 56dp high and focusable/clickable (`item_sidebar_nav.xml` lines 6-12).

Top 10 card structure is a focusable root `ConstraintLayout`, a CardView at 140dp x 210dp, and a large rank number at 160sp with negative X translation (`item_top_10_card.xml` lines 5-21, 40-56; `dimens.xml` lines 143-149).

1080p and overscan risks:

- Top/bottom content padding is 48dp, but content is in a `NestedScrollView`, so vertical overflow is scrollable (`fragment_home_cinematic.xml` lines 41-43).
- The sidebar is flush to the physical left edge and top/bottom (`fragment_home_cinematic.xml` lines 225-232), with only internal 16dp/20dp padding (`view_home_sidebar.xml` lines 9-12). This is visually clean but not overscan-conservative.
- Focus scale is applied to cards and sidebar items. `clipChildren=false` is present on key containers, but RecyclerView rows inside `NestedScrollView` can still produce edge clipping or scroll jitter when scaled cards extend beyond attached child bounds (`fragment_home_cinematic.xml` lines 29-30, 38-39, 53-54, 175-176, 212-213; `Top10Adapter.kt` lines 79-85).
- Sidebar width is inconsistent between layout dimen and helper constants: `sidebar_width_expanded` is 240dp, while `SidebarFocusHelper` animates to 260dp and collapsed 80dp (`dimens.xml` lines 145-146; `SidebarFocusHelper.kt` constants). That forces layout recalculation during focus transitions.

## 5. Navigation and Backstack Behavior

Initial Home loading is a fragment replace with no back stack on authenticated launch (`MainActivity.kt` lines 89-93). Initial sync also replaces with Home and does not add to the back stack (`InitialSyncFragment.kt` lines 125-130).

Home sidebar navigation creates a new Fragment instance and always adds it to the back stack for Search, Live, Movies, Series, Debrid, and Settings (`HomeFragment.kt` lines 393-406). Home itself is a no-op when selected from Home (`HomeFragment.kt` lines 211-216). Settings is a pinned item outside the sidebar adapter and also adds to the back stack (`HomeFragment.kt` lines 221-256, 393-406).

`MainActivity.onBackPressed()` first pops any back stack entry, then if current fragment is Home it delegates scroll-to-top behavior before showing `ExitDialog`; otherwise it replaces current root fragment with a new `HomeFragment` without back stack (`MainActivity.kt` lines 179-204).

Backstack risks:

- Sidebar root navigation from Home uses `addToBackStack(null)`, so pressing Back from Live/VOD/Series/Debrid/Search returns to the previous Home instance rather than treating those as peer root sections (`HomeFragment.kt` lines 403-406).
- Voice search and settings intent navigation also add to back stack (`MainActivity.kt` lines 115-120, 167-176, 246-250).
- Non-Home root fragments can be replaced by a new Home instance on Back when there is no back stack (`MainActivity.kt` lines 198-203), so Home state/focus is not shared or restored across that path.
- Fragment transactions use `commit { replace(...) }`; no state-saved guard is visible around Home/sidebar navigation (`HomeFragment.kt` lines 393-406; `MainActivity.kt` lines 115-120, 201-203).

## 6. Android TV Focus Behavior

Initial focus is not explicitly set in `onViewCreated`. Focus is restored only after `HomeViewModel` emits a non-loading state with non-empty `top10Movies` (`HomeFragment.kt` lines 97-118). If data is empty, loading never completes, or only series is available, Home may open without an intentional initial focus target.

Content focus memory is local to the fragment instance: `lastFocusedRvIndex` and `lastFocusedItemIndex` (`HomeFragment.kt` lines 63-66). It is updated only when a Top 10 item receives focus (`HomeFragment.kt` lines 493-514). It is lost on fragment recreation or root navigation back to new Home.

DPAD behavior:

- `DPAD_RIGHT` on `rv_sidebar` and Settings calls `restoreContentFocus()` (`HomeFragment.kt` lines 161-168, 250-256).
- `DPAD_LEFT` on first Top 10 card calls `returnToSidebar()` (`Top10Adapter.kt` lines 91-99).
- `DPAD_LEFT` on hero buttons calls `returnToSidebar()` (`HomeFragment.kt` lines 357-379).
- `DPAD_UP`, `DPAD_DOWN`, `DPAD_CENTER/ENTER`, and BACK are mostly left to default Android focus or fragment/activity behavior.

Focus disappearance/jump risks:

- `restoreContentFocus()` posts a focus request only for already-attached ViewHolders; if the target holder is not attached, fallback also checks attached position 0 and then does nothing (`HomeFragment.kt` lines 171-189). There is no scroll-to-position before requesting focus.
- `restoreContentFocus()` always smooth-scrolls to top when `lastFocusedRvIndex == 0`, which can cause top resets after returning from sidebar to the movies row (`HomeFragment.kt` lines 176-179).
- Data reload calls `updateItems()` and may rebind focused cards. `Top10Adapter` has no stable IDs and no DiffUtil; same-size updates use `notifyItemRangeChanged`, different-size updates use `notifyDataSetChanged()` (`Top10Adapter.kt` lines 31-40).
- Sidebar expansion uses a global focus listener plus adapter rebinding via `notifyDataSetChanged()` on every expanded/collapsed state change (`HomeFragment.kt` lines 263-318; `SidebarAdapter.kt` lines 53-57). This can compete with RecyclerView focus while focus is moving.
- Active sidebar state is separate in intent but incomplete in implementation. Adapter `selectedPosition` defaults to Home, but `activeNavItemId` in `HomeFragment` is never updated after navigation selection (`HomeFragment.kt` line 66; `SidebarAdapter.kt` lines 30, 45-50).
- Focus state and active state use both adapter-managed backgrounds and XML selector/stateListAnimator. The row has `android:stateListAnimator` and Kotlin also animates scale/translationZ (`item_sidebar_nav.xml` line 12; `SidebarAdapter.kt` lines 92-131), creating duplicate focus animation paths.
- `postDelayed` is used in `SidebarFocusHelper` for focus-loss grace period. It removes the global listener on detach, but delayed callbacks are not explicitly cancelled (`SidebarFocusHelper.kt` focus-loss logic).

## 7. Data Loading and Adapter Behavior

Home displays only two active sections:

- Trending Movies: TMDB trending movies, fallback to cached IPTV VOD sorted by added date (`HomeViewModel.kt` lines 86-96).
- Trending Series: TMDB trending TV, fallback to cached IPTV series first 10 (`HomeViewModel.kt` lines 98-106).

Hero content is derived from the first available movie or series (`HomeViewModel.kt` lines 108-118), but `HomeFragment` independently updates the hero from the first row item after adapter updates (`HomeFragment.kt` lines 101-108).

Data sources:

- `XtreamRepository.readCache()` is required. If cache is null, `loadHomeData()` returns without updating `isLoading=false`, so the fragment never receives a non-loading state (`HomeViewModel.kt` lines 72-80).
- `TmdbRemoteDataSource` is called every `loadHomeData()` for movies and series (`HomeViewModel.kt` lines 86-106).
- `repository.syncProgress` and `HomePreferences.getCategoriesChangedFlow()` can trigger repeated reloads (`HomeViewModel.kt` lines 50-69).

Adapter stability:

- `Top10Adapter` does not call `setHasStableIds()` and does not override `getItemId()` (`Top10Adapter.kt` lines 24-53).
- `Top10Adapter.updateItems()` uses `notifyItemRangeChanged()` or `notifyDataSetChanged()` instead of DiffUtil/ListAdapter (`Top10Adapter.kt` lines 31-40).
- `SidebarAdapter` does use stable IDs based on `SidebarItem.id` (`SidebarAdapter.kt` lines 22-28), but expansion uses `notifyDataSetChanged()` (`SidebarAdapter.kt` lines 53-57).
- Older Home adapters mostly use `notifyDataSetChanged()`; only `FeaturedAdapter` has stable IDs (`FeaturedAdapter.kt`, `ContinueWatchingAdapter.kt`, `FavoritesAdapter.kt`, `NewAddedAdapter.kt`, `RecentLiveChannelsAdapter.kt`, `RecentlyWatchedAdapter.kt`).

Empty/loading/error states are incomplete. There is no visible loading skeleton/error/empty UI in `fragment_home_cinematic.xml`, and `HomeUiState` has only `isLoading`; no error field is modeled (`HomeViewModel.kt` lines 19-24). Empty rows are not hidden or replaced with an empty state.

Late data arrival can steal focus through first-load restore and repeated adapter updates (`HomeFragment.kt` lines 97-118; `Top10Adapter.kt` lines 31-40). Image loading is fixed-size for cards, so it should not change card dimensions, but hero image cross-fades can change perceived screen brightness while navigating (`HomeFragment.kt` lines 328-351).

## 8. Card and Row Rendering

Top 10 cards are poster cards with a large rank overlay. Poster dimensions are fixed at 140dp x 210dp, with 10-20dp margins and a 160sp rank number translated left/down (`item_top_10_card.xml` lines 13-21, 40-56; `dimens.xml` lines 143-149). Focus scales the entire root to 1.15 and applies a slight 3D tilt (`Top10Adapter.kt` lines 79-85; `FocusEffects.kt` scale/tilt methods).

The card foreground is `bg_card_focus_cyan`, a focused selector with 3dp cyan stroke and transparent default (`item_top_10_card.xml` line 21; `bg_card_focus_cyan.xml`). Focus visuals are clear from a distance, but the 1.15 scale on a `wrap_content` item inside a horizontal RecyclerView can overlap adjacent cards and row bounds.

Click routing:

- TMDB movie opens `MovieDetailActivity` with debrid category metadata (`HomeFragment.kt` lines 413-422).
- TMDB series opens `SeriesDetailActivity` with `EXTRA_IS_DEBRID=true` (`HomeFragment.kt` lines 423-431).
- IPTV movie opens `PlayerActivity` only if `streamUrl` exists; otherwise click silently does nothing (`HomeFragment.kt` lines 433-447).
- IPTV live launches a live stream or shows "Stream unavailable" (`HomeFragment.kt` lines 448-487).
- Hero buttons receive focus styling/key handling, but no click listeners are assigned (`HomeFragment.kt` lines 353-379).

Rows do not have row adapters; there are only two independent RecyclerViews. `rv_top_10_movies` and `rv_top_10_series` are direct children inside a vertical scroll container (`fragment_home_cinematic.xml` lines 169-217).

## 9. Performance and Lifecycle Risks

Heavy work/reload risks:

- Every Home data load performs TMDB trending movie and series calls before falling back to cache (`HomeViewModel.kt` lines 86-106). If `TmdbRemoteDataSource` does not internally cache or use IO dispatching, Home can reload network-backed content repeatedly.
- `repository.syncProgress.collect` calls `repository.readCache()` in the collector condition and triggers `loadHomeData()` whenever sync succeeds or cache exists (`HomeViewModel.kt` lines 50-57). Depending on syncProgress emissions, this can duplicate loads.
- HomePreferences changes trigger a full load, but category preferences are not actually applied to the active rows (`HomeViewModel.kt` lines 61-65, 86-106).

Lifecycle/memory:

- `repeatOnLifecycle(STARTED)` is correctly used for Fragment UI collection (`HomeFragment.kt` lines 97-122).
- `viewModelScope` scopes Home loads to the ViewModel (`HomeViewModel.kt` lines 50-73).
- `onDestroyView()` does not clear RecyclerView adapters, listeners, or Glide target requests (`HomeFragment.kt` lines 519-525). Because the Fragment keeps lateinit view references, this can retain old view hierarchies after view destruction.
- `SidebarFocusHelper` removes the global focus listener on detach and cancels its animator, but posted delayed callbacks can still run later and check attachment (`SidebarFocusHelper.kt` focus-loss logic).
- `Glide.with(this)` for hero is lifecycle-aware to the Fragment (`HomeFragment.kt` line 328). Card image loads use `Glide.with(itemView.context)`, less tightly scoped than a Fragment/View lifecycle (`Top10Adapter.kt` line 70).

Crash risks:

- `SidebarAdapter.selectItem()` uses `adapterPosition` from the ViewHolder click path without checking `NO_POSITION` (`SidebarAdapter.kt` lines 135-137). A stale/rebinding ViewHolder click could pass `-1` into `selectItem()`, which then calls `notifyItemChanged(-1)` and accesses no item directly but can throw RecyclerView position errors.
- Fragment transactions are not guarded against state loss. Sidebar clicks after state save could cause transaction timing exceptions (`HomeFragment.kt` lines 393-406).

## 10. Visual/UI Quality Assessment

The current visual direction is closer to premium than the older Home assets: full-screen art, readable gradient overlay, fixed sidebar, clear row hierarchy, and high-contrast cyan focus. It is more cinematic than utilitarian, and generally aligned with Stremio/Samsung-style browsing.

Remaining gaps versus TiviMate-grade stability and premium TV UX:

- Focus reliability is not deterministic enough. Initial focus, restoration, row boundaries, and sidebar-to-content return need stronger rules.
- Hero controls look actionable but are not wired to play/details clicks, which is a premium-UX break.
- Only two rows are active, so the Home page feels sparse for an IPTV/VOD/Series app.
- The sidebar active state is visually present for Home, but global navigation/backstack behavior does not behave like stable root tabs.
- Large rank text and focus scaling are visually bold but need screenshot/device verification for clipping at 1080p and overscan.
- The cyan/emerald mixed palette is readable but not fully harmonized with the recent Login modernization without a final visual pass.

## 11. Findings Table

| ID | Severity | Area | Finding | Evidence | Risk | Recommended Future Fix |
|----|----------|------|---------|----------|------|------------------------|
| H-001 | CRITICAL | Focus | Home has no deterministic initial focus when movies are empty, cache is null, or loading never completes. | `restoreContentFocus()` is only called when `!state.isLoading && state.top10Movies.isNotEmpty()` in `HomeFragment.kt` lines 101-118; `HomeViewModel` returns early with cache null without clearing loading at lines 72-80. | User can land on Home with no obvious focus target or unrecoverable D-pad start state. | Add explicit initial focus policy with fallback order: remembered content, first available row, hero primary action, Home sidebar item; model empty/error state. |
| H-002 | CRITICAL | Focus | `restoreContentFocus()` can fail silently if target ViewHolder is not attached. | It calls `findViewHolderForAdapterPosition(lastFocusedItemIndex)` and fallback position 0 without `scrollToPosition()` or retry after layout, `HomeFragment.kt` lines 171-189. | DPAD_RIGHT from sidebar can leave focus on sidebar or disappear instead of returning to content. | Scroll target RecyclerView to position, wait for layout/child attach, then request focus; clamp index to adapter bounds. |
| H-003 | HIGH | Backstack | Sidebar root navigation adds peer sections to back stack. | `navigateToSection()` always `replace()` + `addToBackStack(null)`, `HomeFragment.kt` lines 393-406. | Back from Live/VOD/Series/Search returns to stale Home instance and can pollute history during tab navigation. | Treat sidebar destinations as root replacements with a single source of truth in `MainActivity`; only detail flows should use back stack. |
| H-004 | HIGH | Focus/Data | Data reload can rebind focused Top 10 cards without stable IDs or DiffUtil. | `Top10Adapter` lacks stable IDs and uses `notifyItemRangeChanged()`/`notifyDataSetChanged()`, `Top10Adapter.kt` lines 24-40. | Focus jumps, top resets, image flicker, and row instability after sync/preference reload. | Convert Top 10 to ListAdapter/DiffUtil with stable content IDs and focus-preserving update policy. |
| H-005 | HIGH | Sidebar Focus | Sidebar expansion changes container width and rebinds the adapter during focus transitions. | `SidebarFocusHelper.attachStandardSidebarAnimation()` animates width; callback calls `sidebarAdapter.setExpanded()`, which uses `notifyDataSetChanged()`, `HomeFragment.kt` lines 263-318 and `SidebarAdapter.kt` lines 53-57. | RecyclerView may lose focused child, relayout content, or jitter when moving between sidebar/content. | Stabilize sidebar width or animate non-layout properties; avoid full adapter rebind for expansion. |
| H-006 | HIGH | Layout | Sidebar expanded width is inconsistent across resources/helper. | `sidebar_width_expanded` is 240dp in `dimens.xml` line 146; helper expands to 260dp and collapses to 80dp. | Content constraint boundary shifts during focus and can cause visible layout recalculation. | Use one dimen source for collapsed/expanded width and verify 1080p screenshots. |
| H-007 | HIGH | UX/Click | Hero buttons are focusable but not clickable. | `btn_hero_watch` and `btn_hero_details` have focus/key listeners only, `HomeFragment.kt` lines 353-379; XML defines visible buttons, `fragment_home_cinematic.xml` lines 107-141. | Users can focus primary actions but pressing center may do nothing. | Wire hero actions to the current hero item or remove focusability until behavior exists. |
| H-008 | HIGH | Lifecycle | `onDestroyView()` does not clear view references, adapters, listeners, or image requests. | Empty override at `HomeFragment.kt` lines 519-525; Fragment keeps lateinit view fields lines 56-72. | View hierarchy can be retained after destruction; delayed focus/Glide/listener work can target stale views. | Clear RecyclerView adapters/listeners and use nullable binding/view refs in a lifecycle-safe cleanup pass. |
| H-009 | HIGH | Crash | Sidebar click uses `adapterPosition` without `NO_POSITION` guard. | `SidebarAdapter.kt` lines 135-137 call `selectItem(adapterPosition)`. | Rare crash or invalid RecyclerView update during rebinding/animation. | Check `bindingAdapterPosition != RecyclerView.NO_POSITION` before selecting. |
| H-010 | HIGH | Navigation | Fragment transactions are unguarded against state-saved timing. | Home and MainActivity use `commit { replace(...) }` from click/intent/back paths, `HomeFragment.kt` lines 393-406 and `MainActivity.kt` lines 115-120, 201-203. | IllegalStateException risk if navigation is triggered after state save. | Centralize navigation with lifecycle/state checks and ignore duplicate/current destination commands. |
| H-011 | MEDIUM | Data | Cache-null path leaves `isLoading=true` forever and has no error/empty state. | `HomeViewModel.kt` lines 72-80 return before updating state; UI has no loading/error widgets in `fragment_home_cinematic.xml`. | Blank Home or no focus recovery when cache is unavailable. | Add loading/empty/error state model and visible TV-safe fallback focus target. |
| H-012 | MEDIUM | Data | TMDB calls are repeated on sync/preference events without local Home-level throttling. | `repository.syncProgress` and preferences both call `loadHomeData()`, `HomeViewModel.kt` lines 50-69; TMDB calls at lines 86-106. | Startup/network churn and late data updates can steal focus. | Cache Home rows per session, debounce reload triggers, and avoid reloads when visible data is unchanged. |
| H-013 | MEDIUM | Data | HomePreferences are collected but not applied to active rows. | `homePrefs.getCategoriesChangedFlow()` triggers reload, `HomeViewModel.kt` lines 61-65; rows ignore selected category sets lines 86-106. | Settings changes can reload Home without changing results. | Either apply selected categories or remove the Home reload dependency in a future Home data pass. |
| H-014 | MEDIUM | Focus | Sidebar active item state is incomplete. | `activeNavItemId` defaults to 1 and is never updated, `HomeFragment.kt` line 66; adapter selected position defaults to 1, `SidebarAdapter.kt` line 30. | `returnToSidebar()` always targets Home even if another item was last focused/activated. | Keep separate focused, active, and last-sidebar-focus state; update intentionally. |
| H-015 | MEDIUM | Focus | Duplicate focus animations exist on sidebar rows. | XML stateListAnimator on row, `item_sidebar_nav.xml` line 12; Kotlin also scales/translates on focus, `SidebarAdapter.kt` lines 92-131. | Over-scaling, jitter, and inconsistent focus timing. | Pick one focus animation owner for sidebar rows. |
| H-016 | MEDIUM | Focus | `DPAD_UP`/`DPAD_DOWN` boundaries rely mostly on default focus search inside nested scroll/RecyclerViews. | Explicit listeners cover sidebar RIGHT, card LEFT, hero LEFT only, `HomeFragment.kt` lines 161-168, 357-379; `Top10Adapter.kt` lines 91-99. | Focus can jump unpredictably between hero/buttons/rows or out of row order. | Add deterministic row boundary handling and focus memory after stability pass. |
| H-017 | MEDIUM | Layout | Top 10 cards scale to 1.15 and large rank text uses negative translation. | `Top10Adapter.kt` lines 79-85; `item_top_10_card.xml` lines 40-56; `dimens.xml` lines 147-149. | Edge cards/rank numbers can clip or overlap at 1080p/overscan. | Screenshot-test focused first/middle/last cards; tune margins or use row padding/item decoration. |
| H-018 | MEDIUM | Data/Click | IPTV movie click silently does nothing when `streamUrl` is null. | `item.streamUrl?.let { ... }` has no else path, `HomeFragment.kt` lines 435-447. | User presses a visible card and receives no feedback. | Route to detail or show a consistent unavailable state. |
| H-019 | MEDIUM | Architecture | Current Home package contains legacy unused adapters and state models. | Active `loadData()` only creates `Top10Adapter`, `HomeFragment.kt` lines 491-515; older adapters inflate unused row/card layouts. | Future fixes may patch dead paths or miss active path. | Mark active Home surface explicitly in docs/tests before removing or reviving legacy rows in a separate task. |
| H-020 | LOW | Code Hygiene | Several imports/fields are unused in active Home. | `HomeFragment.kt` imports `Log`, `FavoriteEntity`, `Dispatchers`, `Job`, `withContext`; `watchHistoryPrefs` is initialized but unused. | Noise and maintainability drag. | Clean only in a dedicated cleanup pass after stability work. |
| H-021 | LOW | Visual | Section headers use positive letter spacing and inline duplicated header pattern. | `fragment_home_cinematic.xml` lines 145-167 and 182-204; `cin_view_section_header_v2.xml` comment notes replacement intent. | Minor inconsistency with polished component system. | Use a shared TV-safe section header during visual modernization. |

## 12. Fix Priority Proposal

### Proposed TASK 012  Home Critical Stability Fixes

- Add deterministic initial focus fallback when cache/data is empty or slow.
- Fix silent `restoreContentFocus()` failure by scrolling/attaching before requesting focus.
- Guard sidebar `adapterPosition`/fragment navigation crash risks.
- Add hero button click behavior or temporarily remove them from focus order.
- Clear adapters/listeners in `onDestroyView()`.

### Proposed TASK 013  Home Focus Restoration and Sidebar Behavior

- Separate active sidebar state, current focus, and last content focus.
- Stabilize DPAD_LEFT/RIGHT between sidebar, hero, and rows.
- Add DPAD_UP/DOWN row boundary rules.
- Remove competing sidebar focus animations/rebinds.
- Normalize sidebar expanded/collapsed width behavior.

### Proposed TASK 014  Home Adapter/Data Stability

- Convert Top 10 rows to stable IDs and DiffUtil/ListAdapter.
- Add Home loading/empty/error state.
- Debounce/coalesce reload triggers from sync and preferences.
- Apply or remove Home category preferences.
- Define whether legacy Home rows are revived or retired.

### Proposed TASK 015  Home Visual Modernization

- Verify 1080p/overscan screenshots for focused cards, sidebar, hero, and bottom row.
- Tune Top 10 rank/card scale/margins to avoid clipping.
- Harmonize Home visual language with Login modernization.
- Add premium empty/loading skeleton states.
- Expand Home content hierarchy after stability is proven.

## 13. Do-Not-Touch List

During first Home fixes, avoid modifying:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/LoginFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/InitialSyncFragment.kt`, except if a future task explicitly scopes login-to-Home transition
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/favorites/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/epg/*`
- Database schema, repository internals, and sync workers unless a Home data task explicitly requires read-only dependency tracing first.

## 14. Build/Test Status

No implementation changes were made to app Kotlin/XML/resources. The only file created by this audit is this report: `docs/reports/TASK_011_HOME_SCREEN_DEEP_AUDIT.md`.

Commands run for audit:

- `Get-ChildItem -Force`
- `rg --files -g "*Home*" -g "*home*" -g "*.kt" -g "*.xml" -g "*.gradle*" -g "*.toml"`
- `rg -n "HomeFragment|fragment_home|HomeAdapter|HomeRow|HomeContent|Sidebar|side.?bar|nav rail|Navigation|navigateTo|showHome|Home" app src docs -S`
- Targeted `Get-Content`/line-number inspection for Home, MainActivity, InitialSync, Home layouts, sidebar/card resources, focus helpers, prefs, model, and adapter files.
- `.\gradlew.bat :app:compileDebugKotlin --no-daemon`
  - First attempt timed out after about 124 seconds before a pass/fail result.
  - Second attempt completed successfully in about 2 minutes.
  - Result: `BUILD SUCCESSFUL`; `21 actionable tasks: 1 executed, 20 up-to-date`.

`testDebugUnitTest`, `assembleDebug`, install, and launch were not run because this was audit-only and the mandatory compile verification passed. No failing Home-related build issue was observed.
