---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
inputDocuments: ['docs/bmm-index.md', 'docs/project-overview.md', 'docs/architecture.md', 'docs/project-scan-report.json']
workflowType: 'prd'
lastStep: 11
project_name: 'debxtrem'
user_name: 'Malik'
date: '2025-12-08T00:01:01Z'
---

# Product Requirements Document - debxtrem

**Author:** Malik
**Date:** 2025-12-08T00:01:01Z

## Executive Summary

DebridXtreamIPTV is a high-performance, feature-rich IPTV and VOD player designed specifically for Android TV. It empowers users to consume content from Xtream Codes providers with an interface that prioritizes **fluid navigation** and **performance reliability**. By addressing the common performance bottlenecks of large playlist management on **restricted TV hardware**, it delivers a seamless viewing experience.

### What Makes This Special

The product differentiates itself through a **multi-level caching strategy** (Memory → Room DB → HTTP Cache → Network) that ensures **high responsiveness and network resilience**. Unlike generic players, DebridXtreamIPTV employs a **robust local-first architecture** and a modern hybrid UI (Jetpack Compose + ViewBinding), ensuring **smooth interactions** even on low-spec devices.

## Project Classification

**Technical Type:** mobile_app
**Domain:** general (Media & Streaming)
**Complexity:** medium

The application is built on the Android ecosystem using Kotlin and Hilt. While the domain does not enforce strict regulatory compliance, the project involves significant technical complexity around local data persistence, background synchronization (WorkManager), and advanced media playback (Media3).

## Success Criteria

### User Success
*   **0ms Navigation Latency:** Transitions to Series/Movie Detail screens happen instantly (< 100ms) using optimistic UI / skeleton loaders.
*   **Cold Start < 500ms:** App launches directly to interaction-ready Home screen from local DB.
*   **Progressive Loading:** Users can browse/watch Live TV while VOD/Series syncs in the background. No blocking "Updating..." spinners on subsequent launches.

### Business Success
*   **"Premium" Perception:** Users cite "speed" as the primary reason for switching to DebXtream from other players.
*   **Retention:** Zero churn due to "loading fatigue" or "stuck" episodes.

### Technical Success (The "TiviMate Standard")
*   **Database-First Architecture:** The UI *always* reads from Room DB. Network updates DB in background.
*   **Navigate-Then-Fetch:** Navigation logic decoupled from network calls.
*   **Progressive Sync:** Sync categories first, then content.
*   **Non-Blocking Parsing:** 100% of JSON parsing happens on `Dispatchers.IO`.

### Measurable Outcomes
*   **Detail Interaction:** Click -> Skeleton UI visible in < 50ms.
*   **Episode List Population:** < 1s (Network) or < 100ms (Cache).
*   **Crash Rate:** 0% OOM related to large playlist syncing.

## Product Scope

### MVP (Launch/Fix)
*   **Refactor Navigation:** Implement "Navigate-Then-Fetch" for Series/Movies.
*   **Sync Logic:** Implement background worker for progressive syncing.
*   **UI Optimization:** Add Shimmer/Skeleton layouts for key screens.

### Growth Features
*   *Global Search across caching layers.*
*   *Predictive pre-fetching of next episodes.*

### Vision
### User Journeys

#### Journey 1: Ali (The Fluid Browse)
Ali settles onto his couch after work, wanting to check the news and sports. He opens DebXtream.
*   **The Launch:** The Home Screen is there instantly (<500ms). He navigates to "Live TV" -> "Sports".
*   **The Experience:** He starts scrolling through the channel list. The text appears instantly. The channel logos load lazily as they enter the viewport. He scrolls back up, and the logos are now instant (cached).
*   **The Differentiator:** He clicks "Sky Sports". The player opens **instantly** (0ms navigation). He sees a black screen for <1s while buffering, then video.
*   **Outcome:** The interface feels "buttery smooth" and never blocks his interactions.

