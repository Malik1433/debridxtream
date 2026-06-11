# Debrid Links List / Source List Audit

Status: audit-only
Scope: Debrid movie/episode links list, source picker, resolver, player handoff, and Debrid Continue Watching reopen/resume.

Swarm note: a hierarchical swarm was initialized and six specialist agents were registered. Direct agent execution was blocked in this environment because `ANTHROPIC_API_KEY` is not set, so the merged findings below are based on local code inspection of the same boundaries.

## A. Swarm Summary

Agents used
- `arch-trace` - architecture trace for movie, series, and continue-watching flow.
- `data-id` - identity and source-field contract audit.
- `fetch-resolve` - provider, fetcher, resolver, timeout, and validation audit.
- `ui-focus` - picker UI, adapter, focus, and row recycling audit.
- `state-life` - loading/error/lifecycle/cancellation audit.
- `player-handoff` - intent extras, metadata preservation, and player recovery audit.

Files inspected by the audit lead
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeNavigationRouter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieSourceAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/sources/SourceFilterUtils.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/StremioAddonFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/SimplifiedPureFireFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/MediaFusionFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/DynamicAddonFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/RealDebridRemoteDataSource.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/AddonCatalogRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/RealDebridRateLimiter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/model/AddonModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/model/RealDebridModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/model/HomeScreenModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerHistoryManager.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/ContinueWatchingAdapter.kt`
- `app/src/main/res/layout/dialog_source_selection.xml`
- `app/src/main/res/layout/item_movie_source.xml`
- `app/src/main/res/layout/item_debrid_loading.xml`
- `app/src/main/res/layout/item_debrid_content.xml`
- `app/src/main/res/layout/item_continue_watching_card.xml`

Key findings by agent
- `arch-trace`: the runtime chain is split across detail screens, `SourceSelectionBottomSheet`, `PlayerActivity`, and `PlayerViewModel`; movie and series share the picker UI but diverge in identity handoff and retry behavior.
- `data-id`: `MovieSource` and `ContinueWatchingItem` carry the right raw fields, but the same identity is sometimes represented as `seriesId`, sometimes `tmdbId`, and sometimes `contentId`, which makes the contract fragile in older CW items and fallback paths.
- `fetch-resolve`: `UnifiedSourceProvider` mixes parallel fetches with early-return provider gating; `PlaybackResolver` correctly ignores stale `streamUrl` for Debrid, but direct HTTP passthrough and provider readiness checks can still allow ambiguous direct-link launches.
- `ui-focus`: `MovieSourceAdapter` and `SourceSelectionBottomSheet` have the right focus scaffolding, but stable-ID collisions, list refreshes, and filtered-out selections can still produce wrong-row focus or stale selection state.
- `state-life`: the movie picker cancels its own load job. The series picker now guards late source-list success/error callbacks by active episode id, but both detail activities still catch broad exceptions and flatten them into empty/error states, which hides provider-specific failures.
- `player-handoff`: `PlayerActivity` preserves the contract for extras and return-to-sources, but series fallback still leans on `tmdbIdExtra` and `contentId` when `EXTRA_SERIES_ID` is missing, which is the main fragility in the handoff chain.

## B. Full File Map

| File | Responsibility | Important functions/classes | Data received | Data output | High-risk notes |
|---|---|---|---|---|---|
| `ui/home/HomeNavigationRouter.kt` | Routes Continue Watching and section navigation. | `onContinueWatchingItemClick`, `openContinueWatchingDetail`, `resolveIptvSeriesIdForContinueWatching` | `ContinueWatchingItem`, fragment state, `PlayerActivity` extras | Starts detail or player intents | High risk because it bridges CW identity into player extras. |
| `data/model/HomeScreenModels.kt` | Holds `ContinueWatchingItem` and other home models. | `ContinueWatchingItem` | Watched history, playback metadata | CW row model | High risk because CW identity is persisted here and later reopened. |
| `ui/vod/MovieDetailActivity.kt` | Movie detail, Debrid source fetch, picker, and playback launch. | `loadMovieSources`, `showDebridSourcePicker`, `playMovie`, `playDebridMovie`, `handleDebridHistoryItem` | TMDB/IMDb IDs, source rows, filters | `PlayerActivity` intent or source picker state | High risk because it owns most movie Debrid launch logic. |
| `ui/series/SeriesDetailActivity.kt` | Series detail, episode list, Debrid source fetch, picker, and playback launch. | `fetchAndShowDebridSources`, `playEpisode`, `playDebridEpisode`, `resolveDebridEpisodeFetchContext` | Series ID, season/episode, source rows | `PlayerActivity` intent or bottom sheet state | High risk because episode identity and next-episode continuity are handled here. |
| `ui/series/SourceSelectionBottomSheet.kt` | Shared Debrid source picker UI. | `showSources`, `showError`, `showLoading`, `applyFilters`, focus helpers | `List<MovieSource>`, filter state, focus return stream IDs | Selected source callback and updated filter state | High risk because it controls focus, filtering, and return behavior. |
| `ui/vod/MovieSourceAdapter.kt` | RecyclerView adapter for source rows. | `bind`, `updateSelection`, `getItemId`, `DiffUtil` callbacks | `MovieSource` list and selected stream id | UI row state and click/focus callbacks | High risk because stable IDs and focus recovery depend on it. |
| `ui/sources/SourceFilterUtils.kt` | Shared filter/sort logic for source rows. | `filter`, `apply`, `isPlaybackReady`, `mapLanguageCodeToName` | `MovieSource`, filter state | Filtered/sorted source list | High risk because it determines which sources stay visible and in what order. |
| `data/debrid/repository/UnifiedSourceProvider.kt` | Orchestrates all Debrid source fetchers and converts them to `MovieSource`. | `getMovieSources`, `getSeriesEpisodeSources`, `convertAddonStreamsToMovieSources`, `resolveCacheStatus`, `deduplicateStreams`, `prioritizeAddonStreams` | TMDB/IMDb/series IDs, addon responses | Unified `MovieSource` list | Highest risk file in the source list flow. |
| `data/debrid/model/AddonModels.kt` | Fetcher DTOs and unified addon stream model. | `AddonDefinition`, `AddonStream`, `AddonSourceType`, `StremioStream`, `MediaFusionStream`, `ZileanStream` | Provider JSON payloads | Parse-ready source DTOs | High risk because nullable/raw fields are normalized here. |
| `data/debrid/source/StremioAddonFetcher.kt` | Fetches Stremio addon manifest and stream endpoints. | `fetchMovieSources`, `fetchEpisodeSources`, `fetchSources`, `mapStream` | Stremio manifest URL and IDs | `AddonStream` list | High risk because it is a provider-specific path with its own validation. |
| `data/debrid/source/SimplifiedPureFireFetcher.kt` | Torrentio-based fetcher. | `fetchMovieSources`, `fetchEpisodeSources`, `parseTorrentioStream` | IMDb ID or title/year fallback | `AddonStream` list | High risk because title fallback and provider query format are fragile. |
| `data/debrid/source/MediaFusionFetcher.kt` | MediaFusion fetcher. | `fetchMovieSources`, `fetchEpisodeSources`, `buildUrl`, `fetchInternal` | IMDb ID and saved provider URL | `AddonStream` list | Medium/high risk because of fallback URL and hardcoded base behavior. |
| `data/debrid/source/DynamicAddonFetcher.kt` | Generic addon scraper engine. | `fetchMovieSources`, `fetchEpisodeSources`, `fetchAndParse`, `parseStream` | Addon definition, search patterns, JSON payloads | `AddonStream` list | High risk because failures are swallowed per addon and can hide provider problems. |
| `data/debrid/repository/DebridPlaybackRepository.kt` | Direct proxy readiness and RD torrent/unrestrict flow. | `requiresDirectProxyReadinessCheck`, `getAddonProxyPlaybackReadiness`, `resolveDebridUrl`, `effectiveAddonProxyPlaybackHeaders` | URL, headers, provider metadata, hashes | Readiness result or resolved URL | High risk because it decides whether a direct link is playable or must be resolved. |
| `data/debrid/repository/PlaybackResolver.kt` | Source-agnostic playback resolution decision engine. | `resolve` | Source type, streamUrl, infoHash, magnet, season/episode | `ResolutionResult` | High risk because it decides whether Debrid uses stale URL or fresh metadata. |
| `data/debrid/source/RealDebridRemoteDataSource.kt` | Real-Debrid REST/OAuth wrapper. | `addMagnet`, `getTorrentInfo`, `unrestrictLink`, `getInstantAvailability` | Hashes, magnets, links | RD API DTOs | High risk because it is the final remote boundary for resolver-backed playback. |
| `data/debrid/repository/AddonCatalogRepository.kt` | Addon discovery and catalog wiring. | `fetchAddonDefinitions`, `fetchMediaFusionStreams`, `fetchZileanStreams` | Registry and addon configs | Catalog and addon results | Medium risk; mostly config and discovery, but not the main links list path. |
| `data/debrid/repository/RealDebridRateLimiter.kt` | Rate-limit and cooldown guard. | `awaitPermit`, `recordFailure`, `sourceCooldown` | Error types and keys | Cooldown decisions | High risk because it can silently suppress source retries. |
| `data/repository/XtreamRepository.kt` | IPTV source generation and `MovieSource` data class. | `MovieSource`, `getMovieSources` | IPTV stream metadata | IPTV `MovieSource` list | Medium risk because debrid and IPTV share the same row model. |
| `player/stabilized/PlayerActivity.kt` | Shared player host and intent contract. | `createIntent`, `onCreate`, `debridSeriesLookupId`, `refreshDirectDebridSourceFromMetadata`, `finishWithReturnToSources`, `redirectToFailureDetail` | Intent extras, current source, playback type | Player lifecycle, failure redirect, and return result | Highest risk downstream boundary. |
| `player/stabilized/PlayerViewModel.kt` | Debrid resolution and episode playlist state. | `loadDebridSeriesPlaylist`, `loadNextDebridEpisode`, `refreshDebridMovieSource`, `resolveDebridMovieSource`, `getDebridLanguageOptions` | Series/movie metadata and source profile | Debrid resolution state | High risk because it turns source metadata into a playable URL. |
| `player/stabilized/PlayerHistoryManager.kt` | Continue Watching and watched-state persistence. | `buildContinueWatchingItem`, `watchedIdentityKey`, `stableSeriesIdentity`, `resolveContinueWatchingId` | Player intent extras and playback state | CW rows and watched-state rows | High risk because CW identity is reused by Home and Player. |
| `ui/home/ContinueWatchingAdapter.kt` | Continue Watching row binding and stable IDs. | `stableIdFor`, `bind`, click/long-press handling | CW item list | Home CW row UI | Medium risk because row identity affects focus restoration and reopen behavior. |
| `res/layout/dialog_source_selection.xml` | Picker shell layout. | N/A | Picker state | Visible bottom sheet | Medium risk because layout can trap or hide focus controls. |
| `res/layout/item_movie_source.xml` | Source row layout. | N/A | `MovieSource` binding | Focusable source row | Medium risk because row density and truncation affect TV usability. |
| `res/layout/item_debrid_loading.xml` | Loading skeleton for Debrid rows. | N/A | Loading state | Placeholder row | Low/medium risk; mostly visual but part of perceived loading correctness. |
| `res/layout/item_debrid_content.xml` | Debrid content card layout used around the picker/detail surfaces. | N/A | Content row binding | Focusable content card | Medium risk because it affects source entry-point discoverability. |
| `res/layout/item_continue_watching_card.xml` | Continue Watching card layout. | N/A | CW item binding | Focusable CW card | Medium risk because it affects reopen click/focus behavior. |

## C. End-to-End Runtime Flow

### Movie Debrid Links Flow

`Movie detail entry` -> `MovieDetailActivity.loadMovieSources(imdbId)` -> `UnifiedSourceProvider.getMovieSources(...)` -> provider fan-out and dedupe -> `MovieSource` list -> `showDebridSourcePicker(...)` -> `SourceSelectionBottomSheet.showSources(...)` -> `MovieSourceAdapter` row focus/click -> `playMovie(...)` or `playDebridMovie(...)` -> `PlaybackResolver.resolve(...)` or `DebridPlaybackRepository.resolveDebridUrl(...)` -> `PlayerActivity.createIntent(...)` -> `PlayerActivity.onCreate(...)` -> `PlayerViewModel` Debrid resolution or direct playback -> ExoPlayer -> return-to-sources or failure redirect.

### Series Episode Debrid Links Flow

`Series detail entry` -> `SeriesDetailActivity.fetchAndShowDebridSources(episode)` -> `UnifiedSourceProvider.getSeriesEpisodeSources(...)` -> provider fan-out and dedupe -> `MovieSource` list -> `SourceSelectionBottomSheet.showSources(...)` -> `MovieSourceAdapter` row focus/click -> `playDebridEpisode(...)` -> `PlaybackResolver.resolve(...)` or `DebridPlaybackRepository.resolveDebridUrl(...)` -> `PlayerActivity.createIntent(...)` -> `PlayerActivity.onCreate(...)` -> `PlayerViewModel.loadDebridSeriesPlaylist(...)` or `loadNextDebridEpisode(...)` -> ExoPlayer -> next episode or return-to-sources/failure redirect.

### Continue Watching Debrid Flow

`Home row click` -> `HomeNavigationRouter.onContinueWatchingItemClick(item)` -> direct resume or `openContinueWatchingDetail(item)` -> `MovieDetailActivity` or `SeriesDetailActivity` reopens -> source list or direct player launch -> `PlayerActivity.createIntent(...)` with CW identity fields -> `PlayerActivity.onCreate(...)` reads `EXTRA_SERIES_ID`, `EXTRA_TMDB_ID`, `EXTRA_IMDB_ID`, `EXTRA_CONTENT_ID`, `EXTRA_SEASON_NUM`, `EXTRA_EPISODE_NUM` -> player resume/history -> back or return-to-sources to the same screen family.

### Player Launch Flow

`Selected source row` -> source row metadata copied into `PlayerActivity.createIntent(...)` -> `PlayerActivity` stores `contentId`, `seriesId`, `tmdbId`, `imdbId`, `seasonNumber`, `episodeNumber`, `headers`, `debridInfoHash`, `debridMagnet`, `directDebridPlayback`, `debridStreamId`, `debridProvider`, `debridSourceType`, `debridSourceName`, `debridQuality`, `debridLanguages`, `debridBingeGroup`, `debridFileIdx` -> `PlayerActivity` chooses direct playback or resolver path -> `PlayerViewModel` fetches or refreshes source data if needed -> ExoPlayer prepares and plays.

### Back Navigation Flow

`Player back` -> `PlayerActivity.setExitResultIfNeeded()` or `finishWithReturnToSources()` -> `EXTRA_RETURN_TO_SOURCES` and optional failed stream id bubble back to detail screen -> `MovieDetailActivity` or `SeriesDetailActivity` can reopen picker or auto-advance to next source -> `SourceSelectionBottomSheet` restores nearby focus using adjacent stream ids or selected stream id -> if not returning to sources, `redirectToFailureDetail()` opens detail again.

## D. Data Model Mapping Table

| Field | Where created | Where passed | Where read | Where persisted | Nullable risk | Mismatch risk |
|---|---|---|---|---|---|---|
| `seriesId` | `HomeScreenModels.ContinueWatchingItem`, `SeriesDetailActivity`, `HomeNavigationRouter`, `PlayerActivity.createIntent` | CW item -> router -> detail/player intent -> `PlayerActivity` -> `PlayerViewModel` -> `PlayerHistoryManager` | `PlayerActivity`, `PlayerViewModel`, `PlayerHistoryManager`, `HomeNavigationRouter` | CW item and watched-state rows | High, because old CW items may lack it | High, because fallback may switch to `tmdbId` or `contentId` |
| `tmdbId` | Movie/series detail screens, CW item, `PlayerActivity` intent | Detail -> player intent -> history | `PlayerActivity`, `PlayerHistoryManager`, `HomeNavigationRouter` | CW item, watched-state rows | Medium | High for series if used as the only fallback identity |
| `imdbId` | Detail screens and addon fetchers | Detail -> provider lookup -> player intent -> history | `UnifiedSourceProvider`, `PlayerActivity`, `PlayerHistoryManager` | CW item | Medium | Medium; mostly fallback/lookup only |
| `contentId` | Detail screens, CW item, player intent | Detail -> player intent -> return-to-sources/failure logic -> history | `PlayerActivity`, `PlayerHistoryManager`, `HomeNavigationRouter` | CW item, watched-state rows | High when a composite id is used | High because it may be a stream id, tmdb id, or composite `series:season:episode` key |
| `streamId` | `MovieSource.stream.stream_id`, `ContinueWatchingItem.debridStreamId`, `PlayerActivity.EXTRA_DEBRID_STREAM_ID` | Source row -> player intent -> history -> retry logic | `MovieSourceAdapter`, `PlayerActivity`, `PlayerHistoryManager`, `DebridPlaybackRepository` | CW item, retry state | Medium | High if duplicate or non-unique ids slip into the list |
| `seasonNumber` | Series detail and player intent | Episode selection -> player intent -> resolver -> history | `SeriesDetailActivity`, `PlayerActivity`, `PlayerViewModel`, `PlayerHistoryManager` | CW item, watched-state rows | Medium | Medium; missing season breaks next-episode and playlist lookup |
| `episodeNumber` | Series detail and player intent | Episode selection -> player intent -> resolver -> history | `SeriesDetailActivity`, `PlayerActivity`, `PlayerViewModel`, `PlayerHistoryManager` | CW item, watched-state rows | Medium | Medium; missing episode breaks next-episode continuity |
| `url` | `AddonStream.url`, `MovieSource.stream.direct_source`, resolved player result | Fetcher -> source model -> player intent | `UnifiedSourceProvider`, `MovieDetailActivity`, `SeriesDetailActivity`, `PlayerActivity`, `PlaybackResolver` | Not usually persisted | High because many direct links are nullable or provider-specific | High; direct URL, stream URL, and resolved URL are easy to confuse |
| `direct_source` | `XtreamVodInfo.direct_source`, addon mapping into `MovieSource.stream.direct_source` | Source model -> detail click -> player/resolver | `MovieDetailActivity`, `SeriesDetailActivity`, `PlayerActivity`, `PlaybackResolver` | No direct persistence | High | High; sometimes it is a magnet, sometimes a direct HTTP URL |
| `resolvedUrl` | `PlaybackResolver` / `DebridPlaybackRepository` result | Resolver -> player intent | `PlayerActivity` | No | Low | Medium; must remain `http(s)` and not be mistaken for raw source metadata |
| `provider/addon name` | Addon metadata and source mapping | Fetcher -> `MovieSource` -> UI -> diagnostics | `MovieSourceAdapter`, `SourceFilterUtils`, `DebridPlaybackRepository` | CW item diagnostics | Medium | Medium; provider label inference is substring-based in the row badge |
| `quality` | Fetchers and `MovieSource` mapping | Fetcher -> filter -> row badge -> player diagnostics | `MovieSourceAdapter`, `SourceFilterUtils` | CW item diagnostics | Medium | Medium; quality parsing can miss `2160p`, `4K`, or CAM/TS variants |
| `size` | Fetchers and `MovieSource` mapping | Fetcher -> filter -> row badge | `MovieSourceAdapter`, `SourceFilterUtils` | No | Medium | Medium; units and missing values are inconsistent across providers |
| `seeders` | Fetchers and `MovieSource` mapping | Fetcher -> sort/filter -> row badge | `MovieSourceAdapter`, `SourceFilterUtils`, `UnifiedSourceProvider` | No | Medium | Medium; some providers return 0 or omit the field |
| `cached/debrid status` | `UnifiedSourceProvider.resolveCacheStatus` | Fetcher -> source model -> filter chips -> row badge | `SourceFilterUtils`, `MovieSourceAdapter`, `SourceSelectionBottomSheet` | No | Medium | High if `DIRECT_STREAM` and `VERIFIED_CACHED` are conflated |
| `headers` | Fetchers, addon payloads, player intent extras | Fetcher -> source model -> player intent -> player/resolver | `DebridPlaybackRepository`, `PlayerActivity`, `MovieDetailActivity`, `SeriesDetailActivity` | No | Medium | High because header preservation is required for direct addon playback |

## E. Fetcher / Resolver Audit

Provider parallel/sequential behavior
- `UnifiedSourceProvider.getMovieSources()` and `getSeriesEpisodeSources()` run Torrentio, MediaFusion, and dynamic addon fetches in parallel with `async`, then await all results and dedupe.
- The movie path has an early return when configured Stremio addon URLs exist. In that branch, it does not fall back to Torrentio/MediaFusion/dynamic add-ons if the configured Stremio addon set returns nothing.
- The series path has the same early-return behavior for configured Stremio addon URLs, which can block fallback providers.
- `RealDebridRateLimiter` serializes requests with a minimum request interval and keeps per-source and global cooldowns.

Timeout behavior
- `UnifiedSourceProvider` uses `withTimeout(TIMEOUT_MS)` around source resolution.
- `StremioAddonFetcher` uses 20 second connect/read timeouts.
- `SimplifiedPureFireFetcher` uses 30 second connect/read timeouts.
- `DynamicAddonFetcher` uses an 8 second per-scraper timeout.
- `DebridPlaybackRepository.getAddonProxyPlaybackReadiness()` uses a redirect probe with a 5 second call timeout for redirected direct-play checks.

Partial failure behavior
- Most fetchers return empty lists on exceptions, which keeps the UI alive but hides the exact provider failure unless logs are checked.
- `UnifiedSourceProvider` catches provider exceptions per fetcher, so one provider failing does not crash the aggregate, but early-return Stremio branches still short-circuit fallback providers.
- `PlaybackResolver` retries Debrid resolution twice, but terminal RD failures stop immediately.

Duplicate handling
- `UnifiedSourceProvider` deduplicates streams after combining provider results.
- `MovieSourceAdapter` and `ContinueWatchingAdapter` depend on stable ids. If source ids collide, duplicates or focus glitches can appear even when the upstream dedupe is correct.

Invalid URL and expired URL handling
- `PlaybackResolver` refuses Debrid playback when both infoHash and magnet are missing, and it validates the final resolved URL starts with `http://` or `https://`.
- `PlayerActivity` blocks expired direct Debrid resume when there is no metadata to refresh and routes to terminal failure.
- `DebridPlaybackRepository.resolveDebridUrl()` can passthrough a direct HTTP link only when allowed and only when the link already looks directly playable.

