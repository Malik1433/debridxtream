# Epics and User Stories

**Status:** In Progress
**Context Loaded:**
*   **PRD:** `docs/prd.md` (Complete)
*   **Architecture:** `docs/architecture.md` (Complete)
*   **UX Design:** N/A (Using functionality defined in PRD)

---

## 0. Context Validation

### Document verification
*   ✅ **PRD.md**: Found 14 Functional Requirements (FRs) and 6 Non-Functional Requirements (NFRs).
*   ✅ **Architecture.md**: Found "Native Rescue Stack" (Room+Paging3), "Sidecar" Isolation, and "V2" Naming Rules.
*   ⚠️ **UX Design**: Not present. Stories will derive UI behavior from PRD "User Journeys" (Ali & Sarah) and standard Material/Leanback patterns.

### FR Inventory (The "Must-Haves")

**1. Content Synchronization**
*   **FR-SYNC-01:** Background Sync of Series metadata to Room DB.
*   **FR-SYNC-02:** Silent Sync (Non-blocking UI).
*   **FR-SYNC-03:** Atomic Integrity (Transactions).
*   **FR-SYNC-04:** Exponential Backoff for retries.

**2. Series Interaction (UI)**
*   **FR-UI-01:** 0ms Latency Navigation (Optimistic).
*   **FR-UI-02:** Skeleton UI placeholders.
*   **FR-UI-03:** Offline browsing capability.
*   **FR-UI-04:** Slow Connection Toast (>3s).
*   **FR-UI-05:** Local Image Placeholders.
*   **FR-UI-06:** Manual Retry Button.

**3. System & Safety**
*   **FR-SYS-01:** Feature Flag (`ENABLE_NEW_SERIES_ENGINE`).
*   **FR-SYS-02:** Router -> V2 Fragment.
*   **FR-SYS-03:** Router -> V1 Legacy Fragment.
*   **FR-SYS-04:** Debug Panel Toggle.

---

## 1. Strategic Epic Plan

**Strategy:** "Bottom-Up Construction". We build the safe environment first (Foundation), then the data engine (Sync), then the UI on top (Display).

### Epic 1: Project Foundation & Safety Net
**Goal:** Establish the isolated V2 environment and safe routing mechanism so we can build without breaking the app.
*   **User Value:** Zero risk of regression. The user sees no change, but the app is ready for the new engine.
*   **Key Deliverables:** `FeatureFlagManager`, New Room Tables (`series_v2`), `NetworkResult` utility, V2 Directory Structure.
*   **FR Coverage:** FR-SYS-01, FR-SYS-02, FR-SYS-03, FR-SYS-04.
*   **Dependencies:** None.

### Epic 2: The Data Engine (Sync & Store)
**Goal:** Implement the "Offline-First" logic. Fetch data, store in DB, and clean up old data.
*   **User Value:** Reliable data access. If the network fails, data is still there. Background syncs happen silently.
*   **Key Deliverables:** `XtreamSeriesRepositoryV2`, `SeriesRemoteMediatorV2` (Paging), `CachePruningWorker`.
*   **FR Coverage:** FR-SYNC-01, FR-SYNC-02, FR-SYNC-03, FR-SYNC-04.
*   **Dependencies:** Epic 1 (DB & NetworkResult).

### Epic 3: High-Performance UI (Series V2)
**Goal:** Build the "0ms Latency" screens that consume data from the DB.
*   **User Value:** Instant navigation and a "Premium" feel. No spinners.
*   **Key Deliverables:** `SeriesDetailViewModelV2`, `SeriesDetailFragmentV2`, Skeleton Layouts, Optimistic Navigation.
*   **FR Coverage:** FR-UI-01 to FR-UI-06.
*   **Dependencies:** Epic 2 (Repo & Mediator).

### Epic 4: Verification & Polish
**Goal:** Prove it works and meets constraints before shipping.
*   **User Value:** A bug-free experience.
*   **Key Deliverables:** Robolectric Integration Tests, Memory Profiling Report (<256MB), Manual Regression Check.
*   **FR Coverage:** All NFRs (Performance & Reliability).
*   **Dependencies:** Epic 3.

