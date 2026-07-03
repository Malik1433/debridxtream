# AUDIT PHASE 2 — Live TV: Zapping, EPG, Channel List Performance (Diagnose Only)

Status: diagnose-only audit. NO code changed. `LIVE_MODULE_REPORT.md` NOT updated.
Date: 2026-06-12
Mode: Full professional audit of the Live TV experience vs TiviMate-level polish.

## Scope Inspected
- `ui/live/LiveFragment.kt` (+ trailing dead adapters)
- `ui/live/ChannelPagingAdapter.kt` (+ `ChannelPagingViewHolder`)
- `ui/live/SidebarCategoryAdapter.kt`
- `ui/live/PreviewPlayerPanel.kt`
- `ui/live/ChannelAdapter.kt` (referenced, apparently unused)
- `util/EpgCache.kt`
- `data/epg/EpgParser.kt`
- `player/stabilized/BrowserAdapters.kt` (`BrowserCategoryAdapter`, `BrowserChannelAdapter` — fullscreen browser)
- `player/stabilized/PlayerViewModel.kt` (zapping `ZapState`/`moveZap`/`initLiveZapping`, `observeEpg`, browser category/channel loading — read-only)

Out-of-scope (pointer-only, NOT inspected): `XtreamRepository` (`enrichChannelsWithCurrentEpg`, `fetchShortEpgNowNext`, `getCurrentProgram`/`getNextProgram`), `EpgSyncWorker`, `FocusGlintHelper`, `MemoryManager`. Player-internal EPG overlay rendering is owned by Phase 1 (`AUDIT_PHASE1_PLAYER.md`); referenced where a Live symptom traces into it.

Protected (NOT modified / NOT "re-verified by changing"): the zapping fix — `zapRequestId`/latest-zap-wins in `PlayerActivity.zapChannel`+`performSeamlessSwitch`, and `streamId` overlay filtering in `observeOverlayState`/`PlayerOverlayUiState.streamId`. Flagged **[TOUCHES PROTECTED]** where relevant.

Legend: ID • file:line • symptom • root-cause hypothesis • reproduction path • risk.

---

## Zapping Fix Verification (read-only — no change made)
The latest-zap-wins guard appears **airtight for the video/metadata mismatch it was built to fix**, by three cooperating mechanisms:
1. `PlayerActivity.zapChannel` (Phase 1 lines 714–741) increments `currentZapRequestId` and updates `contentId`/`currentUrl`/`channelLogoUrl` **synchronously on the main thread** before the switch.
2. `performSeamlessSwitch(url, reqId)` drops stale requests via `reqId != currentZapRequestId` (Phase 1 lines 803–810).
3. EPG overlay is filtered by `streamId`: `observeOverlayState` discards any emission whose `streamId != contentId` (Phase 1 lines 1061–1070); `PlayerViewModel.observeEpg` stamps every `PlayerOverlayUiState` with the requesting `streamId` (lines 865–971), and cancels the prior `epgJob`/`epgRefreshJob` on each call (lines 854–855).

Observation (NOT a defect, do not change): because the immediate `performSeamlessSwitch` call from `zapChannel` is synchronous, `reqId == currentZapRequestId` always holds at that call site, so the request-id drop is effectively defensive; the real correctness guarantee comes from the synchronous `contentId` update + `streamId` overlay filter. The only residual concern is **performance** under held-key surfing (finding H4), not correctness. The fix is left untouched.

---

## CRITICAL

### C1 — `notifyEpgUpdated` does an O(N) main-thread snapshot scan per EPG update; O(N²) across a category warmup
- **Where:** `LiveFragment.notifyEpgUpdated` lines 538–548, driven by `observeEpgUpdates` (482–502) collecting `EpgCache.updates`; `EpgCache` emits one `_updates` event **per channel** (EpgCache 110, 145).
- **Symptom:** Every per-channel EPG refresh emits a key; the collector then calls `channelPagingAdapter.snapshot()` (a full list copy) and iterates **all** items recomputing each item's key to find matches. With a large category, the EPG warmup/preload emits N events, each triggering an N-item scan → ~O(N²) main-thread work plus N `snapshot()` allocations while the user is trying to scroll.
- **Root cause:** No index from channelKey → adapter position; the snapshot is rescanned on every single-channel update instead of mapping the key to a known position.
- **Reproduction:** Open a category with several hundred channels; as EPG populates (preload of first 12 + per-bind misses), observe stutter in the channel list. The larger the category, the worse.
- **Risk:** Visible list jank during EPG population. **Smoothness.**

