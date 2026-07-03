# DIAG — Search Audit: duplicate recent-search text + unopened-category results missing

**Date:** 2026-06-12 · **Mode:** Diagnose Only · **Code changes:** NONE

User report: (1) searching e.g. "ptv" twice shows the term twice — duplicate text persists for every repeated search; (2) content from categories never opened before does not appear in search results.

---

## Finding SRCH-N1 — Recent searches duplicate on every repeat search (NEW, unmapped)

**Files:** `data/local/entity/SearchHistoryEntity.kt:11-15`, `data/local/dao/SearchHistoryDao.kt` (insertSearch), `ui/search/SearchViewModel.kt:198-213`

**Root cause:** `SearchHistoryEntity` has `@PrimaryKey(autoGenerate = true) id` and **no unique index on `query`**. `insertSearch` uses `OnConflictStrategy.REPLACE`, but a conflict can only occur on the primary key — which auto-generates a fresh id on every insert, so REPLACE never fires. Every search-result click inserts a brand-new row; searching "ptv" twice produces two identical rows, and `getRecentSearches` (plain `ORDER BY searchedAt DESC`) returns both. The adapter is a `ListAdapter<String>` and renders duplicates as-is.

Ironic detail: the DAO already ships the dedupe helpers (`getSearchByQuery`, `deleteSearchByQuery`) — `addToRecentSearches` just never calls them.

**Fix (surgical, no DB migration):** in `SearchViewModel.addToRecentSearches`, call `searchHistoryDao.deleteSearchByQuery(query)` before `insertSearch` (case-normalize the query first). 1 file. Alternative (schema): unique index on `query` + REPLACE — needs a Room migration; not worth it.

## Finding SRCH-N2 — Search only covers already-browsed categories (NEW, unmapped; by-design gap)

**Files:** `ui/search/SearchViewModel.kt:141-185` (performSearch), `data/repository/XtreamRepository.kt:2263-2277` (search*), `:685-734` (fetchVodStreamsForCategory), `:224-389` (syncInitialData)

**Root cause chain:**
1. `performSearch` queries **local Room/cache only**: `vodDao.searchMovies`, `seriesDao.searchSeries`, `cacheManager.searchChannels`, `epgDao.searchPrograms`. No provider API call.
2. The Room VOD table is populated **only** by `fetchVodStreamsForCategory(categoryId)` — which runs lazily when the user opens that category (`replaceMoviesForCategory` at line 712). Series follows the same lazy pattern (lines 1088-1160).
3. `syncInitialData` does NOT fill these tables: it writes the JSON cache + category lists + only the "latest" stream subsets (`fetchLatestVodStreams`). Full per-category content never reaches Room until each category is browsed.

So the search index is structurally incomplete; the existing empty-state message ("Please ensure content is loaded by browsing categories") confirms the limitation was known.

**Fix options:**
- **A (recommended): background search-index sync.** After initial sync succeeds (and periodically/background), fetch the full VOD + series stream lists (Xtream `get_vod_streams` / `get_series` without `category_id` returns the full catalog on standard panels; fall back to per-category iteration where unsupported) and populate Room via the existing entity mappers. WorkManager job, throttled, off the UI path. Search then covers everything without changing `performSearch`.
- **B (insufficient):** searching `memoryCache` too — rejected: the initial-sync VOD/series caches only contain the "latest" subsets, so coverage stays incomplete.

**Risk notes for A:** big providers → large payloads; reuse `replaceMoviesForCategory`-style atomic writes; do not block Home load (P4-H1/FIX-P2-8 territory); respect the 404→"All"-list fallback rule (DO_NOT_REPEAT Logic/Data #3).

## Mapping

Neither finding is covered by existing entries: Phase 5 Search findings (C2 recycling → FIX-P1-4, H1 logging → FIX-P0-2 done, H3 voice dup → FIX-P2-25, H7 focus → FIX-P2-20, M3 job cancellation → FIX-P3-UI-7) are all UI/lifecycle-level. Proposed: **SRCH-N1 → quick fix alongside FIX-P3-UI-7 territory (SearchViewModel)**; **SRCH-N2 → new medium feature entry (repository + worker), sequence after FIX-P1-4 to avoid SearchFragment collisions.**
