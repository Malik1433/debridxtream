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
| 2026-08-05 | **M2** | `layout-port/fragment_home_cinematic.xml` + `layout-port/view_home_sidebar.xml` — the phone home: the TV's left nav rail becomes a **bottom bar**, hero art with the text UNDER it (the TV's over-image style eats both at phone width), phone type (22sp hero / 13sp body), 50dp hero buttons, rails scroll with bottom padding so nothing hides behind the bar. All 38 code-bound ids present, so HomeFragment + its managers are untouched. The one code line: `rvSidebar`'s orientation now reads `R.bool.home_nav_is_horizontal` (false in `values`, true in `values-port`) — the qualifier decides, no mode plumbing. **Trap hit and documented: data binding requires an id to be the SAME KIND in every configuration** — `sidebar_settings_item` is an `<include>` on TV, so the portrait one must be too (a FrameLayout failed the build) | **Phone emulator (real, after the claimDevice fix let the account playlist reach it): the portrait home RENDERS** — hero art with the title under it, Play Now / More Info / ♥ at finger size, Trending Movies + Trending Series rails with real content, nav at the bottom (`rv_sidebar` measured at y=2242, 796px wide — a bar, not a rail). TV smoke on .64 re-run with this same build: identical rail/hero/CW, 0 FATAL. **Two follow-ups found and parked in §6: the top status strip sits under the system status bar, and the bottom bar renders ONE full-width nav item instead of five** | `763cb883` |
| 2026-08-04 | **M1** | `layout-port/fragment_login.xml` — the phone login as a resource VARIANT, not a fork: every id the landscape layout has, so LoginFragment + both overlay controllers bind with **zero code change** (verified statically: all 46 code-referenced ids present, id sets match). Phone ergonomics: single column, 15sp fields / 13sp body, 48-54dp touch targets, ScrollView so the IME can't trap Sign In, viewport-sized overlays (the 450dp card would clip at 411dp), "TAP A FIELD TO TYPE" instead of the D-pad legend. `UiModeChooser` — the Smarters-style question, shown from MainActivity **only** when `isAmbiguous()` + pending, marked-shown before display so a dismissal can't repeat | TV smoke on .64: identical home/hero/CW, **no chooser appeared** (Fire TV is unambiguous), no ui_mode keys written, 0 FATAL. **Phone emulator: the portrait login RENDERS — single column, readable fields, finger-sized targets, "DEVICE KEY LYQS-BVXW", "TAP A FIELD TO TYPE"** (the licence gate cleared itself once the device finished registering; see the note below) | `2f4990a2` |
| 2026-08-04 | **M0** | `UiModeResolver` (override > UiModeManager/Configuration TELEVISION > leanback-&&-!touchscreen heuristics) + `isAmbiguous()` as the gate for M1's one-time chooser; `SettingsPreferences.ui_mode_override` + chooser-pending flag; Settings → Home Screen → **"App Layout"** selector (Automatic / TV / Mobile). **No screen routed yet — by design, so TV cannot regress.** 8 unit tests, one per precedence rung | TV smoke on .64: launch, home/hero/CW identical, new row reads "Automatic (detect device)", H9 rows still German (DE) / Hindi (HI), 0 FATAL. Phone: n/a this batch | `c7da970e` |

*(TV regression rule: any batch that breaks a TV flow is reverted first, discussed second.)*

## 6. Findings parked for their own batch

- **M2 follow-up — the bottom bar shows one stretched item, not five tabs.** `item_sidebar_nav`
  is sized for a vertical rail, so under a horizontal LayoutManager its width fills the list
  (measured: `rv_sidebar` 796px wide holding a single 796px `nav_container`). Needs a
  width for the horizontal case — a `values-port` dimen the item reads, same trick as
  `home_nav_is_horizontal`.
- **M2 follow-up — the phone status strip renders under the system status bar** (the app's
  clock overlaps the OS clock). The portrait root needs the top window inset applied.
- **QA trap that cost a cycle (2026-08-05):** an "APK is fresh" watcher keyed on
  `mtime > <epoch>` fired on the PREVIOUS build because that build's timestamp was inside the
  same minute, so M2 was installed and "verified" as the old APK on both devices — the phone
  showed the TV layout and it looked like a resource-qualifier failure. **Verify the artefact,
  not the clock:** `aapt2 dump resources <apk> | grep res/layout-port/` proves what shipped,
  and `pm path` + a dump of the INSTALLED apk proves what is actually on the device.

- **The activation screen shows a TRUNCATED device id** (`installId.take(8)`, e.g. "hw-1178c") while the real Firestore doc id is 35 chars (`hw-1178c5260d5ba0b06a38c630050d29ed`). Nobody can act on the short form — support/activation must use the **activation code** (LYQS-BVXW). Worth either showing the full id, labelling it "(partial)", or dropping it in favour of the code alone.
- **A device is invisible to activation until it has registered itself.** `activateClient` looks a device up by `activationCode`; before the app's first successful Firestore write there is no doc, so the reseller sees "No device found for that activation code". That is correct behaviour but the app gives no hint that registration is still in flight — a "registering…" state on the activation screen would save a support round-trip. (Diagnosed 2026-08-04 on the emulator: the doc only appeared on the app's second launch.)
