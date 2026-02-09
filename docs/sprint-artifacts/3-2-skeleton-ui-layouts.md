# Story 3.2: Skeleton UI Layouts

**Status:** ready-for-dev

## Story

**As a** User,
**I want** to see a placeholder structure instantly,
**So that** I know the app is working while data loads.

## Acceptance Criteria

1.  **Skeleton Item Layout:**
    *   Create `res/layout/item_episode_skeleton.xml`.
    *   Use gray block views (`View` with background color) to mimic Title, Thumbnail, and Duration text presence.
    *   Apply a Shimmer effect (using Facebook Shimmer or custom animation).

2.  **Fragment Layout Update:**
    *   Update `res/layout/fragment_series_detail_v2.xml`.
    *   Add a `ShimmerFrameLayout` (or equivalent container) overlaying the RecyclerView.

3.  **Display Logic:**
    *   Show Shimmer when `PagingData` LoadState is `Loading`.
    *   Hide Shimmer and Show RecyclerView when LoadState is `NotLoading`.

4.  **Performance:**
    *   Ensure the Animation does not consume excessive CPU. Stop animation when View is hidden or destroyed.

## Technical Requirements

*   **Language:** XML / Kotlin.
*   **Library:** `com.facebook.shimmer:shimmer` (Check if available, else standard View Animator).

## Architecture & Code Structure

*   **Location:** `res/layout`
*   **Files:** `item_episode_skeleton.xml`, `fragment_series_detail_v2.xml`

## Dev Notes

*   **UX Pattern:** Optimistic UI requires these skeletons so the user interaction feels "acknowledged" immediately (< 100ms).
*   **Design:** Match the height/width of the actual Episode Item to prevent layout jumps when content populates.

## References

*   [Source: docs/epics.md#Story 3.2: Skeleton UI Layouts](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/epics.md)
*   [Source: docs/prd.md#2. Series Interaction](file:///e:/running%20project%20baxkups/lmsd%20working%20but%20loading%20issues/debxtrem/docs/prd.md)

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Planning Mode)

### File List
*   [NEW] `res/layout/item_episode_skeleton.xml`
*   [MODIFY] `res/layout/fragment_series_detail_v2.xml`
