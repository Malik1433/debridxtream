# AUDIT PHASE 3 — Debrid Resolution, Real-Debrid, Source Picker Reliability

**Date:** 2026-06-12  
**Mode:** Diagnose Only — No code was changed during this audit.  
**Auditor:** Claude Sonnet 4.6 (Phase 3 of 3 audit series)  
**Scope:** Full Debrid chain from addon/stream list fetch through source picker population,
Real-Debrid resolution, direct addon passthrough, link expiry, retry behavior, error surfacing,
token refresh/auth failure handling, parallel resolve cancellation, and DMM reliability blockers.

---

## Files Audited

| File | Lines | Role |
|------|-------|------|
| `data/debrid/repository/PlaybackResolver.kt` | ~180 | Primary resolve decision engine |
| `data/debrid/repository/DebridPlaybackRepository.kt` | ~740 | RD poll loop, file picker, proxy detection |
| `data/debrid/repository/UnifiedSourceProvider.kt` | ~1500 | Source aggregation, cache verification |
| `data/debrid/repository/AddonCatalogRepository.kt` | ~360 | MediaFusion/Zilean CW and catalog |
| `data/debrid/source/RealDebridRemoteDataSource.kt` | ~400 | All RD REST calls |
| `data/debrid/source/StremioAddonFetcher.kt` | ~280 | Stremio addon HTTP client |
| `data/debrid/repository/RealDebridRateLimiter.kt` | ~180 | RD rate-limit and cooldown guard |
| `ui/sources/SourceFilterUtils.kt` | ~200 | Source filter/sort logic |
| `ui/series/SourceSelectionBottomSheet.kt` | ~600 | Source picker bottom sheet |
| `ui/vod/MovieDetailActivity.kt` | ~1443 | VOD Debrid path orchestration |
| `ui/series/SeriesDetailActivity.kt` | ~800+ | Series Debrid path orchestration |
| `player/stabilized/PlayerHistoryManager.kt` | ~300 | CW identity and resume recording |
| `data/local/WatchedIdentityBuilder.kt` | ~200 | Safe CW key construction |
| Context: `docs/reports/DEBRID_MODULE_REPORT.md` | — | Open issues B, C |
| Context: `docs/reports/DO_NOT_REPEAT.md` | — | Hard guardrails |
| Context: `docs/reports/APP_SUCCESS_PATTERNS.md` | — | Proven patterns |

---

## Finding Summary

| ID | Priority | Title | File |
|----|----------|-------|------|
| C1 | CRITICAL | TMDB health-check fires on every movie source fetch | UnifiedSourceProvider.kt |
| C2 | CRITICAL | pickVideoLink() size tiebreaker sorts ascending — smallest file wins | DebridPlaybackRepository.kt |
| H1 | HIGH | Stremio manifest re-fetched on every source request (no caching) | StremioAddonFetcher.kt |
| H2 | HIGH | loadAllDynamicDefinitions() fetches registries sequentially | UnifiedSourceProvider.kt |
| H3 | HIGH | playMovie() detects Debrid path by label string matching | MovieDetailActivity.kt |
| H4 | HIGH | AUTH_REQUIRED applies 120 s rate-limit cooldown instead of re-auth | RealDebridRateLimiter.kt |
| H5 | HIGH | sanitize() silently drops all direct HTTP URL streams | AddonCatalogRepository.kt |
| M1 | MEDIUM | availabilityCache ConcurrentHashMap grows unboundedly | UnifiedSourceProvider.kt |
| M2 | MEDIUM | verifyRealDebridCacheStatuses() Semaphore(1) serialises hash checks | UnifiedSourceProvider.kt |
| M3 | MEDIUM | Proxy detection hardcoded to elfhosted.com only | DebridPlaybackRepository.kt |
| M4 | MEDIUM | allSources/debridSources split causes duplicate source fetch | MovieDetailActivity.kt |
| M5 | MEDIUM | refreshMediaFusionCacheStatus() HEAD checks not cancelled on dismiss | MovieDetailActivity.kt |
| M6 | MEDIUM | handleDebridHistoryItem() resolves without episode context | MovieDetailActivity.kt |
| M7 | MEDIUM | releaseYearDiscriminator() returns null — CW key loses year | PlayerHistoryManager.kt |
| M8 | MEDIUM | searchContent() calls movies then shows sequentially | AddonCatalogRepository.kt |
| M9 | MEDIUM | Log.e() used for non-error paths in getSeriesEpisodeSources() | UnifiedSourceProvider.kt |
| M10 | MEDIUM | fetchDebridSeriesDetail() fetches each season sequentially | SeriesDetailActivity.kt |
| m1 | MINOR | fetchAndShowDebridSources() creates new BottomSheet without existing-sheet guard | SeriesDetailActivity.kt |
| m2 | MINOR | isUnsafeIdentity() base32 pattern may over-reject valid episode IDs | WatchedIdentityBuilder.kt |
| m3 | MINOR | Rate-limiter auth cooldown state cleared on force-quit | RealDebridRateLimiter.kt |
| m4 | MINOR | DebridResolutionManager reference in DEBRID_MODULE_REPORT.md is stale | Report only |

