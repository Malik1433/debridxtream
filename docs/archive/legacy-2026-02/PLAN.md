# Live TV Guide — fix "All", add unified Search (channels + categories)

## Context

The new Live TV EPG guide (`ui/live/guide/`) is working, but three gaps remain that the user hit:

1. **"All" tab shows wrong/incomplete channels.** Root cause (confirmed): the guide's "All" is backed by `XtreamRepository.readCache()?.live?.streams`, which by design holds **only the first live category's streams** (login does lazy per-category loading, comment at `fetchLiveCategoriesAndStreams` ~line 477). So "All" is a wrong, partial subset.
2. **No way to search channels** across the whole list.
3. **No way to search/find a category** among the dozens of chips.

**Decisions (from user):** fix "All" to show the true full channel list, and add **one unified Search** (a single 🔍 entry that finds both categories and channels) — the discoverable, TiviMate-style pattern.

Target repo: `E:\running project baxkups\lmsd working but loading issues\debxtrem` (package `com.tvonnet.debridxtreamiptv`).

## What already exists to reuse (do NOT rebuild)

- **Full live-channel index** in Room: table `channels`, populated by `XtreamRepository.indexLiveCatalog()` (via `getLiveStreams(categoryId=null)`, with per-category fallback), scheduled by `scheduleSearchIndexSyncIfStale()`. `ChannelDao` (`getChannelsByType("live")`, `searchChannels(query)` LIKE %q% LIMIT 50). `ChannelEntity.toXtreamStream()` converts rows.
- **Channel search:** `XtreamRepository.searchLive(query)` → `CacheManager.searchChannels` → `ChannelDao.searchChannels` (searches the full index).
- **Category filter pattern:** classic `LiveFragment.buildDisplayCategories()` (`ui/live/LiveFragment.kt` ~L674) — client-side `category_name.contains(query, ignoreCase=true)`.
- Guide data/genre/EPG mapping already in `LiveTvGuideViewModel.toGuideChannel()`.

---

## Part 1 — Fix "All" to the true full channel list

- **`data/local/dao/ChannelDao.kt`** — add `@Query("SELECT * FROM channels WHERE streamType = 'live'") suspend fun getAllLiveChannels(): List<ChannelEntity>`.
- **`data/cache/CacheManager.kt`** — add `suspend fun getAllLiveChannels(): List<XtreamStream>` = `channelDao.getAllLiveChannels().map { it.toXtreamStream() }.distinctBy { it.stream_id }` (index rows + lazy per-category rows deduped).
- **`data/repository/XtreamRepository.kt`** — add `suspend fun getAllLiveChannels(): List<XtreamStream> = cacheManager?.getAllLiveChannels() ?: emptyList()`.
- **`ui/live/guide/LiveTvGuideViewModel.kt`**:
  - `init` (or `load()`): call `repository.scheduleSearchIndexSyncIfStale()` so the full index gets built.
  - `loadChannels()` `CATEGORY_ALL` branch → `repository.getAllLiveChannels()` (fallback to `readCache().live.streams` if the index is still empty, so it's never worse than now). Keep the `MAX_CHANNELS` cap.
  - `buildCategories()`: "All" count = `getAllLiveChannels().size` (or hide when 0).

## Part 2 — Unified Search overlay (channels + categories)

**Entry point:** in `LiveTvGuideFragment.buildChipsIfNeeded()`, prepend a focusable **"🔍 Search"** chip (same pill styling) before the category chips. OK on it → `showSearchDialog()`.

**Overlay:** new `res/layout/dialog_live_guide_search.xml` shown via `AlertDialog` (dialogs get the on-screen keyboard for free, like the classic `dialog_search_channels.xml`): an `EditText` (`imeOptions=actionSearch`, auto-focus + show IME like classic `showSearchDialog` ~L1281) on top, and a `RecyclerView` of results below.

**Results adapter** (new `LiveSearchAdapter`) with 3 view types: `HEADER` ("CATEGORIES" / "CHANNELS"), `CATEGORY` row, `CHANNEL` row (logo via `GlideUtils.loadChannelLogo` + name). Rebuilt on every keystroke (no debounce, matching classic):
- Category results = client-side filter of `viewModel.uiState.value.categories` by name (reuse the `buildDisplayCategories` contains-ignoreCase pattern).
- Channel results = `viewModel.searchChannels(query)` → `repository.searchLive(query)` mapped to `GuideChannel` via the existing `toGuideChannel` path (new suspend method on the ViewModel so genre/logo/quality are consistent).

**Selecting a result:**
- Category → dismiss + `viewModel.selectCategory(id)` (guide grid jumps to it; existing `pendingGridFocusStreamId`/reload flow handles focus).
- Channel → dismiss + `selectForPreview(channel)` (tunes the mini preview to it with the round-14 select pattern; user can then OK on the tile to go fullscreen).

**ViewModel additions** (`LiveTvGuideViewModel`): `suspend fun searchChannels(query): List<GuideChannel>` (wraps `repository.searchLive`, reuses `toGuideChannel`); `fun filterCategories(query): List<GuideCategory>`.

## Files touched (summary)

- Data: `ChannelDao.kt`, `CacheManager.kt`, `XtreamRepository.kt` (3 small additions).
- Guide: `LiveTvGuideViewModel.kt` (All source + search/filter methods), `LiveTvGuideFragment.kt` (🔍 chip + search dialog + result handling).
- New: `res/layout/dialog_live_guide_search.xml`, `res/layout/item_live_search_*.xml` (or one item layout with a header slot), `LiveSearchAdapter.kt`, plus string `live_guide_search_hint`.

## Verification (on device — 192.168.178.64, .35 when online)

Build `:app:assembleDebug` (JDK21 JAVA_HOME), `adb install -r -d`, open Live TV guide.
1. **All:** select "All" → shows a large, correct channel list (not just first-category). If empty first time, trigger Settings → sync / wait for the index; re-check.
2. **Search entry:** 🔍 chip visible as the first chip; OK opens the overlay with keyboard.
3. **Category search:** type part of a category name → matching categories listed → OK jumps the guide to that category.
4. **Channel search:** type a channel name → matching channels listed (across all categories) → OK tunes the mini preview to it; OK on the tile then goes fullscreen.
5. No regressions to browse/select/zap/fullscreen.