---

## 2. Epic 1: Project Foundation & Safety Net

**Goal:** Establish the isolated V2 environment and ironclad safety mechanism.

### Story 1.1: Feature Flag Infrastructure
**As a** Developer/Admin, **I want** a centralized controls to toggle the V2 engine, **So that** I can safely enable/disable the new code at runtime without redeploying.
*   **Acceptance Criteria:**
    *   `core/featureflag/FeatureFlagManager.kt` object created (Singleton).
    *   `isSeriesV2Enabled` property checks Memory Cache first, then SharedPreferences.
    *   Default value is `false` (Safe Mode).
    *   Debug Panel (Settings -> Dev Options) has a Toggle Switch for this flag.
    *   Changing the toggle restarts the app (ProcessPhoenix or plain Intent restart).
*   **Technical Notes:**
    *   Use `androidx.preference` or raw `SharedPreferences`.
    *   **NO** Firebase Remote Config for MVP (Local only).
*   **Dependencies:** None.

### Story 1.2: Network Safety Utilities
**As a** Developer, **I want** a standardized `NetworkResult` wrapper, **So that** I can handle 401s and IOExceptions consistently without try-catch blocks in every ViewModel.
*   **Acceptance Criteria:**
    *   `core/network/NetworkResult.kt` Sealed Class created (Success, Error, Exception).
    *   `core/network/NetworkExtensions.kt` contains `safeApiCall { ... }` function.
    *   `safeApiCall` catches `IOException` -> returns `NetworkResult.Exception`.
    *   `safeApiCall` checks `response.isSuccessful` -> returns `NetworkResult.Success` or `NetworkResult.Error`.
*   **Technical Notes:**
    *   Must be generic `<T>`.
    *   Must run on `Dispatchers.IO` internally.
*   **Dependencies:** Retrofit (Existing).

### Story 1.3: V2 Room Scaffolding
**As a** Developer, **I want** dedicated V2 database tables, **So that** new data requirements don't corrupt the legacy database schema.
*   **Acceptance Criteria:**
    *   `features/seriesv2/data/model/SeriesEntityV2.kt` created (`@Entity(tableName = "series_v2")`).
    *   `features/seriesv2/data/dao/SeriesDaoV2.kt` interface created.
    *   Main `AppDatabase` updated to include new Entity and DAO.
    *   **Migration Test:** Verify app upgrade doesn't crash on DB migration.
*   **Technical Notes:**
    *   Use `snake_case` for columns to match JSON (e.g., `series_id`, `name`, `backdrop_path`).
    *   Index `series_id` for faster lookups.
*   **Dependencies:** Room (Existing).

### Story 1.4: The Safe Router (MainActivity)
**As a** User, **I want** to be routed to the correct Series screen based on the system configuration, **So that** I don't see the wrong UI.
*   **Acceptance Criteria:**
    *   In `MainActivity` (or Navigation Host), intercept the "Series" click event.
    *   Check `FeatureFlagManager.isSeriesV2Enabled`.
    *   **IF True:** Navigate to `SeriesDetailFragmentV2` (Stub).
    *   **IF False:** Navigate to `SeriesDetailFragment` (Legacy).
    *   Verify both paths work by toggling the flag in Debug Panel.
*   **Technical Notes:**
    *   Create a simple Toast "Welcome to V2" for the V2 Stub for now.
    *   Ensure **Zero Latency** on the check.
*   **Dependencies:** Story 1.1.

---

## 3. Epic 2: The Data Engine (Sync & Store)

**Goal:** Implement the "Offline-First" Paging pipeline and automated cleanup.

### Story 2.1: The Repository Entry Point
**As a** ViewModel, **I want** a clean API to request Series data, **So that** I don't need to know about Paging or Network logic.
*   **Acceptance Criteria:**
    *   `features/seriesv2/data/repository/XtreamSeriesRepositoryV2.kt` implementation created.
    *   `getSeriesById(id): Flow<NetworkResult<SeriesV2>>` (Single source fetch).
    *   `getPagedEpisodes(seriesId): Flow<PagingData<EpisodeV2>>` (The main stream).
    *   **Unit Test:** Mock Retrofit/DAO and verify Flow emissions.