Cached vs uncached handling
- `UnifiedSourceProvider.resolveCacheStatus()` maps verified RD availability to `VERIFIED_CACHED`, direct HTTP/addon sources to `DIRECT_STREAM`, and missing/negative cache proof to `NOT_CACHED` or `UNKNOWN`.
- `SourceFilterUtils.isPlaybackReady()` only treats `VERIFIED_CACHED` as cached-ready.
- `SourceSelectionBottomSheet` only shows the `RD Cached` type option when at least one verified cached source exists after current filters.

Direct vs resolver link behavior
- Direct HTTP addon/proxy links can be launched directly when they pass readiness checks and the source is marked as direct-playable.
- Torrent/magnet-like Debrid sources use `PlaybackResolver` and `DebridPlaybackRepository.resolveDebridUrl()` rather than trusting stored URLs.
- `PlayerActivity` also has metadata refresh paths for direct Debrid resume and next-episode re-resolution.

HTTP headers and user-agent behavior
- `StremioAddonFetcher` sends `User-Agent: Stremio/1.0` and `Accept: application/json`.
- `DynamicAddonFetcher` and `SimplifiedPureFireFetcher` use `User-Agent: Mozilla/5.0`.
- `DebridPlaybackRepository.effectiveAddonProxyPlaybackHeaders()` injects `User-Agent: Stremio/1.0` and `Accept: */*` when missing.
- `RealDebridRemoteDataSource` itself relies on the authorized RD service and does not appear to mutate playback headers.

