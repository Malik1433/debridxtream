# Recent Changes

Track only the last 10 important changes. Newest first. Keep this file compact for regression debugging.

Date: 2026-05-30
Module: Player / Debrid Continue Watching
Issue fixed: Aligned Debrid Continue Watching IDs with canonical watched-state keys. `PlayerHistoryManager` now resolves Debrid CW content IDs through the same hardened identity path used by `watched_state`, removing raw info-hash fallback leakage while preserving stable TMDB/IMDb IDs and using title/year plus collision-resistant episode fallback keys when metadata is incomplete.
Files changed: `PlayerHistoryManager.kt`, `DEBRID_MODULE_REPORT.md`, `PLAYER_MODULE_REPORT.md`, `RECENT_CHANGES.md`
Regression risk: Debrid Continue Watching resume/removal compatibility, automatic watched-state cleanup, no-TMDB/no-IMDb fallback identity parity, IPTV CW path isolation.
QA result: `:app:compileDebugKotlin` passed. `:app:assembleDebug` passed. APK installed on `192.168.0.21:5555` and `192.168.0.84:5555`. Documentation update verified as Markdown-only tracking changes.

Date: 2026-05-30
Module: Debrid / Watched Identity
Issue fixed: Completed Debrid Watched Identity Hardening for series and episode tracking. `WatchedIdentityBuilder` now performs standalone unsafe-ID validation, rejects transient URLs, magnets, token/credential-like values, API/authorization parameters, and raw torrent info-hashes matching 40-character hex or 32-character base32 forms. `PlayerHistoryManager` now routes watched identity inputs through that sanitizer, preserves existing valid Debrid key prefixes, adds release-year discrimination to title fallbacks, and adds fallback discriminators when season/episode metadata is missing to avoid `unknown` key collisions.
Files changed: `WatchedIdentityBuilder.kt`, `PlayerHistoryManager.kt`, `DEBRID_MODULE_REPORT.md`, `RECENT_CHANGES.md`
Regression risk: Debrid watched badge lookup for edge-case metadata, fallback identity compatibility, existing watched-state matching for valid TMDB/IMDb IDs.
QA result: `:app:compileDebugKotlin` passed after stopping a stuck Gradle daemon and rerunning. `:app:assembleDebug` passed. APK installed on `192.168.0.84:5555`, app launched, and process was confirmed running. Documentation update verified as clean Markdown-only changes.

Date: 2026-05-30
Module: Series / Watched State
Issue fixed: Implemented Series-Level Derived Watched State for the series details screen banner. Combined total vs. watched episode count flows in SeriesDetailViewModelV2 to compute a single background-driven SeriesWatchedState, dynamically observed by SeriesDetailFragmentV2 to update the main series header badge without layout shifts.
Files changed: `SeriesDetailViewModelV2.kt`, `SeriesDetailFragmentV2.kt`
Regression risk: Series detail loading speed, D-pad navigation latency, layout shifting.
QA result: QA passed on device. Series banner dynamically updates to "WATCHED" or "IN-PROGRESS". Zero stutter confirmed during rapid D-pad navigation.

Date: 2026-05-30
Module: Series / Watched State
Issue fixed: Implemented Season Derived Watched State. Utilized background SQLite GROUP BY flow aggregation via EpisodeDaoV2 and WatchedStateDao, combined in SeriesDetailViewModelV2, and efficiently applied via SeasonsAdapterV2 with DiffUtil. Handles Watched (all episodes), In-Progress (partial), and Unwatched states.
Files changed: `EpisodeDaoV2.kt`, `WatchedStateDao.kt`, `SeriesDetailViewModelV2.kt`, `SeasonsAdapterV2.kt`
Regression risk: Series detail loading speed, season pill UI, D-pad scrolling lag.
QA result: QA passed on device. Season pills correctly reflect derived watched states; rapid D-pad scroll verified completely smooth with no main-thread DB blocking.

Date: 2026-05-30
Module: VOD / Series / Watched State
Issue fixed: Implemented Manual Mark Watched/Unwatched actions. Movie detail and VOD grid long-press support manual toggle. IPTV/V2 Series episode long-press supports manual toggle. `manual_state` WATCHED/UNWATCHED is respected. Continue Watching exact removal verified (other episodes from same series are not removed accidentally).
Files changed: `VodFragment.kt`, `MovieDetailFragmentV2.kt`, `EpisodesAdapterV2.kt`, `WatchedStateRepository.kt`
Regression risk: Continue Watching eviction, focus stability, playback handoff, badge rendering.
QA result: QA passed on device. Movie detail and VOD/Series long-press Mark Watched/Unwatched works. Badges update correctly. Continue Watching exact removal works correctly. Manual override behavior works as expected. Movie and episode playback, Auto Next Episode watched save, Episode Browser manual switch watched save, and BACK from PlayerActivity still work. Pending: season-level and series-level derived watched UI/actions. Pending: Debrid-specific manual watched/unwatched verification.

