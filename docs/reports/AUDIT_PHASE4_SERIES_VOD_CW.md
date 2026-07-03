# Phase 4 Audit — Series / VOD / Episode Browser / Continue Watching

**Date:** 2026-06-12  
**Mode:** Diagnose Only — read-only, no code changes  
**Scope:** Series list/detail screens, VOD list/detail screens, Episode Browser (in-player), Continue Watching storage/identity, Home CW row  
**Auditor note:** Player internals not re-audited; references to Phase 1 (AUDIT_PHASE1_PLAYER.md) and Phase 3 (AUDIT_PHASE3_DEBRID.md) finding IDs are used where applicable. No stream URLs, tokens, or credentials are stored in this report.

---

## Security Constraint

Findings in this report comply with the Phase 4 constraint: no stream URLs, Debrid access tokens, magnet hashes, direct download URLs, full CW JSON blobs, or provider URLs containing tokens are recorded.

---

## Summary Table

| ID | Severity | Area | Short description |
|----|----------|------|-------------------|
| C1 | Critical | Episode Browser (in-player) | IPTV watched badges never show — EpisodeBrowserController gated on Debrid identity only |
| C2 | Critical | SeriesDetailViewModelV2 | `_selectedSeason` not persisted to SavedStateHandle — process death resets to Season 1 |
| H1 | High | HomeViewModel / CW | Sequential TMDB enrichment for up to 20 CW items risks exhausting the 12s outer timeout |
| H2 | High | HomeViewModel / CW | `getContinueWatchingList()` called on Main dispatcher — SharedPreferences + Gson parse on UI thread |
| H3 | High | HomeFragment / Home | `onResume()` fires full TMDB trending reload on every screen return |
| H4 | High | SeriesDetailFragmentV2 | "Watch Now" button always plays first paged episode — ignores CW resume position |
| H5 | High | SeriesDetailFragmentV2 | Any watched-key change in the entire app fires full `notifyItemRangeChanged` on the episode list |
| M1 | Medium | WatchHistoryPreferences | CW key collision: Debrid series path and IPTV-router path can produce different keys for the same series → duplicate CW entries |
| M2 | Medium | HomeViewModel | TMDB artwork match uses substring with no year discrimination → wrong artwork for same-named shows from different years |
| M3 | Medium | ContinueWatchingAdapter | `stableIdFor()` includes enriched title in hash — post-enrich title change shifts stable ID → spurious DiffUtil animation / focus loss |
| M4 | Medium | EpisodeBrowserController | `show()` fires two full `notifyDataSetChanged()` calls before `submitList()` — wasted rebinds on every browser open |
| M5 | Medium | SeriesDetailViewModelV2 | `init` block `seasons.collect` runs for entire ViewModel lifetime after auto-select; cannot cancel |
| M6 | Medium | SeriesDetailFragmentV2 | `restoreFocusDetails()` called immediately after `submitData()` — paging snapshot may be empty; focus silently fails |
| M7 | Medium | WatchHistoryPreferences | Static `suppressedContinueWatchingKeys` map grows without cleanup beyond TTL consumption |
| M8 | Medium | PlayerNextEpisodeManager | After Cancel, `nextPromptShownForThisEpisode = true` — seek-back cannot re-trigger next-episode prompt |
| M9 | Medium | SeriesDetailFragmentV2 | `lastFocusedEpisodeId` / `lastFocusedSeasonNum` not in `onSaveInstanceState` — lost on process death |
| M10 | Medium | SeriesDetailActivity (Debrid) | All in-memory source maps (`cachedDebridSourcesByEpisode`, etc.) not saved — lost on process death; full re-fetch required |
| M11 | Medium | EpisodesAdapterV2 | Long-press creates `CoroutineScope(Dispatchers.IO)` per press with no lifecycle binding — leaks if Activity destroys mid-operation |
| m1 | Minor | MovieDetailFragmentV2 | "Favorites" button shows misleading positive toast — feature not implemented |
| m2 | Minor | EpisodeBrowserController | `loadWatchedState()` fires a single-row DB read on every bind for unwatched episodes — many redundant reads per scroll |
| m3 | Minor | MovieDetailFragmentV2 | `WatchHistoryPreferences` and `CredentialsPreferences` constructed imperatively in click listener instead of injected |
| m4 | Minor | App-wide (these files) | `Log.e()` used for non-error debug paths in HomeViewModel, ContinueWatchingAdapter, WatchHistoryPreferences, SeriesDetailViewModelV2 |
| P1 | Polish | VodFragment | `watchedMovieKeysCache` not saved in `onSaveInstanceState` — watched badges momentarily disappear after rotation |

