# App Success Patterns

Established engineering and UI patterns that have proven stable and performant in the DebridXtream codebase.

## 1. Persistent Grid Pattern
- **Definition:** Keep the current RecyclerView content visible (dimmed) while a background sync or category refresh is happening.
- **Benefits:** Prevents "empty screen flicker" and maintains focus context for the user.
- **Implementation:** Set `rv.alpha = 0.4f` and show a centered `ProgressBar` overlay.

## 2. Shared Coordinate Space (Overlays)
- **Definition:** Place loading indicators, empty states, and content grids inside the same `FrameLayout` or `ConstraintLayout` container.
- **Benefits:** Guarantees that overlays are centered relative to the content area, ignoring sidebars or headers.
- **Implementation:** 
    ```xml
    <FrameLayout>
        <RecyclerView android:id="@+id/grid" />
        <LinearLayout android:id="@+id/loading" android:layout_gravity="center" />
    </FrameLayout>
    ```

## 3. Atomic Category Switching
- **Definition:** Using a `isSwitchingCategory` flag in the UI State combined with a small delay before clearing the loading state.
- **Benefits:** Synchronizes the slow emission of Room/Paging3 data with the rapid state change of a ViewModel.
- **Implementation:** Use `kotlinx.coroutines.delay(1000)` after a successful repository fetch before setting `isLoading = false`.

## 4. Lumina Sidebar Overlay
- **Definition:** Sidebar expands over the content area rather than pushing it.
- **Benefits:** Maintains grid item size and aspect ratio during focus transitions.
- **Implementation:** Sidebar uses high elevation (`20dp`) and animates its `layout_width` via `ValueAnimator`.
## 5. Shared Source-Gated Overlays
- **Definition:** Using a single Activity/Fragment for multiple content sources (IPTV vs Debrid) but gating specific UI overlays based on content type or source metadata.
- **Benefits:** Reduces code duplication and ensures a consistent UX across different backend sources.
- **Implementation:** In `dispatchKeyEvent`, use helper methods like `isSeriesEpisodePlayback()` to route keys to specific overlay controllers.

## 6. Debrid Re-Resolution Metadata
- **Definition:** Store durable Debrid metadata such as infoHash/magnet/source identity for resume, then re-resolve a fresh playable URL instead of trusting an old unrestricted stream URL.
- **Benefits:** Avoids expired-link playback failures and keeps Debrid resume aligned with Real-Debrid token/link lifetimes.
- **Implementation:** Route Debrid resume through `PlaybackResolver` using stored infoHash/magnet metadata and launch the shared `PlayerActivity` only after fresh resolution succeeds.

## 7. Sensitive Log Redaction
- **Definition:** Log only safe summaries for URLs, magnets, hashes, tokens, serialized history, and provider config paths.
- **Benefits:** Keeps QA/debug logs useful without exposing Real-Debrid access tokens, unrestricted stream links, IPTV credentials, or watch-history payloads.
- **Implementation:** Use `SensitiveLogRedactor.describeUrl`, `describeHash`, or `describeSecret`; disable OkHttp URL/body logging on token-bearing API clients. Do not stringify state objects or provider release labels when those objects can include stream URLs, source titles, hashes, or direct sources.

## 8. Authoritative Debrid Cache Confidence
- **Definition:** Represent Debrid source readiness with explicit confidence states instead of overloading a nullable Boolean or trusting provider text.
- **Benefits:** Prevents `RD+`, `cached`, or addon labels from becoming false claims; lets the UI distinguish verified cached, direct stream, unknown, and not cached.
- **Implementation:** Use Real-Debrid instant availability or playback readiness to set `DebridCacheStatus`; keep unknown as unknown and make cached-only filters require `VERIFIED_CACHED`.

## 9. Streaming XML Declaration Stripping
- **Definition:** Sanitizing redundant or malformed XML processing declarations (`<?xml ... ?>`) in a custom streaming `PushbackReader` wrapper without buffering the entire document as a String.
- **Benefits:** Prevents large XML files from causing out-of-memory errors on TV devices while ensuring robust XML parsing.
- **Implementation:** Stream character buffers in a custom `Reader` and skip extra instances of `<?xml` after the first header.