Date: 2026-05-30
Module: Player / Watched State
Issue fixed: Fixed Episode Browser manual episode switch watched-state gap. Root cause was that `onEpisodeSelected()` switched the episode identity before recording the current episode's history/watched state. Fix: added save-before-switch call to `historyManager.recordPlaybackHistoryIfNeeded()` before selected episode identity/source mutation. Auto Next Episode and manual BACK watched paths remain working. No schema, UI badge, source picker, or Live TV changes were made.
Files changed: `PlayerActivity.kt`
Regression risk: Player identity switching, Episode Browser manual selection, Watched state history recording.
QA result: Built successfully and tested on target devices. QA passed: previous episode is marked watched when switching from the Episode Browser; newly selected episode is not incorrectly marked; Auto Next Episode and manual BACK still work correctly. Manual Mark watched/unwatched actions remain pending. Season/series derived watched state remains pending.

Date: 2026-05-30
Module: Watched State / UI Badges
Issue fixed: Implemented Phase 3 read-only watched badges for Movies. Added watched indicator to IPTV/VOD movie cards (VodAdapter) and Movie Detail V2. The badge correctly observes watched_state from WatchedStateRepository and renders an ic_check_circle icon if is_watched=true. Manual "Mark Watched" actions are intentionally excluded. Series UI is intentionally untouched.
Files changed: `item_movie_card.xml`, `fragment_movie_detail_v2.xml`, `VodFragment.kt`, `MovieDetailFragmentV2.kt`, `WatchedStateDao.kt`, `WatchedStateRepository.kt`
Regression risk: Movie card rendering, movie detail load time.
QA result: Built successfully. Phase 3 Movies read-only watched badge verified on device. Watched movie cards show checkmark. VOD movie detail watched indicator verified on device. Series/Episode watched UI remains pending. Manual watched/unwatched actions remain pending. Debrid movie badge verification remains pending unless explicitly tested.

Date: 2026-05-30
Module: Watched State / Verification
Issue fixed: Completed full Phase 2 QA verification of the automatic watched detection logic. Since remote adb seek was unreliable for mid-progress testing, verification was completed deterministically by creating a local JVM unit test (`PlayerHistoryManagerTest`) that rigorously tested all threshold conditions: <5% ignoring, 5%-89% normal tracking, ≥90% completion, and time-remaining completion (≤5m for movies, ≤3m for episodes). Full regression checks (BACK navigation, source picker, playback, no log leakage) were also completed manually on device 192.168.0.84.
Files changed: `PlayerHistoryManagerTest.kt` (New)
Regression risk: None (Test-only addition, no runtime code changed).
QA result: `testDebugUnitTest` passed flawlessly. Device QA regression checks passed. Phase 2 Automatic Watched Detection is now fully verified and complete.

Date: 2026-05-30
Module: Watched State / Storage
Issue fixed: Added Phase 1 canonical durable watched-state storage for Movies and Episodes without wiring automatic detection or UI. New `watched_state` Room table stores stable identity keys, source/content type, watched flag, progress/duration, timestamps, manual override fields, and non-sensitive content identity metadata.
Files changed: `WatchedStateEntity.kt`, `WatchedStateDao.kt`, `WatchedIdentityBuilder.kt`, `WatchedStateRepository.kt`, `AppDatabase.kt`, `DatabaseMigrations.kt`, `AppModule.kt`
Regression risk: Room database migration/versioning and future watched-state consumers. PlayerActivity, Continue Watching, source picker, Debrid resolver, Live TV, and UI were intentionally untouched.
QA result: `:app:compileDebugKotlin --no-daemon --console plain` passed after stopping a stale Gradle daemon. `:app:assembleDebug --no-daemon --console plain` passed. No install/UI test required for storage-only Phase 1.

Date: 2026-05-29
Module: Navigation / Player
Issue fixed: Fixed PlayerActivity BACK button crash that caused navigation to return directly to Home instead of the originating detail screen (MovieDetailActivity or SeriesDetailActivity). The crash was an `UninitializedPropertyAccessException` in `SourceSelectionBottomSheet` because `showSources` was called via `ActivityResultCallback` before `onViewCreated` finished view inflation. Fix safely defers source updating to `onViewCreated` via `pendingSources`. 
Files changed: `SourceSelectionBottomSheet.kt`, `MovieDetailActivity.kt`, `SeriesDetailActivity.kt`, `PlayerActivity.kt`
Regression risk: Source picker display, PlayerActivity back behavior, ActivityResult callback.
QA result: `clean :app:compileDebugKotlin` and `:app:assembleDebug` passed. Installed on `192.168.0.21:5555`. Device QA confirmed BACK navigation from PlayerActivity now correctly resumes the episode/movie source picker flow instead of crashing to Home.

