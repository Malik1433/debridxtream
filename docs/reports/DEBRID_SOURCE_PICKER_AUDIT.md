# Debrid Source Picker Reliability Audit

Date: 2026-05-29
Mode: Diagnose Only
Status: audit plus follow-up P0 fixes / direct proxy readiness centralized and cached-filter direct false positives addressed

## Follow-Up: Direct Proxy Readiness Centralization
- Date: 2026-05-29
- Runtime change: `DebridPlaybackRepository.requiresDirectProxyReadinessCheck(url, provider, sourceName, sourceType)` is now the shared movie/series policy for deciding whether a direct addon/proxy source must be preflighted.
- Movie path: `MovieDetailActivity` uses the shared policy before direct Player launch and during source-list readiness refresh.
- Series path: `SeriesDetailActivity` uses the same shared policy before direct episode Player launch and during episode source-list readiness refresh.
- Preserved behavior: MediaFusion/AIO proxy URLs and existing MediaFusion/AIO source metadata get the shared HEAD readiness check with playback headers; non-classified direct HTTP sources still launch directly; resolver-backed hash/magnet Real-Debrid playback is unchanged.
- Verification: `clean :app:compileDebugKotlin` passed twice after the targeted fix, `:app:assembleDebug` passed twice, APK installed on `192.168.0.84:5555`, and device QA confirmed Debrid -> detail -> source picker -> direct Player launch/back recovery.
- Targeted provider QA: movie AIOStreams ready source launched `PlayerActivity` and stayed active past 12s; series episode AIOStreams not-ready source stayed/recovered in `SeriesDetailActivity` source picker without launching `PlayerActivity`; non-AIO control source still launched `PlayerActivity`; app-process log scan found no raw URL, magnet, token-param, or long-hash patterns.

## Executive Summary
The active Debrid source picker flow is centralized enough to fix safely, but the reliability boundary crosses Debrid rows, VOD detail, Series detail, the shared source bottom sheet, `UnifiedSourceProvider`, `PlaybackResolver`, and `PlayerActivity`.

Current strengths:
- Movie, series, Search, and Discover all route Debrid content into existing detail screens instead of duplicate playback surfaces.
- Source picker uses one shared `SourceSelectionBottomSheet` and one `MovieSourceAdapter`.
- Player launch preserves `PlaybackSource.DEBRID`, direct-play flag, headers, provider/source profile, language, quality, stream id, binge group, and file index.
- Resolver-backed hash/magnet playback and direct addon/proxy playback are split, which avoids sending fresh direct HTTP addon URLs through app-side Real-Debrid auth.
- Movie and series both now preflight MediaFusion/AIO proxy URLs or MediaFusion/AIO-labelled direct sources before launching Player.

Main reliability risks:
- Source identity is still mixed: real infoHash, direct addon URL hash, and title hash can all become `stream_id`.
- Cached-only UI is stricter than direct-source readiness; it only keeps `VERIFIED_CACHED`, not `DIRECT_STREAM`.
- Provider badges and labels can be misleading because adapter badge detection still relies partly on label/provider text.
- Direct addon/proxy readiness is centralized for hardcoded MediaFusion/AIO host strings and existing MediaFusion/AIO provider/source metadata.
- Some source/error logs still include raw release titles and exception messages; keep future debugging redacted.

## Source Picker Entry-Point Map
| Entry point | Current route | Source picker owner | Notes |
|---|---|---|---|
| Debrid top rows / Discover top content | `DebridFragment.onContentItemClick()` starts `MovieDetailActivity` for movies or `SeriesDetailActivity` for series. | Detail screen opens picker. | Movies pass `EXTRA_MOVIE_CATEGORY_ID = "debrid"`; series pass `EXTRA_IS_DEBRID = true`. |
| Debrid Search results | `DebridSearchActivity.onItemClick()` starts the same movie/series detail activities. | Detail screen opens picker. | Search itself is catalog-only; no direct source fetching until detail. |
| Debrid Discover grid | `DebridDiscoverActivity.onItemClick()` starts the same movie/series detail activities. | Detail screen opens picker. | Discover action from rail currently returns/focuses Debrid landing/top content, not a separate exported route. |
| Debrid movie detail | `MovieDetailActivity.showDebridSourcePicker()` opens `SourceSelectionBottomSheet`. | `MovieDetailActivity` + shared bottom sheet. | Cached `debridSources` are reused and then refreshed for proxy readiness. |
| Debrid series episode detail | `SeriesDetailActivity.fetchAndShowDebridSources(episode)` opens `SourceSelectionBottomSheet`. | `SeriesDetailActivity` + shared bottom sheet. | Sources are cached by episode id. |
| Continue Watching movie/series | `DebridFragment.tryResumeDebrid()` first calls `PlaybackResolver`; `RefreshRequired` falls back to detail. | Resolver first, detail picker on refresh/fallback. | Direct same-click resume can bypass picker when durable hash/magnet resolves. |
| Direct addon/proxy path | Detail screen selects a `MovieSource` whose `stream.direct_source` is HTTP. | Detail screen preflight then Player. | MediaFusion/AIO proxy URLs are HEAD checked before Player launch. |