#### Journey 2: Sarah (The Instant Series)
Sarah wants to continue watching "The Office". She navigates to Series -> Favorites.
*   **The Interaction:** She clicks the poster for "The Office".
*   **Optimistic UI:** The screen transitions **immediately** to the Detail View. She sees the large backdrop (passed from previous screen) and a "Skeleton" (shimmering gray bars) where the episode list will be.
*   **The Race:** The app fetches the episode list in the background.
    *   *Scenario A (Success):* Episodes pop in within 0.8s.
    *   *Scenario B (Slow Network):* After 3s, a small "Taking longer than usual..." toast appears, but the UI remains responsive.
*   **The Second Visit:** Comparisons later, she opens "The Office" again. The episode list loads in 0ms from the local database.

### Requirements Summary & Safety Protocol

*   **UX Pattern:** Optimistic UI with Skeleton loaders and Timeout feedback.
*   **Performance:** "Navigate-Then-Fetch" architecture is mandatory.
*   **🛡️ IRONCLAD SAFETY REQUIREMENT (Zero Regression):**
    *   **Feature Flag:** `ENABLE_NEW_SERIES_ENGINE = false` by default.
    *   **Parallel Implementation:** Create `XtreamSeriesRepository` (New) and `SeriesDetailFragmentV2` (New).
    *   **Zero-Touch:** Do **NOT** modify the original `XtreamRepository.kt` or `XtreamApiService` logic used by LiveUI/Movies.
    *   **Fallback:** The app must support generating the UI purely from the Legacy path if the flag is off.

## Innovation & Novel Patterns

### Detected Innovation Areas
*   **Database-First Sync Engine:** Re-architecting the IPTV client from a typical "Stream Browser" to a "Synced Content Library".
*   **Offline-Ready Search:** Enabling global search across EPG and VOD without network calls by indexing data locally.

### Market Context & Competitive Landscape
*   **The Disruption:** DebXtream mimics premium OTT apps (Netflix) rather than utility players.
*   **The Moat:** Enabling "Offline Browsing" creates a sticky user experience that generic "Live Load" players literally cannot replicate without a rewrite.

### Validation Approach
*   **The "Airport Mode" Test:** Full browsing capability with zero internet.
*   **The "Kill-Switch" Test:** Sync process must be resilient to app termination (Atomic transactions).

### Risk Mitigation
*   **Data Staleness:** Background "Silent Sync" worker.
### Risk Mitigation
*   **Data Staleness:** Background "Silent Sync" worker.
*   **Auth Failure:** Intelligent handling of token expiry during background syncs (Notification to user).

## Mobile App (Android TV) Specific Requirements

### Platform Requirements
*   **Form Factor:** Strictly **Android TV (Leanback)**.
*   **Input Method:** D-Pad Navigation with **High-Contrast Focus States** (Custom Drawables) for all interactive elements.
*   **Hardware Targets:** Validation required on: High-End (Nvidia Shield), Mid-Range (FireStick 4K), and Low-End (Generic Android 9 Boxes).

### System & Permissions
*   **Dependency Constraint:** **Absolutely NO dependency on GMS (Google Mobile Services).**
    *   *No Firebase.*
    *   *No Play Services Location/Ads.*
*   **Storage:** `READ/WRITE_EXTERNAL_STORAGE` for local caching.
*   **Safety:** **Self-Hosted Update Logic:** JSON endpoint check -> In-App Dialog -> Permission Request -> Install.

### Technical Limitations
*   **Memory Management:** App must assume aggressive OS killing of background processes (common on FireSticks). "Silent Sync" must be capable of resuming from partial states.

## Project Scoping & Phased Development

### MVP Strategy & Philosophy
*   **MVP Approach:** **"The Rescue Patch" (Parallel V2).** Focused 100% on fixing the "Loading Speed" and "Blocking UI" issues for Series/Movies.
*   **Resource Requirements:** 1 Senior Dev (Arch/Impl), 1 QA (Regression Testing).