Date: 2026-05-29
Module: Debrid / Source Picker Language Ranking
Issue fixed: Hindi language matching was unreliable because parser aliases missed short Hindi markers like `HI`/`HIN` and dual/multi Hindi phrases, while selected-language filtering could either hide useful fallbacks or restore focus to an older non-matching source. Hindi parsing now recognizes short codes, Hindi-English, dubbed Hindi, dual-audio Hindi, multi-audio Hindi, and Devanagari Hindi markers. Selected language filtering keeps exact matches when present, otherwise falls back to the useful source list while ranking language-compatible rows first. Changing language clears the old selected stream id so focus moves to the first matching row.
Files changed: `LanguageParser.kt`, `SourceFilterUtils.kt`, `SourceSelectionBottomSheet.kt`
Regression risk: Source picker language filtering/ranking, language dropdown focus handoff, source row order.
QA result: `clean :app:compileDebugKotlin` and `:app:assembleDebug` passed after stopping a stale Gradle daemon and rerunning. Installed on `192.168.0.84:5555`. Device QA on a Debrid series episode with 398 sources confirmed Hindi appears in the language dropdown, selecting Hindi narrowed to two Hindi-English direct rows, focus moved to the first matching row, direct playback launched `PlayerActivity`, Back returned to Debrid, and app-process log scan stayed clean (`RawUrl=0`, `Magnet=0`, `TokenParam=0`, `CredentialWord=0`, `LongHash=0`).

Date: 2026-05-29
Module: UI / Source Picker
Issue fixed: Source picker filter options used standard AlertDialog which showed as a full-screen/large modal overlay, obscuring the source list and breaking TV immersion. Replaced AlertDialog with a compact `ListPopupWindow` anchored directly below each filter chip. The chips now include a '▼' arrow to indicate dropdown functionality, and selected options are appended with a '✓' checkmark in the dropdown. The list correctly wraps its content, has a compact width, respects TV D-PAD focus, and gracefully dismisses on BACK or OK selection.
Files changed: `SourceSelectionBottomSheet.kt`
Regression risk: Source picker UI layout, TV D-PAD focus traversal, click listeners on the chips.
QA result: `clean :app:compileDebugKotlin` and `:app:assembleDebug` passed. Verified ListPopupWindow doesn't blur the background and allows quick source filtering.

Date: 2026-05-29
Module: UI / Source Picker
Issue fixed: Source picker top filters were too spread out and confusing. Replaced current source picker top filter buttons with 3 compact TV-friendly chips (Quality, Language, Type). Chips open a standard Android TV AlertDialog for selection. `SourceFilterUtils` was updated to support mapping to Quality, Language, and Type without breaking existing dependencies in the app.
Files changed: `dialog_source_selection.xml`, `SourceFilterUtils.kt`, `SourceSelectionBottomSheet.kt`
Regression risk: Source filtering logic, source picker UI layout, backwards compatibility with `MovieDetailActivity` and `SourceListSection`.
QA result: `clean :app:compileDebugKotlin` and `:app:assembleDebug` passed. Filter states now use 3 standard filters while retaining backward compatible fields for older implementations.

Date: 2026-05-29
Module: Debrid / Source Picker Filter
Issue fixed: Source picker "Playable only" semantics could still show direct HTTP/addon links after a click-time readiness pass because movie/series helpers promoted ready direct links to `VERIFIED_CACHED`. Direct HTTP readiness is not durable Real-Debrid cache proof, so direct sources now stay `DIRECT_STREAM`, the cached filter remains strictly `VERIFIED_CACHED`, and the cached chip is shown only when a verified cached source exists.
Files changed: `SourceFilterUtils.kt`, `MovieDetailActivity.kt`, `SeriesDetailActivity.kt`
Regression risk: Source picker cached filter behavior, movie/series direct readiness status refresh, RD cached chip visibility, direct source playback preflight.
QA result: `clean :app:compileDebugKotlin` and `:app:assembleDebug` passed. Installed on `192.168.0.84:5555`; `192.168.0.21:5555` was unavailable. Device QA on `.84` confirmed direct-only source pickers hide the RD Cached chip, direct links remain visible with the filter off, an AIOStreams not-ready direct source stayed in `MovieDetailActivity` instead of launching `PlayerActivity`, repeated language-chip taps did not crash or lose the picker, and Back returned from detail to Debrid/MainActivity. A mixed verified-cached sample was not available during QA. Full source-fetch log review still found pre-existing raw provider/server URL logging from paths outside this fix scope.