---

## Critical Findings

---

### C1 — TMDB Health-Check Fires on Every Movie Source Fetch

**File:** `data/debrid/repository/UnifiedSourceProvider.kt` ~lines 153–163  
**Priority:** CRITICAL  
**Risk:** User-visible latency on every source picker open; TMDB API quota waste; result always discarded.

**Root cause:**  
`getMovieSources()` begins by calling `tmdbRemote.searchMovies("Inception")` as a connectivity canary.
This is a real outbound HTTP request with a hardcoded title. The result is never used — no guard
branches on it, no metric is recorded, and the code continues regardless. For every movie source
picker open, the app sends an unrequested TMDB search before any Torrentio/Stremio scraping begins.

**Reproduction path:**
1. Open any VOD detail screen (Debrid content).
2. Trigger source loading (tap "Find Sources" or equivalent).
3. In Logcat: filter `TmdbRemoteDataSource` or OkHttp — a `/3/search/movie?query=Inception`
   request fires immediately, before any addon stream requests.
4. The Inception result set is never consumed by any subsequent code path.

**Blast radius:**
- Adds ~100–500 ms latency on every picker open proportional to TMDB API response time.
- On a slow or metered connection the canary call may time out, delaying source fetch further.
- Counts against TMDB API rate limits (40 req/10 s on free tier).

---

### C2 — pickVideoLink() Size Tiebreaker Sorts Ascending — Smallest File Wins

**File:** `data/debrid/repository/DebridPlaybackRepository.kt`  
**Priority:** CRITICAL  
**Risk:** Wrong video file selected silently on score ties; user plays SD rip when HD rip is available.

**Root cause:**  
The file picker comparator is:

```kotlin
compareBy<LinkScore> { it.score }.thenBy { it.sizeBytes }
```

`thenBy` sorts **ascending**, so when two files share the same quality score the one with the
smaller file size wins. For video content this is backwards — larger file = more data = better
quality. A 700 MB SD rip outranks a 4 GB HD rip whenever their RD scores are equal.

**Reproduction path:**
1. Open any movie/episode where the cached torrent contains multiple files with similar names
   (e.g., two video tracks at different resolutions, or a dual-audio pack).
2. Both files receive similar quality scores from `pickVideoLink()`'s scoring heuristics.
3. The file with the smaller size bytes plays. Log the `LinkScore` list to confirm ordering.

**What correct behaviour looks like:**  
`thenByDescending { it.sizeBytes }` — largest file wins on score tie.

**Cross-reference:** The existing `DO_NOT_REPEAT.md` guardrail "do not pick next episode by quality
alone" applies here; the tiebreaker is the secondary signal and it is currently inverted.

