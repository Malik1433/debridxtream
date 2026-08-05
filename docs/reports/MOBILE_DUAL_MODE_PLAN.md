# Mobile Dual-Mode Plan — one APK, TV + Phone (Smarters-style)

**Owner-approved 2026-08-04.** Goal: the same app installs on a phone and gives a real
touch-first mobile experience, while the TV experience stays byte-for-byte what it is
today. Mode is auto-detected (UiModeManager) with a manual override in Settings —
better than IPTV Smarters' first-run question, which we also show only when detection
is ambiguous.

## 0. Feasibility facts (audited 2026-08-04, the reason this plan is safe)

- The APK **already installs and runs on phones**: manifest has `leanback required=false`,
  `touchscreen required=false`, and both LAUNCHER + LEANBACK_LAUNCHER categories.
- The whole app already responds to touch: ~90 files wire `setOnClickListener`; the
  entire Discover/Home QA of 2026-08-04 was driven by `input tap`.
- **androidx.leanback is not used anywhere** — the TV UI is custom over standard views,
  so there is no framework rewrite; only layouts + input ergonomics.
- Portable as-is (zero mobile work): the entire data/domain layer (Xtream, debrid
  orchestration, Room incl. H4 learned languages, H5 Firestore share, licensing,
  accounts/QR pairing, update manager) and the media3 player engine with its
  resume/failover/reliability logic.
- The actual work: **150 layouts are TV-scale** (658 fixed-dp widths, ~290 uses of the
  6–9sp px÷2 font trick), 4 activities are landscape-locked, and player/browse
  ergonomics assume D-pad. Mobile needs its own layouts for ~8 surfaces + touch player
  controls. D-pad code (39 key-routing files, nextFocus wiring) is simply inert under
  touch — it does not need removal.

## 1. Non-negotiables (same discipline as the debt ratchet)

- ⭐ **Each form factor obeys its OWN rulebook (owner, 2026-08-05).** Phone screens follow phone
  standards, TV screens follow TV standards, and neither borrows the other's idioms — "it works"
  is not the bar. The checklist is in the repo's `CLAUDE.md` under *"Two platforms, two rulebooks"*
  (≥48dp targets and one-tap-acts on a phone; D-pad reachability and visible, unstolen focus on TV).
  This rule exists because both directions were already violated here: Live shipped a TV
  focus-then-activate model onto a touchscreen, and Settings still draws a 10-foot two-column
  layout at 411dp where nothing is reachable. **Every batch must say which rulebook it was checked
  against, and on which device.**
- **TV stays untouched.** Mobile UI lives in NEW files (`ui/mobile/` + `res/layout` with
  `mobile_` prefix, or resource qualifiers where cleaner). Any shared-file edit must be
  provably inert on TV. Every batch's QA includes a TV smoke check.
- **The ViewModel/repository layer is shared, never forked.** If a mobile screen needs
  data the ViewModel doesn't expose, extend the ViewModel (additively) — never duplicate
  repository logic into a mobile-only path.
- The debt ledger's ceilings still apply: no new detekt violations, no baselining, files
  under the LargeClass radar from day one (mobile screens should COMPOSE collaborators,
  not grow gods).
- One batch per commit, pushed, with a ledger row in §5; device QA per batch — **phone
  QA on a real handset** (owner's phone via ADB-over-WiFi or USB) + TV smoke on .64.
- Landmines respected as ever: play-then-repair resume order, TS extractor flags,
  never-destructive Room migrations, rethrow CancellationException, no main-thread I/O.

## 2. Architecture decision (locked)

**Single APK, runtime dual-mode.**

- `ui/mode/UiModeResolver` (new): `TV | MOBILE`, decided as
  `override-pref ?: UiModeManager(TELEVISION) ?: heuristics(touchscreen+no-leanback)`.
  Ambiguous devices (rare) get a one-time Smarters-style chooser dialog; the answer is
  stored and changeable in Settings → General → "App layout (TV / Mobile)".
- Activity routing: launcher/entry activities ask the resolver and load either the
  existing TV fragments (unchanged) or the mobile fragments. Player stays ONE activity;
  only its controller overlay + gesture layer differ by mode.
- Orientation: TV activities stay `landscape`. Mobile mode runs `unspecified`
  (portrait-first); the player allows both with sensor.
- Min work per screen: a `mobile_*.xml` layout + a thin fragment/adapter that reuses the
  existing ViewModel. Touch-specific input (gestures) is additive.

## 3. Phases and batches

Estimates are in "batches" — one focused, committed, QA'd working block (the unit this
project has been shipping in all along).