Date: 2026-05-29
Module: Debrid / Direct Proxy Readiness
Issue fixed: Centralized and hardened direct addon/proxy readiness classification so movie and series detail paths no longer carry separate hardcoded MediaFusion/AIO URL checks before direct Debrid playback. Follow-up device QA found a MediaFusion-labelled movie source could still launch Player and bounce back, so the shared policy now also classifies existing provider/source metadata and applies playback headers during preflight.
Files changed: `DebridPlaybackRepository.kt`, `MovieDetailActivity.kt`, `SeriesDetailActivity.kt`
Regression risk: Movie/series source picker direct playback, MediaFusion/AIO readiness preflight, direct Debrid playback identity/extras, resolver-backed hash/magnet playback.
QA result: `clean :app:compileDebugKotlin` passed. `:app:assembleDebug` passed. Installed on `192.168.0.84:5555`. Targeted provider QA passed: movie picker AIOStreams direct/proxy ready source launched `PlayerActivity` and remained active past 12s; series episode AIOStreams not-ready source did not launch `PlayerActivity` and stayed/recovered in `SeriesDetailActivity` source picker; non-AIO control source launched `PlayerActivity`; Back returned from Player/detail to Debrid; app-process log scan found zero raw URL, magnet, token-param, or long-hash patterns.

Date: 2026-05-29
Module: Debrid / Card Animation Safety QA
Issue fixed: None. Verification-only device QA finalized the Debrid card polish safety check after the prior route-focused verification gap.
Files changed: None
Regression risk: Card focus glow/pulse animation, RecyclerView recycling cleanup, rapid D-pad focus stability, rating/title overlay readability, Continue Watching playback route, Trending detail/source picker route, Search grid focus, Discover rail action.
QA result: Installed existing debug APK on `192.168.0.84:5555` successfully. Device QA passed: rapid D-pad stress across Continue Watching and Trending cards, repeated row switching, Search grid stress, and Debrid landing/Discover action samples showed one focused card, one active glow, no stuck glow, no recycled-card visual bleed, no visible jank/lag, readable rating/title/year overlays, visible Continue Watching progress, stable rail left/right navigation, passive hero, Player route from Continue Watching with Back focus restore, and Trending detail/source picker route with Back focus restore. No runtime code changed; infinite pulse was left in place.

Date: 2026-05-29
Module: Debrid / Compact Hero Header
Issue fixed: Debrid hero title/header consumed too much top-screen space and competed with the permanent focused-card title overlay. Reduced the passive hero from a large cinematic title block into a compact metadata header while keeping focused item context.
Files changed: `view_debrid_hero.xml`
Regression risk: Debrid hero readability, row visibility, passive hero focus behavior, rail/content focus restoration, Continue Watching playback route, Trending detail/source picker route.
QA result: `clean :app:compileDebugKotlin` passed after stopping a wedged Gradle daemon and rerunning. `:app:assembleDebug` passed. Installed on `192.168.0.84:5555`. Device QA confirmed compact hero, readable metadata, synopsis only when available, Continue Watching and Trending Movies headings visible, floating rail unchanged, left/right rail focus stable, Continue Watching OK opens Player and Back restores Debrid focus, Trending OK opens detail/source picker and Back restores Debrid focus, and 1080p layout has no clipping.

Date: 2026-05-28
Module: Debrid / Card Verification
Issue fixed: None. Device QA verification of the Debrid card polish found one route failure outside the card visuals: a Continue Watching series item (`Inspector Rishi`) opened `SeriesDetailActivity` on OK, and Back returned to Home instead of restoring Debrid focus. Rapid Trending card scrolling did not show stuck glow, obvious recycling artifacts, or clipping in the sampled pass.
Files changed: None
Regression risk: Continue Watching OK/back route, Debrid focus restoration after detail, card polish acceptance.
QA result: Build had already passed previously; APK install on `192.168.0.84:5555` succeeded; device QA failed because Continue Watching series routing did not return to Debrid. Next action: separate route review for Continue Watching series items before accepting the animation polish as complete.

Date: 2026-05-28
Module: Debrid / Content Cards
Issue fixed: Debrid content cards focused/active state lacked high-quality TV styling (halo glow clipped, rating badges inconsistent, titles unreadable unfocused). Relocated focus glow outside CardView to prevent edge clipping and anchored it with a cyan glow. Styled rating badges with a semi-transparent black pill with border stroke. Made text metadata permanently readable at alpha 1.0f. Added Overshoot scale scaling (1.15f), dynamic pulsing focus halo animation, and title marquee. Overrode onViewRecycled to safely clear animators and prevent memory leaks.
Files changed: `item_debrid_content.xml`, `DebridRowsAdapter.kt`, `DebridDiscoverAdapter.kt`, `DebridSearchAdapter.kt`
Regression risk: Card layouts, D-pad navigation, memory leaks from non-recycled animators, rating badge formatting, search/discover grid focus.
QA result: `clean :app:compileDebugKotlin` passed. `.\gradlew.bat assembleDebug` passed successfully.


