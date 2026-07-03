# Phase 5 Audit — D-pad Focus & UI Polish (Home / Search / Settings / Login)

**Date:** 2026-06-12  
**Mode:** Diagnose Only — read-only, no code changes  
**Scope:** Home fragment + managers + row adapters + layout, Search screen, Settings screens, Login/InitialSync screens, shared focus helpers (`SidebarFocusHelper`, `FocusEffects`)  
**Out of scope (referenced only):** Player internals (Phase 1), Debrid resolver (Phase 3), Series/VOD detail + CW identity internals (Phase 4). `MediaFusionConfigActivity` and `CompanionSetupActivity` were not deep-read (secondary settings surfaces).  
**Security:** No stream URLs, tokens, or credentials are reproduced in this report.

---

## Summary Table

| ID | Severity | Area | Short description |
|----|----------|------|-------------------|
| C1 | Critical | HomeNavigationRouter | Recent Live click logs the raw live stream URL (embeds IPTV username/password) via `Log.e` |
| C2 | Critical | SearchFragment | Search results have zero RecyclerView recycling — all grids are `wrap_content` inside a ScrollView; large result sets inflate every ViewHolder synchronously |
| H1 | High | SearchFragment | Server URL + username logged to logcat on every Search screen open |
| H2 | High | Home (managers) | All Home focus memory is lost when returning from a section — managers recreated in `onViewCreated`; BACK from a non-Home root creates a brand-new `HomeFragment` |
| H3 | High | MainActivity | `passVoiceQueryToSearchFragment` always replaces with a new SearchFragment even when one already exists (`return@forEach` does not break) |
| H4 | High | HomeFocusManager | Clearing the last item of a focused row drops D-pad focus entirely (`applyInitialFocusIfNeeded` early-returns) `[TOUCHES PROTECTED]` |
| H5 | High | HomeHeroManager | Full-screen hero backdrop Glide reload fires on every Top10 card focus change — backdrop thrash while scrubbing rows `[TOUCHES PROTECTED]` |
| H6 | High | LoginFragment | All controls disabled during login → focus dropped; after a failed login nothing is refocused — remote appears dead |
| H7 | High | SearchFragment | Focus is forced back to the search input on every view re-creation — returning from a detail screen loses results position |
| H8 | High | HomeFocusManager + layout | Initial focus prioritizes Continue Watching, which is the **bottom** row of the scroll layout — cold start auto-scrolls past hero and trending rows `[TOUCHES PROTECTED]` |
| M1 | Medium | Top10Adapter / HomeKeyRoutingManager | Two competing `setOnKeyListener` owners on the same row child views — last-writer-wins; adapter's `onLeftBoundary` is dead code; rebind can strip UP/DOWN routing `[TOUCHES PROTECTED]` |
| M2 | Medium | HomeHeroManager | DPAD_LEFT on "MORE INFO" hero button jumps to sidebar, skipping "PLAY NOW" `[TOUCHES PROTECTED]` |
| M3 | Medium | SearchViewModel | No in-flight search cancellation — out-of-order results can overwrite a newer query's results |
| M4 | Medium | SettingsDetailAdapter | Toggle click captures `item.isChecked` at bind time; lambdas in `SettingItem` data classes defeat `areContentsTheSame` → full visible rebind on every state emission |
| M5 | Medium | SettingsFragment/Adapter | Category highlight desyncs from the detail pane after view re-creation (adapter resets to GENERAL, ViewModel keeps real selection) |
| M6 | Medium | SettingsFragment | Escaped `\$` template strings — toasts literally display `${e.message}` / `${result.data}` (3 call sites) |
| M7 | Medium | MainActivity | Network speed test runs on every Activity creation, including configuration changes |
| M8 | Medium | SidebarFocusHelper | Sidebar expand/collapse animates `layoutParams.width` with `requestLayout()` per frame — full Home content relayout ~18×/animation `[TOUCHES PROTECTED]` |
| M9 | Medium | InitialSyncFragment | `navigateToHome()` commits without a state-saved guard (crash window if sync completes while backgrounded); Retry button never explicitly focused on error |
| M10 | Medium | SettingsFragment | `cacheDir.deleteRecursively()` runs on the main thread |
| M11 | Medium | LoginFragment | Raw exception messages shown in user-facing toasts (violates documented login error-redaction rule) |
| M12 | Medium | SearchFragment | DPAD_DOWN from search bar posts two competing focus requests (RV container + first item) |
| m1 | Minor | MainActivity | SEARCH/VOICE_ASSIST keys consumed and silently dropped when voice search is uninitialized (logged-out state) |
| m2 | Minor | HomeFocusManager | `restoreContentFocusFromSidebar()` always returns `true` — DPAD_RIGHT consumed even when no focus moved |
| m3 | Minor | SearchFragment | `setVoiceQuery` fires `QueryChanged` twice (TextWatcher + explicit call) |
| m4 | Minor | SearchFragment | EPG program result plays with placeholder title "Channel <id>" and no logo |
| m5 | Minor | Search/Home | Log spam: ~6 log lines per keystroke in Search; `Log.e("HISTORY_DEBUG")` in Home lifecycle (cross-ref Phase 4 m4) |
| m6 | Minor | LoginFragment | `setBoxStrokeWidth(3)` passes raw pixels, not dp — hairline focus border on high-density panels |
| m7 | Minor | SettingsFragment | "About → Version" is a focusable item with an empty click handler |
| m8 | Minor | SearchFragment | `requestFocusOnFirstItem` attach-listener cleanup relies on a 1.5s `postDelayed` — listener can linger past view destruction |
| P1 | Polish | LoginFragment | Entrance animation holds D-pad focus on an alpha-0 (invisible) input for ~500ms |
| P2 | Polish | fragment_search.xml | 16dp screen padding is below TV overscan-safe margins (≈48dp/27dp) |
| P3 | Polish | HomeSidebarManager | Settings sidebar item `animate()` chains are not cancelled before re-trigger; focus flicker on rapid up/down |
| P4 | Polish | HomeHeroManager | Hero button click/focus/key listeners re-registered on every hero update |