---

## High Findings

---

### H1 — Stremio Manifest Re-Fetched on Every Source Request (No Caching)

**File:** `data/debrid/source/StremioAddonFetcher.kt`  
**Priority:** HIGH  
**Risk:** Doubles network overhead per configured Stremio addon; adds latency proportional to addon count.

**Root cause:**  
`fetchSources()` calls `fetchManifest(baseUrl)` unconditionally before every stream list request.
The manifest (`manifest.json`) defines addon capabilities and changes only when the addon is updated
(rarely). No in-memory or TTL cache exists for the fetched manifest. For N configured Stremio URLs,
each picker open fires N manifest fetches + N stream fetches = 2N network calls.

Additionally, `StremioAddonFetcher` constructs its own `OkHttpClient` instance rather than
sharing the app-level client, so its connection pool is isolated.

**Reproduction path:**
1. Configure 2 Stremio addon URLs in settings.
2. Open source picker for any content.
3. Logcat (OkHttp or network): two `GET .../manifest.json` calls fire before any
   `/stream/...` calls, one per configured addon.

**Correct pattern:** Cache the manifest per base-URL with a short TTL (e.g., 30 min) using an
in-memory map or the existing `availabilityCache` namespace.

---

### H2 — loadAllDynamicDefinitions() Fetches Addon Registries Sequentially

**File:** `data/debrid/repository/UnifiedSourceProvider.kt`  
**Priority:** HIGH  
**Risk:** Linear latency scaling with number of configured registries; sources stall until all definitions resolve.

**Root cause:**  
`loadAllDynamicDefinitions()` iterates addon registry URLs in a plain sequential `for` loop,
`await()`-ing each network call before starting the next. With three registry URLs at 300–500 ms
each, definitions take 900–1500 ms to load — and this must complete before any scrapers begin.

**Reproduction path:**
1. Configure 3 addon registry URLs.
2. Open source picker and watch Logcat: registry fetches complete one at a time, each starting
   only after the previous finishes.

**Correct pattern:** `urls.map { async { fetchRegistry(it) } }.awaitAll()` — wall clock equals the
slowest single fetch instead of the sum.

---

### H3 — playMovie() Detects Debrid Path by Label String Matching

**File:** `ui/vod/MovieDetailActivity.kt`  
**Priority:** HIGH  
**Risk:** Any source from a new or self-hosted provider silently routes through IPTV path, bypassing RD resolution.

**Root cause:**  
`playMovie()` determines whether to invoke the Debrid resolver or the IPTV player by checking
`sourceOverride?.label?.contains("Torrentio")`, `.contains("MediaFusion")`, `.contains("PureFire")`.
This is a text-match heuristic, not a typed classification. Any stream source not matching one of
these three string literals — including Comet, Knightcrawler, a renamed self-hosted addon, or any
future provider — falls through to the IPTV player path. No error is surfaced; the stream URL
(which may be a magnet URI) is sent to the IPTV player.

**Reproduction path:**
1. Add a Stremio addon with a provider name not containing "Torrentio", "MediaFusion", or "PureFire".
2. Open a VOD, select a source from that addon.
3. Observe: `PlayerActivity` receives the raw stream URL rather than a resolved RD link; no
   debrid resolution log entries appear.

**Correct pattern:** APP_SUCCESS_PATTERNS #16 (Direct Debrid Source Profile Continuity) and
#15 (Direct Debrid Playback Identity Guard) describe typed source classification. The source
object should carry an enum/typed field (e.g., `SourceType.DEBRID_TORRENT`,
`SourceType.DIRECT_STREAM`) set at parse time, not inferred from label text at play time.

---

### H4 — AUTH_REQUIRED Failure Applies 120 s Rate-Limit Cooldown Instead of Triggering Re-Auth

**File:** `data/debrid/repository/RealDebridRateLimiter.kt`  
**Priority:** HIGH  
**Risk:** Auth failures present as silent 2-minute stalls rather than triggering a re-auth prompt; error cycles indefinitely.

