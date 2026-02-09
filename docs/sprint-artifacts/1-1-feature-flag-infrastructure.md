# Story 1.1: Feature Flag Infrastructure

**Status:** Ready for Review

## Story

**As a** Developer/Admin,
**I want** a centralized control to toggle the V2 engine,
**So that** I can safely enable/disable the new code at runtime without redeploying and prevent regressions in the live app.

## Acceptance Criteria

1.  **Feature Flag Manager Component:**
    *   [x] Create `core/featureflag/FeatureFlagManager.kt` as a Kotlin `object` (Singleton).
    *   [x] Expose a public property `isSeriesV2Enabled: Boolean`.
    *   [x] Property logic: Check **Memory Cache** first. If null/empty, check **SharedPreferences**.

2.  **Default Safety:**
    *   [x] The default value MUST be `false` (Safe Mode) if no key is found in SharedPreferences.

3.  **Debug Panel Integration:**
    *   [x] Add a Toggle Switch to the existing Debug Panel / Developer Options screen (Settings -> Dev Options).
    *   [x] Label: "Enable Series V2 Engine".
    *   [x] State MUST be synchronized with `FeatureFlagManager.isSeriesV2Enabled`.

4.  **Runtime Application:**
    *   [x] Toggling the switch MUST persist the new value to SharedPreferences IMMEDIATELY.
    *   [x] Toggling the switch MUST trigger an app restart (using `ProcessPhoenix` or standard Intent restart) to ensure a clean state.

5.  **Performance:**
    *   [x] Accessing `isSeriesV2Enabled` must be **Zero Latency** (Memory access) for use in tight loops or UI rendering.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Storage:** `androidx.preference` or standard `SharedPreferences`.
*   **No Remote Config:** strictly local storage only. No Firebase/cloud dependency.
*   **Restart Mechanism:** Ensure the restart logic cleanly kills the current process to avoid singleton state leaks.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.core.featureflag`
*   **File:** `FeatureFlagManager.kt`
*   **Dependencies:**
    *   `core.database` (Existing) - *Check if shared prefs helper exists there, otherwise standard Context usage.*
    *   DO NOT import `features/legacy` or `features/seriesv2`. This component must be dependency-free (Core).

## Dev Notes

*   **Why Singleton?** We need to access this flag from `MainActivity` (Router), ViewModels, and potentially Repositories. A simple object is the most efficient and robust pattern here without circular Di issues.
*   **Context usage:** You might need to initialize the singleton with `Context` in `Application.onCreate()` to read SharedPreferences, OR pass `Context` to the getter. Prefer initializing in Application to keep the getter signature clean: `FeatureFlagManager.init(context)`.

## References

*   [Source: docs/epics.md#Story 1.1: Feature Flag Infrastructure](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Infrastructure](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)
*   [Source: docs/prd.md#3. System & Safety](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/prd.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [NEW] `core/featureflag/FeatureFlagManager.kt`
*   [NEW] `core/featureflag/FeatureFlagManagerTest.kt`
*   [MODIFY] `App.kt`
*   [MODIFY] `ui/settings/SettingsFragment.kt`
*   [MODIFY] `ui/settings/SettingsFragmentNew.kt`
*   [MODIFY] `res/layout/fragment_settings.xml`
*   [MODIFY] `res/xml/preferences_settings.xml`

### Completion Notes
- Implemented `FeatureFlagManager` singleton with memory caching and SharedPreferences.
- Initialized in `App.onCreate`.
- Added UI toggle to **both** Modern (`SettingsFragmentNew`) and Legacy (`SettingsFragment`) settings screens to ensure coverage.
- Implemented auto-restart logic using `Intent.makeRestartActivityTask`.
- Added unit tests verifying default state, persistence, and memory caching.