### Season 1 — Core path on the phone (M0–M6, ~15–25 batches, 2–4 weeks)

| Batch | Scope | Done-when |
|---|---|---|
| **M0** | `UiModeResolver` + override pref + (ambiguity-only) chooser dialog; manifest orientation split; phone-QA harness note (ADB to handset) | Unit tests for resolver precedence; TV smoke: nothing changed on .64 |
| **M1** | Login on mobile: portrait layout for the existing login/QR flow (phone keyboard, paste support) | Login completes on a handset; TV login untouched |
| **M2** | Mobile home shell: bottom-nav (Home / Movies / Series / Live / Settings) replacing the TV sidebar; home rails in portrait | Home renders real rows on handset; CW resume opens player |
| **M3** | Movies + Series browse: portrait grid (2-3 columns), category chips, phone search box (native keyboard, no TV on-screen keyboard) | Browse + search work by touch; pagination scrolls |
| **M4** | Detail page (movie + series incl. season/episode picker) in portrait | Detail → Watch Now reaches the source sheet |
| **M5** | Source sheet on mobile: full-screen bottom sheet, H8/H9 language chips + filters preserved | Pick a source by tap; H4 "✓" chips visible |
| **M6** | **Player touch controls**: tap-to-toggle chrome, drag/swipe seek with preview position, double-tap ±10s, brightness/volume vertical swipes, portrait + landscape + PiP; resume/failover logic untouched | A movie plays, seeks, resumes on the handset; TV player smoke on .64 passes (the P27/P28 fragment split means this is an overlay-level change only) |

**Milestone gate:** owner uses the phone build for a day. Season 2 starts only after
that feedback round.

### Season 2 — Live TV + EPG on mobile (M7–M9, ~1.5–3 weeks)

| Batch | Scope |
|---|---|
| **M7** | Mobile Live pattern: channel list + now/next (list-first, not the TV 3-column guide); tap to play in mini-player, expand to fullscreen |
| **M8** | Mobile EPG: day timeline per channel (vertical), reminders later |
| **M9** | Live player touch: swipe-to-zap, channel drawer as bottom sheet; classic-guide TV paths untouched |

### Season 3 — Polish + parity (M10–M12, ~1 week)

| Batch | Scope |
|---|---|
| **M10** | Settings on mobile (reuse SettingsViewModel; phone-friendly list), favorites, watch-history management |
| **M11** | Tablet pass (sw600dp variants where the phone layout stretches badly), dark/light audit, TalkBack basics |
| **M12** | Release checklist: Play-Store-safe manifest review, phone-vs-TV update channel check (UpdateManager), final dual-device QA matrix |

## 4. Risks and their standing answers

- **Long tail of TV assumptions** (biggest risk): burned down by per-batch handset QA,
  not by up-front analysis. Anything found mid-batch that is out of scope gets a line in
  §5's "found" column, not a scope creep.
- **Live/EPG is a redesign, not a port** — hence its own season with its own gate.
- **Player gestures need iteration**: M6 explicitly budgets a second QA round.
- **Secure-surface / DRM differences on phones**: none expected (no Widevine flows in
  app), verify during M6.
- **Update channel**: UpdateManager serves one APK — fine for dual-mode; note in M12.

## 5. Progress ledger (append one row per landed batch)

