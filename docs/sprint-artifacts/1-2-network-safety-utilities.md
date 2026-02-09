# Story 1.2: Network Safety Utilities

**Status:** Ready for Review

## Story

**As a** Developer,
**I want** a standardized `NetworkResult` wrapper,
**So that** I can handle 401s and IOExceptions consistently without try-catch blocks in every ViewModel and Repository.

## Acceptance Criteria

1.  **NetworkResult Sealed Class:**
    *   [x] Create `core/network/NetworkResult.kt` as a sealed class with generic type `<T>`.
    *   [x] Subclass `Success<T>(val data: T)`: Holds the parsed data.
    *   [x] Subclass `Error<T>(val code: Int, val message: String?)`: specific for HTTP failures (4xx/5xx).
    *   [x] Subclass `Exception<T>(val exception: Throwable)`: specific for connectivity/parsing errors (IOException).

2.  **Safe Call Wrapper:**
    *   [x] Create `core/network/NetworkExtensions.kt` (or similar utility file).
    *   [x] Implement `suspend fun <T> safeApiCall(apiCall: suspend () -> Response<T>): NetworkResult<T>`.
    *   [x] **Logic:**
        *   Execute `apiCall()` inside a `try-catch` block.
        *   Catch `IOException` -> Return `NetworkResult.Exception`.
        *   Check `response.isSuccessful`:
            *   True and `body()` not null -> Return `NetworkResult.Success`.
            *   False -> Return `NetworkResult.Error` with `code` and `message`.
        *   Handle null body case safely (generic nullable T or specific error).

3.  **Threading:**
    *   [x] `safeApiCall` MUST run on `Dispatchers.IO` to ensure main-safety, regardless of caller context.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Networking:** Retrofit 2.9.0 types (`Response`).
*   **Coroutines:** `kotlinx.coroutines`.
*   **Generics:** Must support any data type (e.g., `List<Series>`, `EpisodeResponse`).

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.core.network` (Existing package - ADD new files here).
*   **File:** `NetworkResult.kt`, `NetworkExtensions.kt`.
*   **Dependencies:**
    *   `retrofit2.Response`
    *   `kotlinx.coroutines.Dispatchers`

## Dev Notes

*   **Why a Wrapper?** We want to avoid `try { ... } catch (e: Exception) { ... }` duplication in `XtreamSeriesRepositoryV2`. The repository should strictly return `Flow<NetworkResult<T>>`.
*   **Error Distinctions:** Vital for the "Offline-First" logic. `Error` (401) might mean "Re-login required", while `Exception` (No Internet) means "Show Cached Data".

## References

*   [Source: docs/epics.md#Story 1.2: Network Safety Utilities](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#API & Error Handling](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [NEW] `core/network/NetworkResult.kt`
*   [NEW] `core/network/NetworkExtensions.kt`
*   [NEW] `core/network/NetworkSafetyTest.kt` (Unit Tests)

### Completion Notes
- Implemented `NetworkResult` sealed class with Success, Error, and Exception states.
- Implemented `safeApiCall` utility using `Dispatchers.IO` context switch.
- Added comprehensive unit tests in `NetworkSafetyTest.kt`.
- Verified logic handles 4xx/5xx codes vs IOExceptions correctly.
