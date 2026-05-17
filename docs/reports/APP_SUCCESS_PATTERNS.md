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

