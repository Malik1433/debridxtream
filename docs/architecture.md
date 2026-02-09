# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
*   **The Rescue:** The core FR is `FR-UI-01` (0ms Latency). This dictates a **Reactive Data Layer** (Room + Flow) that pushes data to the UI, rather than the UI pulling from Network.
*   **The Switch:** `FR-SYS-01` mandates a **Runtime toggle**. This requires a clear "Router" component in the navigation graph.

**Non-Functional Requirements:**
*   **Memory Cap (256MB):** Architecture must support **Pagination** (Paging 3 library) from DB to UI. We cannot load `List<Episode>` completely.
*   **Storage Cap (500MB):** We need an **Aggressive pruning strategy** in the new Repository (e.g., delete data for series not accessed in 7 days).

### Technical Constraints & Dependencies
*   **Shared Kernel:** We must reuse `XtreamApiService.kt` (Retrofit) but build a new `XtreamSeriesRepository.kt`.
*   **Database Isolation:** New V2 tables (if needed) or strict schema additions that don't break V1 queries.

### Cross-Cutting Concerns Identified
*   **Feature Flagging:** Need a centralized `FeatureFlagManager` singleton.
### Cross-Cutting Concerns Identified
*   **Feature Flagging:** Need a centralized `FeatureFlagManager` singleton.
*   **Error Handling:** V2 needs a unified "Offline/Error" state mechanism (Sealed Classes) that is distinct from the Legacy "Toast" error handling.

## Starter Template Evaluation

### Primary Technology Domain
**Android / Kotlin / Jetpack / Room**

### Starter Options Considered
*   **Full Library Stack (Sandwich + Paging):** Rejected due to unnecessary dependency bloat.
*   **Native Implementation (Custom Result + Paging):** Selected for control and simplicity.

### Selected Pattern: "Native Rescue" Stack

**Rationale:**
1.  **Paging 3 (Mandatory):** Necessary complexity for Memory safety (NFR-REL-01).
2.  **Custom `NetworkResult` (No Sandwich):** Lightweight `Sealed Class` significantly reduces dependency graph complexity vs external libraries.
3.  **Feature Flag Manager:** Simple Kotlin Object/Singleton fits the "Ironclad Safety" requirement better than complex 3rd party SDKs.

**Architectural Decisions Provided:**

**Language & Runtime:**
*   Kotlin 1.9+
*   Coroutines & Flow (Standard)

**Network Layer:**
*   **Retrofit** (Existing)
*   **Custom `safeApiCall` Wrapper** (New) -> Returns `Flow<NetworkResult<T>>`

**Data Layer:**
*   **Room Database** (Existing)
**Data Layer:**
*   **Room Database** (Existing)
*   **Paging 3 `RemoteMediator`** (New) -> Handles Sync logic & Memory/Storage Caps.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
*   **Data Architecture:** Paging 3 with Remote Mediator (Native Rescue Stack).
*   **Infrastructure:** Feature Flag Manager for Safe Rollout.

**Important Decisions (Shape Architecture):**
*   **Frontend State:** StateFlow adoption for Compose readiness.
*   **Error Handling:** Custom `NetworkResult` implementation.

### Data Architecture

*   **Pattern:** Repository Pattern with **Offline-First** (Room SSOT).
*   **Paging:** `Paging 3` with `RemoteMediator` (Network + DB).
*   **Caching Strategy:** **Time-To-Live (TTL)** 7 Days.
*   **Pruning:** Dedicated `CachePruningWorker` (Nightly) to enforce NFR-PERF-03 (Storage Cap).
*   **Rationale:** Chosen to strictly adhere to the 256MB Heap Limit and 500MB Storage Cap on Android TV devices.

### API & Error Handling

*   **Pattern:** Custom `NetworkResult` Sealed Class.
*   **Granularity:** Must distinguish `HttpError(code)` (for 401 Auth handling) vs `NetworkError` (IO).
*   **Wrapper:** `safeApiCall` extension function.
*   **Rationale:** To avoid "try-catch hell" and reduce dependency bloat (vs Sandwich).

### Frontend Architecture

*   **State Management:** **StateFlow (Hot Stream)**.
*   **Lifecycle Safety:** Must use `repeatOnLifecycle` or `flowWithLifecycle` in Fragments.
*   **Rationale:** Mandatory for future Jetpack Compose interoperability and robust lifecycle handling.

### Infrastructure

*   **Feature Flag:** `FeatureFlagManager` Singleton (Memory Cache + SharedPreferences fallback).
*   **Rationale:** Zero-latency checks at the `MainActivity` router level to prevent regressions in Live TV.

### Decision Impact Analysis

**Implementation Sequence:**
1.  Implement `NetworkResult` and `safeApiCall`.
2.  Implement `FeatureFlagManager` and Routing Logic.
3.  Implement `XtreamSeriesRepository` with Paging 3 `RemoteMediator`.
4.  Implement `CachePruningWorker`.
5.  Implement UI with `StateFlow`.

