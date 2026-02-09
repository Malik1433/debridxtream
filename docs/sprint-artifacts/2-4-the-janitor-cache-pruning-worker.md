# Story 2.4: The Janitor (Cache Pruning Worker)

**Status:** Ready for Review

## Story

As an App Maintainer, I want a background service that periodically cleans up stale data from the database, so that the app's storage footprint remains manageable without manual intervention.

## Technical Design

- **Worker:** `SeriesCachePruningWorker` (Hilt-injected).
- **Trigger:** Periodic WorkManager (Daily).
- **Constraints:** Charging + Idle (Zero user impact).
- **Logic:** `RetentionPolicy` (Pure Logic) + `SeriesDaoV2` (Data Access).

## Dev Agent Record

### Agent Model Used
Gemini 2.0 Flash (Execution Mode)

### File List
*   [NEW] `features/seriesv2/worker/SeriesCachePruningWorker.kt`
*   [MODIFIED] `App.kt`

### Completion Notes
- Implemented Worker using DAO and Policy.
- Scheduled in `App.onCreate` using `KEEP` policy (safe to call repeatedly).
- Configured with `RequiresCharging` and `RequiresDeviceIdle`.
