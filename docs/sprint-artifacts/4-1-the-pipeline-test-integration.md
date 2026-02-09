# Story 4.1: The Pipeline Test (Integration)

**Status:** ready-for-dev

## Story

**As a** Developer,
**I want** to verify the entire pipeline (Network -> DB -> Paging) works,
**So that** I don't ship a broken sync engine.

## Acceptance Criteria

1.  **Test Class:**
    *   Create `features/seriesv2/data/repository/XtreamSeriesRepositoryV2Test.kt`.
    *   Annotate with `@RunWith(RobolectricTestRunner::class)`.

2.  **Mock Environment:**
    *   Start `MockWebServer` (OkHttp).
    *   Setup In-Memory Room Database (`AppDatabase`).

3.  **Test Case:**
    *   Enqueue a valid JSON response (Episodes List) in MockWebServer.
    *   Call `repo.refresh()`.
    *   **Verify 1:** `SeriesDao.count()` increases.
    *   **Verify 2:** `repository.getPagedEpisodes()` emits data matching the JSON.

4.  **Error Case:**
    *   Enqueue 500 Internal Server Error.
    *   Call `repo.refresh()`.
    *   **Verify:** Returns `NetworkResult.Error` and app does NOT crash.

## Technical Requirements

*   **Frameworks:** Robolectric, MockWebServer, JUnit 4, Truth, Turbine (for Flow tests).

## Architecture & Code Structure

*   **Location:** `src/test/java/com/tvonnet/debridxtreamiptv/features/seriesv2/data/repository`
*   **File:** `XtreamSeriesRepositoryV2Test.kt`

## Dev Notes

*   **Why Robolectric?** Because we are testing interaction with Android Components (Room, Context) but want it fast on the JVM.
*   **Crucial:** This test proves the "Rescue Patch" actually rescues correctly.

## References

*   [Source: docs/epics.md#Story 4.1: The Pipeline Test (Integration)](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Testing Strategy](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [NEW] `features/seriesv2/data/repository/XtreamSeriesRepositoryV2Test.kt`