## Source Type Map
| Type | How it is represented now | Playback path | Reliability status |
|---|---|---|---|
| Real-Debrid cached torrent | `MovieSource.cacheStatus = VERIFIED_CACHED`, `stream_id` usually infoHash, `direct_source` magnet or torrent-derived magnet. | `PlaybackResolver.resolve()` -> `DebridPlaybackRepository.resolveDebridUrl()` -> Player. | Strongest path. Cache verification is capped to first 10 unique hashes. |
| Magnet/hash resolver source | `stream_id` infoHash if available; `direct_source` magnet or generated magnet. | Resolver-backed Debrid launch with `debridInfoHash` and `debridMagnet`. | Good, but title-hash fallback can weaken continuity if no infoHash/magnet exists. |
| Direct HTTP addon/proxy source | `cacheStatus = DIRECT_STREAM`; `direct_source` is HTTP; Player gets `directDebridPlayback = true` and headers/source profile. | Direct Player launch after optional proxy readiness check. | Good for fresh sources. Not durable for long-term resume unless metadata/profile can refresh. |
| Non-cached source | `cacheStatus = NOT_CACHED`. | Resolver may fail terminally; auto-next can advance on terminal failure classes. | UI marks UNCACHED but source can still be selectable unless filtered. |
| Unknown source | `cacheStatus = UNKNOWN`. | Selectable unless filter hides it. | Useful fallback, but labels can imply readiness when cache state is actually unknown. |
| Failed/short source | Player can return `EXTRA_RETURN_TO_SOURCES`, `EXTRA_FAILED_STREAM_ID`, `EXTRA_FAIL_REASON`, `EXTRA_AUTO_PLAY_NEXT`. | Detail screen tries next source or reopens picker. | Good recovery path; needs QA across terminal vs transient failures. |
| Addon error pseudo-stream | Filtered in `UnifiedSourceProvider.convertAddonStreamsToMovieSources()` using text checks for API exception/rate limit/invalid token/error text. | Skipped before picker. | Helpful, but text matching can miss new provider error labels. |
| Unknown/legacy source type | `AddonSourceType.UNKNOWN` becomes provider label `PureFire`; dynamic/registry entries can also feed this path. | Same `MovieSource` contract. | Needs provider provenance QA because support claims can look broader than implemented auth/resolver parity. |

## Movie Vs Series Behavior Comparison
| Area | Movie detail | Series detail | Gap |
|---|---|---|---|
| Source fetch | `UnifiedSourceProvider.getMovieSources(streamId, title, category, year, imdbId)`. | `UnifiedSourceProvider.getSeriesEpisodeSources(seriesId, season, episode, title, yearHint)`. | Series requires resolving numeric TMDB id to IMDb id before source fetch; missing/failed external id returns empty. |
| Picker cache | `debridSources` list on activity. | `cachedDebridSourcesByEpisode[episode.id]`. | Series is better scoped by episode; movie is one global detail list. |
| Selected source memory | `selectedDebridStreamId`. | `selectedDebridStreamIdByEpisode[episode.id]`. | Good parity. |
| Filter memory | `debridFilterState`. | `debridFilterStateByEpisode[episode.id]`. | Good parity. |
| Direct/proxy readiness | Shared `DebridPlaybackRepository` policy checks MediaFusion/AIO proxy URLs and source metadata before launch. | Same shared policy for episode sources and readiness refresh. | Broader Stremio/direct proxy capability metadata is still future work. |
| Resolver launch | `playDebridMovie()` uses `PlaybackResolver`; `returnToSources` only when launched from picker. | `playDebridEpisode()` uses `PlaybackResolver`; always puts `EXTRA_RETURN_TO_SOURCES` for picker launches. | Movie and series both preserve source profile into Player. |
| Back behavior | Activity result from Player can reopen sources or auto-next. Detail Back uses default behavior. | Activity result from Player can reopen sources or auto-next. Debrid series Back is overridden to `finish()`. | Prior Debrid CW series Back bug is addressed by `finish()` override. |