Validation before playback
- `DebridPlaybackRepository.getAddonProxyPlaybackReadiness()` validates direct proxy links before launch.
- `PlaybackResolver` validates resolved URLs before returning success.
- The detail activities still have some direct-launch paths that can start playback before all metadata is rechecked, which is efficient but fragile when upstream metadata is incomplete.

## F. UI / Focus Audit

Adapter risks
- `MovieSourceAdapter` uses `setHasStableIds(true)` with `stream.stream_id.hashCode().toLong()`; hash collisions or duplicate ids can destabilize focus and DiffUtil updates.
- `MovieSourceAdapter.updateSelection()` only notifies rows whose ids match the old or new selected stream. If ids are unstable, selection repainting can miss the intended row.
- `ContinueWatchingAdapter` uses a synthetic stable id built from content type, tmdb/imdb/content id, title, and source. That is better than a raw index, but it can still change when title or source metadata changes.

Row recycling risks
- `MovieSourceAdapter` binds focus glint state and play icon visibility on every bind, which is correct, but recycled rows will still misrender if the backing id is unstable.
- `item_movie_source.xml` has a dense right-side badge cluster. On narrow widths, the title can collapse early and make rows look clipped or ambiguous.

Focus movement risks
- `MovieSourceAdapter` overrides DPAD_UP and DPAD_DOWN to move row-to-row. This prevents wrap-to-top behavior, but it can also trap focus if the top controls are not focusable in a given state.
- `SourceSelectionBottomSheet.focusTopControl()` prioritizes the filter chips, then the status text. If all filters are hidden, focus recovery depends on status visibility.