*   **Technical Notes:**
    *   Use `Pager` configuration with `pageSize = 20`.
    *   Connect `SeriesDaoV2.pagingSource()` to the Pager.
*   **Dependencies:** Epic 1.

### Story 2.2: The Sync Engine (Remote Mediator)
**As a** User, **I want** to see cached data instantly while new data fetches in the background, **So that** I never stare at a blank screen.
*   **Acceptance Criteria:**
    *   `features/seriesv2/data/repository/SeriesRemoteMediatorV2.kt` created.
    *   `load(LoadType, PagingState)` implementation handles `REFRESH`, `PREPEND`, `APPEND`.
    *   **Transaction:** On network success -> Clear old entries (if Refresh) -> Insert new entries -> Insert "Next Key".
    *   **Error Handling:** Catch 401/IOException -> Return `MediatorResult.Error`.
*   **Technical Notes:**
    *   This is the **Core Logic**. Must use `withTransaction` for Atomic integrity.
    *   Must handle `LoadType.REFRESH` carefully (don't wipe DB if network fails).
*   **Dependencies:** Story 2.1 (Interface).

### Story 2.3: Domain Logic - Retention Policy
**As a** System, **I want** to know *what* data is stale, **So that** I don't delete recent favorites by mistake.
*   **Acceptance Criteria:**
    *   `features/seriesv2/domain/logic/RetentionPolicy.kt` (Pure Kotlin Class) created.
    *   Function `getPurgableSeriesIds(allSeries: List<SeriesMeta>, thresholdDays: Int): List<String>`.
    *   **Unit Test:** Verify it marks data > 7 days old as purgable, but KEEPS "Favorites".
*   **Technical Notes:**
    *   Test Driven Development (TDD) recommended for this class.
    *   No Android dependencies in this class.
*   **Dependencies:** None.

### Story 2.4: The Janitor (Cache Pruning Worker)
**As a** System, **I want** to delete old data automatically at night, **So that** I stay under the 500MB storage cap.
*   **Acceptance Criteria:**
    *   `features/seriesv2/data/worker/CachePruningWorker.kt` created.
    *   Scheduled via WorkManager (Periodic, 24h, Requires Charging + Idle).
    *   Injects `SeriesDaoV2` and `RetentionPolicy`.
    *   Deletes rows identified by the policy.
*   **Technical Notes:**
    *   Must fail gracefully (return `Result.success()`) if DB is empty to complete the job.
*   **Dependencies:** Story 2.3.

---

## 4. Epic 3: High-Performance UI (Series V2)

**Goal:** Build the Interface that consumes the data stream with zero lag.

### Story 3.1: The State Machine (ViewModel V2)
**As a** Developer, **I want** a robust ViewModel that holds the UI state, **So that** rotation or process death doesn't lose the user's place.
*   **Acceptance Criteria:**
    *   `features/seriesv2/ui/SeriesDetailViewModelV2.kt` created.
    *   Exposes `uiState: StateFlow<SeriesDetailUiState>`.
    *   Exposes `navigationEvents: Flow<SeriesNavigationEvent>` (Channel).
    *   `init { }` block triggers initial load (metadata only).
    *   **Unit Test:** Verify `onRetryClicked` triggers a new `repo.refresh()`.
*   **Technical Notes:**
    *   Use `WhileSubscribed(5000)` for the StateFlow to save resources when backgrounded.
*   **Dependencies:** Epic 2.

### Story 3.2: Skeleton UI Layouts
**As a** User, **I want** to see a placeholder structure instantly, **So that** I know the app is working while data loads.
*   **Acceptance Criteria:**
    *   `res/layout/item_episode_skeleton.xml` created (Shimmer effect).
    *   `res/layout/fragment_series_detail_v2.xml` updated to include a `ShimmerFrameLayout` wrapping the RecyclerView.
    *   Logic: Show Shimmer when `PagingData` is `LoadState.Loading`. Hide when `NotLoading`.
*   **Technical Notes:**
    *   Use `Facebook Shimmer` library (already in dependencies?). If not, use a simple View Animator to keep it light.
*   **Dependencies:** None.

### Story 3.3: The V2 Fragment (Wiring)
**As a** User, **I want** to see the list of episodes, **So that** I can pick one to watch.
*   **Acceptance Criteria:**
    *   `features/seriesv2/ui/SeriesDetailFragmentV2.kt` implementation.
    *   `EpisodesAdapterV2` (PagingDataAdapter) created.
    *   Observe ViewModel `uiState` inside `viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(STARTED) }`.
    *   Bind PagingData to Adapter.
*   **Technical Notes:**
    *   **NO LiveData**.
    *   **NO** complex logic in Fragment. Just bind State -> View.
*   **Dependencies:** Story 3.1.

### Story 3.4: Optimistic Navigation (The 0ms Trick)
**As a** User, **I want** to see the Backdrop image *immediately* when I click a series, **So that** the transition feels seamless.
*   **Acceptance Criteria:**
    *   Update `SeriesDetailFragmentV2` arguments to accept `title`, `backdropUrl`, and `posterUrl`.
    *   Render these ARGUMENTS immediately into the View (before ViewModel even loads data).
    *   This creates the "0ms Latency" effect (FR-UI-01).
*   **Technical Notes:**
    *   Use Glide with `dontAnimate()` for the passed-in backdrop to prevent flickering.
*   **Dependencies:** Story 3.3.

---

## 5. Epic 4: Verification & Polish

**Goal:** Prove reliability before release.

### Story 4.1: The Pipeline Test (Integration)
**As a** Developer, **I want** to verify the entire pipeline (Network -> DB -> Paging) works, **So that** I don't ship a broken sync engine.
*   **Acceptance Criteria:**
    *   `features/seriesv2/data/repository/XtreamSeriesRepositoryV2Test.kt` created.
    *   Use `Robolectric` to simulate Android context.
    *   Start `MockWebServer` -> Enqueue JSON response.
    *   Call `repo.refresh()` -> Verify `SeriesDao` has inserted data.
    *   Verify `NetworkResult` handles 500 error correctly.
*   **Technical Notes:**
    *   This is the *most important automated test* in the project.
*   **Dependencies:** Epic 2.

### Story 4.2: Domain Logic Verification
**As a** Developer, **I want** to ensure my pruning logic is correct, **So that** I don't delete the wrong files.
*   **Acceptance Criteria:**
    *   `features/seriesv2/domain/logic/RetentionPolicyTest.kt` created.
    *   Test Case: "Series fetched 8 days ago -> Purgable".
    *   Test Case: "Series fetched 8 days ago BUT is Favorite -> NOT Purgable".
*   **Technical Notes:**
    *   Pure JUnit 4 test. Fast execution.
*   **Dependencies:** Story 2.3.

### Story 4.3: The "Zero-Touch" Regression Check
**As a** QA, **I want** to verify the Legacy Live TV still works, **So that** we don't regress existing features.
*   **Acceptance Criteria:**
    *   Manual Test Plan created/executed:
    *   1. Launch App -> Open Live TV -> Play Channel. (Pass/Fail)
    *   2. Open Movies -> Play Movie. (Pass/Fail)
    *   3. Toggle `ENABLE_NEW_SERIES_ENGINE` OFF -> Open Series -> Old UI loads. (Pass/Fail)
*   **Dependencies:** Epic 3.

### Story 4.4: Performance Profiling
**As a** Developer, **I want** to verify memory usage, **So that** we don't crash low-end devices.
*   **Acceptance Criteria:**
    *   Run app on Profiler.
    *   Trigger Full Sync (10,000 episodes).
    *   Verify Heap Size < 256MB.
    *   Verify DB size < 500MB (using generated dummy data).
*   **Dependencies:** Epic 3.

---


---


---


---


---


---
