# Debrid Deep Research Report

Date: 2025-12-21

## Scope

- Debrid UI + data layer: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/**`
- Debrid playback integration: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/**`
- Debrid usage in VOD/Series detail flows
- Preferences + auth state: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/DebridPreferences.kt`

## Current Debrid Flow (Observed in code)

- Auth: Real-Debrid device code flow via `DebridAuthViewModel` + `DebridAccountRepository`.
- Storage: encrypted auth state via `DebridPreferences` (access token + refresh token).
- Catalog: TMDB-based rows from `AddonCatalogRepository` via `DebridViewModel`.
- Playback: magnet -> add torrent -> poll status -> select video file -> unrestrict link in `DebridPlaybackRepository`.
- Entry points: Debrid hub from Home, then Movie/Series detail -> resolve Debrid URL -> Player.

## Stremio Behavior (Publicly Known UX Pattern)

This section is based on commonly observed Stremio UX and open-source behavior patterns, not on live web scraping.

- Home catalogs: multiple rows (Trending, New, Providers, Genres) with fast horizontal browsing.
- Addon-first model: catalogs and streams come from addons; user picks sources and sees stream cards.
- Stream card metadata: quality/resolution, size, seeders, language, HDR flags, and cached status.
- Library: add to library, Continue Watching, calendar/next up for series.
- Background updates: content refresh while UI remains usable; subtle progress indicators.
- Search + discovery: fast, global search over addons with filters.
- Device login: QR/code flow with short instructions, minimal steps.

## Gap Analysis (Debrid vs Stremio)

### P0: Safety and correctness
- Token handling: now stored as full auth state; avoid leaking tokens in logs.
- Stream integrity: no fake fallback sources; empty on error.

### P1: UX and responsiveness
- No row-level error messages; a single failure can look like empty catalog.
- No retry affordance in Debrid error state (added in this pass).
- No cached or stale-while-revalidate rows to reduce cold load time.
- Stream cards lack detailed metadata (quality/size/seeders/cached).

### P2: Features and parity
- No Continue Watching or Library in Debrid hub (TODO in code).
- No Debrid-specific filtering (quality, size range, language, cached-only).
- No addon management UI (select/deselect catalogs or sources).
- No watchlist sync / trakt-style scrobbling.

## Recommended Plan (Safe, Modern, Professional)

### Phase 1 (Safe UX + Reliability)
- Add retry action and clear error messaging when catalog load fails.
- Avoid repeated reloads; guard duplicate loads and show cached rows if available.
- Add subtle loading indicator per row or top-level banner for background refresh.

### Phase 2 (Discoverability + Source Quality)
- Add stream metadata badges (resolution, size, language, HDR, cached state).
- Provide filters for streams: cached-only, size cap, language priority.
- Add addon selection UI (basic toggle list). Keep defaults enabled.

### Phase 3 (Library + Continuity)
- Integrate Continue Watching from watch history preferences.
- Add My Library (Room) for Debrid catalog items.
- Add Next Up for series, with episode resume.

## Implementation Notes (Do not break working IPTV/Debrid)

- Keep existing stream resolution pipeline untouched; add metadata and filtering on top.
- Any filtering should be opt-in and default to current behavior.
- Add row caching in memory only first; disk caching can come later.

## Next Actions (If approved)

1. Add row cache + background refresh banner for Debrid hub.
2. Add stream card metadata + cached-only toggle.
3. Add addon selection UI and store in DebridPreferences.