### MVP Feature Set (Phase 1: Performance)
*   **Core Systems:** `XtreamSeriesRepository` (Parallel), Database-First Worker, Feature Flag Infrastructure.
*   **User Value:** Instant Series Details, Zero-Wait Listings, Offline-Ready Series Metadata.
*   **Excluded:** Live TV Refactoring (Too risky for Phase 1), Global Search (Push to Phase 2).

### Post-MVP (Phase 2: Consolidation)
*   **Goal:** Retire Legacy Code.
*   **Action:** Port "Live TV" and "Movies" to the new Database-First Architecture.
*   **Decommission:** Delete `XtreamRepository.kt` (Legacy) and remove the Feature Flag.

### Risk Mitigation Strategy
*   **Technical Debt (Drift):** STRICT rule: No new features added to Legacy V1 code during this phase.
*   **Testing Burden:** Automated Unit Tests required for V2 logic to reduce manual regression overhead.
### Risk Mitigation Strategy
*   **Technical Debt (Drift):** STRICT rule: No new features added to Legacy V1 code during this phase.
*   **Testing Burden:** Automated Unit Tests required for V2 logic to reduce manual regression overhead.
*   **Market Risk:** If V2 fails in the wild, the "Kill Switch" (Feature Flag) is our safety net.

## Functional Requirements (MVP Phase 1)

### 1. Content Synchronization
*   **FR-SYNC-01:** System synchronizes Series metadata to Room DB in background.
*   **FR-SYNC-02:** System performs "Silent Syncs" without blocking UI.
*   **FR-SYNC-03:** System ensures Atomic Integrity (No partial episodes inserted).
*   **FR-SYNC-04:** System implements **Exponential Backoff** for failed sync retries (don't hammer the API).

### 2. Series Interaction
*   **FR-UI-01:** Navigate to Detail screen with **0ms latency**.
*   **FR-UI-02:** Display "Skeleton" placeholders during fetch.
*   **FR-UI-03:** Offline browsing of text metadata.
*   **FR-UI-04:** Non-blocking "Slow Connection" Toast notification (>3s).
*   **FR-UI-05:** UI displays **Local Placeholders** for images when offline/uncached (Don't show empty gaps).
*   **FR-UI-06:** UI displays "Retry" button if sync fails completely (Manual Trigger).

### 3. System & Safety
*   **FR-SYS-01:** `ENABLE_NEW_SERIES_ENGINE` Flag controls routing.
*   **FR-SYS-02:** Route TRUE -> V2 Fragment.
*   **FR-SYS-03:** Route FALSE -> V1 Legacy Fragment.
### 3. System & Safety
*   **FR-SYS-01:** `ENABLE_NEW_SERIES_ENGINE` Flag controls routing.
*   **FR-SYS-02:** Route TRUE -> V2 Fragment.
*   **FR-SYS-03:** Route FALSE -> V1 Legacy Fragment.
*   **FR-SYS-04:** **Debug Panel** allows toggling the Feature Flag at runtime (Admin/Dev usage only).

## Non-Functional Requirements (Performance & Reliability)

### Performance
*   **NFR-PERF-01:** Series Detail Screen renders text in **< 100ms**.
*   **NFR-PERF-02:** Cold Start to Home < 2 seconds.
*   **NFR-PERF-03 (Storage Cap):** Total Local Cache (DB + Images) must not exceed **500MB** (LRU Eviction required).

### Reliability (Stability)
*   **NFR-REL-01 (Memory Cap):** App Heap Memory usage must stay below **256MB** during Sync to prevent OOM on low-end devices.
*   **NFR-REL-02 (Network Resilience):** Sync worker must retry individual failed chunks (Packet Loss) without failing the entire job.
*   **NFR-REL-03 (Offline):** Full navigation of cached data without network.

### Usability
*   **NFR-USE-01:** High-Contrast Focus States (White Border/Zoom) on all elements.
*   **NFR-USE-02:** Operations > 2s show feedback.