| Date | Batch | What landed | QA (phone / TV smoke) | Commit |
|---|---|---|---|---|
| 2026-08-06 | **M8** | **The phone's EPG — built where it could actually work, not where the plan pointed.** Checked BEFORE porting this time: `EpgGridView` (651 lines) implements `onKeyDown` and nothing else — no `onTouchEvent`, no GestureDetector, no scroller — so the TV guide on a touchscreen would render and then ignore every finger. Shipping a portrait layout for it would have shipped a dead screen. The plan's actual ask — "day timeline per channel (vertical)" — already had a home: the Live screen's guide strip, which is on the phone today and is fed `viewModel.guideEpg`, i.e. the whole day. So on a phone that strip is now read DOWNWARDS: `layout-port/item_livev2_epg_card.xml` turns the tile into a full-width row (fixed 76dp time column so every start time lines up), the list grows 64dp → 196dp, and the orientation comes from `R.bool.epg_strip_is_horizontal`. One adapter change came out of QA rather than planning: `LiveEpgStripAdapter` sets each card's width in pixels from its duration — width *means* duration in a horizontal strip — which left every vertical row stranded at ~40% of the screen with its ON AIR pill clipped. That rule now applies only when the strip is horizontal. Extracted to `applyCardWidth` because the addition pushed `bind` to exactly 60 lines and detekt failed; **not baselined** | **Phone: PASS.** The selected channel's day reads down the page — "23:00–00:00 beIN Zap" dimmed, "00:00–00:45 Le Graal d'Amélie" highlighted with its progress bar and an **ON AIR** pill, "00:45–01:00 Ça se passe sur beIN SPORTS", a fourth row scrolling in. Rows measured at x 184→1011 (full width; they were ~40% before the adapter fix). 0 FATAL. TV smoke on .64: launches, home hero + rails, 0 FATAL — and `aapt2 dump` confirms the APK carries `bool/epg_strip_is_horizontal` as `() true` / `(port) false`, so landscape keeps the horizontal strip exactly as before | *(this commit)* |
| 2026-08-05 | **M6** | **Settings, which was the worst screen on the handset** — the landscape file is a category rail beside a detail panel, and at 411dp the rail took the full width while the panel collapsed to an empty sliver, so Playback / Addons / Data & Storage / Sign Out were not awkward but *unreachable*. Four `layout-port` files: `fragment_settings_v2` (category chips on top, the chosen category's settings filling the rest), `item_settings_category` (the landscape row is `match_parent`×54dp — the M2b/M3 trap again — so the chip wraps its content at 56dp), and `item_settings_selection` + `item_settings_toggle`. Those last two were a second, separate fault caught in QA: title and value sat side by side, and at phone width the value plus chevron squeezed the title into "Preferred Audi…" / "Seconda…". They now stack the way Android settings do — title (two lines) / current value / explanation. The toggle pill stays EXACTLY 56×30 with a 22dp dot, because `SettingsDetailAdapter.styleToggle` computes the knob's travel from those numbers. One code line: the rail's orientation from `R.bool.settings_categories_are_horizontal`. All four layouts parity-checked 6/6 | **Phone: PASS.** Settings opens with a scrollable chip rail; Playback shows "Preferred Audio Language / English (EN)", "Secondary Audio Language / No secondary (off)" and "Smart Audio Fallback" with its full description — every name readable in full. **Addons is reachable** ("Where Debrid sources are fetched from · No addons configured"), which is what M5's phone QA was blocked on. Account reaches "Signed in as BOWMZ3TL7U / tvonnet.xyz / Sign Out". 0 FATAL. TV smoke on .64: launch, dialog, home hero + rails, a detail page opens, 0 FATAL — but **I did not open the TV's own Settings screen by hand this round**; the bounded reason is that `values/` keeps the bool `false`, so landscape resolves to the same VERTICAL manager the code hard-coded before | *(this commit)* |
| 2026-08-05 | **M7b** | **One tap acts** — the phone rulebook's first rule, and the cause was found by measurement, not guesswork. Every list row carried `focusableInTouchMode="true"`: on ACTION_DOWN the row takes focus, the list scrolls the newly-focused row into view, and the scroll **cancels the child's click**. The second tap works because the row is focused already — which is why it read as "select, then activate". Fixed with `R.bool.rows_focusable_in_touch_mode` — `true` in `values` (TV keeps focus as its whole interaction model, and the resolved value is identical to the literal it replaced, so landscape is provably inert), `false` in `values-port`. Applied to **all 43 `item_*` layouts**, since the same flag gates the whole app's touch model; the 14 fragment/activity layouts are deliberately left alone (there it stops an EditText grabbing focus and raising the keyboard on open, which is right on a phone too). Also kept from the diagnostic pass: `PreviewPlayerPanel.play()` used three bare `?: return`s on credentials — a preview that could not start said nothing to the user OR the log, which is what made this so hard to pin down | **Phone: PASS, measured.** Before: a tap logged nothing at all — `navigateToPlayer` was never reached. After: one tap gives `navigateToPlayer → play() → building player` and the channel plays; a second tap on the same row goes fullscreen (`playing=true` → PlayerActivity). So Live is now 1 tap = play, 2 taps = fullscreen. **Not fixed, and not claimed: the Movies poster grid still needs two taps** — verified on a settled grid with a fresh poster, so it is a different mechanism in the VOD path. TV smoke on .64: app launches, D-pad focus moves (`btn_hero_watch` → rail), home renders, 0 FATAL | *(this commit)* |
| 2026-08-05 | **M7** | Live TV on the phone. The code was never the problem — a channel tap already fires `LiveEvent.PlayChannel`, `btnFullscreen` already has a click listener, a long-press already toggles a favourite, and `rvCategories`/`rvChannels` are already horizontal/vertical in code. What failed was the SHAPE: three side-by-side columns squeezed into 411dp leave nothing big enough to hit. `layout-port/fragment_live_3column.xml` stacks them list-first — search+clock, category chips, 16:9 preview with now/next + progress + 46dp actions, guide strip, then the channel list taking every remaining pixel. All **42 ids** at their landscape kinds AND default visibilities; the nav rail stays an `<include>` with no id (an id there would hide `livev2_rail` from findViewById); both PlayerViews keep `surface_type=texture_view` + `keep_content_on_player_reset` verbatim because the fullscreen hand-off depends on them. Plus one code line: `getLiveTvStyle()`'s **default** is now configuration-dependent (`R.bool.live_defaults_to_classic`) — an explicit choice still wins on both form factors | **Phone: PASS, real data.** Live opens the list screen; tapping a channel plays it — live football in the 16:9 preview, "Now Playing \|WC\| BEIN SPORTS 2 HD FR" 17:30→20:00 with progress, next-up "20:00 Baseball : MLB", and a real EPG strip ("17:30-20:00 ON AIR Football : Coupe du monde"). Watch → fullscreen `PlayerActivity` with codec activity → BACK → back on LiveFragment. 0 FATAL. **Caveat: a channel needs TWO taps** (first selects, second activates) — the same touch-vs-focus defect already parked in §6. TV smoke on .64: the app resumed straight into `LivePlayerFragment` with live TV *playing*, BACK → home rails, Movies (317,479 titles) and Search both open, 0 FATAL. The TV's `live_tv_style` is an explicit `guide`, so the changed default never applies there and the classic layout is portrait-only | *(this commit)* |
| 2026-08-05 | **M5** | The source picker in portrait: `layout-port/dialog_source_selection.xml` (the TV's 410dp right panel becomes a bottom sheet below a 100dp peek; filter chips move into a horizontal scroller), `layout-port/item_movie_source.xml` (the 34dp seven-column strip at 8-9sp becomes a three-line 72dp card — it is a **data-binding** layout, so the portrait file keeps the `<layout>` root), `layout-port/item_lang_chip.xml` (7sp → 11sp, or H4's "HI ✓" is a smudge in the hand), and a bottom-edge window animation via a **qualified style** (the direction is set in code, but `values-port` can still override `SourcePanelAnimation`). Two resource bools carry the touch/D-pad differences: the play circle rides FOCUS on TV and is permanent on touch (nothing is ever focused there), and backdrop-tap-to-dismiss is touch-only — a click listener would make the backdrop clickable and so a dead stop in the TV focus ladder. Parity checked mechanically: 18/18, 14/14, 2/2 ids at matching kinds and default visibilities | **TV smoke on .64 — PASS with real debrid data:** sheet opens "85 sources · sorted by DE+HI priority" + the `DE+HI PRIORITY` chip; row 1 `AIO / 🇮🇳 HI ✓ / EN / +2 / 1080P / DIRECT / 3.5 GB / ★ BEST`, row 2 `SRC ×2 / HI / TA / +2` — H1/H4/H8/H9 all intact. Play affordance still follows focus: 0 play circles on the BEST row (it uses the badge), 1 after moving down. 0 FATAL. **Phone: NOT verified — blocked, see §6.** The sheet is only reachable on the debrid path and the emulator has no addon configured; addons live in device-bound encrypted prefs, so they cannot be injected, and the Settings screen where you would add one is itself still un-ported | *(this commit)* |
| 2026-08-05 | **M4b** | `layout-port/fragment_series_detail_v2.xml` — all **55 ids** at their landscape kinds AND their landscape **default visibilities** (this fragment toggles nearly all of them, so a drifted default shows empty chrome or hides a view it never turns on); only deliberate difference is `layout_hint_bar`, the D-pad legend, `gone` on touch. The TV's 410dp right-hand `panel_streams` becomes a **bottom sheet**. Caught pre-ship: the sheet was written `height="0dp"`, which inside a FrameLayout is zero pixels — the stream picker would have opened invisibly | Phone (DCI Banks): metadata wraps (7.0 / IMDb 7.7 / 2011 / 5 SEASONS · 32 EPISODES / Crime, Drama / 45M / TV-14), multi-source chip, full plot, actions, 5-face cast, SEASON 1 selector + "0 / 8 WATCHED", episode grid. TV smoke on .64: landscape detail untouched (one-line metadata, 80 SOURCES strip, resume bar, similar rail), 0 FATAL both | `69430e78` |
| 2026-08-05 | **M4** | Three portrait detail layouts. The one the phone reaches is **`fragment_movie_detail_v2`** — the browse grid opens `MovieDetailFragmentV2` via VIEW BINDING; the first attempt only covered `fragment_movie_detail` (the Debrid activity path) and the phone kept rendering the TV layout. Metadata and actions are WRAPPING flexboxes (nine chips / four buttons do not fit a phone line). `container_actions` stays a LinearLayout with a Flexbox inside it — kind-parity, the M2b trap again. Also `layout-port/fragment_movie_detail.xml` + `fragment_series_detail.xml` for the Debrid path (parity-verified, not yet exercised) | Phone: full title, metadata over two lines (6.321 / IMDb 5.6 / RT 63% / 2021 / 2H 28M / genres / R), whole plot, Watch Now + Trailer + ♥ then Mark Watched, director, 5-face cast, similar rail. TV smoke on .64: home + rail unchanged, 0 FATAL both | `a2f48595` |
| 2026-08-05 | **M3** | `layout-port/` variants for **both browse screens** (`fragment_vod`, `fragment_series_vod`): the 210dp left rail becomes a top strip (title + 44dp search, category list as a horizontal rail), grid full-width, sort chips in a HorizontalScrollView; plus phone CHIP variants of `item_vod_category` / `item_series_category` (the landscape rows are `match_parent` — the same trap M2b hit). Both fragments read `R.bool.browse_categories_are_horizontal` for the axis. **Grid span needed no change** — it already derives from measured width (coerced 2..8) and lands on 2 large columns | Phone: Movies opens with chips laid out horizontally (measured y=258, x=79/480/913), four sort chips, real posters (Matrix Resurrections, Narivetta, Diés Iraé). TV smoke on .64: browse rail still **VERTICAL** (all categories at x=48, increasing y), home unchanged, 0 FATAL both | `34fe2c55` |
| 2026-08-05 | **M2** | `layout-port/fragment_home_cinematic.xml` + `layout-port/view_home_sidebar.xml` — the phone home: the TV's left nav rail becomes a **bottom bar**, hero art with the text UNDER it (the TV's over-image style eats both at phone width), phone type (22sp hero / 13sp body), 50dp hero buttons, rails scroll with bottom padding so nothing hides behind the bar. All 38 code-bound ids present, so HomeFragment + its managers are untouched. The one code line: `rvSidebar`'s orientation now reads `R.bool.home_nav_is_horizontal` (false in `values`, true in `values-port`) — the qualifier decides, no mode plumbing. **Trap hit and documented: data binding requires an id to be the SAME KIND in every configuration** — `sidebar_settings_item` is an `<include>` on TV, so the portrait one must be too (a FrameLayout failed the build) | **Phone emulator (real, after the claimDevice fix let the account playlist reach it): the portrait home RENDERS** — hero art with the title under it, Play Now / More Info / ♥ at finger size, Trending Movies + Trending Series rails with real content, nav at the bottom (`rv_sidebar` measured at y=2242, 796px wide — a bar, not a rail). TV smoke on .64 re-run with this same build: identical rail/hero/CW, 0 FATAL. **Two follow-ups found and parked in §6: the top status strip sits under the system status bar, and the bottom bar renders ONE full-width nav item instead of five** | `763cb883` |
| 2026-08-04 | **M1** | `layout-port/fragment_login.xml` — the phone login as a resource VARIANT, not a fork: every id the landscape layout has, so LoginFragment + both overlay controllers bind with **zero code change** (verified statically: all 46 code-referenced ids present, id sets match). Phone ergonomics: single column, 15sp fields / 13sp body, 48-54dp touch targets, ScrollView so the IME can't trap Sign In, viewport-sized overlays (the 450dp card would clip at 411dp), "TAP A FIELD TO TYPE" instead of the D-pad legend. `UiModeChooser` — the Smarters-style question, shown from MainActivity **only** when `isAmbiguous()` + pending, marked-shown before display so a dismissal can't repeat | TV smoke on .64: identical home/hero/CW, **no chooser appeared** (Fire TV is unambiguous), no ui_mode keys written, 0 FATAL. **Phone emulator: the portrait login RENDERS — single column, readable fields, finger-sized targets, "DEVICE KEY LYQS-BVXW", "TAP A FIELD TO TYPE"** (the licence gate cleared itself once the device finished registering; see the note below) | `2f4990a2` |
| 2026-08-04 | **M0** | `UiModeResolver` (override > UiModeManager/Configuration TELEVISION > leanback-&&-!touchscreen heuristics) + `isAmbiguous()` as the gate for M1's one-time chooser; `SettingsPreferences.ui_mode_override` + chooser-pending flag; Settings → Home Screen → **"App Layout"** selector (Automatic / TV / Mobile). **No screen routed yet — by design, so TV cannot regress.** 8 unit tests, one per precedence rung | TV smoke on .64: launch, home/hero/CW identical, new row reads "Automatic (detect device)", H9 rows still German (DE) / Hindi (HI), 0 FATAL. Phone: n/a this batch | `c7da970e` |

