# App Failed Patterns

Patterns that have caused bugs, crashes, or poor UX in this project.

## 1. Hardcoded UI Offsets
- **Avoid:** `android:layout_marginStart="144dp"` to align an element with a grid that starts "somewhere over there".
- **Reason:** Breaks on different screen sizes (720p vs 1080p vs 4K) and dynamic sidebar widths.

## 2. Premature Empty State
- **Avoid:** Checking `itemCount == 0` immediately when a category is selected.
- **Reason:** Paging data takes time to invalidate and fetch from the database. This leads to a "No series available" flash before cards appear.
- **Fix:** Always check `!viewModel.isSwitchingCategory` before showing the empty state.

## 3. Adapter-Driven UI Updates
- **Avoid:** Updating Fragment-level views (like Headers or Backdrops) from inside `onBindViewHolder`.
- **Reason:** Tight coupling and inconsistent updates during fast scrolling or view recycling.
- **Fix:** Use `addOnChildAttachStateChangeListener` on the RecyclerView in the Fragment.

## 4. Unconstrained Shimmer Animations
- **Avoid:** Running infinite animations on views that might be `GONE` or hidden via `alpha=0` but still attached to the window.
- **Reason:** Unnecessary CPU/GPU usage on low-end TV devices.
- **Fix:** Always call `clearAnimation()` when hiding a loading view.
## 5. Duplicate Playback Activities
- **Avoid:** Creating `DebridPlayerActivity` vs `IptvPlayerActivity`.
- **Reason:** Leads to fragmented player logic, mismatched key behaviors, and maintenance overhead for common overlays (subtitles, audio tracks).
- **Fix:** Use a single `PlayerActivity` with source-aware controllers.

## 6. Sensitive Stream Logging
- **Avoid:** Logging direct stream URLs, Debrid provider URLs, magnets, access tokens, unrestricted download URLs, or full serialized watch history.
- **Reason:** Debrid links and tokens are sensitive and can leak through logcat, QA artifacts, crash reports, or shared build output.
- **Fix:** Use `SensitiveLogRedactor` and redacted structured logging that records source type, provider, cache status, and presence/absence of metadata without printing the secret value. Keep OkHttp BODY/BASIC logging disabled for token-bearing Debrid/addon/TMDB clients.

## 7. Cosmetic Provider Support
- **Avoid:** Treating provider badges or labels as proof that a Debrid provider is implemented.
- **Reason:** Users and future agents may assume Real-Debrid, AllDebrid, Premiumize, and Stremio parity exists when only one provider has real auth/API/resolver support.
- **Fix:** Keep provider claims tied to implemented auth, source fetch, cache verification, playback resolution, and QA evidence.

## 8. Label-Inferred Cache State
- **Avoid:** Marking sources cached because text contains `RD+`, `cached`, `instant`, provider names, or similar labels.
- **Reason:** Provider labels are hints, not proof. Treating them as authoritative breaks cached-only filters and creates false Real-Debrid readiness claims.
- **Fix:** Use provider API availability checks or actual playback readiness, and expose unknown cache state separately.

## 9. Retrying Provider-Blocked Debrid Failures
- **Avoid:** Retrying or auto-nexting aggressively after Real-Debrid legal/copyright blocks or HTTP `429` rate limits.
- **Reason:** Legal/copyright failures are terminal for that source, and rate limits can worsen when repeated add-magnet/unrestrict calls continue immediately.
- **Fix:** Classify Debrid failures by type, stop retries for terminal failures, apply cooldown for `429`, and show stable user-facing messages instead of raw backend errors.

## 10. Buffering/Pre-processing Huge XML in Memory
- **Avoid:** Using `readLine()` or regex replaces on large XMLTV strings to sanitize declarations or HTML.
- **Reason:** XMLTV documents can exceed 100MB, and pre-processing them as strings easily causes OOM crashes on RAM-constrained TV hardware.
- **Fix:** Use a streaming reader/parser that sanitizes characters on-the-fly.

## 11. Ignoring EPG Timezone Suffixes
- **Avoid:** Parsing XMLTV timestamps using UTC calendars without evaluating and subtracting local offset suffixes (e.g. `+0200`).
- **Reason:** Causes program guide data to be shifted, showing incorrect listings.
- **Fix:** Extract and subtract timezone offsets to normalize times back to UTC.

## 12. Stale Add-on Config For Regional Debrid Sources
- **Avoid:** Hardcoding old add-on config keys, tiny limits, English-biased provider lists, or source ordering that lets English-only results consume all Real-Debrid verification calls.
- **Reason:** Regional sources can exist but never surface as playable candidates, making Hindi/German playback look broken even when add-ons can return candidates.
- **Fix:** Verify current add-on config syntax, pass language priority where supported, include relevant built-in registries, and rank target-language/multi-audio sources before capped availability checks.

## 13. Mixing Stremio Manifests With Registry Scrapers
- **Avoid:** Storing Stremio `manifest.json` URLs in the old scraper registry list or feeding raw manifests into registry-definition fetchers.
- **Reason:** A Stremio manifest describes addon resources; it is not the same shape as DebridXtream JSON registry definitions. Mixing them creates duplicate searches, failed parsing, and confusing source management.
- **Fix:** Keep separate Stremio manifest URL storage, parse manifests natively, and remove legacy scraper UI only after the native Stremio path is proven by build and device QA.

## 14. Routing Direct Addon URLs Through App Debrid Auth
- **Avoid:** Launching direct Stremio/AIOStreams HTTP playback URLs with `PlaybackSource.DEBRID` metadata or sending them through app-side Real-Debrid auth checks.
- **Reason:** Direct/proxied addon playback URLs may already include provider configuration in the addon URL. App-side Debrid re-resolution can show false Real-Debrid configuration missing errors.
- **Fix:** Direct addon URLs should play as direct sources with headers preserved. Only magnet, infoHash, torrent files, or non-direct unrestrict links should enter app-side Real-Debrid resolver/auth flow.

## 15. Downgrading Direct Debrid Playback To IPTV
- **Avoid:** Using `PlaybackSource.IPTV` or a null playback source for direct Stremio/AIOStreams Debrid playback just to avoid Real-Debrid re-resolution.
- **Reason:** The player then shows IPTV in controls/history and can enter IPTV episode playlist/API paths, causing misleading labels and possible `API exception please try again` errors.
- **Fix:** Keep `PlaybackSource.DEBRID` for source identity and add a separate direct-play guard that disables Debrid resolver/API work for direct addon URLs.