Focus recovery risks
- `SourceSelectionBottomSheet.resolveRestoreFocusStreamId()` prioritizes a focused row, then return-focus stream ids, then the selected row, then the first visible row. That is good, but it still falls back to top-of-list when the previous row is filtered out.
- When a language filter changes, `selectedStreamId` is cleared but `focusedStreamId` is not always enough to keep the user near the prior location if the visible set changes substantially.

Click debounce and double-launch risks
- `ContinueWatchingAdapter` has explicit click suppression and long-press suppression, which is good.
- `MovieSourceAdapter` does not implement a click debounce. If the same row is clicked twice quickly, the activity can receive duplicate launch requests.

Loading, empty, and error UI risks
- `SourceSelectionBottomSheet.showLoading()` shows a spinner and status text, `showSources()` swaps to the list, and `showError()` swaps to a status message. That is sound, but late async results can still race with the wrong visible state.
- `item_debrid_loading.xml` is a simple skeleton with no explicit focus target, so if it is shown alone the UI must ensure the rest of the picker can still be recovered cleanly.

Android TV remote navigation risks
- The picker footer hints are visible, but they are not focusable, so the actual remote path relies entirely on the chips, status text, and the list row focus chain.
- `dialog_source_selection.xml` is a full-screen bottom sheet overlay. If the list is empty and the status view is not focusable, the user can be left with only the dismissal path and no obvious recovery path.

