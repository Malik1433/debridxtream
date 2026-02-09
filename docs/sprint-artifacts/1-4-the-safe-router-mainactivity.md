# Story 1.4: The Safe Router (MainActivity)

**Status:** Ready for Review

## Story

**As a** User,
**I want** to be routed to the correct Series screen based on the system configuration,
**So that** I don't see the wrong UI or experience bugs from the new engine.

## Acceptance Criteria

1.  **Interception Logic:**
    *   [x] In `MainActivity` (or the main Navigation Host / Fragment), locate the event listener for the "Series" interaction (e.g., clicking 'Series' on the Home Dashboard).
    *   [x] Intercept this click.

2.  **Routing Check:**
    *   [x] Call `FeatureFlagManager.isSeriesV2Enabled`.
    *   [x] **IF TRUE:** Navigate to `SeriesDetailFragmentV2`.
    *   [x] **IF FALSE:** Navigate to the original `SeriesDetailFragment` (Legacy).

3.  **V2 Stub:**
    *   [x] Since `SeriesDetailFragmentV2` might not be fully implemented yet, ensure the Fragment exists class-wise (it can just show a "Welcome to V2" Toast/Text for now).
    *   [x] Ensure the navigation graph (if used) supports this new destination.

4.  **Zero Latency:**
    *   [x] The check must happen synchronously on the UI thread without blocking.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Navigation:** Existing system (likely FragmentManager or Jetpack Navigation).
*   **Dependencies:** `core.featureflag` module.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.ui.main` (or equivalent).
*   **File:** `MainActivity.kt` or `HomeFragment.kt`.

## Dev Notes

*   **Safety First:** If `FeatureFlagManager` throws an exception for any reason (it shouldn't), catch it and default to **SAFE MODE** (Legacy Path).
*   **Testing:** Verify by toggling the flag in settings and clicking "Series" multiple times.

## References

*   [Source: docs/epics.md#Story 1.4: The Safe Router (MainActivity)](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Infrastructure](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [MODIFY] `ui/home/HomeFragmentModern.kt`
*   [NEW] `features/seriesv2/ui/SeriesDetailFragmentV2.kt` (Stub)

### Completion Notes
- Implemented routing in `HomeFragmentModern.kt`.
- Created placeholder `SeriesDetailFragmentV2` with simple text message.
- Compilation verified.