Date: 2026-05-28
Module: Debrid / Rows
Issue fixed: Continue Watching and lower row headings could appear clipped or visually lost after the icon-only rail/gutter changes because row-local top spacing and dynamic hero synopsis height left too little stable room during TV focus scroll. Tightened row spacing and capped the passive hero synopsis to keep row titles visible.
Files changed: `item_debrid_row.xml`, `view_debrid_hero.xml`
Regression risk: Debrid row heading visibility, hero readability, row/card focus, Continue Watching playback route, Trending detail route.
QA result: `clean :app:compileDebugKotlin` passed. `:app:assembleDebug` passed. Installed on `192.168.0.84:5555`. Device QA confirmed Continue Watching heading visible, Trending Movies heading visible at top and after DPAD_DOWN focus, rail unchanged, hero non-focusable, left/right rail focus stable, Continue Watching OK opens Player, Trending OK opens detail, and Back restores Debrid focus.

Date: 2026-05-28
Module: Debrid / Sidebar
Issue fixed: Icon-only Debrid floating rail still left a cut/wasted gutter because the main content viewport reserved `168dp` on the left. Reduced the Debrid main viewport start margin to `144dp` so the rail floats over the shared backdrop while preserving a small safe gap before content.
Files changed: `fragment_debrid.xml`
Regression risk: Debrid rail/content overlap, Debrid D-pad focus entry/exit, Search/Discover rail actions.
QA result: `clean :app:compileDebugKotlin` passed. `:app:assembleDebug` passed. Installed on `192.168.0.84:5555` only. Device QA confirmed rail `[104,388][272,692]`, main viewport `[288,0][1920,1080]`, backdrop full-screen, Search opens, Discover returns top Debrid content focus, rail up/down and right-to-content stable, left from first content card enters rail, hero non-focusable, and Continue Watching present.

Date: 2026-05-28
Module: Debrid / Sidebar
Issue fixed: Premium Compact Debrid Sidebar Redesign. Replaced floating focus pill logic with stable item-local focus backgrounds (`bg_debrid_sidebar_item.xml`). Removed broken/stubbed buttons (Library, Movies, Series, duplicate Home). Preserved Search and Discover functionality. Fixed sidebar width to 176dp for professional Android TV readability.
Files changed: `view_debrid_sidebar.xml`, `item_debrid_sidebar.xml`, `DebridFragment.kt`, added selector/color resources.
Regression risk: Debrid sidebar navigation, Search/Discover intents, sidebar focus stability.
QA result: assembleDebug passed. Installed on 192.168.0.21 and 192.168.0.84.

Date: 2026-05-28
Module: Debrid / Addon Scraper
Issue fixed: AIO Streams and MediaFusion addon links throwing API Exception / Rate Limited error streams. Diagnosis found the addon servers perform strict server-side validation expecting Stremio headers. Fixed by injecting `User-Agent: Stremio/1.0` and `Accept: application/json` in `StremioAddonFetcher.kt` and `AddonCatalogServiceFactory.kt`, and increasing timeouts.
Files changed: `StremioAddonFetcher.kt`, `AddonCatalogServiceFactory.kt`
Regression risk: Existing Debrid playback, source picker, Stremio addon fetcher stability.
QA result: compileDebugKotlin passed. assembleDebug passed. Installed on 192.168.0.21 and 192.168.0.84. Manual QA confirmed: AIO/MediaFusion links working and API error no longer appears.

Date: 2026-05-27
Module: Debrid / Continue Watching
Issue fixed: Movie titles in the Debrid "Continue Watching" row displayed as long, raw filenames. Fixed by adding a `cleanDebridTitle` function to `DebridViewModel.kt` to parse out dots, underscores, years, and quality tags (e.g. 1080p, WEBRip) before displaying. Series titles remain untouched as they were correctly populated from TMDb.
Files changed: `DebridViewModel.kt`
Regression risk: Continue Watching title display.
QA result: compileDebugKotlin passed. assembleDebug passed. Installed and launched on 192.168.0.21.

Date: 2026-05-27
Module: Debrid
Issue fixed: Debrid Hero Phase 1 integration completed. Existing `view_debrid_hero.xml` reused and added above rows in `fragment_debrid.xml`. `DebridFragment.kt` updates hero from focused `DebridContentItem`. Hero is passive and does not steal focus. Existing row click/detail/source picker/player routes preserved.
Files changed: `fragment_debrid.xml`, `DebridFragment.kt`
Regression risk: Debrid grid navigation, row focus, hero layout clipping, loading states.
QA result: Build/install/device launch confirmed on 192.168.0.21. Manual QA passed on 192.168.0.21. (192.168.0.84 not tested).