## Current Launch-To-Player Flow
1. Catalog card click starts Movie or Series detail with Debrid markers.
2. Detail Play/episode click opens `SourceSelectionBottomSheet`.
3. Bottom sheet calls back with a `MovieSource`, then dismisses.
4. Movie path:
   - Direct HTTP source goes through `playMovie(sourceOverride)`.
   - MediaFusion/AIO proxy URL or source metadata gets `isAddonProxyPlaybackReady()` preflight.
   - Resolver-backed source goes through `playDebridMovie()`.
5. Series path:
   - Direct HTTP source goes through `launchDirectEpisode`.
   - MediaFusion/AIO proxy URL or source metadata gets `isAddonProxyPlaybackReady()` preflight.
   - Resolver-backed source goes through `PlaybackResolver`.
6. Player is launched through `player/stabilized/PlayerActivity.createIntent()` with Debrid extras:
   - `playbackSource = DEBRID`
   - `directDebridPlayback`
   - `debridInfoHash`, `debridMagnet`, provider, source type/name, languages, quality, stream id, binge group, file index
   - headers for direct addon playback
7. Player resolves expired/missing Debrid URLs before playback when durable metadata exists, or blocks stale direct playback when it cannot refresh safely.

## Back / Focus Restore Flow
- Player launched from source picker gets `EXTRA_RETURN_TO_SOURCES = true`.
- Short playback, manual exit before threshold, or terminal failure can return result extras to the owning detail screen.
- Movie detail handles returned source flow by reopening picker or trying the next filtered source.
- Series detail handles returned source flow by reopening picker or trying the next filtered episode source.
- Debrid main focus restoration is outside the source picker itself and was recently QA-passed for Back from Player/detail.
- Series detail overrides Back for Debrid content to `finish()`, avoiding parent-activity recreation that previously reset to Home.

## P0 / P1 / P2 Reliability Gaps
| Priority | Gap | Evidence / Risk | Safest fix direction |
|---|---|---|---|
| P0 | `stream_id` is not always durable identity. | Direct MediaFusion URLs use URL hash; sources without infoHash can use title hash. This weakens resume, source-profile matching, failure return, and auto-next identity. | Introduce explicit identity fields later: `canonicalInfoHash`, `directUrlFingerprint`, `providerStreamKey`, and keep `stream_id` display/adapter-safe only. |
| P0 | Cached-only filter must exclude `DIRECT_STREAM`. | Direct HTTP/addon readiness is checked at click/refresh time but is not durable Real-Debrid cache proof. A follow-up false-positive fix keeps ready direct links as `DIRECT_STREAM` and reserves `VERIFIED_CACHED` for RD availability proof. | Keep RD cached filter strict to `VERIFIED_CACHED`. Add structured durable direct-readiness metadata only if a future product filter needs "ready direct" semantics. |
| P0 | Direct/proxy readiness host detection was duplicated and hardcoded. | Fixed for movie/series call sites by centralizing the shared policy in `DebridPlaybackRepository` and broadening it to existing MediaFusion/AIO source metadata; targeted `.84` QA covered movie ready and series not-ready branches. | Consider structured source capability metadata later for broader Stremio/direct proxy hosts. |
| P0 | Player terminal-failure redirect can bypass source picker recovery in some paths. | `PlayerActivity.handleTerminalPlaybackFailure()` redirects to detail before trying `finishWithReturnToSources()` in its current order. This may be correct for exhausted failures, but needs route-specific QA. | Verify terminal failure classes and reorder only if source-picker return should own selected-source failures. Do not change until reproduced. |
| P1 | Provider badges can mislead provider support. | `MovieSourceAdapter.bindProviderBadge()` infers RD/AD/PM from label/provider text even though implemented auth/resolver parity is Real-Debrid-focused. | Use structured provider/support fields, not label text, for badges. Keep unknown/generic unless auth+resolver exists. |
| P1 | Addon error filtering is text based. | `UnifiedSourceProvider` skips streams containing known phrases like API exception/rate limit, but providers can change wording. | Promote addon errors to typed `AddonStream` errors where fetchers can detect them, then filter by type. |
| P1 | Availability verification is capped and order-dependent. | Only first 10 unique hashes are verified; order is language/provider/quality/seeders-based. Useful sources after the cap remain UNKNOWN. | Keep cap, but expose "verified subset" clearly and prioritize selected language/provider before verification. |
| P1 | Movie source list and bottom sheet duplicate source-filter surfaces. | Movie detail has inline source list plus bottom sheet source picker. | Keep one runtime picker for Debrid playback; avoid future changes that diverge inline list and bottom sheet behavior. |
| P1 | Series empty state depends on IMDb resolution. | Numeric series id requires TMDB external IDs; failure returns empty sources. | Add safe user-facing empty reason later: "Could not resolve series IMDb id" without raw backend details. |
| P2 | Source row label can truncate important release/provider info. | `item_movie_source.xml` uses one-line ellipsized source labels. | Keep badges first; later use two-line optional release title or focused marquee if TV-safe. |
| P2 | Source picker uses hardcoded dark colors and some legacy text glyph issues. | Layout has literal `#0B0D10`, `#99000000`, and rendered bullet/encoding artifacts in current UI dumps/reports. | Cosmetic-only pass after reliability fixes; no behavior changes. |

