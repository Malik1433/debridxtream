# Story 3.4: Optimistic Navigation (The 0ms Trick)

**Status:** ready-for-dev

## Story

**As a** User,
**I want** to see the Backdrop image *immediately* when I click a series,
**So that** the transition feels seamless.

## Acceptance Criteria

1.  **Arguments Update:**
    *   Update `SeriesDetailFragmentV2` (and navigation graph safe-args) to accept:
        *   `title: String`
        *   `backdropUrl: String?`
        *   `posterUrl: String?`

2.  **Instant Rendering:**
    *   In `onViewCreated` (BEFORE ViewModel loads any data):
    *   Set the `TextView.text = title`.
    *   Load `backdropUrl` into the ImageView using Glide.

3.  **Glide Optimization:**
    *   Use `.dontAnimate()` for this specific load to prevent crossfade flickering.
    *   (Optional) Use `.onlyRetrieveFromCache(true)` if we suspect it's already in memory, but standard load is usually fine.

4.  **Latency Goal:**
    *   The user must perceive the screen as "Already Loaded" while the actual Episode list data is still fetching (Shimmering).

## Technical Requirements

*   **Language:** Kotlin 1.9.25
*   **Image Loading:** Glide 4.16.0.

## Architecture & Code Structure

*   **Location:** `com.tvonnet.debridxtreamiptv.features.seriesv2.ui`
*   **File:** `SeriesDetailFragmentV2.kt`

## Dev Notes

*   **Perception:** This is the most crucial "Speed Hack". The data fetch takes 500ms+, but showing the cached image makes it feel instant.
*   **Shared Element:** If using Shared Element Transitions, configuring the transition name here is also required.

## References

*   [Source: docs/epics.md#Story 3.4: Optimistic Navigation (The 0ms Trick)](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/prd.md#2. Series Interaction](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/prd.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [MODIFY] `features/seriesv2/ui/SeriesDetailFragmentV2.kt`
*   [MODIFY] `ui/main/MainActivity.kt` (Call site arguments)