Date: 2026-05-27
Module: Live TV
Issue fixed: Live TV category focus escape fix. Fixed by removing category RecyclerView container focus target and native nextFocusLeft route in `fragment_live_3column.xml` and implementing a safe delayed retry pattern for `focusSelectedCategoryItem` in `LiveFragment.kt`. DPAD_LEFT now safely routes to the selected category child item instead of the RecyclerView container.
Files changed: `LiveFragment.kt`, `fragment_live_3column.xml`
Regression risk: Category navigation, channel item D-pad movement, Live zapping.
QA result: compileDebugKotlin passed. assembleDebug passed. install/manual QA passed on 192.168.0.21. Live TV playback and fast zapping preserved.

Date: 2026-05-27
Module: Debrid
Issue fixed: Debrid focus restoration drop. Fixed by implementing `onSaveInstanceState` and `restoreFocusIfPossible` in `DebridFragment.kt` to mirror the VOD/Series pattern, preventing focus loss after returning from detail views or player.
Files changed: `DebridFragment.kt`
Regression risk: Debrid grid navigation, returning from detail view, sidebar focus.
QA result: compileDebugKotlin passed. assembleDebug passed. install/manual QA passed on 192.168.0.21.

Date: 2026-05-27
Module: VOD
Issue fixed: VOD focus restoration drop. Fixed by implementing `onResume`, `onPause`, and `onSaveInstanceState` focus restoration lifecycle hooks in `VodFragment.kt`, mirroring the `SeriesFragment` pattern. Focus is now properly restored when returning from detail views or the player.
Files changed: `VodFragment.kt`
Regression risk: VOD grid navigation, returning from detail view.
QA result: Build/install passed successfully. Manual QA passed on 192.168.0.21. No other modules were touched.

Date: 2026-05-27
Module: Player
Issue fixed: Phase 5A PlayerBufferConfigFactory extraction completed. Extracted pure buffer configuration logic (calculateSmartBuffer, network quality enum, and device capability checks) into a stateless factory.
Files changed: `PlayerActivity.kt`, `PlayerBufferConfigFactory.kt`
Regression risk: IPTV playback, Debrid playback, buffering behavior, live TV zapping, buffer config loading.
QA result: Build/install applied successfully. Manual QA passed on 192.168.0.84 with no behavior regression found. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. EPG Overlay / Live Browser remains mapping-only, not approved.

Date: 2026-05-27
Module: Player / Debrid / Continue Watching
Issue fixed: Old Debrid CW item resume failed by looping/reloading expired proxy/direct URLs instead of fresh RD resolving. Fixed by forcing resolver to use `infoHash`/`magnet` directly for old CW items instead of attempting passthrough with expired URLs. Expected behavior is that retry should use durable hash/magnet for fresh RD resolve.
Files changed: `PlayerActivity.kt`, `DebridPlaybackRepository.kt`, `UnifiedSourceProvider.kt`
Regression risk: Resume for normal Debrid items, resume for newer items, normal Debrid source resolution.
QA result: Build/install applied successfully. Old Debrid Continue Watching item resume functionality is PENDING next-day QA to confirm no stuck reloading and proper fresh resolving.

Date: 2026-05-25
Module: Player (Full App Post-Refactor Smoke Test)
Issue fixed: None. Verification-only smoke test after Phase 1, 3A, and 4B PlayerActivity extractions.
Files changed: None.
Regression risk: N/A.
QA result: Refactor regression status PASS. Full app smoke PARTIAL. Device: 192.168.0.84. IPTV movie PASS. IPTV series PASS. Live TV fullscreen PASS. Live TV fast zapping PASS. Episode Browser PASS. Next Episode prompt PASS. Subtitle/audio PASS. Back button PASS. Debrid source picker PARTIAL (MediaFusion/AIO intermittent API exceptions, StremThru PASS). Debrid CW resume INTERMITTENT. Debrid CW expired resume FAIL (starts then stalls on reloading). Debrid issues are pre-existing, not refactor regressions. Phase 2 BLOCKED. Phase 5+ NOT APPROVED.
Open issues recorded:
- Open Issue A: MediaFusion/AIO intermittent API exceptions during Debrid source resolution.
- Open Issue B: Debrid Continue Watching resume intermittently fails.
- Open Issue C: Debrid expired resume starts playback then stalls on reloading.
Next recommended task: Diagnose Only for Debrid expired resume reload/stall (no fix without approval).

Date: 2026-05-25
Module: Player / Debrid / Continue Watching
Issue fixed: Added regression test coverage proving Debrid history/direct-addon refresh does not replay stale direct AIOStreams playback URLs when direct HTTP passthrough is disabled.
Files changed: `PlayerViewModelDebridDirectPassthroughTest.kt`
Regression risk: None at runtime; test-only change.
QA result: `:app:testDebugUnitTest --tests "*PlayerViewModelDebridDirectPassthroughTest*"` passed. `:app:testDebugUnitTest --tests "*DebridResolutionManager*"` passed through the test-only alias for the current PlayerViewModel-owned resolver path. No runtime code changed.

