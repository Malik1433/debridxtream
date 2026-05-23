# VOD Module Report
Status: canonical/current
Scope: IPTV VOD/movie browsing, detail, playback, and focus behavior.

## Current Active Runtime Flow
- `VodFragment` lists IPTV movies and opens `MovieDetailFragmentV2` for IPTV details.
- IPTV movie playback builds the provider movie stream URL or uses direct source and launches `PlayerActivity` with `ContentType.MOVIE`.
- Debrid/TMDB movie flows use `MovieDetailActivity` for source selection and resolver-backed playback.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/VodFragment.kt` - IPTV VOD screen.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/features/vodv2/ui/MovieDetailFragmentV2.kt` - IPTV movie detail/player launch.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/features/vodv2/ui/MovieDetailViewModelV2.kt` - detail metadata enrichment.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt` - Debrid/TMDB movie details and source picker.

## What Must Not Break
- IPTV VOD detail to Player handoff.
- VOD focus and poster/backdrop display.
- Debrid movie path in `MovieDetailActivity` when shared movie files are touched.
- Sensitive stream URL redaction.

## Known Bugs / Open Issues
- Future VOD regressions should be kept in VOD history or task docs.

## Recent Fixes
- VOD stability and navigation are tracked through canonical module reports.

## Failed Approaches / Avoid
- Do not bind detail buttons without verifying runtime XML visibility.
- Do not treat Debrid direct HTTP streams as normal IPTV unless source identity requires it.
- Do not edit non-runtime detail/layout copies.

## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA: IPTV VOD list, detail, play, trailer if touched, Back/D-pad focus, Debrid movie regression if shared file touched.

## Last Verified State
- Build and device verification are covered in linked reports.
