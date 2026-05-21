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

## 16. Trusting Expired Direct Addon URLs
- **Avoid:** Treating a saved direct Stremio/AIOStreams playback URL as durable Continue Watching state.
- **Reason:** Direct/proxied addon URLs can expire or be revoked, causing resume failures even though the same title still works after a fresh addon lookup.
- **Fix:** Store stable content ids and selected source profile metadata, then re-fetch sources and choose the closest matching provider/language/source before playback.

## 17. Breaking Companion Payloads With Schema Changes
- **Avoid:** Replacing companion config keys/shape without backward compatibility.
- **Reason:** Users get stuck with “companion config purani” where the web sends old keys and the TV expects new ones (or vice versa).
- **Fix:** Keep additive fields with defaults, accept legacy names, and introduce a `schemaVersion` to make transitions explicit.


## 18. Creating A Second Companion Path For A Schema Refresh
- **Avoid:** Adding a parallel companion page, duplicate pairing route, or new TV-side config screen just to support Stremio addon URLs.
- **Reason:** Duplicate surfaces split maintenance, confuse users, and make it easy to break the existing working flow.
- **Fix:** Update the active companion route in place, keep one Firestore/device-code payload contract, and repurpose the existing TV-side screen instead of spawning a second flow.

## 19. Leaving Legacy RD Labels As The Primary Live Settings Copy
- **Avoid:** Keeping `Real-Debrid` and `MediaFusion` as the first thing shown on the active settings screen after moving Stremio addon URLs to the new primary path.
- **Reason:** The app looks unchanged after reinstall, even though the underlying schema has moved forward.
- **Fix:** Label the live settings surface as `Stremio Addons`, keep the RD auth path as explicit legacy fallback, and remove MediaFusion wording from the visible copy.

## 20. Mixed Companion Payload Emission
- **Avoid:** Having the web companion emit both the new canonical Stremio-first payload and the old top-level RD/MediaFusion fields by default.
- **Reason:** It keeps the contract ambiguous and can reintroduce legacy shape drift even when the visible UI is already cleaned up.
- **Fix:** Emit one canonical payload from the web dashboard and reserve legacy field names only for backward-compatible parsers.

## 21. Visible Legacy API-Key Auth
- **Avoid:** Leaving manual Real-Debrid API-key entry visible on the main Debrid auth surface after the device-code/Stremio-first flow is in place.
- **Reason:** It makes the deprecated path look primary and causes fresh installs to read as old after the companion refresh.
- **Fix:** Hide the manual API-key affordance by default and keep only the device-code auth path visible.

## 22. Redirect-Only Companion Entry
- **Avoid:** Leaving `/setup` as a bridge page that immediately redirects to the real form.
- **Reason:** Users land on the companion link and do not see any visible change, which makes it look like the update did not work.
- **Fix:** Route the opening entrypoint directly to the config form so the actual IPTV and Stremio fields are visible immediately.

## 23. Serializing Blank Optional IPTV Data
- **Avoid:** Writing an `iptv` object with undefined values when the user leaves IPTV blank.
- **Reason:** Optional fields should stay out of the Firestore payload entirely when they are not being used.
- **Fix:** Build the payload conditionally and omit IPTV unless all three IPTV fields are present and verified.

## 24. Duplicate Companion Entrypoints
- **Avoid:** Leaving both `/setup` and `/config` as active same-screen companion routes alongside a separate root landing page.
- **Reason:** It makes the update look inconsistent and leaves users with multiple URLs that appear to be the same page.
- **Fix:** Pick one canonical route, redirect the old ones, and keep only one visible companion surface.

## 25. Live Loading-State Drift
- **Avoid:** Clearing LiveTV `isLoadingChannels` inside the ViewModel before Paging has actually settled.
- **Reason:** The UI can flicker between loading and loaded states even though the adapter is still working.
- **Fix:** Drive loading state from Paging3 refresh load state and only treat the ViewModel flag as a mirror of that source.