Date: 2026-05-25
Module: Player / Subtitle / Audio
Issue fixed: Phase 3A Subtitle/Audio extraction completed. Extracted subtitle and audio logic from PlayerActivity into PlayerTrackManager.
Files changed: `PlayerActivity.kt`, `PlayerTrackManager.kt`
Regression risk: IPTV playback, Debrid playback, Subtitle/audio behavior, Live TV zapping.
QA result: Build/install completed. Manual QA passed on 192.168.0.21. No behavior regression found. Phase 2 Network/Stall remains BLOCKED / NOT APPROVED. Next extraction phase is NOT approved yet.

Date: 2026-05-25
Module: Player
Issue fixed: Phase 2 PlayerNetworkStallManager extraction mapping completed and reviewed. Full extraction is BLOCKED / NOT APPROVED due to high coupling with Player lifecycle, ExoPlayer instances, and Debrid re-resolution.
Files changed: None (Documentation updated only)
Regression risk: None (No runtime code changed).
QA result: N/A. Next step is either optional Phase 2B mapping (`PlayerStallDetector`) or skipping to lower-risk cleanup.

Date: 2026-05-25
Module: Player / History
Issue fixed: Phase 1 PlayerHistoryManager extraction completed. Refactored history-related logic out of PlayerActivity into a DefaultLifecycleObserver.
Files changed: `PlayerActivity.kt`, `PlayerHistoryManager.kt`
Regression risk: Continue Watching resume, Live TV history save, Debrid resume.
QA result: Build/installation passed on 192.168.0.21. Manual QA confirmed Debrid Continue Watching same-day resume works, and VOD playback triggers onStop history save correctly. No behavior regression reported in tested history path. Phase 2 PlayerNetworkStallManager remains NOT APPROVED / DO NOT START.

Date: 2026-05-24
Module: Debrid / Player / Continue Watching
Issue fixed: Debrid Continue Watching resume no longer replays saved null-expiry history URLs as safe direct playback. Player resume now treats Debrid history with missing `expiresAt` as refresh-required, fresh-resolves hash/magnet entries, and allows freshly fetched direct addon results to pass through.
Files changed: `PlayerActivity.kt`
Regression risk: Debrid Continue Watching movie/series resume, direct addon resume, resolver-backed hash/magnet resume, normal Debrid source picker playback.
QA result: `:app:compileDebugKotlin` and `:app:assembleDebug` passed. Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`; app PIDs confirmed. Content-specific manual Debrid/Continue Watching regression remains required.
Follow-up QA: 2026-05-24 expired/null `expiresAt` Debrid movie Continue Watching resume passed on `192.168.0.21:5555`. Home routed the expired item to Player, PlayerActivity triggered Debrid refresh instead of direct stale URL initialization, `PlaybackResolver` reported success, and playback started without a false Real-Debrid config error.

Date: 2026-05-23
Module: Player
Issue fixed: Phantom audio playing in the background after returning from the player due to `Handler` postDelayed recreating `ExoPlayer` instances after the activity finished.
Files changed: `PlayerActivity.kt`
Regression risk: ExoPlayer initialization, network retry delay logic, and stream switching logic.
QA result: `:app:assembleDebug` passed. `isFinishing || isDestroyed` checks securely prevent recreation.

Date: 2026-05-23
Module: Live TV
Issue fixed: Channel list focus could get stuck or jump back to the category sidebar because focus-return paths targeted the category RecyclerView container instead of a bound category child item.
Files changed: `LiveFragment.kt`
Regression risk: Category navigation, channel item D-pad movement, fullscreen return focus, Live zapping.
QA result: `:app:assembleDebug` passed. Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`. Manual TV D-pad regression still required.

Date: 2026-05-23
Module: Player / Live TV
Issue fixed: Fast Live TV zapping race condition causing video/metadata mismatch. Fixed by using latest-zap-wins logic with `zapRequestId` and `streamId`.
Files changed: `PlayerActivity.kt`, `PlayerViewModel.kt`
Regression risk: Live zapping speed, fullscreen return, async EPG loading.
QA result: Fast zapping in both directions passed on target TVs. Video and UI stay in sync.

Date: 2026-05-23
Module: Debrid / Player
Issue fixed: Debrid playback "Real Debrid config missing" regression caused by Player/Episode Browser changes has been resolved.
Files changed: `PlayerViewModel.kt`
Regression risk: Direct Debrid/addon HTTP streams passing through Real-Debrid config validation instead of playing directly.
QA result: Debrid Movie source click works. Debrid Series source click works. IPTV and Live TV continue working.

### Phase 4B: PlayerNextEpisodeManager Extraction
- Extracted Next Episode prompt and binge playback logic into PlayerNextEpisodeManager.kt
- Wired callbacks between PlayerActivity and the new manager
- Compilation errors fixed, clean build passed
- Installed on 192.168.0.21. Manual QA passed. Phase 4B VERIFIED / CLOSED.

