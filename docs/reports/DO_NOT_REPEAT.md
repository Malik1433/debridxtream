# DO NOT REPEAT

Critical warnings for future development in the IPTV Series and VOD modules.

## UI / Layout
- **DO NOT** use `LinearLayout` with `layout_weight` for the main Sidebar/Content split. It causes the grid to "jump" or cards to resize when the sidebar expands. Use `ConstraintLayout` or fixed-margin containers instead.
- **DO NOT** modify `item_series_card.xml` or `item_movie_card.xml` dimensions without explicit user request. These are tuned for a specific aspect ratio.
- **DO NOT** hardcode the loading skeleton to match card positions. If the skeleton is needed, it MUST share the same parent as the `RecyclerView`.

## Logic / Data
- **DO NOT** clear the repository cache during a category switch unless sync fails.
- **DO NOT** assume `PagingDataAdapter.itemCount` is accurate during the `refresh` load state. It may temporarily report 0 while the new PagingData is being processed.
- **DO NOT** ignore `HttpException` code 404 in the Repository. It often indicates a "Category not found" error that requires a fallback fetch to the "All" list.

## Focus / Navigation
- **DO NOT** allow focus to escape the Sidebar to the left.
- **DO NOT** allow focus to jump from the top row of the grid to the header buttons (Search/Settings) unless DPAD_UP is explicitly handled to allow it (usually it's blocked to maintain grid position).
- **DO NOT** use `ViewTreeObserver` for expansion logic if a simple `OnFocusChangeListener` or `GlobalFocusChangeListener` suffices.

## Player Rules
1. **DO NOT** create a duplicate `PlayerActivity`.
2. **DO NOT** change global DPAD behavior without source/content guard.
3. **DO NOT** create separate duplicate episode overlay systems for IPTV and Debrid.
4. **DO NOT** break Live TV `DPAD_DOWN` behavior.
5. **DO NOT** log stream URLs, Debrid links, tokens, usernames, or passwords.
6. **DO NOT** claim Player task PASS without testing IPTV Series, Debrid Series, Live TV, VOD, and Debrid Movie regression.
7. **DO NOT** omit `EXTRA_SERIES_ID` when launching `PlayerActivity` for Series/Episodes; it is required for playlist loading and the episode browser.

## IPTV Episode Browser Rule
Do not block UI state emission on a long-running provider collect. For player overlays, emit loading/empty/error states quickly, use bounded fetches, and never allow infinite spinner.

## Episode Browser Focus Rule
Do not let browser-visible DPAD or OK/BACK events fall through to Media3 or generic player controls. When the episode browser is visible, it owns LEFT/RIGHT/UP/DOWN/OK/BACK and the player controller must stay hidden.

## Episode Browser Image Rule
Do not show blank or broken IPTV episode thumbnails. Use episode thumbnail, then player poster/backdrop fallback, then a clean episode-number placeholder.

## Continue Watching Series Metadata Rule
Do not launch IPTV Series episodes from Continue Watching without `seriesId`, season number, episode number, and episode id. Player episode browser and next episode logic require the same metadata as the Series detail path.

## Debrid Sensitive Logging Rule
Do not log Debrid access tokens, provider URLs containing tokens, magnets, info hashes paired with titles, direct Debrid stream URLs, unrestricted download URLs, or full Continue Watching JSON.

## Debrid Provider Parity Rule
Do not claim AllDebrid, Premiumize, or Stremio parity from labels/badges alone. Each provider needs real auth, source fetch, cache verification, resolver, error handling, and QA evidence.

## Debrid Cache Confidence Rule
Do not treat text labels such as `RD+`, `cached`, `instant`, or provider names as authoritative cache state. Only Real-Debrid instant availability, explicit negative availability, or playback readiness may promote a source to verified cached/direct/not cached. Keep failed checks as unknown, not uncached.

## Debrid Error Handling Rule
Do not retry Real-Debrid legal/copyright failures or HTTP `429` rate-limit failures as generic playback errors. Classify these failures, stop immediate retry/auto-next cascades where appropriate, apply cooldown for rate limits, and never expose raw sensitive backend details to the user.

## Debrid Auto-Skip Rule
Do not auto-next on every Debrid failure. Auto-skip only terminal per-source failures such as copyright/legal blocked, not cached, or unavailable. Do not auto-skip on `429`, auth/session, network, or unknown failures because those can affect every source and create request storms.

## Debrid Regional Source Rule
Do not assume generic/default Torrentio or add-on requests are enough for Hindi/German playback. Keep provider config syntax current, include language priority when supported, keep built-in registries active, and make sure capped Real-Debrid availability checks include target-language and multi-audio candidates.

## Debrid Stremio Manifest Rule
Do not put Stremio `manifest.json` URLs into legacy JSON scraper registry storage or delete legacy fetchers before the native Stremio path is validated. Preserve the exact configured manifest path and query token when deriving stream endpoints, redact full addon URLs in logs, and do not claim Stremio parity until movie, series, direct URL, and Real-Debrid infoHash playback are manually verified.

## Debrid Direct Addon Playback Rule
Do not send direct Stremio/AIOStreams HTTP playback URLs through app-side Real-Debrid auth or re-resolution. Preserve addon headers and launch them as direct playback. Use app-side Real-Debrid only for magnet/infoHash/torrent/unrestrict paths.

## Debrid Direct Playback Identity Rule
Do not fix direct Stremio/AIOStreams playback by downgrading the player source to IPTV/null. Keep Debrid identity for player controls, Continue Watching, and return-to-sources behavior, but use a dedicated direct-play guard to skip Debrid resolver/API and Debrid playlist loading.

## Debrid Direct Resume Rule
Do not trust saved direct Stremio/AIOStreams playback URLs as long-lived resume links. Persist stable TMDB/IMDb/season/episode metadata plus selected provider/source/language profile, then fresh-resolve before playback when relaunching Continue Watching.

## Debrid Episode Continuity Rule
Do not pick the next Debrid episode by quality alone. Episode browser selection, Next, and auto-next must prefer the same provider/source family and language as the current source when a matching candidate exists, then fall back to playback readiness, quality, and seeders.

## EPG Timezone Parsing Rule
- **DO NOT** ignore timezone offset suffixes (like `+0200`) when converting XMLTV timestamps to epoch milliseconds. Ignoring offsets shifts EPG program schedules.

## Companion Config Rule
- **DO NOT** configure Ktor CORS with `anyHost()` as it exposes IPTV credentials to CSRF cross-origin script access. Use custom security headers and local subnets.