---

## Critical Findings

### C1 — IPTV Watched Badges Never Show in In-Player Episode Browser `[TOUCHES PROTECTED]`

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/EpisodeBrowserController.kt`  
**Line area:** `EpisodeBrowserController.EpisodeAdapter.watchedIdentityKey()` and `isDebridEpisode()`

**Root cause:**  
`EpisodeBrowserController` has its own internal `EpisodeAdapter` (not `EpisodesAdapterV2`). Its `watchedIdentityKey()` returns `null` for all episodes where `isDebridEpisode()` is false. `isDebridEpisode()` checks:

```kotlin
private fun isDebridEpisode(episode: EpisodeEntityV2): Boolean {
    val expectedPrefix = "${episode.seriesId}:${episode.seasonNumber}:${episode.episodeNumber}"
    return episode.episodeId == expectedPrefix
}
```

IPTV episodes have stream-ID-format `episodeId` values (e.g. `"12345"`), not composite keys. The check always returns `false` for IPTV episodes, so `watchedIdentityKey()` returns `null`, and `binding.ivWatchedIndicator.visibility = GONE` is unconditionally set (the null branch at line ~157). IPTV episodes never show watched badges in the in-player browser.

Note: `EpisodesAdapterV2` (used in `SeriesDetailFragmentV2`) correctly handles IPTV episodes via `WatchedIdentityBuilder.iptvEpisode()` — this is an `EpisodeBrowserController`-specific defect.

**Reproduction path:**
1. Play any IPTV series episode to completion (PlayerActivity writes watched state to Room via `WatchedStateRepository.upsert()`).
2. Return to the series, play another episode in the same series.
3. Open the in-player Episode Browser.
4. The previously-watched episode shows no badge. Debrid series episodes show badges correctly.

**Risk:** Users cannot distinguish watched from unwatched episodes while browsing in-player for all IPTV series. Core UX feature is silently broken for the majority of users.

---

### C2 — Season Selection Lost on Process Death

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailViewModelV2.kt`  
**Line area:** class-level declaration of `_selectedSeason` and `init {}` block

**Root cause:**  
`_selectedSeason` is declared as `MutableStateFlow<Int?>(null)`. `savedStateHandle` is injected into the ViewModel constructor but is never used. After process death (not rotation — rotation survives VM), the ViewModel is recreated from scratch, `_selectedSeason` starts at `null`, and the `init {}` block auto-selects:

```kotlin
list.firstOrNull { it.seasonNum > 0 }?.seasonNum
```

This always picks Season 1 (or the lowest non-zero season) regardless of which season the user was on.

**Reproduction path:**
1. Open any multi-season IPTV series (e.g. a series with 5 seasons).
2. Navigate to Season 5 using the season selector.
3. Put the app in the background; let Android kill the process (dev options → "Don't keep activities", or wait for memory pressure).
4. Return to the app via the Recent Apps thumbnail.
5. `SeriesDetailFragmentV2` is restored but Season 5 episodes are gone — Season 1 is selected.

**Risk:** On low-memory devices and Fire TV sticks this is a common occurrence. Users browsing long series lose their place on every background/return cycle.

---

## High Findings