## G. State / Lifecycle Audit

Coroutine scope risks
- `MovieDetailActivity.loadMovieSources()` cancels its previous job before launching a new one.
- `SeriesDetailActivity.fetchAndShowDebridSources()` launches in `lifecycleScope` and now ignores late source-list success/error callbacks when the active episode id has changed.
- `PlayerActivity` uses multiple handlers, callbacks, and playback tasks. The cleanup is present, but it is a complex lifecycle surface.

Lifecycle cleanup
- `PlayerActivity` removes handlers and unregisters callbacks in `onPause`, `onStop`, and `onDestroy`.
- `SourceSelectionBottomSheet` uses `pendingSources` when the view is not ready, which is good for early results, but error/loading state is not queued the same way.

Binding safety
- There are still several `lateinit` fields in the detail and picker screens, but the flow reads them after initialization in the expected lifecycle order.
- The main safety risk is not null binding access; stale source-list success/error application is now guarded in the series picker caller, while other async UI surfaces still need case-by-case review.

Stale results
- `SeriesDetailActivity` now tracks the active Debrid source request by `episode.id`; late source-list success/error callbacks cache under the original episode id but do not update the visible picker when stale.
- `SourceSelectionBottomSheet` can display a source list that belongs to the prior request if the host activity reuses the same sheet instance without a version check.