---

## HIGH

### H1 — EPG fetched inside `onBindViewHolder` triggers a refresh storm during scroll
- **Where:** `ChannelPagingAdapter.onBindViewHolder` lines 47–55 calls `epgProvider(channel)`; `LiveFragment` epgProvider (151–167) calls `EpgCache.getEpgData` → on miss `refreshEpgData` (EpgCache 58–73, 79–125), which launches a background job that hits `repo.getCurrentProgram`/`getNextProgram` and, if empty, `repo.fetchShortEpgNowNext` (**network** per stream).
- **Symptom:** Fast-scrolling a large category binds many rows; each cache-miss row spawns a background DB/short-EPG-network job. `inFlightRefresh` dedups per channelKey, but distinct channels still fan out to many concurrent provider calls, each later emitting an update (feeds C1).
- **Root cause:** EPG retrieval is lazily coupled to bind during scroll rather than bounded/throttled to the settled viewport. `warmVisibleEpgCache` only warms the first 12 items once per category (LiveFragment 858–877), so everything below row 12 falls back to per-bind misses.
- **Reproduction:** Scroll quickly through a 500-channel category → many `fetchShortEpgNowNext` calls fire; on a slow provider this also slows real channel interactions.
- **Risk:** Provider hammering + scroll churn. **Smoothness + network load.** (Repo internals out of scope; trigger is the bind-time `epgProvider`.)

### H2 — `EpgParser` allocates a `Calendar` ×2 per programme and recompiles a `Regex` per text node
- **Where:** `EpgParser.parseTimestamp` lines 491–493 (`Calendar.getInstance(TimeZone.getTimeZone("UTC"))` for every `start` and `stop`), and `cleanTextContent` line 445 (`Regex("<[^>]+>")` constructed inline per call, invoked for every title/desc/category).
- **Symptom:** A full XMLTV guide can contain hundreds of thousands of programmes; this allocates ~2 `Calendar` objects per programme and recompiles the HTML-stripping regex per text node, producing heavy CPU + GC churn during parse.
- **Root cause:** Per-element allocation of reusable objects; the regex and a UTC `Calendar` should be hoisted to constants/`ThreadLocal`.
- **Reproduction:** Trigger a full EPG sync against a provider with a large XMLTV guide; observe elevated CPU/GC and slow parse vs a polished IPTV app.
- **Risk:** Slow EPG sync, GC pressure, possible jank if it competes with UI. **EPG parse performance.**

### H3 — Held D-pad channel surf storms full player rebuilds (no debounce/coalescing)
- **Where:** `PlayerActivity.dispatchKeyEvent` (Phase 1 lines 2290–2295) maps each `KEYCODE_DPAD_UP/DOWN`/`CHANNEL_UP/DOWN` `ACTION_DOWN` to `zapChannel(±1)`; `zapChannel` → `performSeamlessSwitch` does `stop()`+`clearMediaItems()`+`setMediaItem`+`prepare()` each time (Phase 1 822–844).
- **Symptom:** Holding the channel-up/down key emits repeated `ACTION_DOWN` events, each performing a complete media-pipeline reset, so a held surf is a storm of teardown/rebuilds → choppy surfing and (per Phase 1 C1) a burst of disk-cache writes.
- **Root cause:** Each key repeat zaps immediately; there is no key-repeat coalescing / settle-debounce so only the final target actually loads.
- **Reproduction:** In fullscreen Live, hold DPAD_UP for ~2s → many channels are torn down and rebuilt in sequence instead of landing smoothly on the final channel.
- **Risk:** Janky channel surfing. **[TOUCHES PROTECTED]** (zapping). Correctness is preserved by the zap fix; this is the rebuild-cost residual. Root cost is in the Player spine (Phase 1 C1/H1) — referenced, not re-audited.