## 26. Hardcoded Live `.ts` URLs
- **Avoid:** Rebuilding live stream URLs with `.../$id.ts` in every playback entrypoint.
- **Reason:** Some providers expose different container extensions and hardcoding `.ts` makes those paths brittle.
- **Fix:** Use the shared live URL helper and preserve `container_extension` when it exists.

## 27. Snapshot-Only Live HandOff
- **Avoid:** Passing fullscreen live channel IDs from the visible adapter snapshot only.
- **Reason:** Large live categories may have more channels cached than are currently visible, so the handoff list can be incomplete.
- **Fix:** Prefer cached live streams for the selected category, then merge with the adapter snapshot as a fallback.

## 28. History-As-Return-State
- **Avoid:** Restoring the caller UI from watch-history storage after a player screen exits.
- **Reason:** History writes depend on Activity lifecycle timing and can lag behind the actual user action, especially after fullscreen zapping.
- **Fix:** Use an explicit Activity Result payload for immediate caller state, and keep history only as persistent fallback.

## 29. RecyclerView Container Focus Requests
- **Avoid:** Calling `requestFocus()` on a RecyclerView that is configured with `isFocusable = false`.
- **Reason:** Android TV focus will often fall back to the previous focusable area, which looks like list focus jumping back to categories.
- **Fix:** Request focus on a laid-out child item view after scrolling to the desired adapter position.

## 30. Native Focus Search During Rapid Paged List Scroll
- **Avoid:** Leaving rapid DPAD_UP/DOWN inside a paged TV list entirely to Android's native focus-search.
- **Reason:** During fast repeats, the next ViewHolder may not be laid out yet, so focus can escape to side rails or preview buttons.
- **Fix:** Handle UP/DOWN on the focused item, move by adapter position, and keep a pending target while scroll/layout catches up.

## 31. Empty EPG Cached As Fresh Data
- **Avoid:** Caching `(null, null)` guide responses as if they were a valid EPG hit.
- **Reason:** The app can hide later XMLTV syncs or short-provider refreshes for several minutes and leave the preview stuck on "guide unavailable."
- **Fix:** Only cache positive current/next results and let empty lookups retry on the next visible refresh or warmup pass.

## 32. Double-Starting Manual EPG Sync
- **Avoid:** Calling both WorkManager immediate sync and a direct repository EPG sync from the same settings action.
- **Reason:** The two jobs can race through the same parser/database path and crash or corrupt the visible sync state.
- **Fix:** Use one manual sync path, keep scheduled sync separate, and add a repository-level single-flight guard for EPG fetches.

## 33. Mismatched Code and XML Visibilities
- **Avoid:** Registering Kotlin click listeners or bindings on layout elements that are configured as completely hidden (e.g. `visibility="gone"` and `0dp` dimensions).
- **Reason:** Creates dead or unreachable functional routes that look active in the source code but are disabled in the UI.
- **Fix:** Always verify that elements bound to click handlers are visible, focusable, and properly sized in the layout configurations.

## 34. Guessing Next Episode Continuity
- **Avoid:** Advancing a series player by assuming `currentEpisode + 1` or exposing `Next` before `SeriesPlaylistState` confirms the next entry exists.
- **Reason:** The controller can show an action that does nothing or resolve the wrong episode when the playlist is incomplete or still loading.
- **Fix:** Gate the control on `SeriesPlaylistState.hasNext`, and use the resolved next item from playlist state instead of a guessed episode number.

## 35. Restyling A Non-Runtime Copy
- **Avoid:** Editing documentation-only design notes or a guessed duplicate layout when the actual runtime path is a single XML controller file.
- **Reason:** The APK can build successfully and still look unchanged on device if the wrong file is edited.
- **Fix:** Audit `AndroidManifest.xml`, the host activity, and the controller/layout include chain first; then change the one file that is actually inflated.