## 10. Typed Debrid Terminal Failures
- **Definition:** Convert Real-Debrid HTTP/provider errors into typed failures before retry, auto-next, or user messaging decisions.
- **Benefits:** Prevents legal/copyright blocks and `429` rate limits from being treated like transient network failures, reducing wasted API calls and clearer behavior under provider-side denial.
- **Implementation:** Classify `429`, `451`, copyright/legal removal text, auth, not-cached/unavailable, network, and unknown failures; apply global/per-source cooldown where appropriate; retry only transient failure classes.

## 11. Immediate Rate-Limit Feedback
- **Definition:** Treat active provider cooldown as an immediate user-facing state, not as a hidden delay inside request scheduling.
- **Benefits:** Prevents clicks from appearing dead while the app waits for a Real-Debrid cooldown window.
- **Implementation:** Check global cooldown before playback resolution and background availability calls; return a typed `RATE_LIMITED` error with remaining wait time instead of sleeping silently.

## 12. Language-Aware Debrid Source Discovery
- **Definition:** Send provider requests with explicit target-language priority and preserve those candidates before cache-verification caps are applied.
- **Benefits:** Prevents English-only/high-seeder entries from consuming the whole Real-Debrid availability budget when the target playback need is Hindi, German, or multi-audio.
- **Implementation:** Use current add-on config keys such as Torrentio `language=...`, keep built-in registries active, parse regional release aliases, and sort Hindi/German/multi candidates before capped provider checks.

## 13. Safe Debrid Terminal-Source Auto-Skip
- **Definition:** When a selected Real-Debrid source is terminally blocked, unavailable, or not cached, mark it unavailable for the current list and move to the next candidate.
- **Benefits:** Keeps playback attempts moving through scraper results without retrying the same blocked hash or worsening rate limits.
- **Implementation:** Auto-skip only `COPYRIGHT_BLOCKED`, `LEGAL_RESTRICTION`, `NOT_CACHED`, and `UNAVAILABLE`; stop on `RATE_LIMITED`, auth, network, and unknown failures.

## 14. Native Stremio Manifest Source Path
- **Definition:** Treat user-pasted Stremio `manifest.json` URLs as first-class addon sources instead of forcing them through legacy JSON registry definitions.
- **Benefits:** Preserves configured path/token segments, avoids duplicate scraper registry mixing, and matches Stremio addon stream endpoint behavior for movies and series.
- **Implementation:** Store Stremio manifest URLs separately, derive `/stream/movie/{imdb}.json` and `/stream/series/{imdb}:{season}:{episode}.json` from the exact manifest URL, parse stream objects into `AddonStream`, and keep Real-Debrid infoHash/magnet handling separate from direct addon playback URLs.

## 15. Direct Debrid Playback Identity Guard
- **Definition:** Launch direct Stremio/AIOStreams playback URLs with Debrid source identity while using a separate direct-play flag to skip app-side Debrid resolver/API calls.
- **Benefits:** Player controls, history, and source return behavior still show Debrid, while direct addon URLs do not trigger false IPTV labeling, IPTV playlist API calls, or Real-Debrid re-resolution.
- **Implementation:** Pass `PlaybackSource.DEBRID` plus a direct Debrid playback flag for direct HTTP addon streams; gate resolver, Debrid playlist loading, timeout re-resolution, and Debrid next-episode resolution behind a non-direct Debrid resolver check.

## 16. Direct Debrid Source Profile Continuity
- **Definition:** Treat the selected Debrid/Stremio source as a profile, not just a URL, when moving between episodes or resuming playback.
- **Benefits:** Episode browser selection, Next, auto-next, and Continue Watching can prefer the same provider/source family and same language, reducing unstable jumps to unrelated English-only or lower-quality sources.
- **Implementation:** Persist provider, source type/name, languages, quality, stream id, Stremio binge group, file index, and direct-play flag through source conversion, player intents, and Continue Watching. On next/resume, re-fetch sources and rank matching profile/language before cache/quality/seeders fallback.