### H4 — Fullscreen browser category switch blocks the channel list on full EPG enrichment
- **Where:** `PlayerViewModel.selectBrowserCategory` lines 1095–1132 — after loading channels it `repository.enrichChannelsWithCurrentEpg(channels)` under `Dispatchers.IO` and only emits the list **after** enrichment completes (1113–1124).
- **Symptom:** Selecting a category in the in-player channel browser shows nothing until every channel in that category is EPG-enriched; large categories produce a visible delay before channels appear.
- **Root cause:** Channel list emission is gated on a bulk EPG enrichment pass instead of emitting channels first and enriching incrementally.
- **Reproduction:** Open the fullscreen channel browser, switch to a large category → list appears late.
- **Risk:** Category-switch delay in the player browser. **Smoothness.** (`enrichChannelsWithCurrentEpg` impl out of scope; trigger is here.)

---

## MEDIUM

### M1 — Per-bind allocation churn in channel view holders
- **Where:** `ChannelPagingViewHolder.bind`/`bindHorizontalCard`/`loadChannelImage` lines 159–313 (and `BrowserChannelAdapter.bind` 195–268): each bind allocates a new `GlideUrl` + `LazyHeaders` + `RequestOptions(CenterCrop, RoundedCorners(16))`, installs fresh `OnFocusChangeListener`/`OnKeyListener`/`setOnClickListener`/`setOnLongClickListener` lambdas, and calls `FocusGlintHelper.attach(itemView)` every bind (193/268/287).
- **Root cause:** Reusable objects/listeners and the focus-glint attach are rebuilt per bind instead of once per view-holder creation.
- **Reproduction:** Fast-scroll the channel list → steady allocation/GC churn; `FocusGlintHelper.attach` re-running per bind risks duplicate listener registration (impl out of scope).
- **Risk:** Scroll-time GC churn / potential listener buildup. **Smoothness.**

### M2 — Oversized RecyclerView view caches hold many image-bearing rows
- **Where:** `LiveFragment.onViewCreated` lines 267–268: `setItemViewCacheSize(40)` and `recycledViewPool.setMaxRecycledViews(0, 60)`.
- **Symptom:** Up to 40 offscreen item views (each holding a Glide bitmap) are retained plus a 60-deep pool, inflating memory on low-RAM TV boxes.
- **Root cause:** Cache sizes tuned for scroll smoothness without accounting for per-row bitmap cost.
- **Reproduction:** Browse large categories on a low-RAM device; watch heap growth / Glide memory.
- **Risk:** Memory pressure (interacts with the MemoryManager emergency-cleanup path 334–352).

### M3 — Channel-list empty-state flash risk on fast category switch
- **Where:** `LiveFragment.renderChannelLoadState` lines 1200–1224: `NotLoading` + `itemCount == 0` → `showEmptyState("No channels")`, with no `isSwitchingCategory` guard.
- **Symptom:** During a category switch, a brief `NotLoading`/0-item window can flash "No channels available" before the new PagingData settles — exactly APP_FAILED_PATTERNS #2 / DO_NOT_REPEAT "do not assume itemCount during refresh."
- **Root cause:** Empty state is derived purely from `refresh` load state + `itemCount`, which Paging can momentarily report as `NotLoading`/0 mid-switch.
- **Reproduction:** Rapidly switch between categories → intermittent "No channels" flash.
- **Risk:** UX flicker. **Polish-adjacent but user-visible.**

### M4 — Category sidebar rebinds all rows on every search keystroke / state emission
- **Where:** `SidebarCategoryAdapter.updateCategories` lines 77–90 (`notifyItemRangeChanged(0, newSize)` or `notifyDataSetChanged()`), called from `LiveFragment.updateCategoryList` (689–732) on every `uiState` emission, every favorites emission, and every `updateCategorySearch` keystroke (667–672).
- **Symptom:** Despite `setHasStableIds(true)`, category updates rebind every row (each bind does flag/icon resolution, color/typeface, size recompute, focus-anim setup), so typing in the category search box rebinds the whole sidebar per keystroke.
- **Root cause:** Positional `notify*` instead of `DiffUtil`/payloaded updates.
- **Reproduction:** Type in the category search field → sidebar rows visibly re-render each keystroke.
- **Risk:** Typing/scroll jank in the sidebar. **Smoothness.**