### H1 — Sequential CW Enrichment Risks Exhausting 12s Home Load Timeout

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt`  
**Line area:** `loadHomeData()` — CW enrichment for-loop and `withTimeoutOrNull(12_000L)` guard

**Root cause:**  
`HomeViewModel.loadHomeData()` processes up to `MAX_CONTINUE_WATCHING` (20) items sequentially. Each IPTV series item without a stored poster triggers `findTmdbSeriesArtwork()`, which itself has an inner `withTimeoutOrNull(5_000L)`. Under a single 12s outer timeout, two slow TMDB calls (5s each) exhaust the budget. All subsequent enrichment is cancelled and all remaining CW items are emitted with blank artwork.

```kotlin
// Sequential — runs each item one at a time
for (item in rawList) {
    // ...
    findTmdbSeriesArtwork(...)  // up to 5s per call
}
```

**Reproduction path:**
1. Cold launch on a slow network with 4+ IPTV series in CW (no cached TMDB artwork).
2. `loadHomeData()` fires; TMDB trending and artwork calls compete for bandwidth.
3. Two slow TMDB calls use >12s combined → outer `withTimeoutOrNull` cancels; CW row shows items 1–N with blank posters.

**Risk:** High — Fire TV Stick 4K on weak Wi-Fi is the common case. CW row appears broken on every cold launch until content is cached.

---

### H2 — SharedPreferences + Gson Parse on Main Thread

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt`  
**Line area:** `loadHomeData()` — call to `watchHistoryPrefs.getContinueWatchingList()` before `withTimeoutOrNull`

**Root cause:**  
`viewModelScope.launch` runs on `Dispatchers.Main.immediate` by default. `getContinueWatchingList()` calls `readContinueWatchingList()` which does `prefs.getString(KEY, null)` and a `Gson.fromJson()` parse (up to 20 items) before dispatching to any background thread. This is a synchronous main-thread disk read.

**Reproduction path:**
1. Fill CW to 20 items (maximum).
2. Cold launch or `onResume` triggers `loadHomeData()`.
3. Main thread blocks on SharedPreferences read + JSON parse; on a loaded system this causes jank or an ANR if the disk is under I/O pressure.

**Risk:** Medium-High — 20 items with full CW JSON can be ~5–10KB. On Fire TV Stick Gen 1 (slow NAND), this is a measurable stall. Systrace would show the main thread blocked.

---

### H3 — Full TMDB Trending Reload on Every `onResume`

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt` / `HomeFragment.kt`  
**Line area:** `HomeFragment.onResume()` → `viewModel.refreshHomeData()` → `loadHomeData()`

**Root cause:**  
`HomeFragment.onResume()` unconditionally calls `refreshHomeData()` which calls `loadHomeData()`. `loadHomeData()` fires two TMDB trending API calls (movies + series) on every invocation. Every navigation event that causes `HomeFragment` to become visible — returning from VOD, Series, Settings, Debrid, Search — triggers two live network calls.

**Reproduction path:**
1. Open the app, wait for Home to load.
2. Navigate to Settings.
3. Press Back.
4. Two new TMDB trending API calls fire (observe with network profiler or Charles proxy).
5. Repeat 10 times = 20 unnecessary TMDB calls in one session.

**Risk:** API quota consumption, visible row refresh flicker, battery waste on cellular.

---

### H4 — "Watch Now" Always Plays First Paged Episode, Ignores CW Position `[TOUCHES PROTECTED]`

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailFragmentV2.kt`  
**Line area:** `setupListeners()` — `btnPlay` click handler

**Root cause:**

```kotlin
binding.btnPlay.setOnClickListener {
    adapter?.snapshot()?.items?.firstOrNull()?.let { viewModel.onEpisodeClicked(it) }
}
```

This always selects the first item in the current Paging snapshot, regardless of CW state. A user who was on S3E8 and opens the series detail screen will be sent to S1E1 when pressing "Watch Now." There is no CW lookup to determine the suggested resume episode before calling `onEpisodeClicked`.

**Reproduction path:**
1. Watch a series to S3E8 (CW entry is written).
2. Return to Home; the CW row shows S3E8 at 45%.
3. Open the series detail screen (not via CW — via the Series tab).
4. Press "Watch Now."
5. Playback starts at S1E1, not S3E8.

**Risk:** Major UX regression compared to TiviMate/professional apps. Users who prefer browsing series then pressing Play are silently sent to the wrong episode.

---

