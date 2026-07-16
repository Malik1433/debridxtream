# Debrid "Empty Sources" Audit — 2026-07-16

**Symptom (owner):** When the user picks a debrid link/source from the source list, MANY of the
selected links come up **empty** (no playable stream / spinner then nothing / bounced back).

**Method:** Diagnose-only. 4 parallel investigators across the pipeline: (A) source-list
construction, (B) resolution path, (C) addon/scraper fetchers, (D) UI-selection → player wiring.
**No code was changed.**

---

## The through-line (one root cause, four amplifiers)

Every lane converged on the same story:

> The picker offers **uncached torrents that look playable**, cache verification is **broken/partial**
> so nothing filters them out, selecting one **fails slowly (~1–2 min)**, and the failure is
> **silent / dead-ends** instead of falling through to a cached source with a clear message.

To the user this reads as "I keep picking sources and they're empty."

---

## ROOT CAUSE — cache status is unreliable, uncached sources are shown as selectable

### R1 (P0) — RealDebrid `instantAvailability` endpoint is deprecated → cache check is effectively blind
- `RealDebridApiService.kt:69` `GET rest/1.0/torrents/instantAvailability/{hash}`; parse at
  `RealDebridRemoteDataSource.kt:186-195`.
- RD deprecated this endpoint (2024); it now generally returns `{}`. `parseInstantAvailability`
  reads "any items present" → against `{}` → **false → NOT_CACHED for genuinely cached torrents**;
  a 404 throws → UNKNOWN. So the cache badge/sort is untrustworthy at the source.
- **CONFIDENCE: high but should be confirmed with one live `curl`** against the account before we
  build a fix on it.

### R2 (P0) — Only the first 10 hashes are cache-verified; the rest are shown as UNKNOWN and selectable
- Cap `MAX_CACHE_VERIFICATION_HASHES = 10` (`UnifiedSourceProvider.kt:73`), applied `:1457`.
- Torrentio alone requests `limit=50` (`SimplifiedPureFireFetcher.kt:40`) + MediaFusion + Stremio +
  dynamic. With 30–50+ streams, hashes 11+ fall to `DebridCacheStatus.UNKNOWN` (`:1542-1545`) and
  are shown as normal rows. On any verify timeout the whole map is dropped → **every** row UNKNOWN
  (`:1473-1477`).

### R3 (P1) — No fetcher requests "cached-only"; the addon's own cache signal is discarded
- Normalized `AddonStream` has **no `cached` field** (`AddonModels.kt:132-146`). `resolveCacheStatus`
  reads `extras["cached"]` (`UnifiedSourceProvider.kt:1542`) but **no fetcher populates it**.
- Torrentio attaches the RD token only if one is cached at fetch time
  (`SimplifiedPureFireFetcher.kt:238-241`); if the auth cache is cold it runs public → **raw magnets
  for everything**, none instantly resolvable.

### R4 (P1) — Any "direct-looking" URL is blindly labeled `DIRECT_STREAM` = cached, unverified
- `resolveCacheStatus:1528-1537` + `isDirectStreamUrl:1563-1583`: a stream is marked cached purely
  because its URL is a MediaFusion URL or contains `/stream/ /play/ /playback/ /stremio/`.
- Those resolver URLs only 302 to a file **if the torrent is cached on the user's own RD account**;
  otherwise they empty. There is a later async readiness probe
  (`MovieDetailActivity.kt:1420-1465`) that flips bad rows to NOT_CACHED, but it runs *after* the
  list is shown and only covers `mediafusion.elfhosted` / `aiostreams.elfhosted`.

### R5 (P2) — Default picker never hides NOT_CACHED / UNKNOWN
- `SourceFilterUtils.filter()` removes non-cached **only** when the user taps the "RD Cached" chip
  (`SourceFilterUtils.kt:164`); otherwise it just scores them lower (`getCachePriority` UNKNOWN=1,
  NOT_CACHED=0, `:227-234`). Ranking sorts by language/quality/seeders, not cache — plenty of
  uncached rows sit high.

---

## AMPLIFIER 1 — selecting an uncached/dead source fails SLOWLY

### A1 (P0) — No cache pre-check before `addMagnet`; poll ~63s, then retried to ~126s
- `DebridPlaybackRepository.resolveDebridUrl()` never calls instant-availability; it `addMagnet`s and
  polls `getTorrentInfo` up to `maxAttempts = 18` ≈ **63.5s** (`:422-501`, delays `:518-519`), then
  `Error("Torrent not ready after 18 attempts")` (`:499-501`).