### M5 — `EpgParser.parseStream` launches a detached IO scope with a shared cancel handle
- **Where:** `EpgParser.parseStream` lines 94–199: creates `CoroutineScope(Dispatchers.IO).launch { ... }` inside a suspend fun, stores it in the object-level `parsingJob` (shared), then `parsingJob?.join()`.
- **Symptom:** (a) The launched job is rooted in a fresh detached scope, so if the caller's coroutine is cancelled the `join()` is cancelled but the parse keeps running detached. (b) Two concurrent parses clobber the shared `parsingJob`, so the memory-pressure callback (27–34) and a second caller can only cancel the latest job.
- **Root cause:** Non-structured concurrency + shared mutable job handle on a singleton `object`.
- **Reproduction:** Start an EPG parse, navigate away (caller cancels) → parse continues; or overlap two syncs → first becomes uncancellable via `parsingJob`.
- **Risk:** Runaway/uncancellable parse, wasted CPU. **EPG parse correctness.**

### M6 — `parseTimestamp` returns "now" for malformed/short timestamps
- **Where:** `EpgParser.parseTimestamp` lines 477–480 and 520–523 fall back to `System.currentTimeMillis()`.
- **Symptom:** A programme with a missing/short/invalid `start` or `stop` is dated to the current instant, so it can be selected as the "now playing" program (now/next logic uses `nowMs in start..stop`), displacing the real current program.
- **Root cause:** Error fallback fabricates a plausible-but-wrong timestamp instead of marking the programme invalid/skippable.
- **Reproduction:** Provider emits a programme with a malformed `start` → it surfaces as the current program on that channel's card/overlay.
- **Risk:** Wrong EPG now/next display. **EPG correctness.**

### M7 — `EpgParser.parse(String)` buffers the whole guide as a String
- **Where:** `EpgParser.parse(xmlContent: String)` lines 37–58 (size-guarded, then `StringReader(xmlContent)`).
- **Symptom:** Any caller using the `String` overload has already loaded the entire XMLTV into memory — the exact OOM pattern APP_FAILED_PATTERNS #10 warns against. The streaming `parseInputStream`/`parseStream(Reader)` paths are safe; the `String` overload is the risk.
- **Root cause:** A non-streaming convenience overload coexists with the streaming path.
- **Reproduction:** Confirm whether any caller (e.g. `EpgSyncWorker`, out of scope) feeds a large guide as `String`.
- **Risk:** OOM on RAM-constrained TVs. **Pointer — verify callers.**

### M8 — Repeated `snapshot()` allocations across Live operations
- **Where:** `channelPagingAdapter.snapshot()` in `notifyEpgUpdated` (540), `notifyFavoriteChanged` (1339), `restoreChannelFocusIfNeeded` (1112), `resolveLiveChannelIds` (1086), `handleLivePlayerResult` (1074), `warmVisibleEpgCache` (860).
- **Symptom:** Each call materializes an `ItemSnapshotList`; `notifyEpgUpdated` does it per EPG update (compounds C1).
- **Root cause:** Snapshot used as a lookup primitive instead of a cached index.
- **Risk:** Allocation churn on hot paths. **Smoothness.**

### M9 — EPG progress bars are computed once and never tick (stale progress)
- **Where:** channel card `pbEpgProgress` (`ChannelPagingViewHolder.bindHorizontalCard` 208–218), preview `previewProgressBar` (`PreviewPlayerPanel.updateEpg` 152–162), and the in-player EPG overlay "now" progress (PlayerActivity.renderOverlay, Phase 1 1478–1485).
- **Symptom:** Each progress value is a one-shot computation from `System.currentTimeMillis()` at bind/emit time and never advances; over a long-running program the bar stays frozen and can be minutes stale until a rebind or new EPG emission.
- **Root cause:** No per-minute UI tick to refresh elapsed-progress for the visible "now" program.
- **Reproduction:** Leave a channel card / preview / overlay visible for several minutes → progress bar does not move.
- **Risk:** Stale guide progress display. Card/preview are Live; the **overlay** root is Player-internal → see Phase 1 (referenced, not re-audited).

---

## MINOR