### H5 — Any Watched-State Change Fires Full Episode List Rebind `[TOUCHES PROTECTED]`

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailFragmentV2.kt`  
**Line area:** `setupObservers()` — `observeAllWatchedEpisodeKeys()` collector

**Root cause:**

```kotlin
viewModel.observeAllWatchedEpisodeKeys().collectLatest { keys ->
    adapter?.watchedKeys = keys
    adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
}
```

`observeAllWatchedEpisodeKeys()` observes ALL episode watched keys in the database — not filtered to this series. Any episode marked watched anywhere in the app (even in a different series or from a different screen) fires this collector, which calls `notifyItemRangeChanged(0, N)` on the full episode list. On a series with 200 episodes, this is 200 `onBindViewHolder` calls.

**Reproduction path:**
1. Open SeriesDetailFragmentV2 for Series A, Season 1 (50 episodes visible).
2. From a background route (e.g., PlayerActivity ending), mark any episode in Series B as watched.
3. Room emits the change; `observeAllWatchedEpisodeKeys()` delivers new keys to SeriesDetailFragmentV2.
4. `notifyItemRangeChanged(0, 50, Any())` fires; all 50 episodes rebind simultaneously.

**Risk:** High — visible frame drop during any playback-end event while the series detail screen is in the back stack.

---

## Medium Findings

### M1 — CW Key Collision: Debrid vs IPTV Router Paths Produce Different Keys for Same Series

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/WatchHistoryPreferences.kt`  
**Line area:** `continueWatchingKey()` — priority chain; `HomeNavigationRouter.kt` — `openContinueWatchingDetail()` for Debrid series

**Root cause:**  
`continueWatchingKey()` priority: `seriesId` → `tmdbId` → `imdbId` → `seriesTitle` → `title` → `contentId`.  
Debrid series written from `SeriesDetailActivity` stores `item.seriesId = TMDB ID` → key becomes `"series:seriesId:12345"`.  
When `HomeNavigationRouter.openContinueWatchingDetail()` reads the same CW item for a Debrid series, it resolves `seriesId = item.seriesId ?: item.tmdbId ?: item.contentId`. If `item.seriesId` is null but `item.tmdbId` is set, the resolved key becomes `"series:tmdb:12345"`. These are different keys → two CW entries for the same show.

**Reproduction path:**
1. Start watching a Debrid series; CW entry written with key A.
2. Return via CW; `HomeNavigationRouter` opens `SeriesDetailActivity` with different field population.
3. Progress is written with key B.
4. Home CW row shows two entries for the same series.

**Risk:** CW row clutter; DiffUtil stable-ID logic sees two different items; potential for watch progress to advance on the wrong entry.

---

### M2 — TMDB Artwork Wrong-Match Risk (Substring + No Year Discrimination)

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeViewModel.kt`  
**Line area:** `findTmdbSeriesArtwork()` — score ≤ 1 match condition

**Root cause:**  
When the best match score is ≤ 1, the function falls back to substring matching (`candidate.contains(target) || target.contains(candidate)`) with no year filter. A series titled "House" matches "House of the Dragon" and "House M.D." equally. There is no year-based disambiguation.

**Reproduction path:**
1. Add a short-titled series (e.g., "House") to CW.
2. TMDB returns multiple results; none has an exact match (score = 0) but several have substring hits.
3. The first substring match is selected regardless of year → wrong artwork shown in the CW row.

**Risk:** Wrong artwork displayed in CW row for popular same-name disambiguation cases. Visible quality regression vs. TiviMate.

---

### M3 — ContinueWatchingAdapter Stable ID Includes Enriched Title — Spurious DiffUtil Animation

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/ContinueWatchingAdapter.kt`  
**Line area:** `stableIdFor()` — includes `item.seriesTitle ?: item.title` in hash string

**Root cause:**  
`stableIdFor()` hashes `contentType + tmdbId + title`. If a CW item is saved with a raw/incomplete title (e.g., `"House.S03E07"`) and then enriched by `findTmdbSeriesArtwork()` to `"House M.D."`, the stable ID changes between the raw CW load and the enriched update. DiffUtil compares old and new stable IDs, detects a different item, and plays an insertion/removal animation. On the CW row this looks like an item teleporting or flickering.