**Cross-Component Dependencies:**
*   `RemoteMediator` depends on `NetworkResult` for error signaling.
*   Fragments depend on `FeatureFlagManager` for navigation routing.

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

**Critical Conflict Points Identified:**
*   Project Structure (Feature vs Layer)
*   State Management (LiveData vs StateFlow)
*   Naming & Traceability (V1 vs V2)

### Code Structure Patterns

**Pattern:** **Package by Feature (Isolationist)**
*   **Rule:** New V2 code must live in dedicated feature packages, separate from Legacy V1 code.
*   **Examples:**
    *   `com.ram.ramiptv.features.seriesv2.ui` (Fragments, ViewModels)
    *   `com.ram.ramiptv.features.seriesv2.data` (Repository Impl, PagingSource)
    *   `com.ram.ramiptv.core.network` (Shared Retrofit - Existing)
    *   `com.ram.ramiptv.core.database` (Shared Room Entities - Existing)
*   **Rationale:** Facilitates the "Sunset Clause". When V1 is retired, we delete the V1 folders. V2 is cleanly separated.

### State Management Patterns

**Pattern:** **Strict MVI-Lite (Unidirectional Fallback)**
*   **Rule:** UI **NEVER** calls Repository directly.
    *   1. UI sends `Event` -> ViewModel (e.g., `onSeriesClicked(id)`)
    *   2. ViewModel functionality executes -> updates private `MutableStateFlow`
    *   3. ViewModel exposes immutable `StateFlow<UiState>`
    *   4. UI observes `StateFlow`.
*   **Mandatory:** Use `StateFlow` (Hot Stream) backed by `repeatOnLifecycle` in Fragments. **NO LiveData** for new V2 code.

### Naming & Traceability Patterns

**Pattern:** **The "V2" Suffix Rule**
*   **Rule:** All core V2 components MUST have "V2" in their class name.
*   **Examples:**
    *   `SeriesDetailFragmentV2`
    *   `XtreamSeriesRepositoryV2`
    *   `SeriesRemoteMediatorV2`
*   **Rationale:** Instant identification in Crashlytics and Logcat. "If it crashes and says V2, it's the new engine."

### Enforcement Guidelines

**All AI Agents MUST:**
1.  **Check Context:** Before modifying `XtreamRepository.kt` (Legacy), confirm if the change belongs in `XtreamRepositoryV2.kt` (New).
2.  **Respect Boundaries:** Do NOT import `com.ram.ramiptv.ui.subtitles` (Legacy) into V2 features unless moved to `core`.
3.  **Traceability:** Always verify `Log.d("SeriesDebugV2", ...)` tags are used, distinct from "SeriesDebug" (Legacy).

## Project Structure & Boundaries

### Complete Project Directory Structure

```text
com.tvonnet.debridxtreamiptv
├── core
│   ├── network          <-- Shared Retrofit (Existing)
│   ├── database         <-- Shared Room DB (Existing)
│   └── featureflag      <-- [NEW] FeatureFlagManager.kt
├── features
│   ├── seriesv2         <-- [NEW] The Rescue Patch
│   │   ├── data
│   │   │   ├── repository
│   │   │   │   ├── XtreamSeriesRepositoryV2.kt (Impl)
│   │   │   │   └── SeriesRemoteMediatorV2.kt
│   │   │   └── worker
│   │   │       └── CachePruningWorker.kt (Android Entry Point)
│   │   ├── domain       <-- [REFINED]
│   │   │   ├── logic
│   │   │   │   └── RetentionPolicy.kt (Pure Kotlin - Testable)
│   │   │   └── model
│   │   │       └── SeriesV2.kt
│   │   └── ui
│   │       ├── SeriesDetailFragmentV2.kt
│   │       ├── SeriesDetailViewModelV2.kt
│   │       └── adapter
│   └── (legacy folders ignored)
└── ui
    └── main             <-- Updates to MainActivity.kt (Router)
```

### Architectural Boundaries

**Service Boundaries:**
*   **Pruning Logic:** `RetentionPolicy` calculates *what* to delete. `CachePruningWorker` executes the DB delete.
*   **ViewModel:** Talks to `Repository` directly (No UseCase middleware).

**Component/Feature Boundaries:**
*   **Strict Isolation:** V2 features do **not** access V1 Fragments.
*   **Common Ground:** V2 and V1 both access `core.database`.

**Data Boundaries:**
*   **Database:** Shared Schema. V2 uses specific new Tables (or strictly additive columns) to ensure V1 compatibility.
*   **State:** V2 State is strictly local to `SeriesDetailViewModelV2`. No shared `GlobalState` objects.

### Requirements to Structure Mapping

**Background Sync (FR-SYNC-01):**
*   Impl: `features/seriesv2/data/worker/CachePruningWorker.kt` + `SeriesRemoteMediatorV2.kt`

