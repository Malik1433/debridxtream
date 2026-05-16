# IPTV Series RETEST Evidence (TASK 025-RETEST-2)

This document provides definitive evidence that the **IPTV Series section** (not Home, not VOD) has been correctly identified and tested.

## 1. Targeted Screen Identification
- **Fragment**: `com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment`
- **Unique Grid ID**: `com.debridxtream.tv:id/rv_series_grid`
- **Active Layout**: `fragment_series_vod.xml`
- **Evidence**: UI Dump `force_audit_v206_index5.xml` confirms the presence of `rv_series_grid` and `ll_series_sidebar_container`.

## 2. Retention of Correct Screen (Build 27 / v2.0.6)
To avoid any confusion, the testing was performed after exactly **4 Down presses** from the Search item in the Home Sidebar.

| Sidebar Item | Category | Target | Verified |
| :--- | :--- | :--- | :--- |
| Index 0 | Search | Search Screen | Yes |
| Index 1 | Home | Cinematic Home | Yes |
| Index 2 | Live TV | Live Grid | Yes |
| Index 3 | Movies | VOD Grid | Yes |
| **Index 4** | **Series** | **Series Grid** | **YES** |

## 3. Stabilization Fix Verification (IPTV Series Only)

### 3.1 Data Reliability: Relaxed Filter
- **Test**: Navigated to "RAMADAN CARTOON 2026" category in Series.
- **Old Behavior**: Would show "No series available" or 404 Error until a full sync finished.
- **New Behavior**: Data loads **instantly** from the local cache.
- **Proof**: Logs show `Fallback succeeded for category RAMADAN CARTOON 2026 (Relaxed Filter)`.

### 3.2 Error State Clearing
- **Test**: Switched categories rapidly.
- **Observation**: No stale error toasts or "Empty" overlays appeared during the transition. The `SeriesViewModel` correctly clears the error state on every new selection.

### 3.3 Visual Loading Alignment
- **Evidence**: `uiautomator` dump confirms `ll_loading_state` is present with `columnCount=4`.
- **Finding**: While alignment is better than early VOD, the Series module still uses a **160dp margin** which slightly overlaps the expanded sidebar. This is documented for the upcoming Modernization task (TASK 026).

## 4. Root Cause Confirmation

| Hypothesis | Observed Evidence | Confidence |
| :--- | :--- | :--- |
| **Strict Filter** | Confirmed. Logs showed filter mismatch for `category_ids` array. | **High** |
| **Persistent Error** | Confirmed. ViewModel was missing `error = null` on data arrival. | **High** |

---
**Auditor**: Antigravity (AI Coding Assistant)
**Date**: May 13, 2026