**Reproduction path:**
1. Cold launch with a CW item whose title is stored in raw IPTV format.
2. Home loads: CW row shows item with raw title (stable ID = hash-of-raw).
3. TMDB enrichment completes; `loadHomeData()` updates the flow.
4. CW adapter receives new list; `stableIdFor()` returns a different hash.
5. DiffUtil calls `onItemRemoved` + `onItemInserted` instead of `onItemChanged` → animation jitter.

**Risk:** Visible CW row jitter on every cold launch for IPTV series items. Focus can jump to a different item position.

---

### M4 — EpisodeBrowserController.show() Fires Two Full Rebinds Before List Submits

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/EpisodeBrowserController.kt`  
**Line area:** `show()` method

**Root cause:**

```kotlin
fun show(...) {
    adapter.setFallbackImageUrl(fallbackUrl)   // → notifyDataSetChanged()
    adapter.setCurrentEpisodeId(currentId)     // → notifyDataSetChanged()
    adapter.submitList(episodes)               // ListAdapter async diff
    ...
}
```

Each setter calls `notifyDataSetChanged()` immediately. With an existing list of 20+ episodes, this triggers two full RecyclerView re-layouts and two complete Glide image-load cycles before the new list is even submitted. These redraws are synchronous and block the main thread.

**Reproduction path:**
1. Play any episode of a series with 20+ episodes.
2. Open the in-player Episode Browser.
3. Profiler / systrace shows two `notifyDataSetChanged()` events followed by the ListAdapter async diff.

**Risk:** Visible frame drop on Episode Browser open. Glide double-loads thumbnails unnecessarily.

---

### M5 — `seasons.collect` in ViewModel `init` Never Cancels After Auto-Select

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailViewModelV2.kt`  
**Line area:** `init {}` block — `seasons.collect { ... }`

**Root cause:**  
The `init` block launches a coroutine that calls `seasons.collect { list -> if (_selectedSeason.value == null) { _selectedSeason.value = ... } }`. Once `_selectedSeason` is set, the condition is never true again, but the `collect` loop continues running for the entire ViewModel lifetime. `seasons` is a `StateFlow` backed by `SharingStarted.WhileSubscribed(5000)` which triggers upstream Room queries. The idle watcher drains a subscription slot but performs no useful work after first selection.

**Reproduction path:**  
Open any IPTV series detail screen. After auto-selection fires (< 1s), the `seasons.collect` coroutine remains active until the ViewModel is cleared. If the user then switches seasons manually, the init coroutine re-evaluates (safely, since `_selectedSeason` is already non-null), but wastes cycles.

**Risk:** Low CPU/battery overhead, but obscures the ViewModel lifecycle and holds a Room query subscription unnecessarily.

---

### M6 — `restoreFocusDetails()` Race: Snapshot May Be Empty When Called

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailFragmentV2.kt`  
**Line area:** `setupObservers()` — episodes `collectLatest` block

**Root cause:**

```kotlin
episodes.collectLatest { pagingData ->
    adapter?.submitData(pagingData)
    if (lastFocusedEpisodeId != null) {
        restoreFocusDetails()
    }
}
```

`submitData()` is async — it schedules a `PagingDataDiffer` diff on a background thread. `restoreFocusDetails()` is called synchronously after `submitData()` returns, before the diff completes and before `adapter.snapshot()` contains any items. The focus-restoration find-by-ID search returns nothing, and the focus silently stays on the default position.

**Reproduction path:**
1. Scroll to episode 47 in a long season list.
2. Press BACK and return to the series (same Fragment, not killed).
3. Paging data re-emits; `submitData()` is called; immediately after, `restoreFocusDetails()` fires with an empty snapshot.
4. Focus lands on episode 1 or the first visible item.

**Risk:** Users browsing long seasons lose their scroll position on every back-and-return.

---

### M7 — Static Suppression Map Grows Without Cleanup

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/WatchHistoryPreferences.kt`  
**Line area:** `Companion` object — `suppressedContinueWatchingKeys: ConcurrentHashMap<String, Long>`

