# Home Module Report
Status: canonical/current
Scope: Home fragment, focus routing, sidebar behavior, rows, and teardown safety.

## Current Active Runtime Flow
- `HomeFragment` is the default logged-in screen.
- Home managers split navigation, sidebar, hero, focus, and key routing.
- Home rows include Continue Watching, Recent Live, Top 10 Movies, and Top 10 Series.
- Sidebar navigation manually replaces `content_container` with Live, VOD, Series, Debrid, Search, or Settings.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFragment.kt` - Home host and row setup.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt` - Home data, history, TMDB/cache fallback.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeNavigationRouter.kt` - section/detail/player navigation.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeSidebarManager.kt` - sidebar setup/animation.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFocusManager.kt` - focus memory/restore.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeKeyRoutingManager.kt` - row D-pad behavior.

## What Must Not Break
- Row/focus rules and sidebar left/right behavior.
- Continue Watching short press and long-press action menu.
- Focus restoration after data updates and after returning from detail/player.
- Manager cleanup guards during `onDestroyView()`.

## Known Bugs / Open Issues
- Future row/focus changes should be kept in Home history files.

## Recent Fixes
- Manual Mark Watched/Unwatched completed and QA passed. Verified that exact removal of Continue Watching entries works correctly when an item is manually marked unwatched, without accidentally removing other episodes from the same series.
- Phase 2 Automatic Watched Detection threshold logic (wired through `PlayerHistoryManager`) strictly verified the exact removal behavior for the Continue Watching row. The JVM unit test (`PlayerHistoryManagerTest`) covered both <5% ignore threshold removals and >=90% completion removals.
- Home fragment split into smaller managers.
- Focus restoration, sidebar return, and teardown cleanup guarded.
- Compact Continue Watching action menu behavior documented in app patterns.

## Failed Approaches / Avoid
- Do not use layout-weight for main sidebar/content split.
- Do not hardcode card/skeleton offsets.
- Do not let focus escape left from sidebar.
- Do not rely on touch-only long press for TV card actions.
- Do not skip position checks in DiffUtil when stable IDs bind position-dependent UI.

## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA: cold Home focus, sidebar expand/collapse, row up/down/left/right, Continue Watching clear/open detail, return from detail/player.

## Last Verified State
- 2026-05-30: Manual Mark Watched/Unwatched QA passed. Continue Watching exact removal verified. Other episodes from same series are not removed accidentally.
- Build and real-device verification already completed on the current Home flow.