## Provider Parity Notes
- Real-Debrid is the only clearly implemented auth/resolver path in this audit.
- Stremio addon manifests are first-class source fetch inputs and are preferred when configured.
- Torrentio, MediaFusion, dynamic registries, and PureFire-style sources are source providers, not equivalent Debrid accounts.
- AllDebrid/Premiumize badges must not be treated as implemented provider parity unless auth, cache verification, resolver, error handling, and QA exist.
- MediaFusion/AIO strict header failures were previously fixed by Stremio-compatible headers, but provider-side pseudo-streams and rate-limit messages still need typed handling.

## Safe Fix Order
1. P0 identity audit/fix: split source identity from adapter id and persist a durable source profile for direct/proxy and hash/magnet paths.
2. P0 direct/proxy readiness centralization: move host/readiness capability detection into one data-layer helper and use it in movie, series, and future direct playback refresh paths.
3. P0 cached/direct filter semantics: make cached-only wording and behavior match the actual confidence states.
4. P1 provider badge truthfulness: render badges from structured support/capability fields, not labels.
5. P1 typed addon error model: have fetchers classify pseudo-stream/error streams before conversion.
6. P1 terminal failure route QA: verify whether Player should source-return or detail-redirect for each failure class.
7. P2 visual polish: source label wrapping/marquee and layout color cleanup after behavior is stable.