Stuck loaders
- If `showLoading()` is called and the fetch fails before `showError()` or `showSources()` runs, the loader will remain until the catch branch executes.
- The movie path is better guarded than the series path because it explicitly manages a loading job variable.

Swallowed errors
- The detail screens catch generic `Exception` and display a broad empty or failure message, which keeps the UI from crashing but hides whether the source list failed due to provider auth, malformed payload, timeout, or stale metadata.
- Several fetchers also swallow parse or network errors and return an empty list.

Memory leak risk
- The code does not show an obvious hard leak in the audited path, but any late coroutine result applied to a detached bottom sheet or activity can still hold the wrong UI state alive longer than necessary.

## H. Player Handoff Audit

Intent extras contract
- `PlayerActivity.createIntent()` is the single contract writer for playback, and it writes the important series/movie extras plus Debrid metadata and headers.
- `PlayerActivity` reads the same keys on create, including `EXTRA_SERIES_ID`, `EXTRA_TMDB_ID`, `EXTRA_IMDB_ID`, `EXTRA_SEASON_NUM`, `EXTRA_EPISODE_NUM`, `EXTRA_DEBRID_INFOHASH`, `EXTRA_DEBRID_MAGNET`, `EXTRA_DEBRID_STREAM_ID`, and `EXTRA_STREAM_HEADERS`.

Metadata preservation
- `MovieDetailActivity` and `SeriesDetailActivity` pass provider/source profile metadata into player intents, including provider name, source type, source name, language list, quality, stream id, binge group, and file index.
- `HomeNavigationRouter` also preserves CW metadata during direct resume, including `seriesId`, `tmdbId`, `imdbId`, season, episode, and the Debrid profile fields.

Headers preservation
- Headers are preserved from source rows into the player intent, and `DebridPlaybackRepository.effectiveAddonProxyPlaybackHeaders()` will repair the missing User-Agent/Accept headers when direct-proxy readiness is checked.
- The contract is only as good as the source mapping. If headers are missing at source creation, the player cannot reconstruct provider-specific auth headers by itself.

Resume preservation
- `PlayerActivity` can resume Debrid history and next-episode flows using the stored extras, but it needs either a valid series id or a valid TMDB/IMDb fallback to continue series navigation.
- `PlayerHistoryManager` stores CW identity from the same extras, which keeps Home reopening aligned with player metadata when the upstream item is complete.

Next episode continuity
- `PlayerViewModel.loadDebridSeriesPlaylist()` needs a numeric TMDB id, season number, and current episode number to prebuild the playlist state.
- `PlayerViewModel.loadNextDebridEpisode()` takes a series id, season, episode, series title, info hash, and source profile. This is the continuity boundary that can fail if `seriesId` is missing or not numeric where TMDB is expected.

Failed source behavior
- `PlayerActivity.finishWithReturnToSources()` returns failed stream information to the picker when the player can safely recover inside the Debrid source list flow.
- `PlayerActivity.redirectToFailureDetail()` sends the user back to movie or series detail if the player cannot safely return to the source list.

