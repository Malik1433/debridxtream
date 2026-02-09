# Story 3.1: The State Machine (ViewModel V2)

**Status:** ready-for-dev

## Story

**As a** Developer,
**I want** a robust ViewModel that holds the UI state,
**So that** rotation or process death doesn't lose the user's place.

## Acceptance Criteria

1.  **ViewModel Implementation:**
    *   Create `features/seriesv2/ui/SeriesDetailViewModelV2.kt`.
    *   Hilt ViewModel (`@HiltViewModel`).
    *   Inject `XtreamSeriesRepositoryV2`.

2.  **State Exposure (StateFlow):**
    *   Expose `uiState: StateFlow<SeriesDetailUiState>`.
    *   `SeriesDetailUiState` should be a data class/sealed interface holding: `isLoading`, `series`, `episodes`, `error`.
    *   Use `WhileSubscribed(5000)` upstream sharing to preserve resources.

3.  **Event Handling (Channels):**
    *   Expose `navigationEvents: Flow<SeriesNavigationEvent>`.
    *   Events: `NavigateToPlayer(episode)`, `ShowToast(msg)`, etc.

4.  **Initial Load:**
    *   `init { }` block or `fun initialize(id: Int)` must trigger the repository Fetch.

5.  **Unit Test:**
    *   Verify `onRetryClicked` triggers `repo.refresh()`.
    *   Verify State updates from Loading -> Success.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Architecture:** MVI / MVVM.
*   **Threads:** Main-Safe interactions.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.features.seriesv2.ui`
*   **File:** `SeriesDetailViewModelV2.kt`

## Dev Notes

*   **State Reducer:** Keep the state mutation logic clear. StateFlow should always emit the *latest* valid state.
*   **Paging:** You might expose `Flow<PagingData<EpisodeV2>>` separately from the UiState if using `collectAsLazyPagingItems` in Compose/XML Adapter.

## References

*   [Source: docs/epics.md#Story 3.1: The State Machine (ViewModel V2)](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Frontend Architecture](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [NEW] `features/seriesv2/ui/SeriesDetailViewModelV2.kt`
*   [NEW] `features/seriesv2/ui/model/SeriesDetailUiState.kt`