## Files Likely To Change Later
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieSourceAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/sources/SourceFilterUtils.kt`
- `app/src/main/res/layout/dialog_source_selection.xml`
- `app/src/main/res/layout/item_movie_source.xml`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt` only if failure-return ordering is proven wrong by QA.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerViewModel.kt` only if direct resume/source-profile continuity needs runtime change.

## Things Explicitly Not To Touch
- Debrid rail/sidebar/hero/cards/row UI while fixing source reliability.
- Live TV, IPTV VOD, IPTV Series, Home, Search, and Settings modules unless a source-picker root cause is proven there first.
- Real-Debrid credentials, account config, token storage, or companion auth payloads.
- PlayerActivity global D-pad behavior.
- Existing resolver-backed magnet/infoHash path unless a test proves a source identity bug.
- Direct addon passthrough identity: do not downgrade direct Debrid playback to IPTV/null.
- DMM or new provider integrations.
- Sensitive logs: no stream URLs, tokens, magnets, hashes paired with titles, unrestricted URLs, usernames, passwords, or raw source lists.

## QA Checklist For Later Implementation
- Build: `.\gradlew.bat clean :app:compileDebugKotlin --no-daemon --console plain`.
- Build: `.\gradlew.bat :app:assembleDebug --no-daemon --console plain`.
- Install on requested device(s), usually `192.168.0.84:5555` first.
- Movie detail: open source picker, verify sources, filters, badges, selected focus, OK launch, Back restore.
- Series detail: choose an episode, open source picker, verify same behavior and episode-specific cache/filter state.
- Direct MediaFusion/AIO source: verify proxy preflight blocks not-ready/error-video links before Player launch.
- Resolver-backed hash/magnet source: verify Real-Debrid resolution launches Player and preserves Debrid identity.
- Unknown/uncached source: verify user-facing error and no retry storm.
- Terminal source failure: verify auto-next only for copyright/legal/not-cached/unavailable and no auto-next on rate-limit/auth/network/unknown.
- Continue Watching movie: verify expired direct source fresh-resolves from metadata or falls back to detail safely.
- Continue Watching series: verify Back returns to Debrid focus, not Home.
- Search result -> detail -> picker route.
- Discover/top row -> detail -> picker route.
- Player controls: Debrid label, language/source-profile continuity, Back/result extras.
- No sensitive URLs, magnets, tokens, or full provider source data in logs.

## Cached Filter Semantics Audit (Date: 2026-05-29)
- **Problem**: Source picker filter label said "Cached only" (or "Playable only"), but DIRECT_STREAM links could appear as playable false positives. The first fix made `SourceFilterUtils.isPlaybackReady()` strict to `VERIFIED_CACHED`, but movie/series click-time readiness helpers still promoted direct HTTP/addon sources to `VERIFIED_CACHED` after a successful direct readiness check. That made direct links look like RD cached links even though direct readiness is not durable RD cache proof.
- **Implemented Fix**: Kept filter semantics strictly `VERIFIED_CACHED` and stopped direct HTTP readiness from being stored as RD cached state.
  - `SourceFilterUtils.kt`: `isPlaybackReady()` only returns `true` for `DebridCacheStatus.VERIFIED_CACHED`; `hasCacheConfidence()` now mirrors that rule so the RD Cached chip only appears when at least one verified cached source exists.
  - `MovieDetailActivity.kt`: direct HTTP sources marked ready by preflight remain `DebridCacheStatus.DIRECT_STREAM`; only non-direct RD resolver sources can become `VERIFIED_CACHED`.
  - `SeriesDetailActivity.kt`: same direct HTTP classification rule for episode source lists.
  - Direct/proxy readiness preflight before Player launch is preserved. Direct links remain visible/selectable when the cached filter is off.
- **Verification**: `clean :app:compileDebugKotlin` and `:app:assembleDebug` passed. APK installed on `192.168.0.84:5555`; `192.168.0.21:5555` was unavailable. Device QA confirmed direct-only pickers hide the RD Cached chip, direct sources remain visible with the filter off, an AIOStreams not-ready direct source did not launch `PlayerActivity`, repeated language-chip taps did not crash or lose the picker, and Back returned from detail to Debrid/MainActivity. A mixed verified-cached sample was not available during QA, so verified cached inclusion under the filter still needs sampling when available. Full source-fetch log review found pre-existing raw provider/server URL logs from outside this fix scope.

## Language Filter + Provider Error Audit (Date: 2026-05-29)

### 1. Provider/API Pseudo-Source Leaks
- **Current check:** `UnifiedSourceProvider` filters streams where `combinedText` contains: "api exception", "rate limit", "invalid token", "error occurred", or "debrid error".
- **Gap:** Providers often use other phrases like "limit reached", "timeout", "quota exceeded", "provider error", or "api limit". These bypass the filter, appearing as playable video sources in the UI.
- **Recommendation:** Expand the error keyword list in `convertAddonStreamsToMovieSources` or introduce a typed error model across fetchers (`DynamicAddonFetcher`, `SimplifiedPureFireFetcher`) before conversion to catch more provider-specific messages.

### 2. Inconsistent Language Links (e.g., Hindi appearing/missing)
- **Root Cause (Order-dependent Cache Verification):**
  - When fetching streams (often 50+), `UnifiedSourceProvider.prioritizeAddonStreams` sorts them before verification.
  - The sorting gives English (if explicitly preferred) a higher score than Hindi.
  - `verifyRealDebridCacheStatuses` caps verification to the top 10 streams (`MAX_CACHE_VERIFICATION_HASHES`).
  - If Hindi streams fall outside this top 10 limit, they remain `DebridCacheStatus.UNKNOWN`.
- **Secondary Root Cause (Playable-only Filter):**
  - `UNKNOWN` streams are completely hidden when the "Playable only" (Cached only) filter is active. Thus, Hindi links vanish if they weren't in the top 10 to get verified.
- **Language Parsing Gaps:** `LanguageParser.LANGUAGE_MAP` lacks explicit short-codes like "HI". Also, multi-audio streams are assigned `multi` but not `hi`, yielding a lower language match score than explicit English streams.
- **Recommendation:**
  - Increase the cache verification cap slightly or ensure language-diverse streams are interleaved into the top 10 before capping.
  - Update `LanguageParser` to accurately detect abbreviations like "HI" or add language inheritance for "MULTI" tracks.

### 3. Language Filter Crash and Focus Issues
- **Crash Risk (Race Condition):** Selecting a language chip calls `languageAdapter.updateSelection` (which triggers a `notifyDataSetChanged()` or layout pass on `rvLanguageFilters`), while `applyFilters()` concurrently forces a layout on `rvSources` and posts a `requestFocus()` event. This adapter mutation during a focus/layout pass can throw an `IllegalStateException` or index-out-of-bounds.
- **Focus Stealing / Order Bug:**
  - In `SourceSelectionBottomSheet.updateUi()`, after updating the list, the code explicitly tries to restore focus to `selectedStreamId` (the previously selected/playing source).
  - If the user had previously selected an English source, and then clicks the "Hindi" filter, the Hindi sources correctly sort to the top.
  - However, `updateUi()` immediately calculates the index of the old English source (which is now lower down) and calls `rvSources.scrollToPosition(targetIndex)` and `requestFocus()`.
  - The UI scrolls away from the newly sorted top items and pulls focus down to the old item, making it *appear* as if the sort failed. It also steals focus away from the language filter chips.
- **Recommendation:**
  - De-couple the language adapter update from the `rvSources` layout pass, potentially using `post`.
  - When filtering by language, do not attempt to restore focus to a `selectedStreamId` if it doesn't match the new preferred language. Alternatively, reset `selectedStreamId` on filter change, or just focus the first item/keep focus on the filter chip.

## RD Cached Chip Visibility / Filter Mismatch (Date: 2026-05-29)
- **Status**: Diagnose-only. No runtime code or XML changes were made.
- **Current UI shape**: The current source picker no longer has a dedicated RD Cached chip. `dialog_source_selection.xml` exposes `chip_quality`, `chip_language`, and `chip_type`; `SourceFilterUtils.TYPE_OPTIONS` statically includes `"RD Cached"`.
- **Exact visibility condition**:
  - `SourceSelectionBottomSheet.showSources()` hides `layout_source_filters` only when `allSources` is empty.
  - `SourceSelectionBottomSheet.updateFilterOptions()` always sets `layoutSourceFilters.visibility = View.VISIBLE` for non-empty source lists and always updates `chipType` text to the selected type or `All`.
  - `chipType` opens every static `TYPE_OPTIONS` value. There is no availability gate that checks whether the RD Cached option would return any rows.
- **Exact filter predicate**:
  - `SourceFilterUtils.apply()` filters `preferredType == "RD Cached"` with `source.cacheStatus == DebridCacheStatus.VERIFIED_CACHED`.
  - Legacy `cachedOnly` also calls `isPlaybackReady()`, which is strict to `DebridCacheStatus.VERIFIED_CACHED`.
- **Safe sampled source status counts**:
  - Prior `.84` source picker samples had total row counts of 15, 28, 241, and 224. In each sampled visible viewport, `VERIFIED` badges were 0 and `DIRECT` badges were present. No raw URLs, magnets, hashes, tokens, or source names were printed.
  - For the reported case where choosing RD Cached returns zero rows, the active post-quality/language filtered list has `VERIFIED_CACHED = 0` by the exact predicate above. Exact full-list counts for `DIRECT_STREAM`, `NOT_CACHED`, and `UNKNOWN` were not captured because no temporary instrumentation was added in this diagnose-only pass.
- **Badge mismatch check**:
  - `MovieSourceAdapter` cache badge uses `cacheStatus`, not label text: `VERIFIED`, `DIRECT`, `UNCACHED`, or `UNKNOWN`.
  - The provider badge is still inferred from label/provider text (`RD`, `AD`, `PM`, `SRC`), which can confuse provider identity with cache status, but it is not used by the RD Cached filter.
  - `DIRECT_STREAM` currently uses the same cached-pill background resource as `VERIFIED_CACHED`, so direct links can visually feel ready/cached even though the badge text is `DIRECT` and the RD Cached predicate excludes them.
- **Verification cap/order notes**:
  - `UnifiedSourceProvider.verifyRealDebridCacheStatuses()` verifies only the first `MAX_CACHE_VERIFICATION_HASHES = 10` distinct hashes after stream prioritization.
  - `UnifiedSourceProvider.resolveCacheStatus()` returns `DIRECT_STREAM` whenever a direct or MediaFusion URL exists, before consulting verified hash state.
  - Sources outside the verification cap, or sources whose availability calls fail, can remain `UNKNOWN`. That can produce zero RD Cached rows even when unverified candidates exist later in the list.
- **Root cause**: Static Type option availability. RD Cached appears because the generic Type dropdown is available for any non-empty list and always includes the static RD Cached option, not because any `VERIFIED_CACHED` source exists.
- **Smallest safe fix recommendation**:
  - Build Type options dynamically from the pre-type filtered source list and include RD Cached only when `any { cacheStatus == DebridCacheStatus.VERIFIED_CACHED }`.
  - If Quality or Language changes make the selected type unavailable, reset `preferredType` to `All` before applying filters and keep focus stable.
  - Do not broaden RD Cached to include `DIRECT_STREAM`; that would reintroduce the direct-playable false positive.
  - Treat any badge-style change for `DIRECT_STREAM` as optional visual polish, separate from filter correctness.
- **Files likely to change**:
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt` for dynamic Type option gating and selected-type reset.
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/sources/SourceFilterUtils.kt` only if a reusable status-count/options helper is preferred.
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieSourceAdapter.kt` only for optional direct badge visual distinction.
- **QA checklist for the later fix**:
  - Direct-only list: RD Cached is not offered, or is disabled/zero-count and cannot be applied.
  - Mixed list: RD Cached is offered only when at least one `VERIFIED_CACHED` source exists.
  - Selecting RD Cached keeps `VERIFIED` rows and hides `DIRECT`, `UNCACHED`, and `UNKNOWN` rows.
  - Turning RD Cached off restores direct/unknown/non-cached rows according to the other active filters.
  - Quality and Language changes reset unavailable Type selections safely and do not steal focus.
  - Source click, direct readiness preflight, resolver-backed playback, Back restore, and language-chip stability remain unchanged.
  - Logs remain free of raw URLs, magnets, hashes, tokens, usernames, passwords, and raw source lists.

