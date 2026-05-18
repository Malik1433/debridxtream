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