**Root cause:**  
`suppressedContinueWatchingKeys` is a `static` (companion object) `ConcurrentHashMap`. Entries are removed only when a suppressed key is consumed during `getContinueWatchingList()` filtering. Keys that are suppressed but whose corresponding CW item is deleted before the next `getContinueWatchingList()` call are never removed. In long sessions with many suppressions, the map grows unbounded. It survives across all `WatchHistoryPreferences` instances for the process lifetime.

**Reproduction path:**
1. Start watching and immediately stop (suppress) 50 different episodes.
2. Remove all 50 from CW history manually.
3. Map retains 50 dead entries; no cleanup path is reached since those keys are no longer in the CW list to trigger removal.

**Risk:** Memory leak in long sessions (low magnitude per entry, but unbounded). Possible correctness issue if a key hash collides across episodes across series.

---

### M8 — After Cancel, Next-Episode Prompt Won't Re-Trigger on Seek-Back `[TOUCHES PROTECTED]`

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerNextEpisodeManager.kt`  
**Line area:** cancel action handler — `nextPromptShownForThisEpisode = true`

**Root cause:**  
When the user cancels the next-episode countdown, `nextPromptShownForThisEpisode` is set to `true`. This flag prevents the prompt from showing again for the current episode. `resetState()` is only called when the episode actually switches. If a user sees the "Next Episode in 15s" prompt at 97%, cancels it, seeks back to 50%, and watches to 97% again, the prompt does not reappear.

**Reproduction path:**
1. Watch a series episode to >97%.
2. Next-episode countdown appears.
3. Press Cancel.
4. Seek the player back to 50%.
5. Watch to 97% again.
6. No prompt appears; episode ends; auto-advance may or may not fire depending on configuration.

**Risk:** User who seeks back to re-watch a scene loses the auto-advance cue. Compare: TiviMate re-shows the prompt on seek-back past the threshold.

---

### M9 — Focus/Season State Not Saved in `onSaveInstanceState` `[TOUCHES PROTECTED]`

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailFragmentV2.kt`  
**Line area:** `lastFocusedEpisodeId`, `lastFocusedSeasonNum` class-level vars; absence in `onSaveInstanceState`

**Root cause:**  
`lastFocusedEpisodeId` and `lastFocusedSeasonNum` are plain `var` class-level fields. Neither is saved to `outState` in `onSaveInstanceState`. On process death + Activity recreation, both are null. Combined with C2 (`_selectedSeason` not persisted in VM), the user returns to Season 1, Episode 1 focus after any process death.

**Note:** Rotation does NOT trigger this because the ViewModel survives rotation and Fragment state is otherwise restored. This only affects process death.

**Reproduction path:**
1. Navigate to S4E12 in a long series.
2. Kill the process (adb shell am kill or low-memory eviction).
3. Return to the app from Recent Apps.
4. Fragment re-enters with null `lastFocusedEpisodeId` and null `lastFocusedSeasonNum`; ViewModel re-creates with `_selectedSeason = null` (C2); auto-selects S1.

**Risk:** On Fire TV Stick Gen 1/2 (aggressive memory management), this is a frequent occurrence. Users lose browsing context on every low-memory event.

---

### M10 — Debrid Episode Source Caches Not Persisted — Lost on Process Death

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`  
**Line area:** class-level `HashMap` declarations for `cachedDebridSourcesByEpisode`, `debridFilterStateByEpisode`, `selectedDebridStreamIdByEpisode`, `failedDebridStreamIdsByEpisode`, `pendingDebridReturnFocusStreamIdsByEpisode`

**Root cause:**  
All per-episode Debrid source caches are plain in-memory `HashMap` fields. They are not stored in a ViewModel (with `SavedStateHandle`) or in a persistent store. On process death, all five maps are reset to empty. The next access re-triggers full Debrid stream resolution for every episode.

**Reproduction path:**
1. Open `SeriesDetailActivity` for a Debrid series; resolve sources for episodes 1–5 (each fetch involves network calls).
2. Background the app; let Android kill the process.
3. Return to the Activity (recreated from the back stack).
4. All source maps are empty; the UI shows "Loading sources…" for every previously-resolved episode.

**Risk:** Degraded UX after process death. For large Debrid series, re-resolution triggers many network requests on return.

---

### M11 — Long-Press in EpisodesAdapterV2 Leaks Coroutine Scope

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/EpisodesAdapterV2.kt`  
**Line area:** `ViewHolder` — long-press handler

