# Debrid Phase 3 Playback Error Audit

## Scope
Read-only swarm audit of user-reported Real-Debrid playback errors after Phase 2 cache confidence work.

## User-Reported Symptoms
- Cache confidence labels appear and the rest of the flow is mostly okay.
- Some playback fails with: `File was removed from debrid service due to copyright`.
- Some `UNKNOWN` sources fail with content unavailable / legal restriction messaging.
- Some sources fail at add-magnet with HTTP `429`.

## Swarm Agents
- `Kant`: Real-Debrid resolver/API/error-path audit.
- `Rawls`: source/cache/provider flow audit.
- `Carson`: QA/UX/log/error-message audit.

## External Context
- Real-Debrid official API documentation states API errors return HTTP 4XX/5XX with a JSON error object and documents a global API limit of 250 requests per minute; refused requests return HTTP `429` and count toward the limit.
- Recent public reporting around May 2026 describes Real-Debrid returning `File was removed from debrid service due to copyright infringement` / legal-restriction messages for many cached torrents, especially based on filename/release keyword filtering. This supports treating these as provider-side availability/policy failures, not local player crashes.

## Findings

### 1. `VERIFIED` Does Not Mean Guaranteed Playable
`VERIFIED_CACHED` currently means Real-Debrid instant availability returned cached data for the hash. Playback still later runs add-magnet, torrent info polling, file selection, and unrestrict. Those later stages can fail because of legal filtering, removed files, missing file selection, stale links, or account/API limits.

Relevant code:
- `RealDebridRemoteDataSource.getInstantAvailability`
- `UnifiedSourceProvider.verifyRealDebridCacheStatuses`
- `DebridPlaybackRepository.resolveDebridUrl`

### 2. Copyright / Legal Restriction Is Mostly Real-Debrid-Origin
The app already special-cases `451` in add-magnet and unrestrict paths, but it does not parse Real-Debrid error JSON or classify all copyright/legal wording. If Real-Debrid returns a copyright message after unrestrict or during actual download URL playback, `PlayerActivity` sees it as a generic Media3 HTTP/playback failure.

Current behavior:
- Add-magnet `451` becomes `Content Unavailable (Legal Restriction)`.
- Unrestrict `451` becomes `Content Unavailable (Legal Restriction)`.
- Other copyright/removal wording can surface as raw backend/player text.

### 3. HTTP 429 Is Rate Limiting And The App Can Amplify It
HTTP `429` comes from Real-Debrid `torrents/addMagnet` or other API calls. Current code does not classify it, does not respect `Retry-After`, and retries the whole Debrid resolution twice. Re-add-after-delete can add more pressure.

Risk:
- A single bad source can cause multiple API calls.
- Auto-next behavior can cascade through several sources and hit more add-magnet calls.
- Rate-limited failures should not be retried immediately.

### 4. `UNKNOWN` Sources Are Working As Designed But Need Better UX
`UNKNOWN` means cache/playback confidence could not be verified. It is intentionally selectable outside cached-only mode. These sources can fail with legal restriction, unavailable content, not cached, or rate limit.

Problem:
- Users can interpret `UNKNOWN` as a neutral label instead of a warning.
- Normal sorting still allows `UNKNOWN` sources after verified/direct choices.

### 5. MediaFusion Can Be Over-Promoted To `DIRECT_STREAM`
Any MediaFusion URL is initially treated as direct stream confidence before durable readiness is confirmed. Readiness updates can later demote it, but a user can still encounter provider-side legal or unavailable responses during actual playback.

### 6. Error UX Is Raw And Not Actionable Enough
Movie, Series, and Player paths mostly show raw resolver/player messages through Toasts. The app needs stable, user-readable messages and clear retry/next-source behavior.

## Root Causes
- Provider-side Real-Debrid copyright/legal filtering.
- Real-Debrid API rate limiting (`429`).
- App uses cache availability as source confidence, but does not yet separate `cached on RD` from `resolved playable`.
- App retries non-retryable failures and can auto-next through account-wide/rate-limit failures.
- Real-Debrid error response model exists but is not used for classification.

## Recommended Fix Plan

### Phase 3A: Typed Debrid Failure Classification
Add a typed failure model near Debrid playback/remote layer:
- `COPYRIGHT_BLOCKED`
- `LEGAL_RESTRICTION`
- `RATE_LIMITED`
- `AUTH_REQUIRED`
- `NOT_CACHED`
- `UNAVAILABLE`
- `NETWORK`
- `UNKNOWN`

Parse Retrofit `HttpException` status, headers, and Real-Debrid JSON error body. Use existing `RealDebridErrorResponse`.

### Phase 3B: Retry And Cooldown Rules
- Do not retry `451`, copyright, legal restriction, or permanent unavailable failures.
- Do not retry `429` immediately.
- Respect `Retry-After` where present.
- Add a short in-memory cooldown per account/source/hash for rate-limited and terminal failures.
- Prevent auto-next from cascading after account-wide or rate-limit failures.

### Phase 3C: Better Source State
Separate:
- `VERIFIED_CACHED`: hash exists in Real-Debrid cache.
- `READY`: selected source has resolved to a playable URL in this session.
- `UNKNOWN`: not verified; may fail.
- `BLOCKED` / `RATE_LIMITED`: terminal or cooldown state after failed attempt.

### Phase 3D: UX Copy
Replace raw messages with stable TV-friendly messages:
- `This source is blocked by Real-Debrid. Try another source.`
- `Real-Debrid is rate limiting requests. Wait a few minutes before trying again.`
- `This source is not verified on Real-Debrid and may not play. Try a VERIFIED source.`

### Phase 3E: Reduce Add-Magnet Calls
- Before adding a magnet, check/reuse existing Real-Debrid torrent for the same hash where feasible.
- Avoid delete/re-add unless episode selection genuinely requires it.
- Keep per-hash temporary failure cache to avoid repeatedly adding the same blocked source.

## Final Status
PARTIAL  The reported issues are understood. Most are provider-side or rate-limit conditions, but the app needs typed error classification, retry/cooldown rules, source failure state, and clearer UX before claiming robust Stremio-like handling.