*(TV regression rule: any batch that breaks a TV flow is reverted first, discussed second.)*

## 6. Findings parked for their own batch

- ~~M2 follow-up — the bottom bar shows one stretched item~~ **FIXED in M2b (`4116bd3d`)**:
  `layout-port/item_sidebar_nav.xml` (64dp column, 22dp icon, label under it) + the adapter's
  `applyIdleState` no longer force-hides `tv_title` when `home_nav_is_horizontal`. Six labelled
  cells measured on device.
- ~~M2 follow-up — the phone status strip renders under the system status bar~~ **FIXED in M2b**
  via `fitsSystemWindows` on the portrait home root.
- **M3 follow-up (open) — the browse screens carry no bottom nav.** The bar lives in the home
  fragment, so on the phone you leave Movies/Series with Back rather than tapping another tab.
- **M5 blocker (open) — Settings is unusable in portrait, and it is now on the critical path.**
  Settings still renders the TV two-column layout on the phone: the category rail takes the full
  width and the detail panel is a sliver at the right edge showing nothing. So on a phone you
  cannot reach Playback (the H9 language priority), Addons, Data & Storage or Sign Out **at all**.
  This is not only cosmetic — it is what blocks M5's phone QA, because the source sheet only
  opens on the debrid path, addons are the only way to get debrid sources, and Addons lives
  behind that unreachable panel. Port Settings next (rail on top or a list→detail push) and M5's
  phone verification unblocks with it.