**Root cause:**  
`recordFailure(DebridFailureType.AUTH_REQUIRED)` maps `AUTH_REQUIRED` to `DEFAULT_RATE_LIMIT_SECONDS = 120`.
When an RD auth failure occurs (expired token, revoked key), the rate limiter blocks all subsequent
RD calls for 120 seconds. From the user's perspective, the app appears to be rate-limited, not
auth-failed. After 120 s the next call hits the same auth failure, records another 120 s cooldown,
and the cycle repeats indefinitely.

`refreshAuthStateIfNeeded()` in `DebridPlaybackRepository` is called before each resolve, but only
`HttpException` with status 400/401/403 triggers `shouldForceReauth`. A timeout or generic error
during token refresh does not flip the force-reauth flag.

**Reproduction path:**
1. Revoke the RD access token from the Real-Debrid account panel.
2. In-app: open source picker → select any RD-cached source.
3. Observe: no "auth required" prompt; picker appears to try and then stall; 2 minutes later it
   retries and fails again. Logcat shows `AUTH_REQUIRED` then `rate_limit_cooldown=120s`.

**Cross-reference:** `DO_NOT_REPEAT.md` — "do not retry 429/auth/network/unknown" and "do not
send direct Stremio URLs through app-side RD."  
Rate-limiting and auth failures are distinct failure modes and must be handled separately.

---

### H5 — sanitize() Silently Drops All Direct HTTP URL Streams

**File:** `data/debrid/repository/AddonCatalogRepository.kt` line 303  
**Priority:** HIGH  
**Risk:** Callers receive empty or partial stream lists with no indication that valid streams were dropped.

**Root cause:**  
```kotlin
data.filter { !it.infoHash.isNullOrBlank() || !it.magnet.isNullOrBlank() }
```
`sanitize()` retains only streams that have a non-blank `infoHash` or `magnet`. Any stream that is
a direct HTTPS playback URL (no `infoHash`, no `magnet`) is silently dropped. This correctly
handles the MediaFusion/Zilean CW case (which should only return torrent streams), but
`fetchMediaFusionStreams()` and `fetchZileanStreams()` both route through `sanitize()`. If either
addon begins returning direct-URL streams, or if a future caller passes a mixed stream list,
valid streams disappear with no log entry and no `Result.Error` — the caller receives `Success([])`.

**Reproduction path:**
1. Configure an addon that returns direct HTTPS stream URLs without `infoHash`.
2. Call `fetchMediaFusionStreams(url)` — returned list is empty despite valid streams.
3. No error in Logcat; Result wraps an empty success list.

**Correct pattern:** Either rename `sanitize()` to make the drop behaviour explicit, or log the
count of dropped streams at `DEBUG` level so callers can distinguish "no streams from server"
from "streams received but filtered."

---

## Medium Findings

---

### M1 — availabilityCache ConcurrentHashMap Grows Unboundedly

**File:** `data/debrid/repository/UnifiedSourceProvider.kt`  
**Priority:** MEDIUM

`private val availabilityCache = ConcurrentHashMap<String, AvailabilityCacheEntry>()` on a
`@Singleton` instance. Every unique infoHash verified during a session adds one entry. No LRU
eviction, no TTL-based cleanup, no max-size cap. On long sessions with diverse browsing,
this accumulates indefinitely. On low-RAM Fire TV devices (1–2 GB) large hash maps contribute
to memory pressure and GC pauses.

**Reproduction path:** Browse 50+ distinct titles in a session → inspect heap dump → count
`availabilityCache` size vs session duration.

---

### M2 — verifyRealDebridCacheStatuses() Semaphore(1) Serialises All Hash Checks

**File:** `data/debrid/repository/UnifiedSourceProvider.kt`  
**Priority:** MEDIUM

