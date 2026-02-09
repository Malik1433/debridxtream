# Story 4.3: The "Zero-Touch" Regression Check

**Status:** ready-for-dev

## Story

**As a** QA,
**I want** to verify the Legacy Live TV still works,
**So that** we don't regress existing features.

## Acceptance Criteria

1.  **Manual Test Plan:**
    *   Create `docs/manual_test_plans/regression_v2_rollout.md`.
    *   List steps to verify legacy functionality.

2.  **Steps to Include:**
    *   **Live TV Test:** Launch App -> Open Live TV -> Play Channel. (Pass/Fail)
    *   **Movies Test:** Open Movies -> Play Movie. (Pass/Fail)
    *   **Fallback Test:** Toggle `ENABLE_NEW_SERIES_ENGINE` OFF via Debug Panel -> Open Series -> Verify Old UI loads. (Pass/Fail)

3.  **Execution:**
    *   Developer must Perform this test once before merging the Epic.
    *   Mark status in the Story comment or PR description.

## Technical Requirements

*   **Format:** Markdown.

## Architecture & Code Structure

*   **Location:** `docs/manual_test_plans`
*   **File:** `regression_v2_rollout.md`

## Dev Notes

*   **Safety:** This ensures our "Sidecar" architecture worked (V2 didn't break V1).

## References

*   [Source: docs/epics.md#Story 4.3: The "Zero-Touch" Regression Check](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Testing Strategy](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [NEW] `docs/manual_test_plans/regression_v2_rollout.md`