### Mi1 — Dead Live adapters / unused field
- `LiveFragment.kt` lines 1468–1549 define `CategoryAdapter`, `CategoryViewHolder`, `CategoryAdapterNew`, `CategoryViewHolderNew` with no callers (verified by search; the settings `CategoryAdapter` is a separate private class). `ui/live/ChannelAdapter.kt` has no constructor call anywhere (grep for `ChannelAdapter(` finds none) — apparently superseded by `ChannelPagingAdapter`. `PlayerViewModel.refreshAttempts` (line 148) is declared but never read/written.
- **Risk:** Dead weight / maintenance confusion.

### Mi2 — Favorites double-update on toggle
- **Where:** `handleFavoriteLongPress` optimistically mutates `favoriteStreamIds` + `notifyFavoriteChanged` (1300–1303), while `observeFavorites` (457–480) also clears+refills the set and re-pushes sidebar counts on the subsequent DB flow emission.
- **Risk:** Redundant rebind/count work and a brief window where both paths fire. Low.

### Mi3 — `EpgCache.getEpgData` logs hit/miss on every call
- **Where:** EpgCache lines 66 & 71 (`Log.d` per call), invoked per bind during scroll.
- **Risk:** Log spam / minor overhead during fast scroll.

### Mi4 — Stale EpgCache entries only purged once per fragment create
- **Where:** `EpgCache.cleanupStaleEntries` (170–184) is called only from `LiveFragment.primeEpgData` (651–665) on `onViewCreated`. During a long zapping/browsing session without leaving the screen, logically-stale entries are retained in `epgCache`/`lastUpdateTimes` until the next cleanup.
- **Risk:** Slow memory creep over long sessions (bounded by channel count). Low.

### Mi5 — `EpgParser` lateinit init ordering
- **Where:** `EpgParser` is an `object` with `lateinit var memoryManager` (22); `parse()` (39) reads `memoryManager` before any null guard.
- **Symptom:** Calling `parse*` before `initialize(context)` throws `UninitializedPropertyAccessException`.
- **Risk:** Crash if invocation order regresses. Low (current callers presumably init first).

---

## POLISH

### P1 — Hardcoded desktop Chrome User-Agent for channel-logo loads
- `ChannelPagingViewHolder.loadChannelImage` (299) and `BrowserChannelAdapter.bind` (206) attach a Windows Chrome UA to every logo `GlideUrl`. Works, but is a fixed spoof string duplicated in two places.

### P2 — Per-bind name-heuristic badge computation
- `is4k`/`isHd`/`isPay` via `name.contains(...)` recomputed on every bind (ChannelPagingViewHolder 220–226; BrowserChannelAdapter 222–224). Micro-cost; could be precomputed on the model.

### P3 — Static EPG progress (cosmetic facet of M9)
- Even when EPG data is correct, the absence of a minute-tick makes the guide feel "dead" vs TiviMate's advancing progress bars.

---

## Cross-Cutting Themes (for later fix sequencing — NOT fixing now)
1. **EPG-on-scroll coupling** (C1, H1, M8): bind-time `EpgCache.getEpgData` + per-channel `_updates` emits + full-snapshot rescans form one O(N²)-ish loop. Highest Live-smoothness leverage; a channelKey→position index and viewport-bounded warmup would dissolve C1/H1/M8 together.
2. **EPG parse cost** (H2, M5, M6, M7): hoist `Calendar`/`Regex`, use structured concurrency, treat malformed timestamps as skippable, and retire the `String` parse overload for large guides.
3. **Channel-surf rebuild cost** (H3): debounce held-key zapping so only the settled target loads — correctness already guaranteed by the (untouched) zap fix; this is pure smoothness and shares root with Phase 1 C1/H1.
4. **Bind/cache allocation** (M1, M2, M4): per-bind listener/Glide allocation + oversized caches + positional sidebar notifies.
5. **Dead code** (Mi1): safe to prune.

## QA / Verification notes
- Every finding lists a reproduction path or exact code path. No build was run (diagnose-only).
- The zapping fix (`zapRequestId` / `streamId` overlay filtering) was analyzed read-only and left unchanged; it appears airtight for the video/metadata mismatch.
- Items marked **[TOUCHES PROTECTED]** (H3) or that reference Player internals (H3, M9) defer to `AUDIT_PHASE1_PLAYER.md` for the Player-spine root cause and must regress Live zapping if ever fixed.
- No tokens, stream URLs, or credentials are reproduced in this report.