`verifyRealDebridCacheStatuses()` uses `hashes.map { async { semaphore.withPermit { ... } } }`.
The `async` creates concurrent coroutines but the `Semaphore(1)` inside each block allows only
one check to proceed at a time, making verification fully serial. With
`MAX_CACHE_VERIFICATION_HASHES = 10`, this means 10 sequential RD instant-availability API
calls. `RealDebridRateLimiter` already throttles to 1200 ms between requests — a `Semaphore(3)`
would allow 3 concurrent slots while respecting the rate limiter.

**Reproduction path:** Logcat the RD instant-availability endpoint — calls appear
one after another, not overlapping.

---

### M3 — Proxy Detection Hardcoded to elfhosted.com Hostnames Only

**Files:** `data/debrid/repository/DebridPlaybackRepository.kt`, `ui/vod/MovieDetailActivity.kt`  
**Priority:** MEDIUM

`isAddonProxyPlaybackUrl()` and `isAddonProxySource()` match only subdomains of `elfhosted.com`.
Self-hosted addon proxy deployments (StreamThru, custom nginx reverse-proxies, private
MediaFusion instances) are not classified as proxy sources:
- They skip the `isReadyAddonProxyRedirect()` readiness probe (Range-header GET).
- They are treated as direct streams, playing immediately without waiting for proxy-internal resolution.
- The `AddonProxyReadiness` `UNCERTAIN → READY` state machine never fires for them.

`MovieDetailActivity` contains a local copy of `isAddonProxyPlaybackUrl()` with the same blind spot,
meaning the fix would need to be applied in two places.

**Cross-reference:** APP_SUCCESS_PATTERNS #0B (Typed Direct-Proxy Readiness), #52 (Central Direct
Proxy Readiness Policy) recommend a single authoritative source of truth for proxy URL classification.

---

### M4 — allSources/debridSources Split Causes Duplicate Source Fetch in MovieDetailActivity

**File:** `ui/vod/MovieDetailActivity.kt`  
**Priority:** MEDIUM

`loadMovieSources()` populates `allSources` but does **not** populate `debridSources`.
`showDebridSourcePicker()` checks `if (debridSources.isEmpty()) { fetchSources() }`.
Because `debridSources` was never set from `allSources`, a second full source fetch always
fires on the first picker open, even though `allSources` is already populated and ready.

**Reproduction path:**
1. Open a VOD detail screen — sources load (`allSources` populated).
2. Immediately tap "Pick Source."
3. Logcat: a second full source fetch begins — Torrentio, Stremio addons, and MediaFusion
   all re-queried despite the first fetch having just completed.

---

### M5 — refreshMediaFusionCacheStatus() HEAD Checks Not Cancelled on Picker Dismiss

**Files:** `ui/vod/MovieDetailActivity.kt`, `ui/series/SeriesDetailActivity.kt`  
**Priority:** MEDIUM

`refreshMediaFusionCacheStatus()` launches coroutines on `lifecycleScope` (activity scope),
running up to 4 concurrent HEAD probes against cached source URLs. These are not scoped to
the `SourceSelectionBottomSheet`'s lifecycle. If the user dismisses the picker before the
probes complete, the callbacks attempt to call `bottomSheet.updateSource()` on a detached
`Fragment`, which can throw `IllegalStateException` from `Fragment.requireView()`.

The same pattern is present in `SeriesDetailActivity.kt` line 791 — the probe is started
when showing cached sources from `cachedDebridSourcesByEpisode`.

---

### M6 — handleDebridHistoryItem() Resolves Without Episode Context

**File:** `ui/vod/MovieDetailActivity.kt`  
**Priority:** MEDIUM

`handleDebridHistoryItem()` calls the resolver with only the infoHash. No `seasonNumber`,
`episodeNumber`, or `episodeTitle` is passed. `selectFileIds()` in `DebridPlaybackRepository`
uses episode number to choose the correct file inside a series torrent. Without it, the fallback
selects the first or highest-scored file in the torrent, which is the wrong episode for anything
beyond S01E01.

