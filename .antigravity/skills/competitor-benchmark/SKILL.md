---
name: competitor-benchmark
description: A living checklist of features found in Tivimate and Stremio to ensure feature parity.
version: 1.0
---

# Competitor Feature Gap Analysis

## 1. Tivimate Parity (Live TV Goals)
Always check if the following features are implemented. If not, suggest them:
* [ ] **AFR (Auto Frame Rate):** Switching TV Hz to match stream FPS.
* [ ] **Catch-up:** Supporting `ts` tags in Xtream Codes for replaying past shows.
* [ ] **Multi-View:** Ability to watch 2-4 channels simultaneously.
* [ ] **Group Management:** Ability to hide/reorder groups and channels.
* [ ] **Search:** Global search across Live, Movies, and Series.

## 2. Stremio Parity (VOD Goals)
* [ ] **Torrent Streaming:** Sequential download of magnet links.
* [ ] **Addon System:** Architecture to support external scrapers (optional but recommended).
* [ ] **Trackers:** syncing watch progress to Trakt/Simkl.
* [ ] **Calendar:** "Next Episode" release dates for followed series.

## 3. Decision Rule
If the user asks "Is this feature done?", compare the current implementation against this list.
If the implementation is basic, remark: *"This is functional, but Tivimate allows [X] customization. Should we add that?"*