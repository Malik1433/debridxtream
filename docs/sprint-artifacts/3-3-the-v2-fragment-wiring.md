# Story 3.3: The V2 Fragment (Wiring)

**Status:** ready-for-dev

## Story

**As a** User,
**I want** to see the list of episodes,
**So that** I can pick one to watch.

## Acceptance Criteria

1.  **Fragment Implementation:**
    *   Implement `features/seriesv2/ui/SeriesDetailFragmentV2.kt`.
    *   Use ViewBinding (`FragmentSeriesDetailV2Binding`). (See architecture rule: Legacy=ViewBinding).

2.  **Adapter:**
    *   Create `features/seriesv2/ui/adapter/EpisodesAdapterV2.kt`.
    *   Must extend `PagingDataAdapter<EpisodeV2, ViewHolder>`.
    *   Implement `DiffUtil.ItemCallback` for efficient updates.

3.  **Binding Logic:**
    *   In `onViewCreated`:
    *   Observe ViewModel `uiState` inside `viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(STARTED) }`.
    *   Submit PagingData to Adapter (`adapter.submitData()`).

4.  **Click Handling:**
    *   Adapter clicks should trigger `viewModel.onEpisodeClicked(episode)`.
    *   Observe `navigationEvents` to handle the actual player launch.

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **UI:** ViewBinding + RecyclerView.
*   **Concurrency:** `repeatOnLifecycle`.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.features.seriesv2.ui`
*   **File:** `SeriesDetailFragmentV2.kt`, `EpisodesAdapterV2.kt`

## Dev Notes

*   **No Logic:** Keep the Fragment dumb. It just binds data to views.
*   **Memory:** Clear binding references in `onDestroyView`.

## References

*   [Source: docs/epics.md#Story 3.3: The V2 Fragment (Wiring)](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/architecture.md#Frontend Architecture](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/architecture.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [MODIFY] `features/seriesv2/ui/SeriesDetailFragmentV2.kt`
*   [NEW] `features/seriesv2/ui/adapter/EpisodesAdapterV2.kt`