**Reproduction path:**
1. Partially watch a series episode via Debrid (e.g., S02E05).
2. Navigate to the watch history list and resume via history entry.
3. The wrong episode file plays (typically S01E01 or the largest file in the pack).

**Cross-reference:** DEBRID_MODULE_REPORT.md Open Issue B (CW resume intermittent) — this is one
of the contributing causes.

---

### M7 — releaseYearDiscriminator() Returns Null When Title Has No Embedded Year

**File:** `player/stabilized/PlayerHistoryManager.kt`  
**Priority:** MEDIUM

`releaseYearDiscriminator()` extracts a year from the title string via regex. If the title contains
no parenthesised or 4-digit year, it returns `null`. The CW key then uses a null/empty year segment,
making it impossible to distinguish two titles with the same name but different release years
(e.g., "Batman" 1989 vs. "Batman" 2022 share the same CW key — resume position bleeds between them).

**Reproduction path:**
1. Watch 15 minutes of a content item with a plain title (no year in string, e.g., "Batman").
2. Open the same-titled content from a different year.
3. Observe: CW shows the resume position from the first item instead of starting fresh.

**Cross-reference:** DEBRID_MODULE_REPORT.md Open Issue B (CW resume intermittent).

---

### M8 — searchContent() Calls Movies Then Shows Sequentially

**File:** `data/debrid/repository/AddonCatalogRepository.kt` lines 122–124  
**Priority:** MEDIUM

```kotlin
val moviesResult = tmdbRemote.searchMovies(query)
val showsResult = tmdbRemote.searchTvShows(query)
```
These are sequential non-suspending calls awaited one after another. Search results take
`moviesTime + showsTime` instead of `max(moviesTime, showsTime)`. For two 250 ms calls,
search is 250 ms slower than necessary.

**Correct pattern:** `coroutineScope { val m = async { searchMovies(q) }; val s = async { searchTvShows(q) }; m.await() + s.await() }`

---

### M9 — Log.e() Used for Non-Error Paths in getSeriesEpisodeSources()

**File:** `data/debrid/repository/UnifiedSourceProvider.kt` lines ~498, 514, 562, 564, 574, 579, 584, 588  
**Priority:** MEDIUM

Multiple `Log.e()` calls are present in normal, expected code paths within
`getSeriesEpisodeSources()` — e.g., logging that a source list was built, that an addon returned
zero results (expected for some content), or that a particular scraper was skipped. Android's
`Log.e()` is the error level and these should be `Log.d()` or `Log.i()`. The noise makes it
impossible to filter Logcat for real errors during Debrid source fetch.

---

### M10 — fetchDebridSeriesDetail() Fetches Each Season Sequentially

**File:** `ui/series/SeriesDetailActivity.kt` lines 445–454  
**Priority:** MEDIUM

```kotlin
showDetails.seasons?.forEach { season ->
    val seasonResult = tmdbRemoteDataSource.getSeasonDetails(idInt, seasonNumber)
    ...
}
```

Season detail fetches are sequential inside `forEach`. A show with 10 seasons fires 10 sequential
TMDB API calls before the episode list renders. At ~150 ms per call, a 10-season show adds
~1.5 s of loading time that could be reduced to the single-slowest-season latency with
`map { async { getSeasonDetails(...) } }.awaitAll()`.

**Reproduction path:** Open a Debrid series with 8+ seasons — observe the episode list load time
in Logcat vs. a series with 1 season.

---

## Minor Findings

---

### m1 — fetchAndShowDebridSources() Creates New BottomSheet Without Existing-Sheet Guard

**Files:** `ui/series/SeriesDetailActivity.kt`, `ui/vod/MovieDetailActivity.kt`  
**Priority:** MINOR