## Dynamic RD Cached Type Option Fix (Date: 2026-05-29)
- **Status**: Implemented in the existing source picker bottom sheet. Runtime source launch and cache predicates were not changed.
- **Files changed**: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`.
- **Behavior**:
  - Type dropdown options are now built from the current pre-type filtered source list.
  - Pre-type means active Quality, Language, size/cached state, and sorting are applied with `preferredType = null`.
  - `RD Cached` is offered only when that pre-type list contains at least one `DebridCacheStatus.VERIFIED_CACHED`.
  - `Direct`, `Torrent/Add-on`, and `Unknown` are likewise offered only when their matching `cacheStatus` exists in the same pre-type list.
  - If the current selected Type becomes unavailable after source reload or Quality/Language changes, the Type filter resets to `All` before applying filters, avoiding an empty result caused by a stale unavailable Type.
- **Semantics preserved**:
  - RD Cached remains strict to `VERIFIED_CACHED`; `DIRECT_STREAM` is not included.
  - Direct sources remain visible under `All` and `Direct`.
  - Source click/playback and direct/proxy readiness preflight remain unchanged.
- **Verification**:
  - Build passed: `clean :app:compileDebugKotlin` and `:app:assembleDebug`.
  - Install passed on `192.168.0.84:5555` and `192.168.0.21:5555`.
  - Device QA on `.84`: direct-only movie picker with 15 sources showed Type options `All`, `Direct`, and `Torrent/Add-on`; `RD Cached` was not shown. Selecting `Direct` kept direct rows visible. Selecting `English` kept the picker stable and Type still omitted `RD Cached`. A direct source launched `PlayerActivity`, and Back returned to Debrid.
  - Mixed `VERIFIED_CACHED` positive sample was not available during this pass, so RD Cached positive-option QA remains pending.
  - Redacted recent-log scan found URL/token/hash-like patterns in logcat (`RawUrlCount=24`, `TokenCredentialCount=14`, `LongHashCount=2`), with raw values not printed. Treat this as an existing log-hygiene gap; this UI filter change did not add logging.

## Debrid Source Logging Hygiene Cleanup (Date: 2026-05-29)
- **Status**: Implemented and device-verified on `192.168.0.84:5555`.
- **Root cause**:
  - Shared "redacted" URL descriptions still contained URL-like or magnet-like prefixes, so safe logs still matched raw-sensitive scans.
  - Real-Debrid remote logs printed account labels, torrent IDs, partial hashes, file-selection values, and raw throwable objects.
  - Source aggregation logs printed raw title/ID context and per-source detail dumps.
  - Poster/backdrop image loading logs printed raw artwork URLs in the app process.
- **Files changed**:
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/util/SensitiveLogRedactor.kt`
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/RealDebridRemoteDataSource.kt`
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/util/ImageViewExtensions.kt`
- **Behavior**:
  - No source fetching, filtering, sorting, readiness preflight, playback routing, source picker UI, or provider behavior changed.
  - Redacted URL/magnet/hash/credential logs now use placeholders without preserving raw URL or magnet prefixes.
  - Throwable logging in touched Debrid source paths now records exception class only.
  - Source aggregation logs keep safe counts/provider summaries only.