- ⭐ **The wrong-screen trap, hit twice now — check which fragment is actually on screen BEFORE
  writing a layout.** M4 wrote `fragment_movie_detail` when the phone reaches
  `fragment_movie_detail_v2`; M7 wrote the classic Live layout when the phone opened
  `LiveTvGuideFragment`, because the Live screen is chosen at runtime by the `live_tv_style`
  setting and its default was `guide`. Both times the work was invisible on the device and
  looked like a qualifier failure. One command settles it:
  `adb shell dumpsys activity top | grep -oE "[A-Za-z]+Fragment\{"`.
- **The TV's EPG GRID screen is still unusable on a phone (open, and bigger than it looks).**
  M8 gave the phone a vertical day-per-channel guide on the Live screen, which is what the plan
  asked for — but `LiveTvGuideFragment`'s own grid is untouched and cannot simply be re-laid-out:
  `EpgGridView` implements `onKeyDown` and nothing else, so on a touchscreen it draws and then
  ignores every finger. Making it work means adding touch handling (scroll + tap-to-select)
  inside a 651-line canvas view. Anyone who picks "New EPG Guide" in Settings on a phone still
  lands on it.
- ~~**The EPG guide is still TV-shaped on a phone (M8).**~~ **DONE for the Live screen in M8.** It is a multi-day timeline GRID and needs
  width; M7 only changed which screen a phone opens by default. Anyone who picks "New EPG Guide"
  in Settings on a phone still gets the unusable grid.