Every call to `fetchAndShowDebridSources()` (series) and `showDebridSourcePicker()` (movie)
creates a new `SourceSelectionBottomSheet` and calls `.show()` without checking whether one
is already attached and visible. Rapid DPAD-confirm presses (common on Fire TV remotes with
input lag) can stack multiple bottom sheets on the back stack, causing state confusion and
unexpected dismiss behaviour.

**Correct pattern:** Check `supportFragmentManager.findFragmentByTag(SourceSelectionBottomSheet.TAG)`
before showing a new instance.

---

### m2 — isUnsafeIdentity() base32 Rejection Pattern May Over-Reject Valid Episode IDs

**File:** `data/local/WatchedIdentityBuilder.kt`  
**Priority:** MINOR

The 32-character base32 guard in `isUnsafeIdentity()` rejects any 32-character string matching
`[A-Z2-7a-z]{32}`. Some IPTV series episode IDs or content identifiers generated from title
slugs happen to be 32 characters of alphanumeric characters, overlapping with this pattern.
Those identifiers would be rejected, preventing CW record writes for affected content without
any error surfaced to the caller.

---

### m3 — Rate-Limiter Auth Cooldown State Cleared on Force-Quit

**File:** `data/debrid/repository/RealDebridRateLimiter.kt`  
**Priority:** MINOR (acceptable)

All `RealDebridRateLimiter` state is in-process `@Singleton` memory. A force-quit clears all
cooldowns. On restart the app immediately retries RD calls without any backoff — which is the
correct restart behaviour. However, it means auth failure records are ephemeral and
provide no cross-session backoff. Acceptable given how rarely force-quit occurs, but worth
noting in the context of H4.

---

### m4 — DebridResolutionManager Reference in DEBRID_MODULE_REPORT.md is Stale

**Observation:** `DEBRID_MODULE_REPORT.md` lists `player/stabilized/DebridResolutionManager.kt`
as an important file. This file does not exist in the codebase. Its responsibilities appear to
have been absorbed into `DebridPlaybackRepository.kt` and `PlaybackResolver.kt`. The report
entry is stale documentation only; there is no code risk.

---

## DMM Reliability Blockers — Current Status

These map directly to Open Issues B and C from `DEBRID_MODULE_REPORT.md`.

---

### Issue B — CW Resume Intermittently Fails

**Status:** Root cause chain identified; not resolved.

The audit reveals a compound failure chain rather than a single root cause:

1. **M7** — `releaseYearDiscriminator()` returns `null` for titles without an embedded year.
   The CW key becomes non-year-discriminated, making title collision possible and resume position
   bleed across different content with the same name.

2. **m2** — `isUnsafeIdentity()` base32 rejection may silently reject valid 32-character
   episode IDs. When rejected, the CW record is never written; resume can never succeed.

3. **M6** — `handleDebridHistoryItem()` passes no episode context to the resolver.
   For a series torrent, `selectFileIds()` falls back to a heuristic file pick with no episode
   anchor, selecting the wrong file.

4. **C2** — `pickVideoLink()` ascending size tiebreaker selects the smallest file on score ties.
   If the heuristic produces two equally-scored file candidates, the wrong (smaller) one plays.

**Combined effect:** CW resume is reliable only when all of the following are true:
- The title string contains a parseable year.
- The content ID is not 32 chars of base32-compatible characters.
- The history entry carries episode context.
- The file score is unambiguous (no tie on the tiebreaker).

Any one of these conditions failing degrades CW resume silently.

---

### Issue C — Expired/Null expiresAt Stalls After Initial Playback

**Status:** Root cause chain identified; not resolved.

1. **PlaybackResolver.kt** validates that resolved URLs start with `http/https` but does not
   inspect or enforce `expiresAt`. An expired RD link passes URL validation and is passed to
   the player.

2. **H4** — `AUTH_REQUIRED` failure (which includes expired-token cases) is mapped to a 120 s
   rate-limit cooldown in `RealDebridRateLimiter`. The re-auth path is not triggered. The player
   receives a stale link, the player errors, the app retries, hits the 120 s cooldown, and
   the cycle repeats.