**0ms Latency UI (FR-UI-01):**
*   Impl: `features/seriesv2/ui/SeriesDetailFragmentV2.kt` (observes `StateFlow`)

**Safety Switch (FR-SYS-01):**
*   Impl: `core/featureflag/FeatureFlagManager.kt`

**Storage Cap (NFR-PERF-03):**
*   Impl: `features/seriesv2/domain/logic/RetentionPolicy.kt`

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
*   The combination of **Paging 3** (Data) and **StateFlow** (UI) provides a coherent Reactive Data Stream that solves the "Memory Cap" and "0ms Latency" requirements simultaneously.
*   **Structure Alignment:** The `features/seriesv2` directory strictly isolates the "Rescue Patch" code, enabling the "Sidecar" architecture and satisfying the "Sunset Clause" strategy.

### Requirements Coverage Validation ✅

**Functional Requirements:**
*   **FR-SYNC-01 (Background Sync):** Covered by `CachePruningWorker` and `RemoteMediator`.
*   **FR-UI-01 (0ms Latency):** Architecturally guaranteed by the "Offline-First" approach (Room as SSOT).
*   **FR-SYS-01 (Safety Switch):** Covered by the explicit `core/featureflag` module and Router logic.

**Non-Functional Requirements:**
*   **NFR-REL-01 (Memory < 256MB):** Addressed by Paging 3 (Streaming data vs loading all).
*   **NFR-PERF-03 (Storage < 500MB):** Addressed by the Domain Logic `RetentionPolicy`.

### Testing Strategy (The "Rescue" Protocol)

1.  **Unit Tests (Required):**
    *   `RetentionPolicyTest`: Verify date math for 7-day pruning.
    *   `SeriesDetailViewModelV2Test`: Verify StateFlow updates on Events.
    *   **Coverage Target:** 100% of Domain Logic.

2.  **Integration Tests (Critical):**
    *   `XtreamSeriesRepositoryV2Test`: Use `MockWebServer`. Verify Retrofit 401s map to `NetworkResult.Error`.
    *   **Mediator Verify:** Confirm `load()` inserts data into Room.
    *   **Rationale:** Complex state logic in `RemoteMediator` poses the highest risk of "Infinite Loading" bugs.

3.  **Manual Regression (The "Zero-Touch" Check):**
    *   QA manually verifies Live TV (V1) still plays. (Accepted manual cost to save Dev time on Legacy code).

### Architecture Completeness Checklist

**✅ Requirements Analysis**
*   [x] Project context thoroughly analyzed
*   [x] Scale and complexity assessed
*   [x] Technical constraints identified
*   [x] Cross-cutting concerns mapped

**✅ Architectural Decisions**
*   [x] Critical decisions documented with versions
*   [x] Technology stack fully specified
*   [x] Integration patterns defined
*   [x] Performance considerations addressed

**✅ Implementation Patterns**
*   [x] Naming conventions established
*   [x] Structure patterns defined
*   [x] Communication patterns specified
*   [x] Process patterns documented

**✅ Project Structure**
*   [x] Complete directory structure defined
*   [x] Component boundaries established
*   [x] Integration points mapped
*   [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status:** READY FOR IMPLEMENTATION

**Confidence Level:** HIGH - The "Sidecar" approach minimizes risk to the existing legacy codebase while enabling modern performance for the new feature.

## Architecture Completion Summary

### Workflow Completion

**Architecture Decision Workflow:** COMPLETED ✅
**Total Steps Completed:** 8
**Document Location:** docs/architecture.md

### Final Architecture Deliverables

**📋 Complete Architecture Document**
*   **Decisions:** 12 Key Decisions documented (including Paging 3, StateFlow, Feature Isolation).
*   **Patterns:** 4 Core Implementation Patterns (Isolationist Package Structure, MVI-Lite, V2 Naming, NetworkResult).
*   **Structure:** Complete "Sidecar" Directory Tree defined.
*   **Safety:** "Zero-Touch" Regression Strategy for Legacy Code.

**🏗️ Implementation Ready Foundation**
*   **Technology Stack:** Kotlin, Room, Retrofit, Paging 3, WorkManager, Coroutines/Flow.
*   **Testing Strategy:** Unit (Logic) + Integration (Repo) + Manual (UI).
*   **Performance:** Strict adherence to 256MB Heap / 500MB Storage caps.

### Implementation Handoff

**First Implementation Priority:**
Initialize the **Feature Flag Infrastructure** (`core/featureflag`) and the **V2 Directory Structure** (`features/seriesv2`).

**Quality Assurance Checklist**
*   [x] **Coherence:** "Offline-First" Paging works with "Reactive" UI.
*   [x] **Coverage:** All 10 FRs/NFRs are architecturally supported.
*   [x] **Readiness:** "V2" Naming Rule ensures clear boundaries.

---

**Architecture Status:** READY FOR IMPLEMENTATION ✅

**Next Phase:** Begin implementation by creating the Project Structure and Feature Flag mechanism.







