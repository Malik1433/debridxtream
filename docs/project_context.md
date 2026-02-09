---
project_name: 'DebridXtreamIPTV'
user_name: 'Malik'
date: '2025-12-08'
sections_completed: ['technology_stack', 'critical_rules', 'testing_rules', 'workflow_rules']
status: 'complete'
rule_count: 15
optimized_for_llm: true
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

**Core Android:**
*   **Language:** Kotlin `1.9.25` (Target JVM 11)
*   **Min SDK:** 26 (Android 8.0)
*   **Target SDK:** 35 (Android 15)
*   **Platform:** Android TV / FireOS (No Google Play Services)

**Architecture Components:**
*   **DI:** Hilt `2.51.1`
*   **Data:** Room `2.6.1` + Paging `3.3.2`
*   **Async:** Coroutines `1.9.0` + WorkManager `2.9.1`

**UI Framework (Hybrid):**
*   **Legacy (XML):** ViewBinding + **Glide** `4.16.0`
*   **Modern (Compose):** Material3 + **Coil** `2.6.0`
*   **Rule:** Do not mix. If file is Fragment/XML, use Glide. If `@Composable`, use Coil.

**Networking:**
*   **Client:** Retrofit `2.9.0` + OkHttp 4.12.0
*   **Serialization:** Gson `2.10.1`

## Critical Implementation Rules

### 1. Architecture Isolation (The "Sidecar" Rule)
*   **Location:** All new V2 code MUST be in `features/seriesv2/`.
*   **Forbidden Imports:** V2 code cannot import `features/legacy/` packages.
*   **Naming:** All V2 core components MUST end with `V2` (e.g., `SeriesDetailFragmentV2`).

### 2. State Management (Specifics)
*   **UI State:** MUST use `StateFlow` (Hot Stream). `LiveData` is **BANNED**.
*   **One-Shot Events:** MUST use `Channel<UiEvent>.receiveAsFlow()` for Navigation/Toasts.
---
project_name: 'DebridXtreamIPTV'
user_name: 'Malik'
date: '2025-12-08'
sections_completed: []
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

**Core Android:**
*   **Language:** Kotlin `1.9.25` (Target JVM 11)
*   **Min SDK:** 26 (Android 8.0)
*   **Target SDK:** 35 (Android 15)
*   **Platform:** Android TV / FireOS (No Google Play Services)

**Architecture Components:**
*   **DI:** Hilt `2.51.1`
*   **Data:** Room `2.6.1` + Paging `3.3.2`
*   **Async:** Coroutines `1.9.0` + WorkManager `2.9.1`

**UI Framework (Hybrid):**
*   **Legacy (XML):** ViewBinding + **Glide** `4.16.0`
*   **Modern (Compose):** Material3 + **Coil** `2.6.0`
*   **Rule:** Do not mix. If file is Fragment/XML, use Glide. If `@Composable`, use Coil.

**Networking:**
*   **Client:** Retrofit `2.9.0` + OkHttp 4.12.0
*   **Serialization:** Gson `2.10.1`

## Critical Implementation Rules

### 1. Architecture Isolation (The "Sidecar" Rule)
*   **Location:** All new V2 code MUST be in `features/seriesv2/`.
*   **Forbidden Imports:** V2 code cannot import `features/legacy/` packages.
*   **Naming:** All V2 core components MUST end with `V2` (e.g., `SeriesDetailFragmentV2`).

### 2. State Management (Specifics)
*   **UI State:** MUST use `StateFlow` (Hot Stream). `LiveData` is **BANNED**.
*   **One-Shot Events:** MUST use `Channel<UiEvent>.receiveAsFlow()` for Navigation/Toasts.
*   **Concurrency:** Fragments must observe using `repeatOnLifecycle`.

### 3. Data & Networking
*   **Result Type:** Repositories MUST return `Flow<NetworkResult<T>>`.
*   **Offline-First:** Paging MUST use `RemoteMediator` (Network -> DB -> UI).
*   **Threading:** All Suspend functions must be Main-Safe (use `Dispatchers.IO`).

## Testing Rules (The "Rescue" Protocol)

### 1. Mandatory Coverage
*   **Domain Logic:** 100% Unit Test coverage for `RetentionPolicy` and Date logic.
*   **Repositories:** MUST have **Robolectric Integration Tests** to verify `Network -> Room -> Paging` data flow.
*   **ViewModels:** Unit Tests using `MainDispatcherRule` to verify StateFlow updates.
*   **UI:** Manual Regression only (No Espresso/UI Automator for MVP).

### 2. Testing Stack (Strict Legacy Compat)
*   **Runner:** JUnit 4 (Do NOT introduce JUnit 5 / Jupiter Engine).
*   **Mocking:** MockK `1.13.8`.
*   **Assertions:** Google Truth or standard Kotlin `assertEquals`.
*   **Async:** `kotlinx-coroutines-test` + Turbine.

## Development Workflow

### 1. Build & Verification (Strict)
*   **Mandatory Launch:** You MUST build and launch the app (`./gradlew installDebug`) after COMPLETING every story to ensure regression testing, even if the change was purely backend/utility.
*   **No Exceptions:** Never mark a story as "Done" without a successful specific build/launch.

### 2. Branching & Commits
*   **Branches:** `feat/series-v2-components`
*   **Commits:** Conventional Commits (e.g., `feat(seriesv2): add remote mediator`).
*   **Merging:** Squash and Merge.

### 2. The "Do Not Touch" List
*   **Legacy Code:** Do NOT refactor `XtreamRepository.kt` or `LiveTVFragment.kt` unless strictly necessary for V2 integration.
*   **Manifest:** Do NOT remove existing permissions or activities.

## Usage Guidelines

**For AI Agents:**
*   **Read First:** This file is your PRIMARY source of truth for architectural constraints.
*   **Strict Adherence:** Rules marked "BANNED" or "MUST" are non-negotiable.
*   **Conflict Resolution:** If this file conflicts with legacy code patterns, this file wins (for V2 code).

**For Humans:**
*   **Maintenance:** Update this file when library versions change in `build.gradle`.
*   **Evolution:** Add new rules here if AI agents repeatedly make the same mistake.
*   **Scope:** Keep this file LEAN. Do not add general Kotlin tutorials.