3. **PlaybackResolver.kt** has no per-resolve call timeout beyond the inner 60 s poll loop
   (30 attempts × 2 s delay). A stalled resolution occupies a coroutine for up to 60 s before
   surfacing a failure.

4. `handleDebridFailure()` in `DebridPlaybackRepository` logs the failure type detail but
   returns a generic `ResolutionResult.Failure` to the caller. The caller (`PlaybackResolver`,
   `PlayerActivity`) cannot distinguish an expired-link failure from a network timeout or a
   not-cached result — no specific "link expired, force re-resolve" signal exists.

**Combined effect:** An expired RD link stalls playback for up to 60 s, surfaces a generic error
with no user-visible "link expired" message, and does not trigger re-auth. The user's only
recovery is to manually restart playback.

**Phase 1 cross-reference:** Phase 1 finding `PA-09` (PlayerActivity generic error handling)
notes that the player cannot distinguish transient from terminal errors at the UI layer. The
expired-link signal gap at the Debrid layer is the upstream cause — it cannot be fixed at the
player layer alone.

---

## Cross-References to Phase 1 Findings

| Phase 3 Finding | Phase 1 Finding | Shared Root |
|-----------------|-----------------|-------------|
| Issue C (expired link stall) | PA-09 (generic error surface) | No typed failure signal crosses the Debrid→Player boundary |
| H3 (label-string routing) | PA-03 (intent extra fragility) | Source type classification is text-based end-to-end |
| H4 (auth cooldown) | PA-07 (no re-auth prompt) | Auth failure handling is decoupled across layers |
| C2 (file tiebreaker) | PA-05 (quality regression on resume) | File selection quality affects the resume track |

---

## Patterns from APP_SUCCESS_PATTERNS.md — Compliance Status

| Pattern | ID | Status |
|---------|----|--------|
| Multi-Strike Stall Recovery | #0A | Not visible in Debrid poll loop (generic failure after 60 s) |
| Typed Direct-Proxy Readiness | #0B | Partially applied — elfhosted.com only (M3) |
| Debrid Re-Resolution Metadata | #6 | Not carried through history resume path (M6) |
| Authoritative Debrid Cache Confidence | #8 | Applied in SourceFilterUtils; bypassed by H3 label routing |
| Typed Terminal Failures | #10 | AUTH_REQUIRED conflated with rate-limit (H4) |
| Native Stremio Manifest Source Path | #14 | Manifest not cached (H1) |
| Direct Debrid Playback Identity Guard | #15 | elfhosted.com only (M3) |
| Direct Debrid Source Profile Continuity | #16 | Broken by label-string routing (H3) |
| Central Direct Proxy Readiness Policy | #52 | Two local copies (MovieDetailActivity + DebridPlaybackRepository) |

---

## Observability Gaps

- `sanitize()` drops streams with no log entry (H5) — silent data loss.
- `pickVideoLink()` does not log which file won and why (C2) — no audit trail.
- Auth vs. rate-limit failure is indistinguishable in user-visible state (H4).
- CW key rejection in `isUnsafeIdentity()` is silent — no `DEBUG` log when a key is rejected (m2).
- `handleDebridFailure()` swallows failure type detail before returning to caller — typed failure
  signals stop at the repository layer.

---

## Not Audited / Out of Scope

- `PlayerActivity` internals — covered by Phase 1; cross-referenced above where root cause lives there.
- DMM implementation — no DMM code exists in codebase; blocked on open Issues B and C above.
- `DebridDiscoverActivity` / `DebridDiscoverViewModel` — Discover UI, not part of resolution chain.
- TMDB external ID resolution flow (`TmdbRemoteDataSource.getExternalIds()`) — no anomalies
  observed in the files read; not a reported problem area.

---

*End of AUDIT_PHASE3_DEBRID.md — Read-only audit. No code was changed.*