## 17. Backward-Compatible Companion Schemas
- **Definition:** Companion config ingestion must accept new fields without breaking old companion payloads.
- **Benefits:** Phone/web companion can be updated incrementally while older versions keep working.
- **Implementation:** Add additive fields (arrays/objects) with defaults, support legacy field names, and prefer a `schemaVersion` discriminator for future changes.

## 18. Single Companion Surface For Stremio Addons
- **Definition:** Update the existing companion flow in place and expose Stremio manifest URLs as the primary addon input rather than creating a second companion screen.
- **Benefits:** Keeps the current working pairing route stable, avoids duplicate settings/pages, and lets IPTV data and Stremio addon links sync together from one payload.
- **Implementation:** Reuse the same Firestore/device-code entrypoint and TV-side companion screen, write `debridConfig.stremioAddonUrls` plus top-level `stremioAddonUrls`, and keep legacy `debrid` / `mediafusion` only as backward-compatible fallback data.

## 19. Stremio-First Settings Labels
- **Definition:** Present Stremio Addons as the primary live settings surface and label Real-Debrid only as legacy fallback when the feature is still needed.
- **Benefits:** Fresh installs read as current instead of RD-first, reducing user confusion after schema migrations.
- **Implementation:** Reorder the DEBRID settings items, rename the category/title copy, and make the legacy auth prompts explicitly say fallback or legacy.

## 20. Canonical Companion Payload
- **Definition:** Keep the companion web payload Stremio-first by emitting one canonical schema with `schemaVersion: 2`, `iptv`, `debridConfig.stremioAddonUrls`, and top-level `stremioAddonUrls`.
- **Benefits:** Prevents the web companion from reintroducing legacy top-level RD/MediaFusion fields while still letting the TV app accept older payloads.
- **Implementation:** Build the Firestore payload from the active addon URL list, keep legacy fields only in backward-compatible parsers, and log summary counts instead of raw config internals.

## 21. Hidden Legacy Auth Affordance
- **Definition:** Keep deprecated manual auth controls out of the visible primary Debrid auth surface once device-code and Stremio-first flows are in place.
- **Benefits:** Reduces user confusion and prevents the old API-key path from looking like the preferred setup method.
- **Implementation:** Hide legacy manual-entry controls by default, keep device-code auth visible, and preserve hidden fallback code only when required for compatibility.

## 22. Mobile Companion Form With IPTV Preflight
- **Definition:** Make the companion opening route show the actual mobile form with IPTV inputs and multiple Stremio addon rows, then verify IPTV before any payload is sent.
- **Benefits:** Users can configure everything on one phone-friendly screen, bad IPTV credentials are blocked inline, and blank IPTV details are not serialized.
- **Implementation:** Route `/setup` to the config form, use stacked mobile sections, allow add/remove addon rows, call a companion verification endpoint, and omit IPTV from the payload unless it is complete and verified.

## 23. Canonical Companion Root
- **Definition:** Keep one visible companion entrypoint by making the root route canonical and redirecting old companion paths back to it.
- **Benefits:** Removes duplicate-looking same-page routes and prevents users from landing on two different URLs that show the same config surface.
- **Implementation:** Render the config form at `/` and redirect `/setup` and `/config` to `/` while preserving the query string.

## 24. Live Load-State Source Of Truth
- **Definition:** Use Paging3 refresh state to drive LiveTV channel loading rather than clearing ViewModel loading state early.
- **Benefits:** Prevents stale loading flags and keeps the Live overlay aligned with actual adapter readiness.
- **Implementation:** Emit a loading-state event from the fragment when Paging refresh changes and let NotLoading/Error clear the ViewModel flag.

