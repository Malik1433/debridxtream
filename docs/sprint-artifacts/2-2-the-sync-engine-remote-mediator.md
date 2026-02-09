# Story 2.2: The Sync Engine (Remote Mediator)

**Status:** Ready for Review

## Story

**As a** User,
**I want** to see cached data instantly while new data fetches in the background,
**So that** I never stare at a blank screen.

## Acceptance Criteria

1.  **RemoteMediator Implementation:**
    *   Create `features/seriesv2/data/repository/SeriesRemoteMediatorV2.kt`.
    *   Extend `RemoteMediator<Int, SeriesEntityV2>`.

2.  **Load Logic:**
    *   Implement `load(loadType, state)`.
    *   **REFRESH:** Clear DB (if aggressive refresh requested) or Smart Update. Fetch Page 1 from Network. Insert into DB.
    *   **APPEND:** Calculate next page key. Fetch next page. Insert into DB.
    *   **PREPEND:** Return `Success(endOfPaginationReached = true)` (usually no prepend for Series lists needed unless specific UX).

3.  **Transaction Safety:**
    *   Use `database.withTransaction { ... }` for all DB operations inside `load()`.
    *   Ensure atomic operations (Delete + Insert).

4.  **Error Handling:**
    *   Catch `IOException` and `HttpException`.
    *   Return `MediatorResult.Error(e)`. Do NOT crash.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Paging:** Paging 3 Experimental API (RemoteMediator).
*   **Threads:** `Dispatchers.IO`.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository`
*   **File:** `SeriesRemoteMediatorV2.kt`

## Dev Notes

*   **Offline-First Core:** This is the most critical component. It bridges the Gap between "Network Data" and "Local UI".
*   **Keys:** You may need a `RemoteKeys` table if the API uses complex pagination tokens. If it uses simple Limit/Offset or standard Page numbers, standard keys might suffice.

## References

*   [Source: docs/epics.md#Story 2.2: The Sync Engine (Remote Mediator)](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Data Architecture](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [NEW] `features/seriesv2/data/repository/SeriesRemoteMediatorV2.kt`
*   [MODIFIED] `features/seriesv2/data/repository/XtreamSeriesRepositoryV2.kt`
*   [MODIFIED] `features/seriesv2/data/dao/SeriesDaoV2.kt`

### Completion Notes
- Implemented `SeriesRemoteMediatorV2` without `RemoteKeys` (API returns full list).
- Integrated into Repository via `getPagedSeries`.
- Added category support to `SeriesDaoV2`.
- Validated via Compilation.