Back navigation
- Back from `PlayerActivity` is source-aware for Debrid and can return to the source list rather than always closing to Home.
- Back from `SeriesDetailActivity` intentionally finishes for Debrid content so the parent flow can restore Home or re-open the source chain correctly.

## I. Top 15 Bugs / Risks

1. Priority: Critical
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- Class/function: `getMovieSources()`, `getSeriesEpisodeSources()`
- Risky behavior: configured Stremio addon branches return early and do not fall back to Torrentio/MediaFusion/dynamic add-ons when the addon result is empty.
- User-visible symptom: a stale or misconfigured addon can make the whole Debrid source list appear empty even though other providers could have valid sources.
- Root cause: early return on the configured addon branch.
- Safe fix idea: treat addon results as one provider set, not a terminal branch; merge or fall through when the branch returns zero usable streams.
- Fix now / later: Fix now.

2. Priority: High
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`
- Class/function: `fetchAndShowDebridSources()`
- Risky behavior: fixed. Repeated episode taps can still let an older async fetch complete, but stale success/error callbacks are ignored before updating the visible picker.
- User-visible symptom: wrong episode source list, stale focus, or source count mismatch after rapid episode navigation.
- Root cause: no request token or cancellable job guard for per-episode fetches.
- Safe fix applied: `SeriesDetailActivity.fetchAndShowDebridSources()` records the active `episode.id` and checks it before calling `showSources()` or `showError()`.
- Fix now / later: Fixed 2026-06-07; manual rapid episode-switch QA pending.

3. Priority: High
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieSourceAdapter.kt`
- Class/function: `getItemId()`, `DiffUtil.areItemsTheSame()`
- Risky behavior: stable ids are derived from `stream.stream_id.hashCode()`, which can collide or destabilize row identity when provider ids repeat or change.
- User-visible symptom: wrong row focus, incorrect selection repaint, or item jump after source list refresh.
- Root cause: hash-based stable id instead of a stronger unique id contract.
- Safe fix idea: use a stronger deterministic unique key from source metadata or ensure upstream ids are globally unique before hashing.
- Fix now / later: Fix now.

4. Priority: High
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- Class/function: `debridSeriesLookupId()`, `refreshDirectDebridSourceFromMetadata()`, `redirectToFailureDetail()`
- Risky behavior: series fallback identity can collapse to `tmdbIdExtra`, `EXTRA_SERIES_ID`, `imdbIdExtra`, or the prefix of `contentId`.
- User-visible symptom: next episode lookup or failure redirect can open the wrong series when the explicit series id is missing.
- Root cause: the player accepts multiple fallback identities, which is convenient but not always equivalent.
- Safe fix idea: always pass explicit `seriesId` from series detail and CW resume paths, then keep fallbacks only for legacy recovery.
- Fix now / later: Fix now.

5. Priority: High
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`
- Class/function: `playDebridEpisode()`
- Risky behavior: the Debrid player intent passes `tmdbId = seriesId` but does not explicitly pass `seriesId` to `PlayerActivity.createIntent()`.
- User-visible symptom: the player depends on `tmdbId` fallback for episode continuity and watched-state identity.
- Root cause: identity contract is implicit rather than explicit.
- Safe fix idea: pass `seriesId = seriesId` in every series episode launch.
- Fix now / later: Fix now.

6. Priority: High
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- Class/function: `getSeriesEpisodeSources()`
- Risky behavior: the function assumes a numeric series id is TMDB and non-numeric is IMDb, then stops when IMDb resolution fails.
- User-visible symptom: some user-provided or legacy series ids can fail to fetch any episode links even when the provider supports the content.
- Root cause: identity normalization is too opinionated for mixed provider metadata.
- Safe fix idea: preserve the original id type explicitly and only resolve when the source truly requires a different id family.
- Fix now / later: Needs runtime testing.

7. Priority: Medium
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/SimplifiedPureFireFetcher.kt`
- Class/function: `fetchMovieSources()`, `fetchEpisodeSources()`
- Risky behavior: title fallback builds a path from raw title/year without URL encoding.
- User-visible symptom: title-based fallback searches can fail or mis-query for titles containing spaces, punctuation, or non-ASCII characters.
- Root cause: fallback identifier is concatenated as a raw path segment.
- Safe fix idea: URL-encode title fallback queries before building the Torrentio path.
- Fix now / later: Fix now.

8. Priority: Medium
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeNavigationRouter.kt`
- Class/function: `onContinueWatchingItemClick()`, `openContinueWatchingDetail()`
- Risky behavior: CW reopen for Debrid movies and series still falls back through `tmdbId` and `contentId` when `seriesId` is missing.
- User-visible symptom: older CW items can reopen the wrong detail screen or lose source continuity.
- Root cause: CW history items are not guaranteed to carry a durable series id.
- Safe fix idea: backfill explicit series ids into CW history when possible and keep the fallback path only for old records.
- Fix now / later: Needs runtime testing.

9. Priority: Medium
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/sources/SourceFilterUtils.kt`
- Class/function: `filter()`, `apply()`
- Risky behavior: filter and sort logic treat `VERIFIED_CACHED` as the only playback-ready cached state, while direct sources are classified separately.
- User-visible symptom: users can interpret direct playable rows as uncached or think the cached filter is broken.
- Root cause: cached, direct, and not-cached states are intentionally separate, but the UI language is easy to misread.
- Safe fix idea: keep the logic but make the visible labels and help text more explicit.
- Fix now / later: Later.