- That message → `DebridFailureType.UNKNOWN` (`DebridFailure.kt:101-106`) → `retryable = true`
  (`:23`) → `PlaybackResolver`'s `for (attempt in 1..2)` runs the whole cycle **again** (~126s total)
  (`PlaybackResolver.kt:61,104`).

### A2 (P1) — Torrent with no whitelisted video file stalls until timeout
- `selectFileIds` returns empty when nothing matches the extension whitelist
  (`.mkv/.mp4/.avi/.mov/.ts/.m2ts/.wmv/.flv/.webm`) (`:593-619,723-735`) → `selectFiles` never
  called (`:475`) → status stuck `waiting_files_selection` → times out. (`.m4v` is accepted by
  `isDirectStreamUrl` but NOT by `isVideoFile` — inconsistent.)

### A3 (P1) — Series: `pickVideoLink` returns null even when a playable file exists
- Multi-file torrent + episode hint: if no file scores > 0 (unusual naming, absolute numbering,
  non-`SxxEyy`), `:649-653` returns null → delete+re-add once → still null →
  `Error("Desired episode not found in torrent after re-adding")` (`:469`). No "fall back to largest
  video file" for the hinted multi-file case.

### A4 (P1) — A blank `download` from unrestrict is treated as Success and cached for 15 min
- `DebridPlaybackRepository.kt:528` guards only **null**, not blank: `download: ""` → `Success("")`
  → cached `resolvedLinkCache[key] = CachedResolution("", now+15min)` (`:509-511`). `PlaybackResolver`
  rejects the blank (good), but the poisoned `""` is served instantly for 15 min → repeat empties.

---

## AMPLIFIER 2 — the failure is SILENT / dead-ends (no fallthrough, no message)

### B1 (P0) — `UNKNOWN` / `RATE_LIMITED` failures do NOT auto-advance to the next source
- `shouldTryNextDebridSource()` returns **false** for `UNKNOWN, RATE_LIMITED, AUTH_REQUIRED,
  NETWORK`; only `COPYRIGHT_BLOCKED / LEGAL_RESTRICTION / NOT_CACHED / UNAVAILABLE` auto-try next
  (`MovieDetailActivity.kt:1699-1711`, mirrored in Series/VM). The two most common failures
  ("Torrent not ready…", generic unrestrict error) are UNKNOWN → **dead-end**, no fallback.

### B2 (P1) — Terminal failure silently bounces to a fresh detail screen with NO feedback
- `handleTerminalPlaybackFailure` → `redirectToFailureDetail` first (`PlayerActivity.kt:3663-3720`);
  for the 9 default call sites it `startActivity(detail, EXTRA_OPENED_FROM_PLAYBACK_FAILURE=true)` +
  `finish()`. But `MovieDetailActivity.onCreate:247` only *stores* the flag; it's consulted only in
  the `playerLauncher` result callback (`:206`), which does **not** run on a fresh `startActivity`.
  → No toast, no banner, no reopened picker. Player just closes and the detail page reappears.

### B3 (P1) — One 429 blocks EVERY source for ≥120s
- `RealDebridRateLimiter` sets a **global** cooldown `coerceAtLeast(120s)` on any rate-limit, even if
  `Retry-After` says 5s (`:55-75,88`). During cooldown every `resolveDebridUrl` returns
  `RATE_LIMITED` (`:396-399`) which (B1) doesn't auto-next → all selections empty for ≥2 min.

### B4 (P1) — Resolution ("Loading") has no UI watchdog/timeout
- The playback watchdog (12–25s) is armed only inside `initializePlayer` / `performSeamlessSwitch`
  (after a URL exists). The VM resolve methods run under `withContext(Dispatchers.IO)` with **no
  `withTimeoutOrNull`** (`PlayerViewModel.kt:561-604,446-501,839-889`). A hung addon/RD poll leaves
  the cinematic loader spinning with no error/cancel/exit (bounded only by OkHttp socket timeouts).

