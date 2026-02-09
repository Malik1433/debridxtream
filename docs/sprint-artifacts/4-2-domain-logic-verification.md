# Story 4.2: Domain Logic Verification

**Status:** ready-for-dev

## Story

**As a** Developer,
**I want** to ensure my pruning logic is correct,
**So that** I don't delete the wrong files.

## Acceptance Criteria

1.  **Test Class:**
    *   Create `features/seriesv2/domain/logic/RetentionPolicyTest.kt`.

2.  **Test Cases:**
    *   `test_purgable_if_old_and_not_favorite()`: Date > 7 days ago, Fav = False -> Returns ID.
    *   `test_kept_if_recent()`: Date < 7 days ago -> Returns Empty List.
    *   `test_kept_if_favorite_even_if_old()`: Date > 7 days ago, Fav = True -> Returns Empty List.

3.  **Boundary Conditions:**
    *   Test exactly 7 days (Equal case).

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Framework:** Pure JUnit 4 (No Android/Robolectric needed if RetentionPolicy is pure).

## Architecture & Code Structure

*   **Location:** `src/test/java/com/tvonnet/debridxtreamiptv/features/seriesv2/domain/logic`
*   **File:** `RetentionPolicyTest.kt`

## Dev Notes

*   **Fast:** This test should run in milliseconds. It validates the core business logic of the "Storage Cap" requirement.

## References

*   [Source: docs/epics.md#Story 4.2: Domain Logic Verification](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Testing Strategy](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [NEW] `features/seriesv2/domain/logic/RetentionPolicyTest.kt`