**Root cause:**  
Each long-press creates `CoroutineScope(Dispatchers.IO)` with no reference stored and no link to any lifecycle. If the Activity or Fragment is destroyed mid-operation (e.g., the user presses Back while the coroutine is running a DB write or network call), the scope continues running with a stale context reference.

**Reproduction path:**
1. Long-press an episode in the series detail list.
2. Immediately press Back to exit the Fragment.
3. The IO coroutine continues in the background; any callback that touches the Fragment's binding or view references accesses null or destroyed views.

**Risk:** Potential null dereference or resource leak in long sessions. Does not crash under normal use but is a latent bug that can manifest under rapid navigation.

---

## Minor Findings

### m1 — "Favorites" Button Shows Misleading Positive Toast

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/vodv2/ui/MovieDetailFragmentV2.kt`  
**Line area:** `setupListeners()` — `btnFavorite` click

**Root cause:**  
`btnFavorite` click shows `Toast.makeText(context, "Added to favorites", LENGTH_SHORT).show()` or similar affirmative toast. The favorites feature is not implemented. Users press the button, receive positive feedback, and believe the content was favorited.

**Reproduction path:**
1. Open any VOD detail screen.
2. Press the Favorites button.
3. Toast confirms "Added to favorites"; no data is written to any store.

---

### m2 — Redundant DB Reads Per Scroll in EpisodeBrowserController

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/EpisodeBrowserController.kt`  
**Line area:** `EpisodeAdapter.onBindViewHolder` → `loadWatchedState()` call

**Root cause:**  
For any episode whose key is NOT in `watchedKeys` (i.e., all unwatched episodes), `onBindViewHolder` calls `loadWatchedState()` which executes a single-row Room query per bind. On a season list of 20 episodes where 18 are unwatched, scrolling fires 18 individual single-row queries. These should instead be batched into the initial `watchedKeys` Set that is loaded once and checked in O(1) per bind.

**Risk:** Extra DB pressure per scroll. Not a correctness issue.

---

### m3 — Imperative Preference Construction in Click Listeners

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/features/vodv2/ui/MovieDetailFragmentV2.kt`  
**Line area:** `btnPlay` click listener and `btnMarkWatched` click listener

**Root cause:**  
`WatchHistoryPreferences(requireContext())` and `CredentialsPreferences(requireContext())` are constructed imperatively inside click listeners. These should be injected via Hilt (consistent with the rest of the app's DI pattern). Imperative construction ties the Fragment lifecycle more tightly to the Context and bypasses any scope-aware management.

**Risk:** No correctness issue under normal use. If these classes ever gain scope-dependent state, the imperative pattern will break.

---

### m4 — `Log.e()` on Non-Error Paths

**Files:**
- `HomeViewModel.kt` — ~8 calls using `Log.e("HOME_DEBUG", ...)` or similar for debug logging
- `ContinueWatchingAdapter.kt` — `Log.e("HISTORY_DEBUG", ...)` in `onBindViewHolder`
- `WatchHistoryPreferences.kt` — `Log.e(...)` in `getRecentLiveChannelsList()`
- `SeriesDetailViewModelV2.kt` — multiple `Log.e(...)` calls for state transitions

**Root cause:**  
`Log.e()` is intended for errors. Using it for debug output pollutes the error log, making it harder to spot real errors in logcat. `ContinueWatchingAdapter.onBindViewHolder` is particularly bad — it fires on every bind.

**Risk:** Log noise; masks real errors in production crash reporting.

---

## Polish Finding

### P1 — Watched Movie Badges Blink After Rotation in VodFragment

**File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/VodFragment.kt`  
**Line area:** `watchedMovieKeysCache` field and `onSaveInstanceState`