---

## Critical Findings

### C1 — Recent Live Click Logs Raw Stream URL (Embedded Credentials)

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeNavigationRouter.kt` (line ~412, `onRecentLiveItemClick`)

**Root cause:**

```kotlin
Log.e("HISTORY_DEBUG", "Click Recent Live: ${item.channelName} | id=${item.channelId} | stream=${item.streamUrl}")
```

`item.streamUrl` is logged raw. Xtream live URLs embed the IPTV username and password in the path. The adjacent Continue Watching click handler correctly routes through `SensitiveLogRedactor.describeUrl(...)` — this Recent Live path was missed. Direct violation of DO_NOT_REPEAT Player Rule 5 ("DO NOT log stream URLs, ... usernames, or passwords") and App Failed Pattern #6.

**Reproduction path:** Home → focus any Recent Live card → OK. Logcat (`HISTORY_DEBUG`, error level) contains the full credentialed URL. Leaks via logcat, QA artifacts, and any crash-report breadcrumb capture.

**Risk:** Credential exposure. Highest-priority fix in this phase.

---

### C2 — Search Results Have No RecyclerView Recycling

**Files:** `app/src/main/res/layout/fragment_search.xml` (rv_live_results / rv_vod_results / rv_series_results / rv_epg_results), `SearchFragment.setupRecyclerViews()`

**Root cause:**  
All four result grids are `layout_height="wrap_content"` with `android:nestedScrollingEnabled="false"`, nested inside a full-screen `ScrollView`. A `wrap_content` RecyclerView inside a ScrollView measures **all** items — recycling is disabled in practice. A search like "the" against a large IPTV catalog returning 200+ live channels inflates and binds 200+ ViewHolders (each with a Glide load) synchronously on the main thread when `submitList` lands.

**Reproduction path:**
1. Open Search (sidebar → Search).
2. Type a 3-letter common substring against a large provider catalog.
3. When results land, the UI freezes for the full inflate+bind pass; GC churn and dropped frames visible in systrace. Focus navigation only becomes responsive after every grid has fully built.

**Risk:** Multi-second main-thread stall on Fire TV class hardware; scales with catalog size. This is the single biggest Search smoothness gap vs. professional apps.

---

## High Findings

### H1 — Server URL + Username Logged on Search Open

**File:** `SearchFragment.kt`, `initializeRepository()` (line ~158)

```kotlin
android.util.Log.d(TAG, "Initializing repository: serverUrl=$serverUrl, username=$username")
```

Logs the provider server URL and account username on every SearchFragment view creation. Violates the same sensitive-logging rules as C1 (usernames are explicitly listed). Password is not logged here, but URL+username alone identify the account/provider.

**Reproduction path:** Open Search once; check logcat tag `SearchFragment` at debug level.

---

### H2 — Home Focus Memory Lost on Every Return From a Section `[TOUCHES PROTECTED]`

**Files:** `HomeFragment.onViewCreated()` (manager construction), `HomeFocusManager` (all `last*` fields), `MainActivity.onBackPressed()` (line ~198–204)

**Root cause (two halves):**
1. All focus memory (`lastContentFocusArea`, `lastMovieIndex`, `lastFocusedSidebarItemId`, …) lives in `HomeFocusManager`, which is **constructed fresh in `onViewCreated`**. When the user navigates Home → Movies (replace + addToBackStack) and presses BACK, the HomeFragment instance survives but its view — and all five managers — are rebuilt. Every remembered index resets to defaults (`MOVIES`, index 0, sidebar = HOME).
2. Separately, `MainActivity.onBackPressed()` at a non-Home root with an empty back stack replaces with a **new** `HomeFragment()` instance — even the ViewModel state is discarded on that path.

The HOME_MODULE_REPORT lists "Focus restoration … after returning from detail/player" as must-not-break. Returning from PlayerActivity/detail Activities (same view alive) works; returning from **sibling section fragments** does not.

**Reproduction path (exact keys):**
1. Home → DPAD_DOWN to Trending Series row → RIGHT ×4 (focus card 5). Sidebar focus memory = Series area, index 4.
2. LEFT to sidebar → DOWN to "Movies" → OK (VodFragment replaces Home).
3. BACK.
4. Home view rebuilds; focus lands per fresh `applyInitialFocusIfNeeded` priority (Continue Watching index 0 if present), not Series index 4. Sidebar memory points to "Home" instead of "Movies".

**Risk:** Every section round-trip discards the user's place — the most frequent navigation loop in the app.

---

### H3 — Voice Query Always Spawns a Duplicate SearchFragment

**File:** `MainActivity.passVoiceQueryToSearchFragment()` (line ~157–177)

**Root cause:**

```kotlin
supportFragmentManager.fragments.forEach { fragment ->
    if (fragment is SearchFragment) {
        fragment.setVoiceQuery(query)
        return@forEach   // <-- continues the loop; does NOT exit the function
    }
}
// This block ALWAYS runs, even when a SearchFragment was found:
supportFragmentManager.commit { replace(R.id.content_container, SearchFragment()...) ; addToBackStack(null) }
```

`return@forEach` only skips to the next loop iteration. The fall-through replace always executes, so when a SearchFragment is already showing, the voice query is delivered **and then the fragment is replaced by a fresh instance**, adding a back-stack entry. Each voice search performed from the Search screen stacks another SearchFragment; BACK then unwinds through stale copies.

**Reproduction path:** Open Search → press the remote microphone key → speak a query → repeat twice → press BACK repeatedly: each BACK pops a duplicate SearchFragment.

---

### H4 — Clearing the Last Item of a Focused Row Drops Focus Entirely `[TOUCHES PROTECTED]`

**Files:** `HomeFocusManager.restoreContentFocusAfterDataUpdate()` (line ~252–256) + `applyInitialFocusIfNeeded()` (early return at line ~59)

**Root cause:**  
When a data update empties the row the user was focused on (e.g., long-press → "Clear" on the only Continue Watching item), `restoreContentFocusAfterDataUpdate` finds `itemCount <= 0` and falls back to `applyInitialFocusIfNeeded(state)`. But that method begins with `if (hasAppliedInitialFocus || …) return` — and `hasAppliedInitialFocus` is already `true` for this view. No focus is assigned; the row's section is also set `GONE` by `updateHistoryRowVisibility`. Focus is now null; the next D-pad press is resolved by the framework's default search from nowhere (typically jumping to the first focusable in the hierarchy — a hero button or sidebar — or being dropped).

**Reproduction path (exact keys):**
1. Have exactly one Continue Watching item.
2. Home → focus the CW card → long-press OK → focus "Clear" chip → OK.
3. CW row disappears; nothing is focused. Next DPAD press lands somewhere unpredictable.

**Risk:** Direct hit on the protected "Continue Watching clear/open detail" QA flow in HOME_MODULE_REPORT.

---

### H5 — Hero Backdrop Reloads on Every Card Focus `[TOUCHES PROTECTED]`

**File:** `HomeHeroManager.updateHeroSection()` (line ~36–63); callers: `Top10Adapter.onItemFocused` for both rows (HomeFragment line ~253–273)

**Root cause:**  
Both Top10 adapters call `heroManager.updateHeroSection(item)` from `onItemFocused`. Every single DPAD_RIGHT/LEFT step inside a trending row triggers: hero title/description set + a full-screen Glide backdrop request with crossfade. Holding RIGHT across 10 cards fires 10 sequential full-screen image loads. There is no debounce, no request cancellation policy beyond Glide's default target reuse, and the crossfade restarts mid-flight, producing visible backdrop flashing while scrubbing.

**Reproduction path:** Home → Trending Movies row → hold DPAD_RIGHT. Backdrop flickers/crossfades repeatedly; on a slow network it lags several cards behind and pops late.

**Risk:** Most visible polish gap vs. TiviMate-class apps on the Home screen. (A ~250–300ms focus-settle debounce is the standard pattern — fix suggestion only, no change made.)

---

### H6 — Login: Focus Dropped When All Controls Are Disabled

**File:** `LoginFragment.performLogin()` / `setLoginControlsEnabled()` (line ~344–350)

**Root cause:**  
On login start, all five focusables (`etServerUrl`, `etUsername`, `etPassword`, `btnLogin`, `btnSetupPhone`) are set `isEnabled = false`. Disabling the focused view removes focus, and with **zero** enabled focusables remaining, focus becomes null. On failure, controls are re-enabled but nothing calls `requestFocus()` — the focus stays null until the framework happens to re-assign on the next key event, which on some Fire TV builds requires multiple presses and lands on the first field rather than the Login button the user was on.

**Reproduction path (exact keys):**
1. Fill in invalid credentials → DOWN to "Login" → OK.
2. Spinner shows, error toast appears, controls re-enable.
3. Press OK again — nothing happens (no view focused). Press DOWN/UP until focus visibly reappears.

**Risk:** Reads as "remote stopped working" at the most critical first-run moment.

---

### H7 — Search Steals Focus Back to the Input on Every View Re-Creation

**File:** `SearchFragment.setupSearch()` — trailing `etSearchQuery.post { post { requestFocus() } }` (line ~282–287)

**Root cause:**  
The unconditional focus request runs on every `onViewCreated`, including when the user returns from a VOD/Series detail (SearchFragment is on the back stack; its view rebuilds). The EditText also restores its text via view-state, the TextWatcher re-fires `QueryChanged`, results re-render — and focus is forced into the search bar instead of the result the user had selected. There is no saved result-grid focus position at all.

**Reproduction path:** Search "batman" → DOWN into VOD grid → RIGHT ×3 → OK (detail opens) → BACK. Focus is in the search bar; result-grid position is lost; on-screen keyboard may pop up over the results.

---

### H8 — Initial Focus Targets the Bottom Row of the Page `[TOUCHES PROTECTED]`

**Files:** `HomeFocusManager.applyInitialFocusIfNeeded()` (priority order, line ~78–87) vs. `fragment_home_cinematic.xml` row order (Hero → Trending Movies → Trending Series → Recent Live → Continue Watching)

**Root cause:**  
The focus priority is CW → Recent Live → Movies → Series, but the layout places Continue Watching **last** (bottom of the NestedScrollView). On any cold start where CW has items, initial focus lands on the bottom row, which makes the NestedScrollView scroll all the way down — the hero and both trending rows are scrolled off-screen the instant the app settles. BACK is then needed (scroll-to-top handler) just to see the hero.

**Reproduction path:** Have ≥1 CW item → cold launch → observe the home screen settle scrolled to the bottom with the CW row focused; hero is off-screen.

**Risk:** This may be an intentional "resume-first" choice, but combined with the layout order it contradicts the hero-first cinematic design. Either focus priority or row order should change (decision deferred — diagnose only). Note any fix touches the protected initial-focus logic.

---

## Medium Findings

### M1 — Two Competing OnKeyListener Owners on Home Row Children `[TOUCHES PROTECTED]`

**Files:** `Top10Adapter.Top10ViewHolder.bind()` (line ~127–135) and `HomeKeyRoutingManager.installContentItemKeyRouting()` (line ~63–87)

**Root cause:**  
A view holds exactly one `OnKeyListener`. The adapter sets one in `bind()` (handles LEFT at position 0 → `onLeftBoundary`). The key-routing manager sets another on child attach **and** again after every data update via `refreshContentRowKeyRouting()` (handles LEFT/UP/DOWN). Order of operations decides which survives:
- Normal flow: bind → attach → manager's listener wins → the adapter's `onLeftBoundary` lambda is **never invoked** (dead code; behavior coincidentally equivalent because the manager also routes LEFT→sidebar).
- Rebind without re-attach (`notifyItemChanged`, payload binds): adapter's listener wins → that card loses UP/DOWN row routing until the next `refreshContentRowKeyRouting()` pass, during which fast vertical D-pad falls back to native focus search (App Failed Pattern #30 territory).

**Reproduction path:** Hard to hit deterministically; the window exists between a `notifyItemChanged` on a Top10 card and the next uiState emission. Exact code path is the listener overwrite sequence above.

**Risk:** Fragile listener ownership in the protected Home key-routing area; should be consolidated to a single owner when fixed.

---

### M2 — Hero "MORE INFO" DPAD_LEFT Skips "PLAY NOW" `[TOUCHES PROTECTED]`

**File:** `HomeHeroManager.updateHeroSection()` — `btnDetails?.setOnKeyListener` (line ~111–122)

**Root cause:**  
Both hero buttons intercept DPAD_LEFT → `returnToSidebar()`. For `btn_hero_details` (which sits to the right of `btn_hero_watch`), LEFT should move to the sibling button; instead it consumes the event and jumps to the sidebar.

**Reproduction path (exact keys):** Home → focus hero "PLAY NOW" → RIGHT (focus "MORE INFO") → LEFT. Expected: back to "PLAY NOW". Actual: sidebar.

---

### M3 — Search Has No In-Flight Cancellation; Stale Results Can Win

**File:** `SearchViewModel.performSearch()` (line ~141–185)

**Root cause:**  
Each debounced query launches a new coroutine; no `Job` is stored or cancelled. The `collect` body calls `performSearch` without cancelling the previous one. A slow search for "bat" (e.g., EPG table scan) can complete **after** the fast search for "batman", overwriting the state with results for the older query while `state.query` says "batman".

**Reproduction path:** On a device with a large EPG table, type "bat", pause 350ms, then quickly extend to "batman". If the first query's DB scan outlasts the second, the results list reverts to "bat" results.

---

### M4 — Settings Toggles: Stale Capture + Lambda Equality Defeats DiffUtil

**File:** `SettingsDetailAdapter` (`SettingItem` declarations line ~11–33, `ToggleViewHolder.bind` line ~82–97); `SettingsFragment.updateDetails()`

**Root cause (two parts):**
1. `binding.root.setOnClickListener { val newState = !item.isChecked … }` captures `isChecked` from bind time. Two rapid OK presses before the state round-trip rebinds compute the **same** `newState` twice — the second press is a no-op toggle-wise while the Switch widget visually flips, desyncing UI from the stored pref until the next emission.
2. Every `updateDetails` call constructs new `SettingItem` instances whose data classes contain fresh lambda references (`onToggle`/`onClick`). `areContentsTheSame` (`oldItem == newItem`) compares lambdas by reference → always false → **every visible row rebinds on every uiState emission**, flickering switch animations.

**Reproduction path:** Settings → Player → rapidly double-press OK on "Tunneling Mode". Observe the switch ending in a state that does not match the persisted value (verified by leaving and re-entering the category).

---

### M5 — Settings Category Highlight Desyncs After View Re-Creation

**Files:** `SettingsFragment.setupAdapters()` (adapter defaults), `SettingsCategoryAdapter.selectedCategory = SettingCategory.GENERAL` (line ~16)

**Root cause:**  
The adapter's `selectedCategory` starts at GENERAL on every `onViewCreated`. The ViewModel survives and keeps the real selection (e.g., PLAYER). On return from the back stack or rotation, `updateDetails` renders Player items in the right pane while the left pane highlights "General". Nothing re-syncs `categoryAdapter.setSelectedCategory(state.selectedCategory)` from the observer.

**Reproduction path:** Settings → select "Player" → sidebar-navigate away (Settings is on back stack) → BACK. Right pane shows Player settings; left pane highlights General.

---

### M6 — Escaped Template Strings Shown Literally in Toasts

**File:** `SettingsFragment.kt` lines ~512, 523, 528

**Root cause:**

```kotlin
Toast.makeText(requireContext(), "Error: \${e.message}", Toast.LENGTH_LONG).show()       // refreshIptvData
Toast.makeText(requireContext(), "EPG synced: \${result.data} programs", ...)            // syncEpgNow
Toast.makeText(requireContext(), "Error: \${e.message}", ...)                            // syncEpgNow catch
```

`\$` escapes the dollar in Kotlin — users literally see `EPG synced: ${result.data} programs` on a successful EPG sync.

**Reproduction path:** Settings → IPTV & EPG → "Sync EPG Now" → wait for success toast.

---

### M7 — Network Speed Test on Every Activity Creation

**File:** `MainActivity.onCreate()` (line ~103–107)

**Root cause:** `networkQualityManager.runSpeedTest()` launches unconditionally in the logged-in `onCreate` path. Configuration changes, theme changes, and process-death restores all re-run a bandwidth-consuming speed test.

**Reproduction path:** Toggle an OS-level configuration change (or kill/restore) — observe a new speed test in network logs each time.

---

### M8 — Sidebar Width Animation Relayouts the Entire Home Content Per Frame `[TOUCHES PROTECTED]`

**File:** `SidebarFocusHelper.attachStandardSidebarAnimation()` (ValueAnimator update, line ~57–68); consumer: `HomeSidebarManager.setupSidebar()`

**Root cause:**  
The expand/collapse animates `layoutParams.width` and calls `requestLayout()` on every animation frame (~18 frames over 300ms). The home `NestedScrollView` is constrained to the sidebar's end edge, so each frame triggers measure/layout of the entire content tree — four RecyclerViews and the hero. On Fire TV Stick class GPUs this is a visible stutter every time focus enters or leaves the sidebar.

**Reproduction path:** Home → LEFT into sidebar (expand) → RIGHT out (collapse) repeatedly; watch frame timing. Systrace shows full-tree layout passes during the animation window.

**Risk:** Protected sidebar behavior (HOME_MODULE_REPORT). The standard TV pattern is an overlay sidebar animated with `translationX` over a fixed content inset — noted as fix direction only.

---

### M9 — InitialSync: Unguarded Commit + Retry Button Never Focused

**File:** `InitialSyncFragment` (`startSync` line ~78–91, `navigateToHome` line ~125–131, `showError` line ~117–123)

**Root cause:**
1. `startSync`'s coroutine runs in `viewLifecycleOwner.lifecycleScope` without a STARTED gate; if the user presses HOME mid-sync and the sync finishes while the Activity is stopped, `navigateToHome()` executes `supportFragmentManager.commit {}` after `onSaveInstanceState` → `IllegalStateException` crash window.
2. `showError` makes the Retry button visible/enabled but never calls `requestFocus()`. The screen had no focusable views before the error, so focus is null; the first D-pad press is consumed by the framework's focus-search bootstrap rather than acting on Retry.

**Reproduction path:** (1) Start first sync on slow network → HOME → wait for sync completion → reopen from recents (crash window). (2) Force a sync failure (airplane mode) → error appears → press OK: first press does nothing on some devices.

---

### M10 — Cache Deletion on Main Thread

**File:** `SettingsFragment.showClearCacheDialog()` (line ~537–543)

**Root cause:** `requireContext().cacheDir.deleteRecursively()` runs directly in the dialog's positive-button callback on the main thread. With image caches of several hundred MB this is a multi-second ANR-window stall.

**Reproduction path:** Use the app for a while (populate Glide cache) → Settings → IPTV & EPG → "Clear Cached TV Data" → Clear. UI freezes for the duration of the recursive delete.

---

### M11 — Login Surfaces Raw Exception Messages

**File:** `LoginFragment.performLogin()` (lines ~272–278)

**Root cause:** `loginResult.exceptionOrNull()?.message` and `e.message` are toasted verbatim. HTTP client exception messages can include the request URL (which embeds username/password in Xtream API form). LOGIN_MODULE_REPORT explicitly lists "Do not expose raw credential/server errors in user-facing … responses."

**Reproduction path:** Enter a syntactically valid but unreachable server URL → Login → toast shows the raw transport exception text.

---

### M12 — Double Competing Focus Requests on Search DPAD_DOWN

**File:** `SearchFragment` — `etSearchQuery.setOnKeyListener` (line ~252–279)

**Root cause:** The DOWN handler queues `target.post { target.post { target.requestFocus() } }` (focuses the RecyclerView container) **and** calls `requestFocusOnFirstItem(target)` (focuses child 0 via posts/attach listener). Two focus requests race across frames; the container-level one can win first and then be re-stolen by the child request, causing a visible double focus-highlight hop, or — if position 0 isn't laid out — leave focus on the RV container itself.

**Reproduction path:** Search with results → focus search bar → DPAD_DOWN; watch the focus highlight flash on the grid edge before settling on the first card.

---

## Minor Findings

### m1 — Voice Keys Silently Swallowed When Logged Out
`MainActivity.dispatchKeyEvent` returns `true` for KEYCODE_SEARCH / KEYCODE_VOICE_ASSIST when `voiceSearchManager` is uninitialized (logged-out path never initializes it). The key is consumed with zero feedback on the Login screen.

### m2 — Voice Query Fires QueryChanged Twice
`setVoiceQuery` calls `etSearchQuery.setText(query)` (TextWatcher → `QueryChanged`) and then explicitly `viewModel.onEvent(QueryChanged(query))`. Harmless due to `distinctUntilChanged`, but redundant; same pattern in the arguments-based path in `onViewCreated`.

### m3 — EPG Result Playback Has Placeholder Metadata
`SearchFragment.playLiveChannelById` launches the player with title/channelName `"Channel $channelId"` and `channelLogo = null` (acknowledged in an inline comment). Player overlay shows a raw channel id instead of the channel name the user clicked.

### m4 — Keystroke-Level Log Spam
`SearchFragment` emits ~6 `Log.d` lines per keystroke (text watcher, line ~203–208) and ~10 lines per state emission (line ~406–414). `HomeFragment` logs lifecycle via `Log.e("HISTORY_DEBUG")` (onAttach/onCreate). Cross-reference Phase 4 finding m4 for the shared `Log.e`-as-debug pattern.

### m5 — `setBoxStrokeWidth(3)` Uses Pixels
`LoginFragment.setupFocusListeners` passes `3` (pixels) to `setBoxStrokeWidth`, which expects px — on a 1080p/4K panel this focus border is a hairline; the intent was clearly dp.

### m6 — `requestFocusOnFirstItem` Listener Lifetime
The attach-state listener used to catch position 0 is removed only by a 1.5s `postDelayed`; if the view is destroyed within that window the runnable still executes against the dead RecyclerView reference (guarded only by `isAdded` at entry, not in the delayed lambda).

### m7 — Dead Focusable "Version" Row
Settings → About → "Version" is an Action item with `onClick = {}` — a focusable, clickable row that does nothing. Either make it non-focusable info or attach an action (e.g., build info dialog).

### m8 — Search Error Surfaces Raw `e.message`
`SearchViewModel.performSearch` catch block renders `"Search failed: ${e.message}"` into the empty-state TextView — transport exceptions can include host details.

---

## Polish Findings

### P1 — Focus Held on Invisible View During Login Entrance
`startEntranceAnimations()` sets all card elements to `alpha = 0` **after** `etServerUrl.requestFocus()` runs; the focused server field is invisible for the first ~500–700ms of the staggered entrance. A user pressing keys immediately types into a field they cannot see.

### P2 — Search Screen Overscan Margins
`fragment_search.xml` uses a flat `android:padding="16dp"`. TV overscan-safe guidance is ~48dp horizontal / 27dp vertical; on TVs with overscan the search bar and first column sit partially outside the safe area. (Home/Settings use 32dp margins — closer, but also below the horizontal guideline; sidebar occupies the left edge by design.)

### P3 — Settings Sidebar Item Animation Stacking
`HomeSidebarManager`'s settings-item focus listener and `SidebarAdapter`'s item focus listener start new `animate()` chains without cancelling prior ones; rapid UP/DOWN across the boundary can briefly stack scale states (ViewPropertyAnimator mostly coalesces, but durations differ 220/180ms producing slight pop).

### P4 — Hero Button Listener Re-Registration
`updateHeroSection` re-creates and re-assigns click/focus/key listeners on both hero buttons on every call — which, per H5, is every card focus event. Allocation churn only; behavior unaffected.

---

## Cross-Reference Map

| Phase 5 ID | Related prior finding | Guardrail |
|-----------|----------------------|-----------|
| C1, H1, M11, m8 | Phase 4 security constraint; App Failed Pattern #6 | Player Rule 5 / Debrid Sensitive Logging Rule |
| H2, H4, H8 | — | HOME_MODULE_REPORT "What Must Not Break" (focus restoration, CW row) |
| H5 | — | App Failed Pattern #3 (adapter-driven fragment-view updates) — same family |
| M1 | — | App Failed Pattern #30 (native focus search during rapid scroll) |
| M8 | — | DO_NOT_REPEAT "no layout-weight sidebar split" — same jank family, different mechanism |
| H7 | Phase 4 M6 (focus restore race) — same UX class | — |
| m4 | Phase 4 m4 (`Log.e` as debug) | — |

---

## Protected-Area Notes (read-only verification)

- **Home global D-pad routing** (`HomeKeyRoutingManager` + `HomeFocusManager`): the documented row rules (LEFT at position 0 → sidebar; UP/DOWN walk rows in layout order; DOWN at bottom row consumed; no focus escape left of sidebar) are implemented and consistent with DO_NOT_REPEAT Focus rules. Findings H4/H8/M1/M2 are gaps **around** the rules, not violations of the implemented routing itself.
- **CW long-press action menu**: wiring (long-press → dialog → `openDetailChip.requestFocus()`) matches App Failed Pattern #39 constraints; no interaction-contract drift observed.
- **Sidebar left-escape**: no path found that lets focus escape left of the sidebar. ✓

---

*Audit complete. No code was modified.*
