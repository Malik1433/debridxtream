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

## Series Controller Next Rule
- **DO NOT** make the series `Next Episode` button always active or guess continuity from the current episode number.
- **DO NOT** show the control for non-series playback.
- **DO NOT** let the button point at a dead action when `SeriesPlaylistState.hasNext` is false.

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

## Debrid Direct Resume Metadata Fallback Rule
Do not block direct Debrid fresh-resolve just because older history entries are missing TMDB/IMDb fields. If the item still has stable title/content identity and source profile metadata, use that to refresh the source instead of replaying the expired URL.

## Debrid Direct Freshness Split Rule
Do not use one no-passthrough branch for both stale direct history replay and fresh provider-fetched direct URLs. Block the history URL, but let fresh direct metadata refresh results pass through and play.

## Debrid Episode Continuity Rule
Do not pick the next Debrid episode by quality alone. Episode browser selection, Next, and auto-next must prefer the same provider/source family and language as the current source when a matching candidate exists, then fall back to playback readiness, quality, and seeders.

## EPG Timezone Parsing Rule
- **DO NOT** ignore timezone offset suffixes (like `+0200`) when converting XMLTV timestamps to epoch milliseconds. Ignoring offsets shifts EPG program schedules.

## Companion Config Rule
- **DO NOT** configure Ktor CORS with `anyHost()` as it exposes IPTV credentials to CSRF cross-origin script access. Use custom security headers and local subnets.
- **DO NOT** add a second companion pairing page or duplicate config screen just to switch from MediaFusion fields to Stremio manifest URLs. Update the existing route in place and keep one payload contract.
- **DO NOT** leave Real-Debrid or MediaFusion wording as the primary label on the live settings surface after Stremio becomes the main addon path. Legacy fallback must be clearly secondary.
- **DO NOT** let the web companion emit both new Stremio-first fields and old top-level `debrid` / `mediafusion` payload keys by default. Keep the outgoing schema canonical and legacy names only in compatibility parsers.
- **DO NOT** leave the manual Real-Debrid API-key entry visible on the main Debrid auth screen after the device-code/Stremio flow exists. Hide it by default and keep legacy fallback behavior out of the primary UI.
- **DO NOT** keep `/setup` as a redirect-only bridge when the user expects the actual companion form. The first opened page must be the real mobile form.
- **DO NOT** serialize an empty IPTV object into the companion payload when IPTV is optional. Omit the field entirely unless the credentials were filled and verified.
- **DO NOT** cap Stremio addon input to a single field when the user needs multiple manifest URLs. Use add/remove rows on the same form.
- **DO NOT** leave `/`, `/setup`, and `/config` all as active same-screen companion pages. Choose one canonical visible route and redirect the others.

## LiveTV Rules
- **DO NOT** clear `isLoadingChannels` in `LiveViewModel` before Paging refresh settles.
- **DO NOT** hardcode `.ts` into Live preview/fullscreen/search/favorite playback URLs when `container_extension` is available.
- **DO NOT** build fullscreen live channel handoff lists from the adapter snapshot only when cached live streams are available.
- **DO NOT** add a second Live screen or a parallel Live loading path just to work around paging state drift.
- **DO NOT** use recent live history as the primary return signal from fullscreen playback; use an explicit Activity Result payload for the final zapped channel.
- **DO NOT** call `requestFocus()` on a non-focusable Live channel RecyclerView container; focus a bound channel item view after scroll/layout.
- **DO NOT** let rapid Live channel-list DPAD_UP/DOWN fall through to native RecyclerView focus-search only. The focused channel card must consume it and move by adapter position.
- **DO NOT** cache empty `(null, null)` EPG responses as if they were fresh guide data.
- **DO NOT** wait for the first focus bind alone to warm Live EPG rows; prefetch the visible rows after Paging settles.
- **DO NOT** trigger WorkManager immediate EPG sync and a direct repository EPG sync from the same settings click.
- **DO NOT** allow two EPG sync jobs to run concurrently against the same parser/database state without a mutex or single-flight guard.

## Layout & Cache Sync Rules
- **DO NOT** bind click listeners to action buttons on detail screens without verifying that the layout XML actually renders and exposes those buttons.
- **DO NOT** mix V1 and V2 database models without manual schema sync policies. Updating V1 categories or favorites will not automatically sync to V2 details tables unless handled explicitly.
- **DO NOT** assume a player UI restyle needs a duplicate source-set controller file. If the runtime path is `activity_player.xml` -> `custom_player_control_view.xml`, update that single file and verify the install on device.
- **DO NOT** collapse direct audio/subtitle track actions into one combined chooser when the player already exposes track-specific selection dialogs.
- **DO NOT** use current audio/text tracks as a substitute for Debrid source-language metadata when you need to re-pick a different source.
- **DO NOT** leave the player progress bar in a decorative wave style if the controller design is meant to be flat and compact.
- **DO NOT** rely on touch-only long-press handling for Home cards that must work on TV remotes. Handle D-pad repeat-press detection and suppress the follow-up click so action menus do not double-fire.
- **DO NOT** inflate Continue Watching long-press menus into large panel/dialog spacing when the action set is only 1-2 items; keep them compact chip-style with clear focused state.
- **DO NOT** send exhausted playback failures back through the same retry/result path that handles transient player errors. Redirect terminal failures to the owning detail screen with a failure-origin guard so the detail view stays in browse mode.

