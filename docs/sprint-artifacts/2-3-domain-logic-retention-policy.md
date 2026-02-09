# Story 2.3: Domain Logic - Retention Policy

**Status:** Ready for Review

## Story

**As a** System,
**I want** to know *what* data is stale,
**So that** I don't delete recent favorites by mistake.

## Acceptance Criteria

1.  **Retention Policy Class:**
    *   Create `features/seriesv2/domain/logic/RetentionPolicy.kt`.
    *   This should be a **Pure Kotlin Class** (No Android dependencies if possible).

2.  **Purgable Logic:**
    *   Implement `getPurgableSeriesIds(allSeries: List<SeriesMeta>, thresholdDays: Int): List<String>`.
    *   **Rule 1:** Older than `thresholdDays` (e.g., 7 days) calls `isPurgable`.
    *   **Rule 2:** `isFavorite == true` is NEVER purgable, regardless of age.

3.  **Unit Test:**
    *   Verify logic with JUnit. Create mock tests:
        *   "Old + NotFavorite -> Delete"
        *   "Old + Favorite -> Keep"
        *   "New + NotFavorite -> Keep"

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Testing:** JUnit 4.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.features.seriesv2.domain.logic`
*   **File:** `RetentionPolicy.kt`

## Dev Notes

*   **TDD:** This is a perfect candidate for Test Driven Development. Write the test first!
*   **Simplicity:** Keep this logic clean. It determines what gets deleted from the user's cache. Mistakes here mean data loss.

## References

*   [Source: docs/epics.md#Story 2.3: Domain Logic - Retention Policy](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Data Architecture](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [NEW] `features/seriesv2/domain/logic/RetentionPolicy.kt`
*   [NEW] `features/seriesv2/data/model/SeriesMeta.kt`
*   [MODIFIED] `features/seriesv2/data/dao/SeriesDaoV2.kt`
*   [NEW] `features/seriesv2/domain/logic/RetentionPolicyTest.kt` (Test)

### Completion Notes
- Implemented pure domain logic for Retention (Old + NotFav = Purge).
- Added DAO support for bulk deletion and lightweight fetching.
- Added Unit Test covering all rules.
