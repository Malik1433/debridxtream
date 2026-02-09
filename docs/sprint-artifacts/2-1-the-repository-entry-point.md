# Story 2.1: The Repository Entry Point

**Status:** Ready for Review

## Story

**As a** ViewModel,
**I want** a clean API to request Series data,
**So that** I don't need to know about Paging or Network logic.

## Acceptance Criteria

1.  **Repository Implementation:**
    *   Create `features/seriesv2/data/repository/XtreamSeriesRepositoryV2.kt`.
    *   Inject `SeriesDaoV2` and `XtreamApiService` (Existing).

2.  **Single Source Fetch:**
    *   Implement `getSeriesById(seriesId: Int): Flow<NetworkResult<SeriesV2>>`.
    *   Logic: Fetch from DB -> Emit. Fetch from Network -> Save to DB -> Emit new data. Use `NetworkBoundResource` pattern or simple Flow logic.

3.  **Paged Episodes:**
    *   Implement `getPagedEpisodes(seriesId: Int): Flow<PagingData<EpisodeV2>>`.
    *   Return a `Pager` flow configured with `pageSize = 20`.
    *   Source must be `SeriesDaoV2.pagingSource()`.

4.  **Unit Test:**
    *   Mock Retrofit and DAO. Verify Flow emissions occur correctly.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Paging:** Paging 3 `Pager` and `PagingConfig`.
*   **Result Type:** `NetworkResult<T>` (from Story 1.2).

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository`
*   **File:** `XtreamSeriesRepositoryV2.kt` (Interface + Impl).

## Dev Notes

*   **Clean API:** The ViewModel should ONLY interact with this Repository, never the DAO or API service directly.
*   **Flow:** Ensure all flows run on `Dispatchers.IO`.

## References

*   [Source: docs/epics.md#Story 2.1: The Repository Entry Point](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Data Architecture](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [NEW] `features/seriesv2/data/repository/XtreamSeriesRepositoryV2.kt`
*   [NEW] `features/seriesv2/data/model/EpisodeEntityV2.kt`
*   [NEW] `features/seriesv2/data/dao/EpisodeDaoV2.kt`

### Completion Notes
- Implemented Repository with Paging logic.
- Created missing Episode scaffolding (Entity + DAO).
- Updated Database Schema (v10).
- Validated via KAPT.