### Phase 2: Memory & Lifecycle Cleanup
- **SeriesFragment.kt**: Nullified RecyclerView adapters in onDestroyView() to fix massive memory leaks resulting from retained PagingData and ViewHolders.
- **LiveFragment.kt**, **VodFragment.kt**, **SeriesFragment.kt**: Added if (!isAdded) return@post guards inside async focus restorations (post and postDelayed) to fix crash patterns and phantom focus states when rapidly switching tabs during UI delays.
- **Verification**: Conducted adb monkey stress test (--pct-syskeys 0 -v 500) with zero memory or lifecycle crashes observed.

### Phase 3: Paging3 Buffer Expansion
- **LiveViewModel.kt**, **SeriesViewModel.kt**, **VodViewModel.kt**: Increased Paging3 pageSize from 20 to 60, and prefetchDistance from 5 to 20. This prevents loading jitter and stuttering when rapidly scrolling through long lists via D-pad, ensuring a seamless user experience.
- **Verification**: Built assembleDebug successfully. Ran ADB monkey test (--pct-syskeys 0 -v 500) on device 192.168.0.84:5555 with 0 crashes.
P o l i s h e d   D e b r i d   s i d e b a r :   a d j u s t e d   w i d t h   t o   1 7 6 d p ,   r e m o v e d   b r a n d   t e x t ,   u p d a t e d   D i s c o v e r   i c o n   t o   h o m e .  
 
- 2026-05-29: Debrid source picker Language dropdown dynamically populated from active sources.
- 2026-05-29: Debrid source picker visual UI polished (solid dark panel, cyan focused border, fixed footer overlap, aligned header).
- 2026-05-29: Source picker final UI polish completed. Reduced focused row glow intensity, removed layout scaling jump, kept normal rows readable (alpha 0.85), updated filter chips to pill shapes, and increased vertical space to fit 4 source rows on 1080p. Tested and passed on .21 and .84 devices.
- 2026-05-29: Source picker final UI tuning completed. Made panel 100% solid dark, removed cyan glow and reduced translationZ to 4f, kept normal rows bright and separated footer with 1dp border. Tested and passed on .21 and .84 devices.
- 2026-05-29: Fixed language filter bug in source picker. Filter now correctly translates selected dropdown label to matching language codes from source metadata using SourceFilterUtils.mapLanguageCodeToName, preventing generic MULTI sources from showing up under specific language searches.
-   2 0 2 6 - 0 5 - 2 9 :   F i x e d   ' N o   a d d i t i o n a l   s o u r c e s   a v a i l a b l e '   r e g r e s s i o n   i n   s o u r c e   p i c k e r   b y   i n t r o d u c i n g   s o r t L a n g u a g e   t o   S o u r c e F i l t e r S t a t e .   T h i s   p r e v e n t s   g l o b a l   l a n g u a g e   s e t t i n g s   f r o m   a g g r e s s i v e l y   f i l t e r i n g   o u t   u n m a t c h e d   s o u r c e s   b e f o r e   t h e y   r e a c h   t h e   U I .  
 
- 2026-05-29: Fixed source picker scrolling bug where the list would jump back to the top when navigating past the first few visible rows. The bug was caused by duplicate streamIds breaking RecyclerView focus with hasStableIds(true). Appended index to streamIds in UnifiedSourceProvider.kt to ensure uniqueness.
- 2026-05-29: Fixed source picker RD Cached Type option UX. `SourceSelectionBottomSheet.kt` now builds Type dropdown options from the current pre-type filtered list, so direct-only lists no longer offer RD Cached and stale unavailable Type selections reset to All. Build passed (`clean :app:compileDebugKotlin`, `:app:assembleDebug`), APK installed on 192.168.0.84 and 192.168.0.21, and `.84` QA confirmed direct-only Type options omit RD Cached, direct rows remain visible under All/Direct, English filter stays stable, direct playback launches, and Back returns to Debrid. Mixed verified-cached positive QA remains pending; redacted log scan still shows pre-existing URL/token/hash-like log patterns.
- 2026-05-29: Cleaned Debrid/source log hygiene. `SensitiveLogRedactor` now emits placeholder tokens instead of URL-like redacted strings, Real-Debrid remote logs redact credentials/hashes and avoid raw throwable output, source aggregation logs no longer print source titles/IDs or per-source detail dumps, and image loading logs redact poster/backdrop URLs. Build passed (`clean :app:compileDebugKotlin`, `:app:assembleDebug`), APK installed on `192.168.0.84:5555`, and app-process log scan after Debrid movie source picker/direct playback returned zero matches for raw URLs, magnets, token params, username/password words, or long hash-like strings.
