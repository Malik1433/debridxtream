# Story 4.4: Performance Profiling

**Status:** ready-for-dev

## Story

**As a** Developer,
**I want** to verify memory usage,
**So that** we don't crash low-end devices.

## Acceptance Criteria

1.  **Profiling Report:**
    *   Create `docs/performance_reports/v2_memory_profile.md`.
    *   Run app on typical device (or Emulator with restricted RAM).
    *   Trigger Full Sync (Series Update).

2.  **Metrics to Capture:**
    *   **Heap Size:** Must be < 256MB peak.
    *   **DB Size:** Check size of `series_v2` table after syncing 1000 items. Must be reasonable (< 50MB for metadata).

3.  **Validation:**
    *   Attach Screenshots of Android Studio Profiler in the report (as links or description).

## Technical Requirements

*   **Tools:** Android Studio Profiler.

## Architecture & Code Structure

*   **Location:** `docs/performance_reports`
*   **File:** `v2_memory_profile.md`

## Dev Notes

*   **OOM:** If Heap hits 512MB+, we failed the NFR. Check Paging config `initialLoadSize` and `pageSize`.

## References

*   [Source: docs/epics.md#Story 4.4: Performance Profiling](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Requirements Coverage Validation](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [NEW] `docs/performance_reports/v2_memory_profile.md`