### B5 (P1) — `filterMismatchedAddonMovieStreams` misparses a year inside the title → can empty the list
- `MOVIE_YEAR_REGEX.find()` takes the **first** `19xx/20xx` token as the release year
  (`UnifiedSourceProvider.kt:78-110`). "Blade Runner **2049**" (yearHint 2017) → candidateYear 2049 ≠
  2017 → every stream dropped → picker empty. Hits any title containing a year-like number (2012,
  1917, 2001, Blade Runner 2049…). Only when a non-null yearHint is passed.

### B6 (P2) — Direct-HTTP passthrough sources skip resolution AND readiness for most hosts
- `playDebridMovie` `isDirectHttp` branch launches with `directDebridPlayback=true` after only
  `requiresDirectProxyReadinessCheck`, gated to `mediafusion.elfhosted` / `aiostreams.elfhosted`
  (`MovieDetailActivity.kt:1493-1496,1511-1604`). A dead direct-http source from any other provider
  launches straight into ExoPlayer, fails, and hits B2's silent redirect.

### B7 (P2) — `notifyDebridFailure` returns early on a blank reason → zero feedback
- `MovieDetailActivity.kt:1395-1396` / `SeriesDetailActivity.kt:1763-1764`:
  `if (reason.isNullOrBlank()) return`.

---

## Lower-severity / hardening
- **P2** over-permissive infoHash/magnet admission (`normalizeInfoHash` accepts any ≥32 chars,
  `UnifiedSourceProvider.kt:1585-1598`) → malformed magnets enter the list, unresolvable on select.
- **P2** `pickVideoLink` assumes RD `links[]` index aligns with filtered `selectedFiles` order
  (`DebridPlaybackRepository.kt:630-651`) → wrong file if order diverges (plays wrong, not empty).
- **P2** cache-status TTL 15 min (`UnifiedSourceProvider.kt:74`) → a torrent evicted within 15 min
  still shows cached.
- **P2** MediaFusion failover returns on first non-throwing 200 "even if empty"
  (`MediaFusionFetcher.kt:51-84`) → a stale user MediaFusion URL short-circuits the cascade; fallback
  bases are called without the required config path (`:30-31`).
- **P2** hardcoded GitHub-raw registries / addon hosts can 404 silently → fewer sources
  (`DebridPreferences.kt:144-145`, `AddonCatalogRepository.kt:346`, `SimplifiedPureFireFetcher.kt:35`,
  `MediaFusionFetcher.kt:30-31`).

---

## Verified NOT broken (ruled out — don't chase these)
- A literal blank/`""` URL reaching ExoPlayer is **guarded**: `PlaybackResolver.kt:78-88` requires
  non-blank + `http(s)`; cold-launch guard `PlayerActivity.kt:766-770`.
- `Loading` always terminates in VM control flow (empty list → `Error("No sources found…")`); only an
  un-timed network hang leaves it dangling (B4).
- Fetchers do **not** inject placeholder/empty streams on timeout — each returns `emptyList()`.
- Dedup does **not** drop the resolvable copy (magnet vs direct keyed separately; fileIdx in key).
- Entries with no url AND no magnet AND no infoHash are filtered before the picker (`hasValidSource`
  gate `:951-956`); addon error-marker pseudo-streams are dropped (`:907-939`).
- TorBox never returns a stream URL to resolve, so it isn't a source of empty RD URLs.
- Addon self-declared `cached=true` does not create a false badge (downgraded to UNKNOWN).

---

## Priority fix order (for the fix pass — NOT done here)
1. **Stop offering uncached sources as if playable.** Either (a) replace the dead
   `instantAvailability` with a working availability signal / the addons' own cache markers wired
   through `extras["cached"]`, and/or (b) hide (or clearly badge + push below a divider)
   NOT_CACHED/UNKNOWN by default, and (c) lift/rethink the 10-hash cap or verify lazily on selection.
2. **Fail fast + fall through.** Add a cache pre-check before `addMagnet`; make the "not ready"
   timeout classify as terminal-for-this-source and **auto-advance** (fix `shouldTryNextDebridSource`
   for the not-ready case), so a bad pick silently rolls to the next cached source.
3. **Always tell the user.** Fix B2 silent redirect → show an error/toast and reopen the picker;
   surface "not cached / caching…" state; a resolution watchdog (B4).
4. **Correctness bugs:** blank-download-cached-as-success (A4), year-in-title misparse (B5), no-video
   file stall (A2), series episode-match null fallback (A3), global 120s rate-limit floor (B3).

**First confirm R1** (deprecated endpoint) with one live curl before building on it.