## 25. Shared Live URL Helper
- **Definition:** Build LiveTV stream URLs from the shared stream helper instead of repeating `.ts` URL strings across fragments and player handoff paths.
- **Benefits:** Preserves non-TS `container_extension` values, reduces duplicate URL logic, and keeps live zapping/preview/fullscreen consistent.
- **Implementation:** Use the shared `toLiveStreamUrl` helper for live preview, fullscreen, favorites, search playback, and zapping.

## 26. Explicit Live Fullscreen Return Contract
- **Definition:** Return the final LiveTV channel from `PlayerActivity` to the Live screen through an Activity Result payload.
- **Benefits:** Keeps the mini-player aligned with the channel actually playing after fullscreen zapping instead of relying on lifecycle-timed watch-history writes.
- **Implementation:** Return channel id, title, logo, EPG id, category id, and current stream URL; let the Live preview use that exact URL.

## 27. Child-Targeted TV List Focus
- **Definition:** For Android TV RecyclerViews that are not focusable containers, request focus on a bound child item after scrolling/layout.
- **Benefits:** Prevents focus from bouncing back to the previous rail or category list.
- **Implementation:** Scroll to the target adapter position, wait briefly for the ViewHolder, retry a few times, and request focus on `itemView`.

## 28. Deterministic Paged TV List Navigation
- **Definition:** For paged Android TV lists, let the focused item view consume DPAD_UP/DOWN and move focus by adapter position instead of relying only on native focus-search.
- **Benefits:** Fast remote repeats stay inside the list even while RecyclerView/Paging is binding or scrolling.
- **Implementation:** Track a pending target position during repeated key events, scroll to that position, focus the bound child view, and clear the pending target after focus succeeds or retries finish.

## 29. Negative-Cache-Safe EPG Refresh
- **Definition:** Treat empty guide lookups as retryable misses instead of fresh cache hits.
- **Benefits:** Late XMLTV syncs and short-EPG provider updates can repopulate rows that initially had no current/next program data.
- **Implementation:** Cache only positive EPG results, remove empty entries from the cache, and warm the first visible LiveTV rows once Paging settles.

## 30. Single-Flight Manual EPG Sync
- **Definition:** Route manual EPG refresh through one code path at a time and serialize it with a mutex.
- **Benefits:** Prevents settings clicks from racing WorkManager and direct repository parsing against the same XMLTV/database state.
- **Implementation:** Use one manual sync entrypoint, keep scheduled sync separate, and guard the repository fetch with a mutex so only one EPG parse runs at once.

## 31. Zero-Latency Detail Metadata Rendering
- **Definition:** Render optimistic UI metadata on details/action screens immediately using bundle arguments from the caller before background repository or database fetches settle.
- **Benefits:** Creates a native, instant-loading feel and prevents empty screen flashes.
- **Implementation:** Bind title/backdrop from Bundle arguments in `onViewCreated()` before collecting StateFlow emissions.

## 32. TV Recycler Null Item Animators
- **Definition:** Disable RecyclerView item animations on TV scroll lists.
- **Benefits:** Prevents focus shifts, card jitter, and layout bouncing when rapid D-Pad updates trigger adapter changes.
- **Implementation:** Call `recyclerView.itemAnimator = null` on paging lists.

## 33. State-Gated TV Action Buttons
- **Definition:** Show TV controller actions only when the underlying playlist or playback state can actually satisfy them.
- **Benefits:** Prevents dead buttons, reduces focus dead-ends, and keeps the controller honest about available navigation.
- **Implementation:** Bind series-only actions like `Next Episode` to `SeriesPlaylistState.hasNext`, and keep them disabled or hidden until the next item is real.

## 34. Single Runtime UI Path Audit
- **Definition:** Verify the runtime XML host/layout path before changing a TV controller or overlay.
- **Benefits:** Prevents restyling documentation-only copies or assuming a duplicate source-set file exists when the app uses only one active layout.
- **Implementation:** Trace `AndroidManifest.xml` -> Activity -> layout include/controller id -> actual resource file before editing visuals.


