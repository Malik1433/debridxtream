# LiveFragment Decomposition Plan

**Target:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt` — **1756 lines**,
~65 methods / ~45 fields. The **Live TV browse** screen (3-column v2 redesign: nav-rail + category
sidebar + channel grid + preview player + EPG strip). The **last** of the original god-classes
(XtreamRepository / UnifiedSourceProvider / PlayerActivity / SeriesDetailActivity / MovieDetailActivity
are done). See [[phase-7-god-class-decomposition]].

**Started:** 2026-07-26, right after the MovieDetailActivity decomposition shipped + was owner-verified.

## What already exists (do NOT duplicate)

The v2 redesign already extracted real collaborators; LiveFragment is the remaining orchestrator that
wires them. Reuse these, don't recreate:
`LiveViewModel` (556, state machine), `LiveNavRail` (312), `PreviewPlayerPanel` (362, the preview
player), `ChannelPagingAdapter` (349), `SidebarCategoryAdapter` (167), `LiveEpgStripAdapter` (151),
`CategoryAccents` (57), `ChannelAdapter` (49), guide sub-package (`LiveTvGuideFragment`/`EpgGridView`/
`LiveTvGuideViewModel`). `LiveViewModelTest` (312) is the existing test net for the state machine.

## Approach (identical playbook to the Series/Movie decompositions)

**Behaviour-preserving verbatim relocations, one cohesive domain per commit.** Each phase: `clean
:app:assembleDebug :app:testDebugUnitTest :app:detekt` **all green** (baseline discipline: relocated
already-baselined methods re-fire under new keys → regenerate baseline + verify the total does not grow
beyond the collaborator-constructor `LongParameterList` cost — and NEVER baseline a *genuinely new*
violation; if a mechanical move nudges a method over a threshold, tighten formatting back, don't
baseline) + device .64 **launch-stability** + owner eyes-on QA + commit + push. The unit is a
**delegate/controller taking the Fragment** (`LiveFragment` + the views it owns), constructed in
`onViewCreated` from already-injected deps. Fields read across many domains **STAY in the Fragment**;
only cohesive method+own-state clusters move down.

**CRITICAL process rules (carried over — do NOT repeat past mistakes):**
- **Incremental Gradle gives FALSE GREEN here** — always clean-verify before committing. See [[gradle-stale-dex-merge]].
- **`livePlayerLauncher = registerForActivityResult(...)` MUST stay REGISTERED IN THE FRAGMENT** (field
  initializer; timing before STARTED is load-bearing). Delegate only its body to a controller's
  `handleLivePlayerResult(resultCode, data)`, pass the controller `launch = livePlayerLauncher::launch`.
  Same rule proven on SD-4c / MD-4.
- **`by viewModels()` and `registerForActivityResult` stay in the Fragment.** Collaborators receive the
  viewModel + launcher lambda.
- **Live-playback LANDMINES must not regress** ([[live-firstframe-freeze-audiotrack]],
  [[ts-extractor-flags-freeze-regression]], [[liveconfiguration-ts-freeze-postmortem]],
  [[live-surf-drawer-flags]]). In particular `launchFullscreen` MUST keep
  `previewPlayerPanel?.releasePlayer()` **synchronously before** `livePlayerLauncher.launch(...)` — the
  account is `max_connections=1`, so a still-open preview socket + the fullscreen session = two live
  connections and the server rejects the second. Do not reorder this.
- **This is an Android TV Fragment: D-pad focus is load-bearing.** The channel-grid focus/quick-jump/
  focus-restore logic (`didRestoreFocusForThisView`, `pendingChannelFocusPosition`, category focus
  block/unblock) is the app's most focus-sensitive code — verify on the device, not an emulator, and
  don't let a refresh steal focus. See [[focus-fixes-and-preserving-helper]], [[live-tv-v2-redesign]].
- **Device is NOT autonomously driveable** (secure live surface → screencap black, `adb input` no-op) —
  autonomous verification = install + launch-stability only; interactive browse/zap/EPG/playback QA is
  **owner eyes-on on the TV**. See [[firetv-test-device-setup]].

## Domains (from the method map) — risk-ascending phase order

| Phase | Domain → collaborator | Methods (~) | Risk |
|---|---|---|---|
| **LF-1** | Header + clock → `LiveHeaderController` | `startHeaderClock` + `millisUntilNextMinute` + `updateHeaderInfo` + `updateGuideStripHeader` + clock views/`clockJob`/formatters | **LOW** (self-contained timer + 3 TextViews) |
| **LF-2** | Search pill → `LiveSearchController` | `setupSearchPill` + `toggleSearchPill` + search views + `searchDebounceJob` (emits a viewModel search event) | **LOW** (self-contained overlay; one VM event) |
| **LF-3** | EPG preview + strip → `LiveEpgPreviewController` | `observeEpgUpdates` + `updatePreviewEpg` + `notifyEpgUpdated` + `primeEpgData` + `warmVisibleEpgCache` + `setupEpgStrip` + `maybeRestorePreviewFromState` + EPG jobs/keys/`epgStripAdapter` | **MED** (EpgCache + preview-panel coupling) |
| **LF-4** | Category list + chips + category-focus → `LiveCategoryController` | `buildDisplayCategories` + `applyCategoryChipFilter` + `refreshCategoryChips` + `updateCategoryList` + `handleCategoryClick` + `focusSelectedCategoryItem` + `focusCategoryStrip` + `isFirstCategoryFocused` + category focus block/unblock + the 2 inline `CategoryViewHolder*` adapters + `getCategoryIcon` + `sidebarCategoryAdapter`/`displayCategories`/`categoryChipQuery` | **MED** (RecyclerView + focus restore) |
| **LF-5** | Favorites → `LiveFavoritesController` | `observeFavorites` + `handleFavoriteLongPress` + `toggleFavorite` + `notifyFavoriteChanged` + `updateFavoriteButtonState` + `favoriteStreamIds`/`favoritesCount` | **MED** (cross-fragment favorite bus + button state) |
| **LF-6** | Channel-grid focus + quick-jump (D-pad) → `LiveChannelFocusController` | `restoreFocusGrid` + `focusChannelItem` + `moveChannelFocusBy`/`From` + `handleChannelItemKey` + `currentFocusedChannelPosition` + `isFirstChannelFocused` + quick-jump (`onQuickJumpDigit`/`commitNumberJump`/`onQuickJumpLetter`/`channelFirstLetter`/`showQuickJumpHud`/`hideQuickJumpHud`) + `restoreInitialFocusIfNeeded`/`restoreChannelFocusIfNeeded` + focus/quick-jump state | **HIGH** (the app's most focus-sensitive path; TV D-pad) |
| **LF-7** | Playback launch + return-restore → `LivePlaybackLauncher` | `navigateToPlayer` + `launchFullscreen` + `handleLivePlayerResult` + `resolveLiveChannelIds` + the `livePlayerLauncher` body | **HIGH** (first-frame Live + the `max_connections=1` release-before-launch landmine) |

**Stays in the Fragment:** `onCreateView`/`onViewCreated`/`onResume`/`onPause`/`onDestroyView`,
`observeViewModel`/`renderState`/`renderChannelLoadState`/`showLoading`/`showEmptyState`/`hideEmptyState`/
`getDisplayErrorMessage` (the central state-render spine), `setupNavRail`/`focusNavRail`, the
`livePlayerLauncher`/`channelPagingAdapter`/`emergencyCleanupCallback` registrations, and the widely-read
fields (`previewPlayerPanel`, `channelPagingAdapter`, `rvChannels`, `viewModel`, `lastUiState`). These are
the orchestration glue that wires the collaborators.

**Honest target: ≤900 lines** for LiveFragment (it is MORE interwoven than the detail Activities —
focus-restore + preview state are shared across LF-3/4/6/7 — so it won't reach ≤700; the goal is to
drop it out of `LargeClass` and give each domain one reason to change). Each new collaborator <600.

## Device-QA matrix (owner eyes-on, per MED+/HIGH phase and at the end)

Open Live → nav-rail → category sidebar (chips + counts) → channel grid → **D-pad up/down + number/
letter quick-jump** → **1st click = preview plays + EPG strip + header updates** → **2nd click = fullscreen
first-frame** → BACK from fullscreen restores the same channel focus + preview (no double live
connection, no first-frame freeze) → favorite long-press toggles → search pill filters → EPG "now/next"
correct. No crash; no focus theft on refresh; no `.ts` freeze.

## Progress

- **Plan drafted (`1143e1a`).** Phase order LF-1..LF-7, risk-ascending. One phase per commit,
  clean-verify + device QA each. LF-6 and LF-7 are the HIGH-risk gates (TV focus + Live playback
  landmines) — flag before starting each.
- **LF-1 DONE (`7b0e8e0`)** — `LiveHeaderController` (~90 lines): top-bar minute clock
  (`startHeaderClock`/`millisUntilNextMinute` + `clockJob` + `topBarTimeFormatter` + `tv_header_clock`)
  and the "<CATEGORY> CHANNELS" / "N LIVE" title+count row (`updateHeaderInfo` + `tv_category_name`/
  `tv_channel_count`). Reads the live favourites count via a getter (stays in the Fragment). Constructed
  fresh in `onViewCreated`, cancelled + nulled in `onDestroyView`. `updateGuideStripHeader` intentionally
  LEFT for LF-3 (it manages the EPG-strip subtitle, called from `updatePreviewEpg`). Behaviour
  byte-identical (verbatim; `this`/field refs → `fragment`/passed views/getter). LiveFragment
  **1756→~1665**. Removed the now-unused `java.util.Date` import. Clean build green; **no baseline change**
  (5-param ctor, short methods). Device .64 launch-stability + owner eyes-on QA PENDING.