10. Priority: Medium
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- Class/function: `showSources()`, `showError()`, `showLoading()`, `updateUi()`
- Risky behavior: loading and error state are not queued with the same persistence as `pendingSources`, so a recreated view can briefly show stale UI state.
- User-visible symptom: spinner, empty state, or old list can appear out of sync after rotation, back/return, or fast dialog recreation.
- Root cause: only the source list itself is cached when the view is missing.
- Safe fix idea: queue a small view state object, not just the source list.
- Fix now / later: Needs runtime testing.

11. Priority: Medium
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerHistoryManager.kt`
- Class/function: `buildContinueWatchingItem()`, `stableSeriesIdentity()`
- Risky behavior: CW persistence stores `seriesId` only from `EXTRA_SERIES_ID`, while the watched-state identity can fall back to TMDB/IMDb/title when needed.
- User-visible symptom: CW rows can reopen with weaker identity than the watched-state row that was actually saved.
- Root cause: CW item persistence and watched-state persistence are not perfectly identical.
- Safe fix idea: persist the explicit series id on the CW item whenever the player knows it.
- Fix now / later: Fix now.

12. Priority: Medium
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- Class/function: `resolveDebridUrl()`
- Risky behavior: direct HTTP passthrough returns the URL as-is when allowed, with limited validation beyond scheme and provider readiness.
- User-visible symptom: malformed or semi-valid direct links can reach the player and fail later instead of being rejected earlier.
- Root cause: permissive direct-play shortcut.
- Safe fix idea: validate direct URLs more strictly before passthrough and surface a picker-friendly error.
- Fix now / later: Needs runtime testing.

13. Priority: Low
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/MediaFusionFetcher.kt`
- Class/function: `buildUrl()`, `fetchInternal()`
- Risky behavior: uses a hardcoded deprecated default base URL when no stored provider URL exists.
- User-visible symptom: the provider can silently fail or return empty lists if the saved config is missing.
- Root cause: fallback URL is baked into the client.
- Safe fix idea: require explicit provider configuration or surface a clear setup error.
- Fix now / later: Later.

14. Priority: Low
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- Class/function: `loadMovieSources()`, `playMovie()`, `playDebridMovie()`
- Risky behavior: broad `catch (Exception)` blocks flatten provider/auth/parser failures into a generic empty/error state.
- User-visible symptom: the picker just says no sources or fails without telling the user which provider or metadata failed.
- Root cause: error handling prioritizes survival over diagnostics.
- Safe fix idea: keep the safe fallback UI, but add typed error reporting for the visible state and logs.
- Fix now / later: Later.

15. Priority: Low
- File path: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- Class/function: `resolveRestoreFocusStreamId()`, `buildAdjacentReturnFocusStreamIds()`
- Risky behavior: when the prior focus row is filtered out, focus can drop to the first visible row instead of the most nearby row.
- User-visible symptom: after filter changes or return from player, focus may jump farther than expected on Android TV.
- Root cause: fallback order is focused row -> adjacent rows -> selected row -> first visible row.
- Safe fix idea: preserve a stronger "previous visible row" heuristic before falling back to top.
- Fix now / later: Later.

## J. Recommended Fix Order

Must fix now
- `UnifiedSourceProvider.getMovieSources()` and `getSeriesEpisodeSources()` early-return fallback behavior.
- `SeriesDetailActivity.fetchAndShowDebridSources()` stale-result guard. Fixed 2026-06-07; manual QA pending.
- Explicit `seriesId` propagation in `SeriesDetailActivity.playDebridEpisode()`.
- Stronger source-row stable ids in `MovieSourceAdapter`.

Safe quick wins
- URL-encode title fallback in `SimplifiedPureFireFetcher`.
- Persist explicit series ids into `PlayerHistoryManager` CW items whenever available.
- Improve picker error text for provider/auth/network failures.

Needs runtime testing
- Direct HTTP passthrough validation in `DebridPlaybackRepository`.
- Series identity fallback behavior in `PlayerActivity` and `PlayerViewModel`.
- Focus recovery after filter changes in `SourceSelectionBottomSheet`.

Do not touch yet
- The shared `PlayerActivity` retry/watchdog logic outside the Debrid links flow.
- The general IPTV playback path in `XtreamRepository` and non-Debrid player behavior.
- Visual polish files that do not affect source identity, focus, or playback launch.

## K. Manual Android TV Smoke Test Checklist

1. Open a Debrid movie, fetch the source list, filter by language and quality, and launch one source.
2. Open a Debrid series episode, fetch the source list, switch episodes quickly, and confirm the list does not show stale results.
3. Launch a Debrid series episode, then press Next Episode and confirm the next episode uses the same series identity.
4. Resume a Debrid Continue Watching item and verify the correct detail screen or player opens.
5. Trigger a source failure and verify the app returns to the source picker or detail screen instead of dead-ending.
6. Move focus with the TV remote through filter chips, the source list, and the footer hints.
7. Change a language filter and confirm the selected row or focus target stays near the prior row, not stuck at the top unexpectedly.
8. Back out of the player and confirm the picker/detail return path is the expected one for movie, series, and continue watching.
9. Verify headers survive the handoff by testing a direct addon/proxy source that requires them.
10. Confirm empty-state and error-state screens still expose a usable back path on Android TV.
