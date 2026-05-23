# Search Module Report
Status: canonical/placeholder
Scope: global search, voice search handoff, and Debrid search entrypoints.

## Current Active Runtime Flow
- `MainActivity` handles TV search/voice keys and routes voice results to `SearchFragment`.
- `SearchFragment` handles app-wide IPTV search.
- Debrid-specific search uses `DebridSearchActivity` and opens Debrid movie/series detail screens.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchFragment.kt` - app search UI.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchViewModel.kt` - app search state.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/util/VoiceSearchManager.kt` - voice search state.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/search/DebridSearchActivity.kt` - Debrid search.

## What Must Not Break
- Voice search should route to Search without crashing Login/Home.
- Search result detail/player handoff must preserve source type.
- Debrid search results must open Debrid detail paths, not IPTV-only paths.

## Known Bugs / Open Issues
- No full current Search module report exists; `docs/DEEP_SEARCH_REPORT.md` is archived context.

## Recent Fixes
- No recent Search-specific fix documented in canonical reports.

## Failed Approaches / Avoid
- Do not scan all content synchronously on the UI thread.
- Do not mix Debrid result identity with IPTV stream identity.

## QA Checklist
- Text search from Home.
- Voice search key from MainActivity.
- IPTV movie/series/live result navigation.
- Debrid search result navigation if touched.

## Last Verified State
- Placeholder summary; verify during the next Search task.