- **Verification**:
  - Build passed: `clean :app:compileDebugKotlin`.
  - Build passed: `:app:assembleDebug`.
  - Install passed on `192.168.0.84:5555`.
  - Device QA covered Debrid movie detail -> source picker -> direct source -> PlayerActivity -> Back return to Debrid.
  - Final app-process log scan counts: `RawUrl=0`, `Magnet=0`, `TokenParam=0`, `CredentialWord=0`, `LongHash=0`.
- **Remaining gaps**:
  - Broader non-Debrid app logs were not audited in this task.
  - Mixed `VERIFIED_CACHED` positive-case QA remains separate from log hygiene.

## Source Picker Hindi/Multi Language Ranking Fix (Date: 2026-05-29)
- **Status**: Implemented and device-verified on `192.168.0.84:5555`.
- **Root cause**:
  - The active parser lives at `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/util/LanguageParser.kt`, not the older `data/debrid/source` path.
  - Hindi aliases missed the short `HI` marker and several Hindi-English / dual-audio / multi-audio Hindi phrases, so valid Hindi rows could be parsed as generic `multi` or English/German only.
  - `SourceFilterUtils.apply()` used selected language as a hard filter. When no exact match existed, useful direct sources could disappear; when exact matches did exist, cache priority could still compete with the user-selected language ordering.
  - `SourceSelectionBottomSheet` restored focus to the previously selected stream id after filter updates, which could scroll away from newly promoted Hindi rows.