- **Stale note corrected:** the memory that both Fire TVs run the *classic* Live style was wrong
  as of 2026-08-05 — `.64` holds an explicit `live_tv_style=guide`.
- **The Movies/Series poster grid STILL needs two taps (open).** Groundwork done so the next
  session does not start from zero: the grid uses `VodAdapter` (not `VodPagingAdapter`), whose
  click path is a plain `itemView.setOnClickListener { onClick(movie) }` → `VodFragment.onMovieClick`
  → the detail. There is no select-then-activate model in that path, and `item_movie_card` already
  carries `rows_focusable_in_touch_mode` (false in portrait) — so **both of the causes that
  explained Live are excluded here**. What is different from Live: `VodAdapter` also sets an
  `OnFocusChangeListener` and the fragment drives `onItemFocused` (backdrop + header). The next
  step is the one that settled Live — a temporary log at the click listener to see whether the
  first tap reaches it at all, rather than another theory. M7b fixed the Live channel list —
  cause found and verified — but the same fix did not cure the VOD grid: on a settled grid, a single
  tap on a fresh poster still does nothing and the second opens the detail. `item_movie_card` does
  carry the new bool, so the mechanism there is something else in the VOD path (its own
  select-then-activate model is the first place to look). Do not assume it is the same bug.
- ~~**Touch needs two taps to open a card**~~ — **CAUSE FOUND AND FIXED FOR LIVE in M7b**: rows were
  `focusableInTouchMode="true"`, so ACTION_DOWN moved focus, the list scrolled the row into view, and
  the scroll cancelled the click. Kept below for the record of what was tried. Confirmed on the Live channel list
  as well as posters. **Measured on the device, so M7b starts from evidence rather than a guess:**
  - a single tap on a channel row produces **no `PlayChannel`, no focus, no selection change** —
    the only log line is `LiveFragment: renderState`. The click listener does not fire at all.
  - a second tap on the SAME row plays it immediately.
  - it is not a one-off "entering touch mode" event: after a swallowed tap on row 003, a single
    tap on row 004 was swallowed too.
  - nothing intercepts touch — there is no `setOnTouchListener` anywhere in the live package or
    in `FocusGlintHelper`; the rows only carry `onFocusChangeListener` + `FocusGlintHelper.attach`.
  - `focusableInTouchMode="true"` IS set on both channel-row layouts (`item_channel_card`,
    `item_channel_card_new`) and on ~20 other layouts, so it is a suspect but not proven.
  **Round 2 — two theories tested and BOTH KILLED, so nobody retries them:**
  - *"the window is out of touch mode"* — **disproved**: `dumpsys window` reads `mInTouchMode=true`.
  - *"the list is rebinding under the finger"* — **disproved**: the list sat quiet for 25s (zero
    `renderState`), and a single tap still did nothing.

  **What the tap actually does.** `LivePlaybackLauncher.navigateToPlayer` is explicit —
  *"1st click = preview, 2nd click = fullscreen"*. So two taps is the DESIGN (and it matches this
  plan's own "tap to play in mini-player, expand to fullscreen"). The defect is narrower and worse:
  **the first tap's preview never starts.** The tap does reach the app — `renderState` fires, which
  can only come from the `RememberPreviewStream` event at the END of that same else-branch — yet
  `previewPanel()?.play(stream)`, one line above it, produces no ExoPlayer init and no codec
  activity even 35 seconds later.

  So something between the branch running and the player starting is swallowed. Two candidates, in
  order, both cheap to instrument next round:
  1. `previewPanel()` is **null** at that moment and the `?.` eats the call silently.
  2. `PreviewPlayerPanel.play()` **returns early**: it reads server URL / username / password from
     prefs and `?: return`s on any null, with nothing logged and nothing shown to the user.

  Either way there is a product bug worth fixing on its own: **a preview that cannot start says
  nothing at all.** Note also that a single tap failed on fresh rows even AFTER another channel had
  previewed successfully, so "cold prefs on first read" alone does not explain it. On the phone the first tap only moves
  selection (the TV focus model) and the second one activates. Same root cause as M2c below;
  worth fixing together — under touch, a tap should act, not focus.
- **M2c (open) — the TV focus/quick-info bubble appears on the phone.** The poster that holds
  focus pops the "title / MOVIE" bubble over the rail header even under touch, where nothing
  is focused by intent. It should be suppressed in mobile mode.
- **QA trap that cost a cycle (2026-08-05):** an "APK is fresh" watcher keyed on
  `mtime > <epoch>` fired on the PREVIOUS build because that build's timestamp was inside the
  same minute, so M2 was installed and "verified" as the old APK on both devices — the phone
  showed the TV layout and it looked like a resource-qualifier failure. **Verify the artefact,
  not the clock:** `aapt2 dump resources <apk> | grep res/layout-port/` proves what shipped,
  and `pm path` + a dump of the INSTALLED apk proves what is actually on the device.

- **The activation screen shows a TRUNCATED device id** (`installId.take(8)`, e.g. "hw-1178c") while the real Firestore doc id is 35 chars (`hw-1178c5260d5ba0b06a38c630050d29ed`). Nobody can act on the short form — support/activation must use the **activation code** (LYQS-BVXW). Worth either showing the full id, labelling it "(partial)", or dropping it in favour of the code alone.
- **A device is invisible to activation until it has registered itself.** `activateClient` looks a device up by `activationCode`; before the app's first successful Firestore write there is no doc, so the reseller sees "No device found for that activation code". That is correct behaviour but the app gives no hint that registration is still in flight — a "registering…" state on the activation screen would save a support round-trip. (Diagnosed 2026-08-04 on the emulator: the doc only appeared on the app's second launch.)
