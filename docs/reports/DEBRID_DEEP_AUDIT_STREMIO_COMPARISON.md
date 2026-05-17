# Debrid Deep Audit And Stremio Comparison

## 1. Scope
- Audit target: Debrid browsing, authentication, catalog, source fetching, source selection, playback resolution, resume/history, settings, and player handoff.
- Comparison target: Stremio's documented add-on model, stream model, add-on installation model, BitTorrent support, and player/streaming settings.
- Audit type: Read-only code/report audit. No Android source changes were made.

## 2. Files Inspected
- `docs/reports/DEBRID_MODULE_REPORT.md`
- `docs/reports/APP_SUCCESS_PATTERNS.md`
- `docs/reports/APP_FAILED_PATTERNS.md`
- `docs/reports/DO_NOT_REPEAT.md`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/DebridPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/api/RealDebridApiService.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/api/RealDebridAuthInterceptor.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/RealDebridRemoteDataSource.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/SimplifiedPureFireFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/MediaFusionFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/DynamicAddonFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/TmdbRemoteDataSource.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/AddonCatalogRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/DebridViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/DebridFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/search/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/discover/*`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/WatchHistoryPreferences.kt`

## 3. External Reference Baseline
Stremio official docs establish these reference points:
- Stremio add-ons are HTTP/IPFS resources with a manifest and REST-like `/{resource}/{type}/{id}.json` routes. Resources include catalog, meta, stream, and subtitles.
- Stremio stream responses are shortcuts to media, not embedded video. Stream objects can provide `infoHash` or direct `url`.
- Stremio manifests declare resources, content types, catalogs, id prefixes, configuration, and behavior hints such as `p2p`, `configurable`, and `configurationRequired`.
- Stremio installs add-ons into the user's account; add-ons are websites/search services, not local binary plugins.
- Stremio supports BitTorrent as a streaming protocol, while noting only official permitted-content BitTorrent support and no support for infringing third-party add-ons.
- Stremio player settings include default subtitle language/size/style, autoplay next episode, caching, local add-on, and torrent profile.

Sources:
- Stremio add-on protocol: https://stremio.github.io/stremio-addon-sdk/protocol.html
- Stremio manifest format: https://stremio.github.io/stremio-addon-sdk/api/responses/manifest.html
- Stremio stream guide: https://stremio.github.io/stremio-addon-guide/step5
- Stremio add-on install docs: https://stremio.zendesk.com/hc/en-us/articles/360021348391-How-to-install-uninstall-Add-ons
- Stremio BitTorrent FAQ: https://stremio.zendesk.com/hc/en-us/articles/360000281292-Does-Stremio-use-BitTorrent
- Stremio options: https://stremio.zendesk.com/hc/en-us/articles/360017301779-Stremio-options-explained
- Real-Debrid API surface used by this app: https://api.real-debrid.com/

## 4. Current DebridXtream Architecture
### 4.1 High-Level Flow
1. Debrid home/catalog rows are built from TMDB discovery/search plus local Continue Watching and Favorites.
2. Movie/series details are routed through existing VOD/Series detail activities with `categoryId == "debrid"` or `EXTRA_IS_DEBRID`.
3. Sources are fetched by `UnifiedSourceProvider`.
4. Built-in providers are Torrentio-style requests through `SimplifiedPureFireFetcher` and MediaFusion through `MediaFusionFetcher`.
5. Optional dynamic add-ons are loaded from configured registry URLs and executed by `DynamicAddonFetcher`.
6. Source rows are mapped to `MovieSource` wrappers around `XtreamVodInfo`.
7. `SourceSelectionBottomSheet` shows filters for language, size, and cached-only.
8. `PlaybackResolver` always re-resolves Debrid playback from infoHash/magnet instead of trusting saved stream URLs.
9. `DebridPlaybackRepository` uses Real-Debrid: add magnet, select files, poll torrent info, pick a link, unrestrict it, then launch shared `PlayerActivity`.
10. Continue Watching stores debrid infoHash/magnet/source metadata so resume can re-resolve later.

### 4.2 What Is Strong
- Single shared `PlayerActivity` is preserved for IPTV and Debrid, matching `DO_NOT_REPEAT` guidance against duplicate player activities.
- Debrid resume correctly avoids relying on expired saved stream URLs when infoHash/magnet metadata is present.
- Source UI exposes useful TV-facing filters: language, cached-only, and size cap.
- Series source resolution tries to convert TMDB series IDs to IMDb IDs before querying stream add-ons.
- Episode pack file selection has scoring for `SxxEyy`, `1x02`, season/episode words, and title-token matches.
- `EncryptedSharedPreferences` is used for Real-Debrid auth state.
- Dynamic add-on registry support exists, giving the project a path toward Stremio-like extensibility.

## 5. Findings
### Critical: Token-Bearing Provider URLs Can Be Exposed
Evidence:
- `SimplifiedPureFireFetcher` builds Torrentio config strings containing `realdebrid=$token` for movies and series.
- It then constructs a full request URL with that config.
- Debug OkHttp BASIC logging is enabled for this fetcher in debug builds.
- `DebridPlaybackRepository` logs full direct HTTP URLs when it detects direct stream playback.
- `MovieDetailActivity` logs full playback URLs.
- `WatchHistoryPreferences` logs the full Continue Watching JSON, which can include stream URLs and Debrid metadata.

Impact:
- Access tokens and ephemeral Debrid links can leak into logcat, bug reports, CI logs, or shared QA artifacts.
- This directly conflicts with the repo rule: do not log stream URLs, Debrid links, tokens, usernames, or passwords.

Recommendation:
- Redact tokens, magnets, stream URLs, and complete Continue Watching JSON everywhere.
- Do not pass the Real-Debrid token in any URL that is logged by OkHttp. If an external provider requires token-in-path configuration, disable URL logging and add explicit redaction utilities.

### High: Real-Debrid Only, But UI/Report Language Implies Multi-Debrid
Evidence:
- Real-Debrid API, auth state, token refresh, and unrestrict flows are implemented.
- No equivalent Premiumize or AllDebrid API clients/repositories were found.
- `MovieSourceAdapter` displays provider badges for RD/AD/PM, but that is label parsing only.
- `DEBRID_MODULE_REPORT.md` says the module handles Real-Debrid, Premiumize, and AllDebrid integration.

Impact:
- Users and future agents may assume Premiumize/AllDebrid are functional when they are not.
- Source ranking and cached flags may display provider confidence the backend cannot actually enforce.

Recommendation:
- Either downgrade docs/UI language to "Real-Debrid only" or add real provider abstractions and API clients.

### High: Cached Source State Is Mostly Inferred, Not Verified
Evidence:
- `UnifiedSourceProvider.inferCachedFlag()` infers cached status from `cached`, text markers, `rd+`, `instant`, `uncached`, and Real-Debrid strings.
- There is no Real-Debrid instant availability check before presenting cached-only confidence.
- `DebridPlaybackRepository.resolveDebridUrl()` may add magnets and wait up to roughly 60 seconds.

Impact:
- "Cached" can be wrong.
- A user can choose a source that looks instant but triggers slow magnet conversion or fails.
- Stremio-style debrid setups generally make the source list communicate instant vs download/caching clearly; this app only approximates that behavior.

Recommendation:
- Add a provider-normalized cache availability layer using Real-Debrid instant availability where possible.
- Treat unknown cache status separately from false/uncached in UI.

### High: Add-On Support Is Not Full Stremio Add-On Support
Evidence:
- `DynamicAddonFetcher` can call configured URL templates and parse streams.
- It does not install Stremio manifests into an account.
- It does not fully consume manifest `resources`, `types`, `idPrefixes`, catalogs, config, subtitles, behavior hints, or add-on lifecycle.
- Debrid home catalogs come mostly from TMDB rows, not installed add-on catalogs.

Impact:
- The app looks Stremio-inspired but is not Stremio-compatible as a general add-on client.
- Add-on compatibility will be fragile and registry-specific.

Recommendation:
- Decide explicitly: either keep a curated provider registry, or implement a real Stremio manifest client with install/config/enable/disable support.

### Medium: Source Detection Mixes IPTV And Debrid Heuristics
Evidence:
- `UnifiedSourceProvider.getMovieSources()` routes to Debrid only when `categoryId == "debrid"` or heuristic title/category detection says so.
- For Debrid catalog detail routes this is usually fine because the category is explicit.
- The fallback heuristics include broad title patterns such as `" s"` and `" e"` that can misclassify titles.

Impact:
- If future routes omit the explicit Debrid category flag, routing can drift.

Recommendation:
- Make Debrid source path explicit from typed content origin rather than title/category guessing.

### Medium: Static Token Login Creates A Non-Refreshable Auth State
Evidence:
- `DebridPreferences.saveRealDebridToken()` stores access token with blank refresh token and one-hour expiry.
- `DebridAccountRepository.refreshAuthState()` returns an error for blank refresh token.
- Fetchers may still read cached access token directly without first validating freshness.

Impact:
- Manual token login can degrade after expiry, and failure mode may be inconsistent between catalog, source fetch, and playback.

Recommendation:
- Separate static API-token mode from OAuth token mode. Static tokens should not be treated like expiring OAuth access tokens unless the service contract says so.

### Medium: Catalog Is Stronger Than Source Identity
Evidence:
- Debrid rows use TMDB discovery categories: trending, language/region rows, provider-themed rows such as Netflix/Prime/Disney/Max.
- Movie source fetching works best with IMDb IDs, but `getDebridMovieSources()` currently uses the provided IMDb ID and does not resolve one when missing.
- For series, TMDB-to-IMDb resolution is implemented.

Impact:
- Movies launched from TMDB ID without IMDb ID may fall back to title/year search, reducing source accuracy.

Recommendation:
- Resolve movie TMDB IDs to IMDb IDs before provider fetch, same as series.

### Medium: Source Ranking Is Underpowered
Evidence:
- Source filtering sorts by preferred language only when preferred language is set, then cached, then seeders.
- Built-in Torrentio request asks provider for `sort=qualitysize`, but after merging providers, app-level ranking is minimal.
- No codec/HDR/DV/audio-channel/release-group penalties or device/network size heuristics are applied.

Impact:
- The top source may be too large, uncached/unknown, wrong codec, or poor fit for Android TV hardware.

Recommendation:
- Introduce deterministic rank scoring: cached > provider reliability > quality cap > size/network fit > language > codec compatibility > seeders > release hygiene.

### Medium: Source Selection UI Is Good But Not Yet Stremio-Level Complete
Present:
- Source count, badges, provider badge, quality, size, seeders, cached/uncached, language, backdrop.

Missing:
- Explicit provider source grouping.
- "Unknown cache" separate visual state.
- Source health/failure memory.
- Sort controls.
- Codec/HDR/audio/subtitle labels.
- Clear "try next source" reason display after player failure.

Recommendation:
- Keep the existing bottom sheet, but add rank/sort chips and richer metadata only after cache verification is made reliable.

### Medium: Debrid History Stores Useful Metadata But Also Sensitive Data
Evidence:
- `ContinueWatchingItem` stores `debridInfoHash`, `debridMagnet`, `source`, `expiresAt`, and `streamUrl`.
- Resume logic correctly re-resolves Debrid when metadata is available.
- `WatchHistoryPreferences` logs full JSON.

Impact:
- Functionally good, but data/log handling needs stronger privacy rules.

Recommendation:
- Store infoHash/magnet for re-resolution, but avoid storing ephemeral stream URLs for Debrid unless there is a specific offline resume reason.
- Never log full history JSON.

### Low: Text Encoding/Log Noise Is Degraded
Evidence:
- Several files contain mojibake in comments/log strings.
- Many source-resolution logs are verbose and use debug/error levels inconsistently.

Impact:
- QA logs are harder to search.
- Important Debrid errors are buried under noisy provider diagnostics.

Recommendation:
- Normalize logs to ASCII tags and structured summaries; reserve error level for actual user-impacting failures.

## 6. Stremio Comparison Matrix
| Area | Stremio Reference | DebridXtream Current | Gap |
|---|---|---|---|
| Content source model | Add-ons provide catalog/meta/stream/subtitles resources. | TMDB drives Debrid catalog; providers mostly supply streams. | Partial. Catalog is not add-on-driven. |
| Add-on install | Installed/uninstalled into user account; add-ons are remote sites. | Registry URLs can be stored; no account add-on lifecycle. | Large gap. |
| Manifest support | Manifest declares resources, types, catalogs, id prefixes, config, behavior hints. | Dynamic registry definitions use custom URL templates and JSON keys. | Not fully compatible. |
| Stream endpoint shape | `/stream/<type>/<videoID>.json`; stream ID can be video ID for series episodes. | Torrentio/MediaFusion paths are called directly; series uses `imdb:season:episode`. | Similar for stream fetch only. |
| Stream object support | Streams can point via `infoHash` or direct `url`. | Supports `infoHash`, `magnet`, direct URL, headers in model. | Good overlap. |
| Subtitles | Add-on resource supports subtitles; player has subtitle settings in Stremio. | No Debrid add-on subtitle integration found. | Missing. |
| BitTorrent engine | Stremio supports BitTorrent streaming. | App does not appear to run a local torrent engine; it relies on Real-Debrid/direct URLs. | Different model. |
| P2P warning | Manifest behavior hint can identify P2P content. | No provider-level P2P behavior hint handling. | Missing. |
| Debrid provider support | Stremio itself is provider-neutral; third-party add-ons can integrate debrid services. | Real-Debrid implemented; AD/PM only cosmetic in UI. | Real-Debrid-only. |
| Cached/uncached source clarity | Add-ons can expose source labels; debrid setups often separate instant/download behavior. | Cached state inferred from labels/extras. | Needs verification. |
| Source filters | Depends on add-on config and app UI. | Language, cache, and size filters present. | Good base, needs sorting/metadata. |
| Source ranking | Add-on/provider dependent. | Provider request asks Torrentio for quality/size sorting; app merge ranking is limited. | Needs app-level rank. |
| Catalog rows | Add-ons define catalogs; Cinemeta provides metadata. | TMDB discovery rows hardcoded in ViewModel. | Less flexible. |
| Search | Add-on catalog extras can support search. | TMDB search and Debrid search exist. | Good UX, not add-on-native. |
| Account sync | Stremio add-ons are installed to user account. | Local encrypted prefs only. | Missing cross-device sync. |
| Continue Watching | Stremio tracks in-progress media in app/account. | Local Continue Watching with Debrid re-resolution metadata. | Functional locally; no cloud sync. |
| Player settings | Subtitle defaults, autoplay next, cache, torrent profile, hardware acceleration. | Debrid prefs include player engine, tunneling, autoplay next, UI scale; player has Media3 controls. | Some overlap, less complete. |
| Error handling | Empty streams array is valid; add-ons can fail independently. | Provider failures are caught and empty lists returned. | Good, but source health not surfaced enough. |
| TV navigation | Stremio Android TV has a mature source/playback UI. | DebridXtream TV source UI is customized and focus-aware. | Promising but needs QA hardening. |

## 7. Priority Backlog
### P0 Security/Privacy
1. Redact all stream URLs, magnets, tokens, and full history JSON from logs.
2. Disable or redact OkHttp logging for provider URLs that contain Debrid tokens.
3. Add a unit-testable `SensitiveLogRedactor`.

### P1 Correctness
1. Implement Real-Debrid instant availability before showing "cached" confidence.
2. Resolve TMDB movie IDs to IMDb IDs before source fetching.
3. Split OAuth token and static API-token auth modes.
4. Replace Debrid title/category heuristics with explicit source-origin routing.

### P2 Stremio Parity
1. Decide curated registry vs full Stremio manifest client.
2. If full parity is desired, add manifest parsing, add-on install/uninstall state, config pages/fields, behavior hints, and subtitle resource support.
3. Add provider/source health memory and source failure feedback.

### P3 UX/Ranking
1. Add deterministic ranking with cached, provider reliability, quality, size, language, codec, HDR, audio channels, and seeders.
2. Add "Unknown cache" badge separate from "Uncached".
3. Add source sort controls and provider grouping.

## 8. What Not To Change Blindly
- Do not create a separate Debrid player activity.
- Do not bypass `PlaybackResolver` for Debrid resume.
- Do not trust saved Debrid stream URLs as durable.
- Do not claim Real-Debrid/AllDebrid/Premiumize parity until each provider has a real auth/API/resolver implementation.
- Do not add more source labels before the cache/source metadata is verified.
- Do not add Stremio manifest support as a side path that duplicates the current source provider; integrate it behind the existing `UnifiedSourceProvider` boundary.

## 9. Final Status
PARTIAL  Debrid setup is usable and has a strong Real-Debrid playback foundation, but it is not Stremio-equivalent. The largest gaps are sensitive logging, real cache verification, full add-on lifecycle/manifest support, multi-debrid provider implementation, subtitle integration, and source ranking.