- **Files changed**:
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/util/LanguageParser.kt`
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/sources/SourceFilterUtils.kt`
  - `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- **Behavior**:
  - Hindi parsing now detects `hi`, `hin`, `hindi`, Hindi-English, dubbed Hindi, dual-audio Hindi, multi-audio Hindi, and Devanagari Hindi markers.
  - Dual/Multi rows with Hindi hints are tagged as Hindi-compatible instead of only generic `multi`.
  - Selecting Hindi filters to exact Hindi-compatible rows when present.
  - If no exact Hindi-compatible rows exist after other active filters, the picker keeps the useful fallback source list visible and ranks language-compatible rows first instead of showing an empty list.
  - Language changes clear stale selected-source focus so the first matching row receives focus after the list updates.
- **Semantics preserved**:
  - RD Cached remains strict to `VERIFIED_CACHED`; direct streams are not promoted to RD cached.
  - Direct links remain visible under All/Direct modes.
  - Source click/playback and direct/proxy readiness preflight remain unchanged.
  - No source URLs, magnets, hashes, tokens, credentials, raw source titles, or raw source lists were logged.
- **Verification**:
  - Build passed: `clean :app:compileDebugKotlin`.
  - Build passed: `:app:assembleDebug`.
  - Install passed on `192.168.0.84:5555`.
  - Device QA: Debrid search -> series detail -> episode source picker returned 398 sources; Language dropdown included Hindi; selecting Hindi returned two Hindi-English direct rows; focus moved to the first matching row; direct source launched `PlayerActivity`; Back returned to Debrid.
  - Final app-process log scan counts: `RawUrl=0`, `Magnet=0`, `TokenParam=0`, `CredentialWord=0`, `LongHash=0`.
- **Remaining gaps**:
  - The sampled episode was season 3 episode 5 rather than the suggested season 3 episode 4 because D-pad navigation landed on episode 5.
  - RD Cached positive mixed-source filtering was not revalidated in this task.
  - The cache verification cap/order issue can still leave later target-language torrent hashes as `UNKNOWN`; this fix improves parsing and UI ranking only.