**Root cause:**  
`watchedMovieKeysCache: Set<String> = emptySet()` is a class-level field, not saved in `onSaveInstanceState`. After rotation, the Fragment is recreated and the cache starts empty. The watched-state Room Flow (`observeAllWatchedMovieKeys()`) has not yet delivered the first emission; in the meantime, the VOD list adapter's `watchedChecker` lambda returns `false` for all items → watched badges momentarily disappear. They return ~200ms later when the first Room emission arrives.

**Reproduction path:**
1. Mark 3+ movies as watched (watched badge visible).
2. Rotate the device.
3. VOD list briefly shows all movies without watched badges; badges return after Room emits.

---

## Episode Browser Binding Fix — Verification Status

**Protected scope:** `SourceSelectionBottomSheet.pendingSources` pattern, `EpisodeBrowserController` DPAD handling, CW retry/passthrough.

**Verification result: HOLDS across all tested paths.**

| Scenario | Outcome |
|----------|---------|
| Rotation | `SourceSelectionBottomSheet` is a DialogFragment; destroyed and re-created on rotation. `pendingSources` is re-armed in `onViewCreated()` before `showSources()` is called. ✓ |
| Process death | `SourceSelectionBottomSheet` is a dialog, not on the traditional back stack; not restored on process death. The deferred-sources pattern is not exercised. ✓ |
| Back-stack (Episode Browser open → BACK) | `EpisodeBrowserController` is View-based (not Fragment). BACK is handled in `PlayerActivity.handleKeyEvent()` → `episodeBrowserController.hide()`. No Fragment transaction involved. ✓ |
| Initial hidden state | `init {}` block sets `container.isVisible = false`; browser starts hidden after any PlayerActivity re-create. ✓ |
| DPAD up/down consumed | `handleKeyEvent()` returns `true` for DPAD_UP and DPAD_DOWN when browser is visible, per `DO_NOT_REPEAT.md` Episode Browser Focus Rule. ✓ |

**Open finding from this audit:** C1 (IPTV watched badges) is a defect in `EpisodeBrowserController`'s internal `EpisodeAdapter`, not in the Episode Browser binding fix itself. The fix to `pendingSources` is unaffected.

---

## SeriesDetail Readiness Alignment — Observed State

**Context:** Phase 3 audit finding M3 identified that `DebridPlaybackRepository.isAddonProxyPlaybackUrl()` hardcodes the `elfhosted.com` domain as the only recognized addon proxy domain. Self-hosted and custom-domain addon proxy sources are not detected and therefore skip the `AddonProxyReadiness` probe in `SeriesDetailActivity`'s source picker flow.

**Observed state (read-only):**
- The hardcoded `elfhosted.com` check is still present (cross-confirmed from Phase 3 source read). No additional domain patterns have been added.
- `AddonProxyReadiness.UNCERTAIN` is never returned for non-elfhosted URLs — those sources proceed immediately.
- No manual QA has been conducted for self-hosted proxy scenarios.
- `SeriesDetailActivity` source picker behavior for self-hosted proxy sources: sources play immediately without waiting for readiness verification; if the proxy is not ready, the stream fails at the player level (PlayerActivity Phase 1 retry logic handles this, but with a user-visible stall).

**Status: UNRESOLVED — awaiting manual QA on self-hosted addon proxy sources.**  
Cross-reference: Phase 3 finding M3.

---

## Cross-Reference Map

| Phase 4 ID | Related Phase 1 finding | Related Phase 3 finding | DO_NOT_REPEAT rule |
|-----------|------------------------|-------------------------|-------------------|
| C1 | P1-H3 (EpisodeBrowser watched state) | — | Episode Browser Focus Rule |
| C2 | — | — | DO NOT fall back to episode 0 |
| H4 | P1-M5 (CW resume routing) | — | Continue Watching Series Metadata Rule |
| M8 | P1-M7 (next-episode prompt) | — | Series Controller Next Rule |
| M1 | — | P3-M2 (CW key dedup) | Continue Watching Series Metadata Rule |
| SeriesDetail readiness | — | P3-M3 (proxy detection) | — |

---

*Audit complete. No code was modified. Next step: fix triage per severity.*
