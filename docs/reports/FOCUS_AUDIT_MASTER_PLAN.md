# App-Wide TV Focus & D-pad Navigation — Audit Master Plan

**Date:** 2026-07-13
**Mode:** Diagnose Only — zero code changes made in this pass. Documentation + prioritization only.
**Scope:** Entire app (not just Live Player), audited against Android TV design guidelines, Amazon Fire TV app-quality guidelines, and 10-foot-UI focus conventions.
**Method:** 6 parallel per-area reads of Kotlin + XML layouts + focus-state drawables. Areas: (A) Home/Shell/Login, (B) Live TV browse + EPG guide, (C) Player OSD & overlays, (D) VOD + Series, (E) Debrid/Stremio home, (F) Search/Settings/Dialogs/misc.
**Audit dimensions (dim#):** 1 Visibility · 2 Initial focus · 3 Traversal order · 4 Traps/dead-ends & modal containment · 5 Back restoration · 6 Async/loading focus survival · 7 Overlay/OSD scoping · 8 RecyclerView recycling stale-focus · 9 Search/text-input · 10 Settings/forms · 11 Custom focusable components · 12 Focus latency · 13 TalkBack/a11y · 14 Remote edge/auto-repeat.
**ID convention:** `F<area><n>` — FA=Home, FB=Live/EPG, FC=Player, FD=VOD/Series, FE=Debrid/Stremio, FF=Search/Settings.

---

## 0. Headline

- **No P0 hard focus traps exist on any *reachable* screen.** Every overlay can be escaped (BACK/LEFT) and the remote can always reach playback controls. The app's core focus architecture (Home focus managers, TopAlignRecyclerView, EpgGridView custom grid, player BACK-precedence stack) is sound.
- **1 latent P0-class crash** exists in dead-but-still-compiled code (`DebridDiscoverActivity`) — harmless today because it's unreachable, dangerous the moment that path is re-enabled.
- **1 P1** on a reachable screen: an entire control cluster (VOD sort chips + column toggle) is unreachable by remote.
- **~24 P2** real UX degraders, dominated by **one recurring anti-pattern**: *refresh-steals-focus* (async/periodic data updates that re-focus or rebuild a list regardless of where the user is). This is the same class as the just-fixed EPG recycling bug and is the single highest-leverage thing to fix.
- **TalkBack is systemically absent** app-wide (P4) — not a D-pad blocker, but a real gap if screen-reader support is a goal.

---

## 1. Cross-cutting patterns (surfaced beyond the checklist)

**CC-1 — "Refresh steals focus" (the dominant bug class).** ~8 sites call `notifyDataSetChanged()`/`submitList()` + `scrollToPosition(0)`/`requestFocus()` on *every* async emission or periodic tick, ignoring where focus currently is. A late paging append, a 30-second EPG tick, or a watched-progress refresh on return-from-player yanks focus back into the list or drops it entirely.
Sites: `SearchFragment`/`StremioSearchResultAdapter` (FF), `VodFragment` + `SeriesFragment` AdapterDataObservers (FD), `SeriesDetailFragmentV2` stream-panel resubmit (FD), `CinEpisodeAdapter` onResume (FD), `LiveSurfChannelAdapter` 30s tick (FC), `LiveTvGuideFragment` requestFocus-on-every-state (FB), `EpgGridView.setData` reset (FB).
**Recommended systemic fix:** a shared *focus-preserving submit* helper — capture focused stable-id, DiffUtil/ListAdapter partial update, restore focus by id; and only auto-focus/scroll-to-top when the query/category actually changed AND the list already holds focus. (Home already does this correctly via `captureContentFocusSnapshot`/`restoreContentFocusAfterDataUpdate` — use it as the template.)

**CC-2 — Missing/ambiguous initial focus.** Several screens open with no explicit `requestFocus`, so Android auto-picks the first focusable — often the *wrong or destructive* one: Settings (first category, non-deterministic), `SeriesDetailFragmentV2`, `DebridSeeAllActivity`, `FavoritesFragment` (lands on **Clear-All**, destructive), `MediaFusionConfigActivity` (lands on an EditText → soft IME pops), `RecoveryActivity` (lands on **Clear Cache & Reset**, destructive). **Convention to adopt:** every screen posts initial focus to its primary *safe* control after first layout/first data.

**CC-3 — Focusable dead controls.** Focusable elements with no `onClick`: Home hero favorite (FA), Stremio hero watchlist + nav bell + nav profile (FE), `MovieDetailFragmentV2` favorite (FD), library "Sort"/discover "See all" headers (FE). Each is a remote dead-end that looks actionable. **Rule:** wire it or make it `focusable=false` until implemented.

**CC-4 — Overlay containment gaps.** Some overlays don't block background focus and/or don't handle BACK: Login QR pairing overlay (focus leaks to EditTexts behind it; no BACK dismiss — flagged by two agents), player surf drawer (transport controls stay focusable behind the scrim), guide search (soft IME can occlude results). Home's `StremioSearchOverlay` does this correctly (toggles `FOCUS_BLOCK_DESCENDANTS` + restores). **Adopt one shared overlay-open helper:** block descendant focus on background layers, set initial focus inside, handle BACK to dismiss, restore focus to the trigger on close.

**CC-5 — Edge behavior is inconsistent across screens.** Some consume at the edge (stop), some wrap (`FocusTrapHelper loopFocus=true` on VOD/Series sidebars), some let focus escape to a surprising target (EPG grid DOWN → floating "Jump to Now"). Pick one convention (recommended: stop at vertical edges, no wrap) and apply app-wide.

**CC-6 — TalkBack largely absent (systemic P4).** Icon-only nav rail, poster cards ("Movie Poster" static or none), settings toggles (no state semantics), player favorite, EPG guide cells (position-driven, non-focusable) have no/again-uninformative `contentDescription`. Not a D-pad blocker; a real a11y gap.

**CC-7 — Dead code inflates the focus graph.** Unreachable but compiled: `DebridFragment`, `SidebarFocusController`, `LockableLinearLayoutManager`, `DebridDiscoverActivity` (+ latent crash), `DebridSearchActivity`, `FeaturedAdapter`, `NewAddedAdapter`, `VodPagingAdapter`, `SeriesEpisodeAdapter` (as adapter), `SeasonsAdapterV2`, `VodContinueWatchingAdapter`, `SeriesContinueWatchingAdapter`, `SearchEpgAdapter`+`item_search_result.xml`. Deleting these removes confusion and the one latent crash landmine.

---

## 2. Phase-by-phase findings

### Phase A — Home / App Shell / Login
- **FA1 [P2] (dim11)** `HomeKeyRoutingManager.kt:69` overwrites the Continue-Watching ViewHolder's own key listener (`ContinueWatchingAdapter.kt:145`) → long-press "Clear/Open detail" actions menu is unreachable by remote.
- **FA2 [P2] (dim7)** `fragment_login.xml:637` (qr_overlay `focusable=false`) + `LoginQrOverlayController.kt:96` — QR overlay doesn't scope focus; UP/DOWN from Close lands on login EditTexts *behind* the opaque overlay (invisible focus).
- **FA3 [P2] (dim1,2)** `btn_login_modern.xml` + `InitialSyncFragment.kt:117` — sync-error "Retry Sync" button has no `state_focused` (invisible focus) and `showError()` never `requestFocus()`s it; the only recovery control is invisible + unfocused.
- **FA4 [P3] (dim5)** `MainActivity.kt:206-238` — returning to Home rebuilds HomeFragment + a fresh focus manager, discarding `lastFocusedSidebarItemId`; focus lands on a content card, not the sidebar item launched from.
- **FA5 [P3] (dim11)** `HomeHeroManager.kt:126` — hero favorite button focusable but no onClick (dead control; see CC-3).
- **FA6 [P3] (dim2,6)** `HomeFocusManager.kt:78-83` — initial focus on first content rail (below the 340dp hero) scrolls the hero partly off-screen and skips the hero Play button.
- **FA7 [P3] (dim6)** `HomeFocusManager.kt:73` — arriving data steals focus off a sidebar item the user moved to during load.
- **FA8 [P4] (dim13)** `item_sidebar_nav.xml`, `fragment_home_cinematic.xml:325` — nav rail + hero icons have no contentDescription (TalkBack silent).
- **CLEAN:** ExitDialog, LoginFragment field chain + focused drawables, Home sidebar (cyan capsule + flyout), content card focus visuals, async focus snapshot/restore, cross-rail column preservation, empty-state fallback.

### Phase B — Live TV browse + EPG guide
- **FB1 [P2] (dim3,14)** `EpgGridView.kt:534-541` — vertical D-pad keeps the same program *index*, not time-column, so UP/DOWN jumps to a wildly different time + large horizontal scroll.
- **FB2 [P2] (dim6)** `EpgGridView.kt:154-173` — every `setData()` hard-resets focusRow/focusProg/scrollY, so any channel re-emission bumps the user back to the top.
- **FB3 [P2] (dim6,7)** `LiveTvGuideFragment.kt:242` — `epgGrid.requestFocus()` on every non-loading emission; selecting a day tab yanks focus into the grid, so you can't tab across days.
- **FB4 [P2] (dim9)** `LiveTvGuideFragment.kt:596-601` — guide search pops the system soft IME inside an AlertDialog (can occlude results; awkward handoff) instead of the app's on-screen key grid.
- **FB5 [P2] (dim3)** `LiveFragment.kt:301-303,882-899` — UP from the first channel is consumed to a no-op, so the channel column can't reach the top-bar icons directly.
- **FB6 [P2] (dim12,14)** `LiveFragment.kt:821-859` — channel UP/DOWN driven by manual scroll + `postDelayed(50ms)` + retries instead of native focus → latency + drop risk under fast auto-repeat.
- **FB7 [P2] (dim3,4)** `item_favorite.xml` — each favorite card is focusable AND embeds a focusable remove button → two focus stops per card, ambiguous traversal.
- **FB8 [P2] (dim2)** `FavoritesFragment.kt:58-76` — no initial focus → auto-lands on `btn_clear_all` (destructive).
- **FB9 [P3]** EPG grid RIGHT-at-last / DOWN-at-last edge cues (`EpgGridView.kt:510-523`); guide day-tab clip (`clipChildren`); asymmetric UP-back-to-day-tabs; sidebar 1.1x scale clipping; Favorites remove-focus restoration; EpgFragment (legacy) initial focus.
- **CLEAN:** EpgGridView highlight visibility + gain-focus snap + NOW seeding + exit handoffs; guide back/fullscreen restore (`fullscreenReturnFocus`, `pendingGridFocusStreamId`); ChannelPagingAdapter recycle reset; LiveFragment default-category initial focus + LEFT hardening.

### Phase C — Player (Live + VOD) OSD & overlays
- **FC1 [P2] (dim4,7)** `LivePlayerOsdManager.kt:587-608` — opening the surf drawer only dims the transport controls (alpha 0.12) without making them non-focusable; UP/DOWN off the first/last channel can leak focus onto a scrim-covered control behind the drawer.
- **FC2 [P2] (dim3,5)** `LivePlayerOsdManager.kt:643-651` vs `978-985` — in the two-panel drawer, BACK from categories → back-to-channels, but LEFT → close-everything, contradicting BACK *and* the on-screen "◀ BACK TO CHANNELS" hint.
- **FC3 [P2] (dim6,8)** `LiveSurfChannelAdapter.kt:35-48` + 30s minuteTicker — `notifyDataSetChanged()` full rebind while browsing the open drawer relies on best-effort focus recovery → drop/flicker (see CC-1).
- **FC4 [P2] (dim2)** `PlayerTrackManager.kt:169-231` — audio/subtitle picker never seats focus on the active track; always lands on row 0 ("Off"/"Auto").
- **FC5 [P3]** EpisodeBrowser double requestFocus overrides currently-playing episode; EpisodeBrowser focuses the loading spinner (soft dead-end if load hangs); player favorite/guide-cells TalkBack; aspect-ratio RIGHT edge no-op.
- **CLEAN:** VodSeekOverlay (non-focusable), LiveGuideAdapter + LiveOnAirAdapter (position-driven, non-focusable), transport control L/R chain + play/pause swap, LiveSurfCategoryAdapter focus + panel handoff, BrowserAdapters edge-wrap, BACK-precedence stack, overlay scoping when stacked, consistent `playerView.requestFocus()` restore.

### Phase D — VOD (Movies) + Series
- **FD1 [P1] (dim3,4)** `VodFragment.kt:469-472` — UP from the top grid row is a no-op, so the **sort chips row AND column toggle are unreachable by remote** (nothing else routes to them). *Whole control cluster dead to the remote.*
- **FD2 [P2] (dim6,8)** `VodFragment.kt:170-176,955-967` — AdapterDataObserver auto-focuses the grid on *every* data change (paging append, watched-cache refresh) regardless of where focus is (see CC-1).
- **FD3 [P2] (dim6,8)** `SeriesFragment.kt:145-151,978-990` — identical focus-steal pattern.
- **FD4 [P2] (dim2)** `SeriesDetailFragmentV2.kt:90-98` — no deterministic initial focus (MovieDetailV2 does it right; this doesn't).
- **FD5 [P2] (dim4,7)** `SeriesDetailFragmentV2.kt:428-455` — closing the SELECT STREAM panel doesn't restore focus to the episode that opened it.
- **FD6 [P2] (dim6,8)** `SeriesDetailFragmentV2.kt:34-55` — a filter-chip change rebuilds the stream list via `notifyDataSetChanged()` and drops focus.
- **FD7 [P2] (dim5,6)** `CinEpisodeAdapter.kt:52-58` + `SeriesDetailActivity.kt:784-791` — onResume watched-progress refresh does a blanket `notifyDataSetChanged()` on return-from-player, dropping focus off the just-watched episode.
- **FD8 [P2] (dim4)** `SourceSelectionBottomSheet.kt:440-447,345-355` — empty/error state leaves no focusable target (status text not focusable, filter chips hidden) → focus in limbo, only BACK escapes.
- **FD9 [P3/P4]** VOD/Series sidebar `loopFocus=true` wrap (edge inconsistency); Series column-toggle routing; MovieDetailV2 favorite dead control; poster/row/episode TalkBack; IPTV stream-row weak focus visual.
- **CLEAN:** MovieDetailFragmentV2 (deterministic focus + nextFocus chain + null-guards), SourceSelectionBottomSheet non-empty path (containment + restore chain), MovieSourceAdapter, CategorySidebarAdapter, poster ViewHolders (FocusGlow + recycle reset), MovieDetailActivity, EpisodesAdapterV2 (targeted notifyItemChanged), Cast/Similar/LanguageFilter adapters.

### Phase E — Debrid section + Stremio home
- **FE0 [P0-latent] (dim2)** `DebridDiscoverActivity.kt:63-128` — `onCreate` calls `setupInitialFocus()` but never `initViews()/setupGrid()/setupSelectors()`, so it dereferences an uninitialized `lateinit btnType` → **crash**; `setupGrid()` also recurses into itself → StackOverflow. Currently **unreachable** (only the dead `DebridFragment` launches it) so it can't hurt users today — but it will crash instantly if that path is ever wired. Fix or delete before re-enabling.
- **FE1 [P2] (dim2,6)** `DebridSeeAllActivity.kt:98-104` — no initial focus after async load until the user presses a key.
- **FE2 [P3] (dim2,1)** `DebridAuthFragment.kt:141,155` — hiding `btnStartAuth` on FetchingCode/ShowingCode loses focus transiently.
- **FE3 [P3] (dim4,5)** `StremioHomeFragment.kt:321-325` — BACK with a Discover filter dropdown open navigates home instead of closing the dropdown first.
- **FE4 [P3/P4]** Top-10 row low-contrast focus; search keyboard→results geometric handoff (no nextFocusRight); nav bell/profile + hero watchlist + library "Sort"/"See all" dead/decorative controls.
- **CLEAN:** StremioHomeFragment nav entry + per-tab nextFocusDown rewiring, TopAlignRecyclerView (deterministic 2D + edge-consume + See-all round-trip), StremioSearchOverlay containment (FOCUS_BLOCK + restore), all Stremio adapters (stableIds + focus rings), DiscoverSection pills⇄grid⇄Top-10 routing, StremioHeroManager (decorative non-focusable), StremioFocus (chains listeners), SeeAll card focus, MainActivity BACK routing.

### Phase F — Search / Settings / Dialogs / Misc / Custom views
- **FF1 [P2] (dim6,8)** `SearchFragment.kt:215-231` / `StremioSearchResultAdapter.kt:30` — `submit()`→`notifyDataSetChanged()` + `scrollToPosition(0)` on *every* state emit; separate VOD/series/live emits blow away ViewHolders and steal focus/scroll from the grid (see CC-1).
- **FF2 [P2] (dim2)** `SettingsFragment.kt:65-73` — no explicit initial focus (relies on auto-focus of first category; non-deterministic).
- **FF3 [P2] (dim4,5)** `CategorySelectionDialog.kt:69` — `setCancelable(false)` → BACK doesn't dismiss; only exit is navigating to Cancel.
- **FF4 [P2] (dim4,11)** `TrailerActivity.kt:563-576` — `dispatchKeyEvent` unconditionally consumes OK/CENTER, so the focusable `btn_close` can never be activated; only BACK exits.
- **FF5 [P2] (dim2,9)** `MediaFusionConfigActivity.kt:29` — first focusable is a multiline EditText → soft IME auto-pops over QR/instructions on entry.
- **FF6 [P2/P3] (dim4,5)** `LoginQrOverlayController.kt:76-105` — QR overlay has no BACK handler (BACK falls through to login) and `hide()` doesn't restore focus (overlaps FA2).
- **FF7 [P3]** Search Trending-chip focus loss; settings select-on-focus preview; settings toggle TalkBack state; "Loading Categories" dialog `setCancelable(false)` transient dead-end; CategorySelectionDialog weak checkbox focus + no initial focus; RecoveryActivity low-contrast + destructive default focus.
- **FF8 [P4]** Search key-grid no horizontal wrap; settings items `focusableInTouchMode` unneeded; WavySeekBar consumes L/R (standard).
- **CLEAN:** on-screen keyboard (focus visuals + firstKey initial + BACK routing), search result cards, settings detail rows (cyan glow + stableIds preserve focus), all Settings AlertDialog selectors (BACK-dismissable + restore), CompanionSetupActivity, TrailerActivity initial focus + BACK, WavySeekBar focus feedback.

---

## 3. Consolidated priority table

### P0 — reachable hard traps
**None.** (No reachable screen strands the remote.)

### P0-latent — crash landmine in dead code
| ID | File:line | Problem | Fix approach |
|----|-----------|---------|--------------|
| FE0 | `DebridDiscoverActivity.kt:63-128` | Uninitialized-lateinit deref + recursive `setupGrid()` → guaranteed crash/StackOverflow if launched | Call `initViews()/setupGrid()/setupSelectors()` in onCreate + remove the self-recursion — **or delete the activity + its dead launcher** (preferred, see CC-7). |

### P1 — reachable, severe
| ID | File:line | Problem | Fix approach |
|----|-----------|---------|--------------|
| FD1 | `VodFragment.kt:469-472` | Sort chips + column toggle unreachable by remote (top-row UP is a no-op) | On first-row UP, `requestFocus` the first sort chip and wire `rv_movies_grid` nextFocusUp — mirror SeriesFragment's UP→genre-pills routing. |

### P2 — reachable UX degraders (24)
Grouped by the cross-cutting pattern that fixes them cheapest.

*Refresh-steals-focus (CC-1) — one shared helper fixes most:*
FF1 SearchFragment · FD2 VodFragment · FD3 SeriesFragment · FD6 SeriesDetailV2 filter · FD7 CinEpisodeAdapter onResume · FC3 LiveSurf 30s tick · FB3 LiveTvGuideFragment requestFocus-on-emit · FB2 EpgGridView setData reset.
→ **Fix:** focus-preserving submit (capture stable-id → DiffUtil → restore); gate auto-focus/scroll-to-top on "query/category changed AND list already focused." Template: Home's `captureContentFocusSnapshot`/`restoreContentFocusAfterDataUpdate`.

*Missing/ambiguous initial focus (CC-2):* FF2 Settings · FD4 SeriesDetailV2 · FE1 DebridSeeAll · FB8 Favorites(→destructive) · FF5 MediaFusion(→IME).
→ **Fix:** post `requestFocus()` on the primary *safe* control after first layout/first data.

*Overlay containment (CC-4):* FA2/FF6 Login QR overlay · FC1 surf-drawer controls · FB4 guide search IME.
→ **Fix:** shared overlay-open helper — block background descendant focus, set inside-focus, BACK dismiss, restore trigger on close.

*Traversal / semantics:* FB1 EPG vertical time-column · FB5 LiveFragment UP→top-bar · FB6 LiveFragment manual-focus latency · FC2 drawer LEFT-vs-BACK · FC4 track picker selection focus · FB7 Favorites double focus-stop.
→ **Fix (one-liners):** FB1 select the program overlapping the current start-time on row change; FB5 let row-0 UP fall through to top bar; FB6 use native RecyclerView focus traversal; FC2 make Panel.BOTH LEFT match BACK/hint; FC4 `listView.setSelection(selectedIndex)`; FB7 `blockDescendants` on the card, move remove to long-press.

*Modal dead-ends:* FF3 CategorySelectionDialog + FF7 loading dialog `setCancelable(false)`; FD8 SourceSelectionBottomSheet empty/error no-focus; FF4 TrailerActivity OK-key hijack; FD5 SeriesDetailV2 stream-panel close no-restore.
→ **Fix:** FF3 `setCancelable(true)`/BACK handler; FD8 make status text focusable or keep chips visible; FF4 only swallow CENTER while the play-gate overlay shows; FD5 refocus the active episode on panel close.

*Home/CW:* FA1 CW long-press listener overwrite (chain listeners, don't replace); FA3 InitialSync retry button invisible+unfocused (add focused drawable + requestFocus).

### P3 / P4 — polish & a11y
Edge-cue/clip/scale-clip items (FB9, FC5, FF8), select-on-focus previews (FF7), dead controls (CC-3: FA5, FD9, FE4), and the systemic **TalkBack gap (CC-6)** across nav rail, posters, toggles, player fav, guide cells. Track as a single a11y workstream if screen-reader support is in scope.

---

## 4. Summary — clean screens vs worst offenders

**Fully clean (no issues, verified):** ExitDialog · LoginFragment fields · Home sidebar & content-focus/async-restore · ChannelPagingAdapter · Player transport controls & BACK-precedence & overlay scoping · VodSeekOverlay/LiveGuide/LiveOnAir (non-focusable by design) · MovieDetailFragmentV2 · MovieDetailActivity · EpisodesAdapterV2 · SourceSelectionBottomSheet (non-empty path) · all Settings AlertDialog selectors · on-screen search keyboard · TopAlignRecyclerView + StremioSearchOverlay + all Stremio adapters · WavySeekBar · CompanionSetupActivity.

**Worst offenders (start fixes here, in order):**
1. **VOD/Series fragments** — FD1 (P1 unreachable controls) + FD2/FD3 (focus-steal). Highest user impact.
2. **Search** — FF1 focus-steal on every streamed result emit.
3. **EPG guide + Live browse** — FB1/FB2/FB3/FB5 (grid traversal + focus-steal + top-bar reach).
4. **Player OSD** — FC1/FC2/FC3/FC4 (drawer focus leak + LEFT/BACK divergence + tick rebind + track selection).
5. **Series detail (V2)** — FD4/FD5/FD6 (initial focus + panel-close restore + filter rebind).
6. **Dialogs/misc** — FF3/FF4/FD8 (modal dead-ends), FE0 (delete/fix the latent crash).

**Biggest single win:** implement the **focus-preserving submit helper (CC-1)** once and apply it to the ~8 sites — it resolves the largest cluster of P2s and prevents the whole bug class from recurring (same lesson as the EPG recycling fix).

**Reachability note:** the Debrid tab is served by `StremioHomeFragment`; `DebridFragment`, `DebridDiscoverActivity`, `DebridSearchActivity`, `SidebarFocusController`, `LockableLinearLayoutManager` are **dead/unreachable** — audited for completeness but excluded from live severity except the FE0 landmine. Recommend deleting per CC-7.

---

*Diagnose-only. No code changed. Fix sequencing, protected-behavior gating, and device-QA requirements to be attached per the standing rules in `AUDIT_MASTER_PLAN.md` when this converts to a fix plan.*
