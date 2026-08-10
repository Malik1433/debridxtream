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

## 1b. ⭐ ORIENTATION — LANDSCAPE ONLY (owner decision, 2026-08-07, overrides everything above)

> "जैसे ही app खुले तो वह portrait ना हो, landscape ही हो … उसको हम configure करें ही नहीं portrait में."

**The phone app is landscape. There is no portrait mode.** Every screen — home, Live TV, browse,
detail, Settings, player — opens and stays landscape however the handset is held. The user watches
video with the phone sideways anyway, and the app should look on a phone the way it looks on the TV.

What this rewrites in the rest of this plan:

- **Seasons 1–3 were written "in portrait" and that wording is now void.** Read every "in portrait"
  as "in landscape, using the TV layout".
- **The 21 `layout-port/` files (M1–M8) are dead** — that qualifier only matches portrait. They are
  left on disk so the decision stays reversible; nothing loads them.
- **M11 is superseded.** It locked the browse screens to PORTRAIT to solve the same complaint from
  the other side. M13 locks them to LANDSCAPE instead. The reasoning M11 recorded is still correct
  and worth keeping — only the direction changed.
- **The bools are split SHAPE vs BEHAVIOUR, not phone vs TV.** Shape flags (nav/category
  orientation, EPG strip, Live default) must match the layout, which is now the TV one on both
  devices, so they live in `values` with no override. Only the four interaction flags
  (`ui_uses_dpad_focus`, `rows_focusable_in_touch_mode`, `source_row_play_always_visible`,
  `dismiss_on_backdrop_tap`) are overridden in `values-television`. **Looks like TV, driven by a
  finger.**
- **Text is scaled up on the phone (owner chose option B, 2026-08-07).** The TV layouts are sized to
  read from three metres; ~1.6× on a handset. Caveat to verify, not assume: a scale layer only
  reaches sizes that come from `sp` dimens, and many TV layouts hard-code `textSize="7sp"` inline.
- **`values-port/` is banned for device questions** and `layout-land/` is not an option — orientation
  outranks UI mode in Android's qualifier table, so a TV would pick `-land` over `-television`.

## 1c. ⭐ LANDSCAPE SHAPE, MOBILE STANDARDS (owner decision, 2026-08-07)

Landscape (§1b) settled the ORIENTATION. It did not make the phone a television. The layout
arrangement comes from the TV design; **everything about how it behaves and how it is sized must
still meet ordinary mobile-app standards.** Both halves are binding — "it looks like the TV" is
never a reason to ship something a handset user cannot operate.

**The standard, screen by screen — this is the checklist a batch is measured against:**

- **A primary action is never unreachable.** If a form scrolls, its Save / Confirm / Continue must
  stay reachable — pinned footer, or a scroll container that includes it. *(Live defect: pasting a
  long link into a Settings item pushes Save below the fold and there is no way to get to it. This
  is the standard being broken, not a cosmetic nit.)*
- **Touch targets ≥48dp**, and text readable at arm's length — the `fontScale` layer (M13) is a
  floor, not a finish. Anywhere it clips a fixed-height row, the row gets fixed.
- **Scrolling, insets, and keyboard:** nothing under the status bar or the gesture pill; when the
  soft keyboard opens, the focused field and its action stay visible.
- **One tap acts.** No focus-then-confirm, no D-pad legends, no "PRESS OK" anywhere a finger goes.
- **Pickers and long option lists are bottom sheets**, and the scrim closes them.
- **Back always goes up** and every screen is leavable.
- **Every list has a visible loading / empty / error state**, and a failure says something.

**Screens explicitly owed this pass (owner, 2026-08-07):**

| Batch | Screen | Why |
|---|---|---|
| **M14** | **Settings — a complete pass** | The Save-below-the-fold defect above; item editors, text fields, keyboard behaviour, and the category/detail split all need to be operable in the hand |
| **M15** | **Home** | Sizing and density read as 10-foot; wants a handset pass over hero, rows, and card metrics |
| **M16** | Movies / Series browse and detail | Same standard applied to the remaining browse surfaces |

This supersedes the earlier "Two platforms, two rulebooks" split ONLY on orientation and layout
arrangement. Every behavioural line of the phone rulebook in `CLAUDE.md` still applies unchanged.

## 1d. ⭐ THE DEBRID SECTION ON A PHONE (owner request, 2026-08-08)

M14–M16 covered the IPTV side: Settings, Home, Movies/Series browse and both detail pages. The
**DEBRID section has had no phone pass at all.** Owner asked for it to be planned and fixed the same
way. This section is the plan; batches land in §5 like every other.

**What the section actually is (audited 2026-08-08 — do not re-derive):**

- **One fragment inside MainActivity**: `StremioHomeFragment` (`ui/debrid/stremio/`), which carries
  the whole Home / Discover / My Library tab set plus the search overlay — 30-odd collaborator
  classes, its own font system (`StremioFonts`), palette and gradients.
- **Three separate activities**: `DebridDiscoverActivity`, `DebridSeeAllActivity`,
  `DebridSearchActivity`.
- The section is gated behind `Entitlements.isDebridConfigured()` — an addon URL, a Real-Debrid
  token, or a MediaFusion URL. With none of them the whole section is one overlay.

**The finding that matters most, and it is a one-line-per-file fix:**

> **The M13 font scale reaches only THREE activities** — `MainActivity`, `MovieDetailActivity` and
> `SeriesDetailActivity` are the only callers of `phoneScaledContext`. So Debrid **Discover, See All
> and Search render at the TV's px÷2 sizes — 6–9sp — in the hand.** The Debrid *home* escapes this
> only because it happens to live inside MainActivity.

Two of those activities (`Discover`, `SeeAll`) are also `screenOrientation="landscape"` in the
manifest and never call `lockLandscapeOnTouchDevices()`. The orientation outcome is the same today,
so this is not a live defect — but they sit outside the M13 mechanism, so any future change to it
misses them silently.

**Defects measured on the phone (914×411dp, fontScale 1.6):**

| id | Screen | Defect |
|---|---|---|
| **DB-1** | Debrid home | The hero plot is painted **under** the Play Now / Trailer row — the same 0dp-column overflow class M16a fixed on the movie detail page |
| **DB-2** | Debrid home | The top nav bar sits **under the system status bar**: the DebridXtream logo collides with the clock, the profile chip with the wifi/battery icons |
| **DB-3** | Debrid home | The hero plot runs to six lines and owns the entire left column |
| **DB-4** | Debrid home | The bar draws **its own clock** ("14:35") beside Search — the same duplication M15 removed from the IPTV home |
| **DB-5** | Debrid home | The first content row is clipped by the bottom edge |
| **DB-6** | Discover / See All / Search | No font scale at all (the finding above) |
| **DB-7** | Setup gate | TV copy — "This TV picks them up on its own", "Or on this TV" — and a `BACK / RETURN` D-pad legend |
| **DB-8** | Discover | The Top-10 rail's rank number wraps to two lines and is sliced by its own row ("0" over "1") |
| **DB-9** | Discover | Top-10 titles truncate at about seven characters — "Spider…", "The La…", "Obses…" |
| **DB-10** | Discover | ⭐ **"See all" does not respond to touch at all** (two taps, no navigation). It is the only route to `DebridSeeAllActivity`, so that screen is currently unreachable on a phone — and it blocks the QA of D1's own fix |
| **DB-11** | Debrid home | The nav tabs sit under the system status bar, so a tap on their upper half is swallowed — Discover opens at y=82px but not at y=60px |

**Batches:**

| Batch | Scope | Done-when |
|---|---|---|
| **D1** | The three missing `phoneScaledContext` calls; route the two manifest-landscape activities through `lockLandscapeOnTouchDevices()` | Discover / See All / Search readable in the hand; TV smoke shows all three unchanged |
| **D2** | Debrid home: kill the plot overlap, inset the top bar below the status bar, drop the duplicate clock, size the hero so a content row is whole | Nothing overlaps; one full row visible; TV byte-identical |
| **D3** | Discover / See All / Search: the §1c checklist — touch targets, chip heights, card captions | Same standard as M16a's browse pass |
| **D4** | The setup gate: phone-appropriate copy, no D-pad legend | Reads correctly on a handset |

**Two harness notes for whoever QAs this:**

- **The section is gated.** QA needs `isDebridConfigured()` true. Adding
  `https://a.invalid/manifest.json` through Settings → Addons → + Add Addon unlocks it and resolves
  to nothing, so no third-party service is involved. `.invalid` is reserved and never resolves.
- **`uiautomator dump` does not work on the Debrid home.** The hero rotates on a timer, so the tree
  never reaches an idle state and every dump returns `ERROR: could not get idle state`. Measure this
  screen from screenshots, and drive it by coordinates.
- `adb shell input text` drops characters on a long string. **Type a URL in four short chunks** —
  that produced a clean `https://a.invalid/manifest.json` where one long call had produced
  `hhttps://torren`.

## 1e. ⭐ WHAT IS LEFT — M17-M20, written so a FRESH SESSION can start cold (2026-08-09)

Everything in this section was **measured on 2026-08-09**, not guessed. A new session should read
§1b, §1c, §1d and this section, and then start at M17. Do not re-derive any of the facts below.

### The one sentence that explains the remaining work

**M13 made the phone LANDSCAPE, which killed all 21 `layout-port/` files** — that qualifier only
matches portrait, so every phone layout M1-M12 shipped is dead on disk and those screens now render
the raw TV layout, unreviewed. M14-M16 redid Settings, Home, browse and both detail families.
D1-D7 redid the Debrid section. **The screens below are the ones nobody has looked at since.**

### Measured evidence (2026-08-09)

**Six activities never get the M13 phone font scale** — they do not call `phoneScaledContext`, so
every `sp` in them renders at the TV's px÷2 sizes (6-9sp) in the hand:

| Activity | Why it matters |
|---|---|
| `PlayerActivity` | the chrome, track pickers and episodes panel — the screen the user is in most |
| `ActivationActivity` | the FIRST screen a new user ever sees |
| `MediaFusionConfigActivity` | has text fields |
| `TrailerActivity` | mostly a WebView, low value |
| `CompanionSetupActivity`, `RecoveryActivity` | rare paths |

Check it with: `grep -L phoneScaledContext $(find app/src/main/java -name '*Activity.kt')`

**Two D-pad legends are still live and un-gated** (the phone rulebook bans them by name; four have
already been fixed this way — M14b home, M16a IPTV series, D3 debrid gate, D4 debrid series):

- `app/src/main/res/layout/fragment_login.xml`
- `app/src/main/res/layout/view_live_player_osd.xml`

Find any more with:
`grep -rl "NAVIGATE\|PRESS OK\|OK SELECT\|BACK EXIT\|ESC RETURN" app/src/main/res/layout/*.xml`

### The batches

| Batch | Scope | Done-when |
|---|---|---|
| **M17** | **Player controls.** Add `phoneScaledContext` to `PlayerActivity`. Then measure the VOD and Live chrome, the track/subtitle pickers and the episodes panel against §1c. Expect the same classes found everywhere else: fixed row heights that clip at 1.6x, sub-48dp targets, and text sized for three metres | Chrome readable and every control ≥48dp on the phone; TV player smoke on .64 unchanged (the P27/P28 fragment split means this is overlay-level only) |
| **M18** | **Live TV + the EPG strip.** Never measured in landscape. Hide the `view_live_player_osd` legend on touch. Check the 3-column shape, the guide strip and the channel list against §1c | Live opens, a channel plays on ONE tap, nothing overlaps; TV Live smoke unchanged |
| **M19** | **Login + Activation.** `ActivationActivity` gets the font scale; hide the `fragment_login.xml` legend on touch; check the fields, the soft keyboard and the QR/pairing flow | A new user can read and complete first-run on a handset |
| **M20** | **Source picker sheet.** M5 built a portrait sheet which is now dead, so the TV's 410dp right panel is what a phone gets. ~~MediaFusion needs the font scale~~ **Corrected 2026-08-10: `MediaFusionConfigActivity` was an ORPHAN — in the manifest but launched by nothing anywhere in the codebase (and despite its name it saved Stremio addon URLs, a job Settings → Addons already does). Owner decision: DELETED** (activity, manifest entry, `activity_webview.xml`, five orphan strings). The MediaFusion *fetcher*, prefs and entitlement gate are live via the companion/pairing flow and are untouched | Pick a source by tap on the phone; TV panel unchanged |

### Rules a fresh session MUST know before touching anything

1. **Every new/edited activity needs the orientation in BOTH places** (D7): the code helper
   `lockLandscapeOnTouchDevices()` AND `android:screenOrientation="sensorLandscape"` in the
   manifest. Code alone leaves a one-frame PORTRAIT FLASH, because the system sizes the window from
   the manifest before `onCreate` runs.
2. **An ancestor's `clipChildren="false"` defeats a descendant ScrollView's clip** (M16a). If
   `uiautomator` bounds say "clipped" but the screenshot shows overflow, that is why. Also
   `fillViewport="true"` measures the child at the viewport height and stops the scroll engaging.
3. **A touch device never changes `lastFocusTarget` and never focuses.** Any code guarded on focus
   state simply never runs on a phone. This has been the cause FOUR times (M7b, M7c, M15, M16b).
   When something is stale, empty, or needs two taps, grep the owning class for `lastFocusTarget`,
   `hasFocus`, `OnFocusChangeListener` and `requestFocus` **first**.
4. **There are TWO detail-page families.** IPTV uses `fragment_*_detail_v2.xml`; Debrid uses
   `fragment_*_detail.xml`. They are structural twins — a defect in one exists in the other. Fix
   both, or at least check which one is on screen.
5. **Device split is by resource qualifier, never a runtime branch.** Sizes live in
   `values/dimens_*.xml` (PHONE) + `values-television/dimens_*.xml` (TV literals, verbatim).
   Behaviour bools live in `values-television/bools_ui_mode.xml`. `values-port`/`layout-port` are
   BANNED — they are dead under the landscape decision.
6. **Verify the TV branch mechanically when you cannot reach the screen by hand:**
   `aapt2 dump resources <apk> | grep -A3 <name>` shows `()` and `(television)` values side by side.

### Harness traps (each of these cost real time on 2026-08-08/09)

- **The Pixel emulator needs 4GB.** At `hw.ramSize=3072` Android 15 goes into an endless "System UI
  isn't responding" loop that reads exactly like an app hang. Already raised in
  `~/.android/avd/Phone_Pixel.avd/config.ini`; if a fresh clone regresses, check `adb shell free -m`
  before blaming the app.
- **Check the HOST's RAM before the guest's (M17, cost two hours).** With Windows itself at 0.4GB
  free (Android Studio + a finished Gradle build), qemu's 4GB gets paged out and EVERY input into
  the emulator ANRs — app dialogs, System UI, all of it, straight after a fresh install, which
  reads exactly like a regression in the new APK. `Get-CimInstance Win32_OperatingSystem` for the
  host, and free host memory (close Studio, `gradlew --stop`) before rebooting guests or blaming
  the build. Two more emulator artifacts from the same session: after heavy memory churn the app
  window can screencap BLACK while `uiautomator` still sees every view — display sleep/wake
  (`KEYCODE_SLEEP`/`WAKEUP`) forces a recomposite and the real UI reappears; and on the Fire TV a
  playing video blanks the whole capture WHITE, chrome included, so player-over-video is verified
  by `uiautomator` bounds + `MediaCodecLogger`, never by screenshot.
- **`uiautomator dump` never works on the Debrid home** — the hero rotates on a timer so the tree
  never idles. Measure that screen from screenshots.
- **`adb shell input text` drops characters on a long string.** Type a URL in four short chunks.
- **`adb exec-out screencap -p > f.png`** when `/sdcard` throws "Transport endpoint is not
  connected"; a blank white capture usually means a video/secure surface, not a broken screen.
- **The Debrid section is gated** behind `Entitlements.isDebridConfigured()`. Unlock it for QA by
  adding `https://a.invalid/manifest.json` via Settings → Addons → + Add Addon. `.invalid` is
  reserved and never resolves, so no third-party service is involved.
- **`dumpsys activity | grep topResumedActivity` does not see fragment navigation** — home → detail
  is a fragment swap inside MainActivity. Use `uiautomator dump` and look at the ids on screen.
- Gradle takes 8-18 minutes here. Run it with `run_in_background` and poll the output file.

### The per-batch contract (unchanged, owner's rule)

build + `:app:detekt` + `:app:testDebugUnitTest` green → phone QA on the Pixel emulator **with real
data** (a title that has a plot AND a cast AND recommendations — an empty sample hides the bug) →
TV smoke on `192.168.178.64:5555` → commit + push → a row in §5 → APK published to
`admin-panel/DebridXtream-latest.apk` + `firebase deploy --only hosting`, then **download it back
and `aapt2 dump badging` it** before quoting the link. Bump `versionCode` AND `versionName`.

⭐ **Standing owner rule (2026-08-08): fix small defects you notice as you go.** He is the only
tester; handing items back as "your call" is work moved onto him. Ask only for genuine product
decisions — removing a feature, changing what a control does.

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
| 2026-08-10 | **M19** | **Login for the hand — and the §1e line about Activation was WRONG, which is worth more than the fix.** §1e said "ActivationActivity gets the font scale"; measuring the screen showed it uses REAL sizes (28sp title, 56sp code, 16sp status) — at 1.6x the centred column would total ~474dp on a 411dp screen and clip both ends, so the scale was deliberately NOT applied and the reason is now a comment in the activity. The screen that DID need the work was login (it lives in MainActivity, so the 1.6x scale already reaches its px÷2 type): the SEVENTH D-pad legend (`▲ ▼ NAVIGATE · OK SELECT · BACK CLEAR`) hidden on touch; every control under the minimum brought to 48dp via `dimens_login.xml` (fields **35**→48dp, Sign In **36**→48, the two secondary buttons **32**→48, account-overlay rows **34**→48, QR-close **28**→48, account-close **30**→48, the eye toggle **18**→40dp) with TV literals verbatim; the card wrapped in a `ScrollView` (focusable=false) because at 1.6x + 48dp rows it outgrows a 411dp viewport — on TV the card fits so the wrapper collapses to nothing; and the QR overlay's step-3 title "Add this **TV**" became a qualified string ("Add this **device**" on touch, D3's pattern) | **Phone (Phone_Fresh3 AVD, genuine first-run): PASS, measured.** All three fields **126px = 48dp exactly**, Sign In 126px, eye toggle 105px = 40dp, `tv_login_hint` absent from the tree, the card scrolls to Connect-via-Phone/QR + account button + DEVICE KEY, a field tap opens the **native soft keyboard** (landscape extract editor with DONE), the QR overlay reads correctly at 1.6x with pairing code + "Add this device", and BACK leaves the screen. **TV (Television_4K emulator, genuine first-run): PASS, measured** — fields **140px = 35dp**, Sign In 144px = 36dp, both the TV's exact old literals; `tv_login_hint` **present**; the cinematic card renders centred with the ScrollView inert. **.64 smoke: PASS** — vC 63 installed, home + rails + CW unchanged. **Two harness traps recorded:** the login screen's ambient animators never idle, so `uiautomator` needs `settings put global animator_duration_scale 0` first (then put it back); and the Television_4K AVD carried a January build signed with a different debug keystore — `install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and needs an uninstall first. **Multi-user QA on the Pixel AVD does NOT work** — `am switch-user` wedged the display twice; a fresh AVD is the honest first-run harness | *(this commit)* |
| 2026-08-10 | **M18** | **The Live guide against §1c, and the FIFTH and SIXTH D-pad legends silenced on touch.** The Live player OSD's hint bar (`▲▼ CHANGE CH · ◀ CHANNELS · ◀◀ CATEGORIES · OK SELECT · BACK EXIT`) and the in-player TV-Guide overlay's two hint rows got ids and are hidden in `LivePlayerOsdManager` when `ui_uses_dpad_focus` is false — same construct as the four before them. Then the guide screen (`fragment_live_tv_guide.xml`, the phone's default Live): its 14dp top padding put the preview tile under the system status bar (measured y=37px against a 63px bar); the code-built category chips and day tabs were ~42-46dp; Jump-to-Now was 46dp; and at 1.6x the fixed 176dp info block **overflowed — the description was sliced mid-line and the panel's button was pushed clean out of view**. New dual-config resources: `epg_top_inset` 38/**14**dp, chip/tab `minHeight` floors 48/**0**dp (0dp = the TV's wrap_content, exactly), `epg_jump_now_height` 48/**46**dp, title/desc `maxLines` 1/**2**. **One deliberate hide, flagged for the owner:** the panel's "Add to Favorites"/"Set Reminder" button is a STUB on both devices — its click shows a Toast of its own label, nothing else — and the phone's overflow was already clipping it invisible, so on touch it is now `epg_shows_primary_btn=false`; the TV keeps it. The grid itself needed nothing: it already pans, flings and tunes on one tap (touch support was built with the M8-era work), and rows are 58-96dp | **Phone (Pixel emulator, real data — 300 CH, BEIN/PTV): PASS, measured.** Preview tile top at y=100px = 38dp (clear of the 63px status bar), day tabs **126px = 48dp exactly**, Jump-to-Now 126px, title and description each on ONE whole line with the progress bar visible, `btn_primary` absent from the tree, and a channel row tap opens `PlayerActivity` on **one tap**. In the player the OSD renders with `live_hint_bar` at **0 occurrences** in two uiautomator dumps. **TV smoke on .64: PASS.** The guide is unchanged — preview tile at the top edge, compact chips/tabs, focus ring moving down the channel column, **"Add to Favorites" present**, real EPG data (43 CH) — and in the fullscreen live player `live_hint_bar` is **present (1)** in the tree. `aapt2 dump resources` shows every new value carrying `(television)` at its old literal: inset 38/**14**dp, floors 48/**0**dp, jump 48/**46**dp, lines 1/**2**, btn false/**true**. **Stated, not hidden:** the CLASSIC Live style on a phone (explicit Settings opt-in only — the default is the guide) was not re-QA'd this batch; it renders the TV 3-column layout and is parked for its own pass if the owner uses it in the hand | *(this commit)* |
| 2026-08-10 | **M17** | **The player finally gets the phone font scale — the screen the user is in most was the biggest one still rendering at px÷2.** `PlayerActivity` now overrides `attachBaseContext` with `phoneScaledContext`, same three lines as every other activity (orientation was already right in both places since D7). Then the §1c pass over the chrome, by measurement: **every player control was under 48dp** — the VOD row's nine ImageButtons were 40dp, the Live OSD's five control chips were 28dp tall, the fav button 28×28, and the seek bar's whole touch strip was 24dp. New `dimens_player.xml` pair (phone 48dp everywhere, seek strip 48dp, fav icon 20dp; **TV literals verbatim in `values-television/`** — 40/28/24/12dp). One clip found and fixed before it shipped: `live_min_left` is a fixed 46dp box and "55 MIN LEFT" at 1.6x needs ~70dp — it would have wrapped and sliced exactly like D3's rank numbers; 84dp on the phone now. Checked and left alone: track-picker rows (wrap_content — they grow past 48dp on their own), the episode card (164dp, its weighted thumbnail absorbs the text growth), the surf drawer rows (72dp/54dp, already over the minimum). The two D-pad legends §1e lists are **M18/M19's scope, untouched here** | **Phone (Pixel emulator, 914×411dp, fontScale 1.6): PASS, measured.** VOD chrome: title 20.8sp readable, `exo_rew` **126×126px = 48dp exactly**, play circle 137px, seek strip 126px tall — and on the series player `btn_prev/next_episode`, `btn_episodes`, audio/subtitles/aspect all measure the same 126px. Audio picker opens on one tap, rows ~24sp. Episodes coverflow renders whole — E-badge, title, "Season 1" meta, nothing clipped. Live: guide → **one tap plays**, OSD chip tap opens the channel browser, `live_min_left` measures 221px = 84dp. BACK exits player → home every time. **TV smoke on .64: PASS** — Live OSD screenshot renders pixel-correct at the old sizes (compact 28dp chips, hint bar, ON AIR card), a Debrid film plays end-to-end (SELECT SOURCE panel → HW AVC codec active), and `aapt2 dump resources` shows every new dimen carrying `(television)` at its exact old literal: btn 48/**40**dp, seek 48/**24**dp, chip 48/**28**dp, fav 48/**28**dp, min-left 84/**46**dp. **Harness note that cost two hours:** the HOST ran out of RAM (0.4GB free — Studio+Gradle daemons after the build), which thrashed the emulator into an ANR-on-every-input loop that reads exactly like an app hang; freeing host memory fixed every "symptom" at once. Check the host BEFORE the guest next time | *(this commit)* |
| 2026-08-09 | **D7** | **The portrait flash when opening a detail page — owner-reported, confirmed, fixed at the source.** Opening any movie or series poster showed the page in PORTRAIT for a moment and then flipped to landscape. Cause: **the orientation was only ever asked for in CODE.** `lockLandscapeOnTouchDevices()` runs before `super.onCreate`, but by then the system has already SIZED and started the window from the manifest — and the manifest declared no `screenOrientation` for these activities, so the phone's own portrait was used and the requested rotation arrived one frame later. M13 fixed the *outcome* (the app ends up landscape) but never the *first frame*. Confirmed by enumerating the manifest: eight activities had no orientation at all — MainActivity, **MovieDetailActivity**, **SeriesDetailActivity**, PlayerActivity, DebridSearchActivity, MediaFusionConfigActivity, ActivationActivity, RecoveryActivity. All eight now declare `android:screenOrientation="sensorLandscape"`, matching what the helper asks for, so the window is landscape from the first frame and the helper becomes a no-op confirmation. It answers the owner's "IPTV ke movies aur series dono mein" — yes, both, and the same two Debrid pages, because they are the same two activities | **Phone: PASS** — a poster now opens straight into landscape; five burst screencaps taken from the moment of the tap are all 2400×1080, no portrait frame. **TV smoke on .64: PASS, 0 FATAL** — 1920×1080, launch and hero unchanged; `sensorLandscape` is what the TV was already doing. Verified in the built APK, not just the source: `aapt2 dump xmltree` shows `screenOrientation=6` (sensorLandscape) on all eight, and `0` (landscape) still on the three that already had it. **⭐ OWNER-CONFIRMED on his real handset (2026-08-09): the flash is gone.** That makes D7 the first batch in this programme verified on real hardware rather than the emulator | *(this commit)* |
| 2026-08-09 | **D6** | **The series Play button is gone on the phone (owner decision) — on BOTH series pages.** Owner: episodes already play on one tap, so the page needs no Play button on a handset; keep Trailer and the favourite, adjust them somewhere visible. Done by REPARENTING, not duplicating: on touch only, `btn_play` / `btn_watch_now` goes GONE and the trailer + favourite Views move out of the scrolled info column into the always-visible season row at 48dp — same View objects, so every existing binding and click listener keeps working untouched. Applied to the IPTV page (`SeriesDetailFragmentV2.relocateActionsForTouch`) and the Debrid page (`SeriesDetailActivity`, extracted to its own method after detekt flagged the nesting). This also closes the long-standing "Play S01·E01 below the fold" item — the column no longer holds any primary action | **Phone: PASS, measured.** IPTV series: no Play; season row reads `SEASON 1 ▾ · 0/0 WATCHED · [Trailer] [♥]`, both at 48dp. Debrid series: same — `btn_watch_now` absent from the tree, Trailer + ♥ in the season row at 48dp. **TV smoke on .64: PASS, 0 FATAL** — the Debrid movie detail still shows "Watch Now"+"Trailer" side by side in their old bounds; the relocate is gated on `ui_uses_dpad_focus`, the same construct five prior batches proved inert on TV. NOT hand-checked this round: the TV *series* page itself (the gate makes the code unreachable there by construction; D4's TV screenshot shows the unchanged layout) | *(this commit)* |
| 2026-08-08 | **D5** | **The TV cast clipping sized to fit, and a correction of my own D4 change.** (1) On a Debrid film with a long plot AND a cast row the cast NAMES were clipped ~4-8dp at the column's edge; ~16dp is reclaimed from vertical margins INSIDE the column (credits block and actions row 14dp → 8dp, cast rail and director line 5dp → 3dp) and the cast name is pinned to one line so a two-word name cannot re-clip it. No type size changed. (2) **D4's 20dp bottom margin was wrong and is reverted.** I added it to give the phone's action row "breathing room" once the similar rail is hidden — and it did the opposite: the column is bottom-LIMITED, so a bigger bottom margin shortens the viewport and pushes the actions further out. Measured: Watch Now went from flush-but-readable to a sliver. (3) With that reverted the actions were still flush on the worst case (a resume bar AND a three-line plot), so the phone's plot is capped at two lines — `@integer/detail_plot_lines`, phone 2 / TV 3 | **TV: PASS** — "The Odyssey" now shows DIRECTOR "Christopher Nolan" and the cast names "Matt Damon", "Tom Holla…", "Anne Hath…", "Robert Pat…", "Himesh Pa…" in full, with the "BECAUSE YOU WATCHED" header clear below them; "91 SOURCES FOUND · 91 CACHED · BEST 4K 345 MB"; 0 FATAL. **Phone: PASS** — Watch Now / Trailer / ♥ fully visible and clear of both the screen edge and the gesture pill. `aapt2` confirms `detail_plot_lines` is `() 2 / (television) 3` | *(this commit)* |
| 2026-08-08 | **D4** | **The Debrid section's OWN movie and series detail pages — which M16a never touched.** They are separate files: the IPTV path uses `fragment_*_detail_v2.xml`, the Debrid path uses `fragment_movie_detail.xml` / `fragment_series_detail.xml`, and the second pair are structural twins of the first, carrying the identical defects — root `clipChildren="false"`, a 0dp info column that paints over what is below it, 38dp actions, a top bar at y=0, and on the series page a **`◄ ► ▲ ▼ NAVIGATE / BACK / ESC RETURN` legend, the FOURTH found on a touch device** (after M14b home, M16a IPTV series, D3 setup gate). Same recipe, and every dimen and bool it needed already existed from M16a/D2: root clips, the column scrolls (`scroll_details`), `container_actions` becomes a wrapping FlexboxLayout, heights read `@dimen/detail_action_height`, the top bar reads `@dimen/detail_top_bar_inset`, the similar rail is gated on `detail_shows_similar_row`, and the legend is hidden on touch. Easier than the IPTV twin in one way: the series page's action row already lives INSIDE the column, so nothing had to be moved. Also finished off the Debrid HOME: the hero plot cap went 3 → **2** lines, because three still overlapped Play Now (measured on "Obsession"), and the movie column got a 20dp bottom margin since hiding the rail leaves its actions flush to the screen edge | **Phone: PASS.** Movie: top bar clear, nothing overlapping, Watch Now / Trailer / ♥ on screen. Series: **legend gone**, title/metadata/plot clear, the season row clear and **seven episodes** with thumbnails, durations, titles and descriptions — nothing overlapping. Debrid home hero now two lines with Play Now completely clear. **TV smoke on .64: PASS, 0 FATAL.** Series detail unchanged — Play S01 · E01 / Trailer / ♥ on ONE flexbox line, legend present, five episodes. Movie detail unchanged — "44 SOURCES FOUND · 44 CACHED · BEST 4K 6.4 GB", actions on one line, DIRECTOR/CAST, and the "BECAUSE YOU WATCHED" rail with six posters; the source panel still opens ("SELECT SOURCE · DE+HI PRIORITY"). **One honest TV change:** on a film with a long plot AND a cast row, the cast NAMES are now clipped at the column's edge where they used to be painted over the rail's header. Clipped-and-scrollable is the correct containment and the overlap was the bug, but it is a visible difference and the owner should say if they want it sized instead | *(this commit)* |
| 2026-08-08 | **D3** | **Everything §1d had diagnosed but not fixed (owner: "jo diagnose kiya magar fix nahi kiya, wo bhi karo").** **DB-10** — Discover's "See all" is REMOVED, not wired: it had no id, no listener anywhere and was not focusable, so it was decoration on the TV as much as the phone, and the grid beneath it already pages endlessly, so there was nowhere more complete to send anyone. A control that ignores every tap is worse than no control. **DB-8/DB-9** — the Top-10 rail: an 18dp rank box holding 13sp wrapped "01" onto two lines and the fixed 42dp row sliced it; the title had ~40dp and stopped at seven characters. Rank 30dp, row `wrap_content` over a 48dp floor (it is a touch target), two lines, 6sp — and, the change that actually did it, **the 26dp thumbnail is dropped on a phone**, because the grid immediately to its right is nothing but posters and no font size fits a film title in 40dp. **DB-7** — the setup gate's TV copy ("This TV picks them up on its own", "Or on this TV") moved to qualified strings, and its `BACK · RETURN` legend is hidden on touch — third offender after M14b and M16a. **DB-5** — the hero band 320dp → 250dp, returning 70dp to the content below | **Phone: PASS.** Top-10 now reads "Spider-Man: Brand New Day", "The Odyssey", "The Last House", "Obsession", "Evil Dead Burn", "Supergirl", "Disclosure Day" — every one complete, no mid-word break, ranks 01-07 on one line — and Discover's row header no longer offers a dead "See all". **A mistake worth recording:** buying hero space by dropping its content inset 65dp → 40dp hid the "NOW IN 4K" label BEHIND the nav bar. The bar is 22dp of inset + 45dp = 67dp, so 70dp is a floor, not a preference; reverted after measuring. **TV smoke on .64: PASS, 0 FATAL** — the Debrid detail path is healthy ("122 SOURCES FOUND · 122 CACHED · BEST 4K 13.4 GB", resume bar, similar rail with six posters). The TV's Top-10 rail was not reached by hand, so it is verified **mechanically** instead: `aapt2 dump resources` shows every new value carrying its television variant at the old literal — hero 250/**320**dp, rank 30/**18**dp, thumb 0/**26**dp, title lines 2/**1**, "Or right here"/**"Or on this TV"** | *(this commit)* |
| 2026-08-08 | **D2** | **The Debrid home for the hand — plus the overshoot D1 caused.** Four fixes on the home: the top nav bar is inset below the system status bar (DB-11 — measured, a tap at y=60px was taken by the system and Discover only opened at y=82px); the bar's own clock is hidden on a phone, which already shows the time (DB-4, the same flag M15 used); the hero plot is capped to three lines with an ellipsis, so it can no longer paint under the Play Now row (DB-1/DB-3); and `item_debrid_content`'s caption sizes moved into `dimens_debrid.xml`. That last one is **D1's own overshoot**: giving Discover / See All / Search the font scale they had never had took a 14sp caption to 22sp inside a 128dp card, so titles broke mid-word — "Spider-M / an: Bran…", "Obsessio / n" — and the bottom overlay swallowed the poster. **DB-10 diagnosed and NOT fixed:** Discover's "See all" is a decorative `TextView` with no id and no listener anywhere — it is dead on BOTH devices, not a phone regression, and wiring it means choosing a destination I should not invent. The row "See all" on the Debrid HOME is a different view and does work, one tap — which is how D1 finally got verified | **Phone: PASS.** Nav bar clear of the status bar, no duplicate clock, hero plot three lines with nothing overlapping, and See All now reads "Spider-Man: Brand New Day", "Spider-Man: No Way Home", "Disclosure Day" complete on two lines. **D1 is now verified too** — that grid is one of the three screens it fixed. **TV smoke on .64: PASS, 0 FATAL** — the Debrid bar keeps its own clock ("16:05"), sits at y=0, the hero plot renders in full (the 10-line cap never bites), and Continue Watching captions are one line at the old 14sp. **Left open:** the first content row is still clipped by the bottom edge on a phone (DB-5) — the page scrolls and the row peeks, same call as the series page | *(this commit)* |
| 2026-08-08 | **D1** | **The Debrid section's plan (§1d) plus its first fix — the one that was a single line per file.** Audit: the section is `StremioHomeFragment` inside MainActivity (Home / Discover / My Library + search overlay, ~30 collaborator classes) plus three activities. **The M13 font scale reaches only `MainActivity`, `MovieDetailActivity` and `SeriesDetailActivity`** — so Debrid **Discover, See All and Search** were rendering at the TV's px÷2 sizes, 6–9sp, in the hand, and the Debrid home escaped only by living inside MainActivity. All three now call `phoneScaledContext`, and the two that were pinned landscape by the manifest alone (`Discover`, `SeeAll`) also go through `lockLandscapeOnTouchDevices()` so every screen sits on ONE orientation mechanism. §1d records the audit, the seven measured defects and batches D2–D4 | **TV smoke on .64: PASS, 0 FATAL** — and both changes are inert there by construction (`phoneScaledContext` returns its argument when `ui_uses_dpad_focus` is true; the lock helper returns early). **Phone: the code change is NOT visually verified, and here is why.** The section is gated behind `isDebridConfigured()`; unlocking it with a `https://a.invalid/manifest.json` addon got me to the Debrid home and Discover — both readable, both already inside MainActivity — but the three activities the fix targets are reached through **"See all", which does not respond to touch at all** (tried twice; the app stays on Discover). That is a new defect, DB-10, and it blocks its own fix's QA. Also found while measuring: the top nav tabs sit **under the system status bar**, so a tap on their upper half is swallowed by the system — Discover only opened at y=82px, not y=60px | *(this commit)* |
| 2026-08-08 | **M16b** | **The two things M16a left open — and the second one took three attempts, which is worth recording.** (1) The series page gave its info column **92dp**: the header started 91dp down when the top strip only occupies 62dp, and the episode thumbnail is a 10-foot 184x100dp. Both are qualified dimens now (66dp header inset, a 110x62dp card) and the column is **155dp**. (2) The browse header read **"0 titles" beside a sidebar reading 141308**. My first fix reordered the render so the count is looked up after `selectedCategoryId` is assigned; my second added a paging load-state listener so a page load re-runs the count. Both were right, and **neither fixed it**. The actual cause is the same class of defect as M7b/M7c/M15: `refreshSectionCount()` is guarded by `lastFocusTarget != MOVIES` so it cannot clobber the meta line a FOCUSED poster writes — but `lastFocusTarget` is **initialised to MOVIES** and a touch device never changes it, so on a phone the count was never written at all and the header kept the layout's literal. The guard now reads `R.bool.ui_uses_dpad_focus && lastFocusTarget == MOVIES`, which is the identical condition on TV | **Phone: PASS, measured.** Movies reads "**141308 titles**", Series "**37277 titles**" — both now agree with their sidebar. Series page: info column 92dp → 155dp, metadata complete on two lines including the "AVAILABLE IN 6 CATEGORIES · MULTI-SOURCE" chip, season row clear, and **seven** episodes with thumbnails and titles, nothing overlapping. **TV smoke on .64 — both branches of the guard checked, which is the point:** with a poster focused the header still shows the focused item ("The Outer Threat" / "★ 2.8"), and with focus on the category rail it shows "**317550 titles**"; search box 38dp, chips 28dp unchanged. **0 FATAL on both.** **Still not done and not claimed:** the series page's `Play S01 · E01` remains below the fold in the scrolled column. It needs ~62dp more and the only ways to get them are hiding the plot or shrinking the title further. Left as-is deliberately, because **episode 1 is the first card in the strip — on screen and one tap** — so the action itself is not unreachable. Owner's call whether to pin the button | *(this commit)* |
| 2026-08-08 | **M16a** | **Browse and the movie detail page — and the owner's photograph of a real handset found the defect my emulator sample could not.** Browse first: at the M13 1.6x scale the sidebar's search hint wrapped and was sliced ("Search" over half of "movies…"), category names truncated to "All Mo… 141308" / "WORLD CU… 93", the `singleLine` section title became "All…", every poster caption was cut ("NL The …", "The Ne…", "Spider-…"), and the sort chips were 28dp. New `dimens_vod.xml` (phone) + `values-television/` (TV literals verbatim) serves both browse screens and both cards; the poster caption also gets **two** lines on a phone via `@integer/vod_card_title_lines`, because a 99dp card cannot hold a film title on one. Then the detail page. The owner's screenshot showed the plot, the buttons and the "BECAUSE YOU WATCHED" rail all painted **on top of each other**; my own sample had no plot, so it looked fine. Cause: the info column is a 0dp-tall LinearLayout that lays children out from the top and runs past its own bottom — and **`clipChildren="false"` on the ROOT means that overflow is not clipped anywhere in the subtree**, so it paints over the rail. Wrapping the column in a NestedScrollView did NOT fix it until the root's `clipChildren` was restored; that was two builds and it is the single most useful thing learned here. Also: `container_actions` is now a FlexboxLayout that WRAPS (the four buttons need more than the 480dp column, so "Mark Watched" was squeezed to 93dp and its label sliced), actions are 48dp, and the top bar clears the system status bar. With the column scrolling, Watch Now still sat below the fold — so on a phone the recommendation rail is hidden (`R.bool.detail_shows_similar_row`), which returns 169dp to the film's own column. Series detail got the same recipe plus its **D-pad legend hidden** (M14b's rule, second offender) | **Phone: PASS, measured.** Browse: search box 48dp with its hint on one line, chips 48dp, "All Movies" in full, and captions reading "The Odyssey" / "Spider-Man: Brand New Day" / "Water Park Shark" complete. Movie detail: nothing overlaps, and Watch Now / Trailer / ♥ are on screen at 48dp with "Mark Watched" wrapped onto a second row in full. Series detail: no overlap, no legend, episode strip clear of the buttons. **TV smoke on .64 — the one that mattered, since two layouts were restructured:** browse is byte-identical (search 38dp, chips 28dp, captions one line, category rows 44dp at a 48dp pitch); movie detail's `group_details` measures 293dp inside a 324dp viewport so **it never scrolls**, the four actions stay on ONE flexbox line, the similar rail is present and D-pad DOWN reaches the cast; series detail's `container_header` is 256dp in a 392dp viewport, `layout_credits` renders in its old place, Play S01·E01 is 29dp and takes focus, hint bar present. **0 FATAL on both.** **NOT done, stated rather than implied:** on the phone the series page's Play S01·E01 and plot are still below the fold in the scrolled column (the episode strip owns the bottom half) — episodes are tappable, which is that page's job, but the button needs a scroll. That is M16b. Also NOT verified by me: a real handset — the owner's photo was the input, not my output | *(this commit)* |
| 2026-08-08 | **M15** | **Home for the hand — the density complaint turned out to be one number.** The hero BAND is a fixed 360dp: 67% of a 540dp television and **88% of a 411dp phone**, which is exactly why Continue Watching was a sliver at the bottom edge. It is 268dp on a phone now (section 340→248dp), and every action on the screen was under the touch minimum — Play Now 125x**31.6**dp, More Info 112x**30**dp, favourite **30x30**dp, nav rail items **34x34**dp. All the numbers moved into `dimens_home.xml` with the **TV literals kept verbatim in `values-television/`**. Two things were found only by looking at the result: (a) a two-line featured title rendered straight **through** the KEY chip, so home's own clock/key/expiry strip is now hidden on a phone — it is TV chrome (a television has no system status bar) and it duplicated the system clock; insetting the hero below it instead needs a 280dp section, which pushes Continue Watching off the bottom. (b) **Play Now needed TWO taps.** Same defect M7b/M7c chased, in the 14 layouts they deliberately left alone: `focusableInTouchMode="true"` and `focusedByDefault="true"` as literals on the three hero buttons, plus `HomeFocusManager.applyInitialFocusIfNeeded` MOVING focus onto Play Now at launch. All four now read the touch bools | **Phone: PASS, measured before and after.** Targets: Play Now **119x48dp**, More Info 112x48dp, favourite **48x48dp**, every nav rail item **60x40dp** and all six fully on screen (the first attempt sliced the sixth in half — six items at 40dp+3dp need 258dp and the rail has 236dp, so the gap is 0 and the list inset 4dp). Continue Watching is whole, with poster, progress bar, title and "1:09:55 / 1:41:12" all above the fold, and a two-line hero title no longer collides with anything. **One tap acts, proved three ways:** a CW card → PlayerActivity, a rail icon → Live TV with real channels, and Play Now → the detail page **on the first tap** where it previously took two. **TV smoke on .64:** launch focus lands on `btn_hero_watch` exactly as before, the status strip is present, and every metric is byte-identical — hero band 360dp, hero_content [192,175][952,648], buttons 95x30 / 90x30 / 30x30dp, rail items 34x34dp at a 38dp pitch, Settings 34dp, profile 30dp. D-pad moves DOWN into the rails and RIGHT along them. **0 FATAL on both.** Honest shortfall: the rail's 40dp is 8dp under the 48dp guideline in one dimension (60dp wide, so larger in area) — six destinations plus Settings plus the profile mark need ~431dp at 48dp and the column is 411dp. Raised for the owner, not hidden. Not verified: a real handset (emulator only); the emulator also needed its RAM raised 3→4GB to stop ANR-ing, which is a harness fix, not an app one | *(this commit)* |
| 2026-08-08 | **M14c** | **Settings, measured rather than eyeballed — every fixed height in it overflowed the M13 font scale.** Four faults, all proved with `uiautomator` bounds before touching anything: the detail row is a fixed 74dp and the toggle's description wanted 243px of it, so the second line was cut through the middle; the category row is a fixed 54dp and sliced "AUDIO · LANGU"; those TextViews were `wrap_content` inside a *weighted* column, which CLIPS instead of ellipsising, so the subtitle also lost its tail sideways; and the two headers ate **144dp of a 411dp-tall** screen — 35% — leaving about 2.5 rows visible. Rows are now `wrap_content` over a `minHeight` floor, the text is `match_parent` so it degrades to an ellipsis, and the chrome sizes moved into `dimens_settings.xml` with the **TV's literals kept verbatim in `values-television/`**. One fault could not be fixed in a shared file: LinearLayout serves a `wrap_content` child its width BEFORE a weighted one, so the value starved the title to ~169dp and it still read "Secondary Audio Langu…" even in a taller row — capping the value only moves the ellipsis onto the value. So `layout/item_settings_selection.xml` now stacks title / value / description the way Android's own settings do, and the TV's side-by-side row moved to `layout-television/` **byte-for-byte**. Also verified rather than assumed: **M14a's Add-Addon fix, which its own commit said was unverified** | **Phone (Pixel emulator, 914×411dp landscape, fontScale 1.6) — PASS, re-measured after the fix.** Every string renders in full: "AUDIO · LANGUAGE", "MOVIE · SERIES · LIVE", "REFRESH · CACHE", "Preferred Audio Language" over "English (EN)", and the toggle's whole sentence "…the main audio format (AC3/EAC3) is not supported" on three lines. Rows 68dp / 91dp — all clear of 48dp. Detail list grew 209dp → 326dp. **M14a proved on-device:** typing a long manifest URL leaves the dialog's geometry unchanged (222→858px) and ADD reachable at 88×53dp; the landscape IME opens the platform's fullscreen extract editor and DONE returns with the text intact. Picker dismisses on a scrim tap; BACK leaves Settings. **TV smoke on .64 — the important one, since a layout was forked:** every number is back on its old value *exactly* — category row 54dp, selection row 74dp, toggle row 74dp, rail and panel headers pixel-identical; title and value still side by side; the language picker opens with real data; BACK×2 reaches home. **0 FATAL on both.** The first attempt did NOT achieve that — 6dp/10dp of new padding beat the minHeight floor and pushed the TV to 56dp/85.5dp; caught by measuring, fixed with a `0dp` television override | *(this commit)* |
| 2026-08-06 | **M7c** | **The poster grid opens on one tap** — and the cause was the one thing I had told myself was not there. M7b turned `focusableInTouchMode` off in the row LAYOUTS, but `VodFragment` sets `rvMoviesGrid.isFocusableInTouchMode = true` in CODE, which simply turned it back on: the RecyclerView takes focus from the touch, `FOCUS_AFTER_DESCENDANTS` hands it to the poster, the poster takes focus, and the click is swallowed. An earlier grep of mine for that symbol reported nothing and I believed it — there were **21 such assignments across 16 files** (Movies, Series, Debrid, EPG, Favorites, Settings, Browser). All 21 now read `R.bool.ui_uses_dpad_focus`: `true` in `values`, so TV resolves exactly the literal it replaced, `false` in `values-port`. Also gated `VodFocusController`'s four focus-MOVING entry points on the same bool — correct per the rulebook (a touch device should not move focus programmatically) but **it did not fix the two-tap on its own**, and that is stated rather than implied | **Phone: PASS, measured before and after.** Before: `TOUCH DOWN → TOUCH UP → FOCUS=true`, no click. After: `TOUCH DOWN → TOUCH UP → CLICK`, and the detail opens — "FEATURE FILM / \|DE\| The Matrix Resurrections". Re-verified on the clean build with the diagnostics removed. 0 FATAL. **TV smoke on .64 — the important one, since the D-pad focus model was touched in 16 files:** focus present on launch (`btn_hero_watch`), D-pad moves it, Movies opens, RIGHT/DOWN move focus inside the grid, and **one CENTER press opens a movie** ("\|AR\| Fists of Legend"). 0 FATAL | *(this commit)* |
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
- ~~**The Movies/Series poster grid STILL needs two taps**~~ — **FIXED in M7c.** The cause was a
  CODE assignment (`rvMoviesGrid.isFocusableInTouchMode = true`) re-enabling what M7b had turned
  off in the layout. ⭐ **Lesson worth keeping: a layout-level flag is only half the story — grep
  the Kotlin for the same property, and do not trust a single grep that comes back empty.** Mine
  did, and it cost two wrong fixes. The record of those attempts is below.
- ~~**The Movies/Series poster grid two-tap groundwork**~~ Groundwork done so the next
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

### M9 addendum (2026-08-06) — the player on touch

Three defects reported from the owner's own handset, all one root cause: the player screen was
still listening only for keys.

1. **"the movie player doesn't go back."** `vodBackAction` spends the first BACK hiding the
   controls and only exits on the second — correct for a remote, wrong for a back GESTURE, where
   it reads as nothing happening. On a phone BACK now leaves immediately (PiP still passes
   through). Covered by a new unit test; the TV cases are unchanged and still pass.
2. **"touching Live shows no controls."** A tap on the video now sends the same KEYCODE_DPAD_CENTER
   the remote's OK sends.
3. **"how do you zap?"** A vertical fling sends DPAD_UP / DPAD_DOWN — swipe up for the next
   channel. Horizontal flings are ignored, and anything under 120px is treated as a stray finger.

Gestures deliberately SYNTHESISE the existing key events instead of calling the handlers directly:
the zap debouncer, the warm-zap adopt path and the OSD's auto-hide are all wired to those keys and
have months of device testing behind them — a second parallel path is how the two drift apart.
TV is untouched by construction: the gesture layer is never attached when `ui_uses_dpad_focus`.

**QA honesty:** built green (unit tests + detekt), TV smoke on .64 launches with 0 FATAL. The Live
player's touch behaviour could NOT be verified on the emulator — its window is a secure surface, so
uiautomator cannot see the OSD and a synthetic swipe produced no zap in the log. That part is
verifiable only on a real handset.

**M9 / M9b — CONFIRMED ON THE OWNER'S HANDSET (2026-08-06).** All three reports are closed:
BACK leaves the movie player, a tap on Live shows the controls, and swipe up/down zaps. This is
the QA my own commits could not provide — the Live player's window is a secure surface, so the
emulator showed neither the OSD nor a zap in the log. Recorded here because two of those commits
say "not verified by me", and this is what verified them.

⭐ **The qualifier lesson, worth more than the batch:** `-port` answers "is this screen portrait?",
NOT "is this a phone?". Fullscreen playback is landscape on a phone, so any device-level flag on
`-port` silently gives the TV answer exactly where it matters most. Device-level flags belong on
`-television`; only true layout-orientation flags belong on `-port`.

---

### M10b — brightness and volume by dragging the video (2026-08-07)

The last two gestures a phone player is expected to have. Drag the **left** half vertically for
screen brightness, the **right** half for volume; a pill in the middle of the video shows the level
and fades ~0.7s after the finger lifts. Both live in the same `GestureDetector` M10a added, behind
the same `!ui_uses_dpad_focus` gate, so the TV attaches nothing.

**Seeking was deliberately NOT added.** media3's `DefaultTimeBar` already scrubs on touch and
`PlayerVodControlsUi.setupSeekOverlay()` already listens to it — a second seek path over the video
would fight the one that is already device-tested (and that path carries the directional
`SeekParameters` that stopped seeks snapping back to the start). Horizontal drags are therefore
passed straight through: `onScroll` returns false whenever |dx| >= |dy|.

⭐ **The bug this batch nearly shipped, and the rule behind it.** The first version applied each
scroll delta to the *current* volume. Volume is an integer 0..15, one scroll delta is worth ~0.15 of
a step, and `toInt()` throws that away — **every single time**. The gesture compiled, ran, called
`setStreamVolume` on every move event, and the volume never changed by one unit. It was silently
dead, and only a before/after `dumpsys audio` read caught it (a screenshot would have shown a
perfectly plausible badge). The fix is to hold the value the gesture *started* from and apply the
*accumulated* travel: **on a coarse integer scale, always integrate the gesture, never the deltas.**

**QA — phone (Material rulebook), Pixel emulator, portrait, real playback:**
- volume: `STREAM_MUSIC` **0 → 8** on a swipe up, **8 → 0** on a swipe down (`dumpsys audio`), and
  **13/15** after a long drag; badge screenshotted mid-drag reading `🔊 53%`.
- brightness: badge screenshotted mid-drag reading `☀ 99%`, video still playing underneath.
- the seek bar still scrubs: dragging the thumb moved **06:14 → 51:37** and playback continued
  there, so the new layer did not steal horizontal touches.
- 0 FATAL in logcat.

**QA — TV (10-foot rulebook), Fire TV .64, versionCode 40:** app launches, Continue Watching plays
(Silo S1:E1, 4K debrid), the OSD shows with the focus ring on the play button, and D-pad RIGHT
seeks (13:05 → 13:54). 0 FATAL. Nothing about the TV path changed — by construction the gesture
layer is never attached there.

---

### M2c — the Continue Watching options stop being a TV control (2026-08-07)

The quick-info bubble was the last 10-foot control still reachable with a finger. Long-pressing a
Continue Watching card opened it on a phone too: **5.5-7.5sp text, 20dp chips**, and its entry point
calls `qiOpen.requestFocus()` — a no-op in touch mode, so the menu opened with nothing focused.
Three separate breaches of the phone rulebook in one control.

**What the phone gets now:** a Material bottom sheet — full-width **56dp** rows, **16sp**, one tap
acts, scrim tap closes — and a **visible ⋮ button on the card**, because the rulebook says a
long-press may be a shortcut but never the only route to an action. The button is a 48dp touch
target with 12dp padding, so it keeps the Material minimum without swallowing a 144×81dp card.
Long-press still works and opens the same sheet.

**What TV gets: nothing new.** `openOptions()` is the single entry point and branches on
`ui_uses_dpad_focus`, so the D-pad long-press still reaches `enterActionsMode` and the bubble. The
⋮ button stays GONE there (it would only be one more stop for the D-pad). The focus-dwell bubble is
now explicitly gated too — under touch the card has not been focusable since M7c, so it could not
fire anyway; the guard keeps that true if focus behaviour changes again. Also fixed:
`view_card_quick_info.xml` still hard-coded `focusableInTouchMode="true"` on both chips, which M7b's
sweep missed because it only covered `item_*.xml`.

⭐ **A `BottomSheetDialog` built from a Context alone gets Material's LIGHT dialog theme.** Our
layout paints everything inside the sheet, so the only thing that showed was the window's
navigation-bar area — a pale band under a dark sheet. Colouring the sheet container in code does
**not** reach it, and neither does setting `navigationBarColor` on the dialog window (before or
after `show()`, with or without `isNavigationBarContrastEnforced=false` — all three were tried and
all three measured identical). The fix is an explicit `ThemeOverlay.MaterialComponents.
BottomSheetDialog` overlay passed to the constructor.

**QA — phone (Material rulebook), Pixel emulator, portrait:**
- ⋮ button present and correctly sized — uiautomator reports `btn_cw_more` at `[294,1328][420,1454]`
  = **126px = 48dp** at density 420, `clickable="true"`.
- tapping it opens the sheet; **long-press opens the same sheet**; both screenshotted.
- **Details** opens the movie detail page with the resume bar reading `RESUME FROM 15:30`.
- **Remove** dismisses the sheet, drops the row, and persists — `continue_watching` in
  `shared_prefs/watch_history.xml` reads `[]` afterwards.
- 0 FATAL.
- Residual, measured not guessed: sheet content is `#050608`, the gesture-bar band `#2D2D2D`
  (it was `#353535` before the theme overlay). That band is the system's contrast treatment over
  the sheet, not our colour; it is cosmetic and it is not fixed.

**QA — TV (10-foot rulebook), Fire TV .64:** launches, Continue Watching intact (13 titles),
**no ⋮ button on any card**, OK on a card still starts playback (Silo S01E05), BACK returns, 0 FATAL.
**Not verified by me: the TV bubble's long-press.** `adb input keyevent --longpress` sets the
long-press FLAG but leaves `repeatCount` at 0, which is exactly what the card's key listener tests,
so the synthetic press lands as an ordinary select and starts playback instead. The listener body
was moved verbatim into `attachCardKeyListener` with no logic change, but that is an argument, not
a test — the bubble needs a real remote. (Same limitation already recorded in the CW bubble notes.)

**Also confirmed while testing, still open:** the Movies browse screen has no bottom navigation, so
from there Home is reachable only with the system Back gesture. That is the M3 follow-up, untouched
by this batch.

---

### M10c — the VOD controller never appeared on a phone (2026-08-07)

Owner report: "controller aur gestures VOD mein sahi se kaam nahi kar rahe." It reproduced on the
first try, and it was a real defect — not a gesture problem at all.

**Root cause, two handlers fighting inside one tap.** `Activity.onUserInteraction()` fires on
ACTION_DOWN, and `hostUserInteraction()` showed the VOD controller from there. Then on ACTION_UP,
media3's own `controllerHideOnTouch` toggle ran, saw a visible controller, and hid it again. Show
and hide inside a single tap, so **on a phone the VOD chrome never appeared at all.** On TV nobody
taps — the chrome comes up on a D-pad key — which is why months of TV QA never saw it.

Proof it was that and not something else: logcat showed `PLAYER_SEEK_FOCUS: show controller` and
`focus transport target=exo_pause` on every tap — the controller genuinely was being shown — while
screenshots 0.7s later showed bare video.

**Fix:** `hostUserInteraction()` now treats a touch device the same way it already treats Live and
PiP — chrome suppressed. A touch device already has a correct show/hide (PlayerView's tap toggle);
the job was to stop competing with it, not to add a third path.

**QA — phone (Material rulebook), Pixel emulator, LANDSCAPE (the orientation the player actually
runs in on a handset, which earlier batches never tested):**
- one tap → full controller: title, seek bar, transport row, all buttons (screenshot)
- tap again → hides (screenshot)
- volume swipe on the right half: `STREAM_MUSIC` **13 → 15**
- brightness drag on the left half: badge `☀ 100%`
- 0 FATAL

**Not verified: double-tap-to-skip.** `adb input tap` spawns a process per call, so two taps land
~300-400ms apart — at or beyond the platform's double-tap window — and the OSD's toggle-on-every-tap
makes reading the position back unreliable. The key path the gesture uses is fine
(`KEYCODE_MEDIA_FAST_FORWARD` moved 08:02 → 08:39 across two presses), and the same GestureDetector's
`onScroll` demonstrably works, so only stock double-tap recognition is unproven. It needs a real
finger.

⭐ **The lesson, and it is the M9b lesson again from the other side:** M10a's comment claimed
"media3's own controller already shows and hides the chrome on a tap here" — an assumption written
into a code comment and never exercised, because that batch's QA only covered the double-tap's
target, not the chrome. **Every batch that touches the player must include one plain tap, in
landscape.**

**Also seen (open, not this batch):** in landscape a phone falls back to the TV home layout — side
rail, "OK SELECT / BACK EXIT" legend — because `layout-port` by definition does not apply there.

---

### M11 — a phone stays a phone when you rotate it (2026-08-07)

Owner report: rotate the handset and the Live channel list disappears. It reproduced immediately,
and the cause is the qualifier system, not the Live screen.

**Two distinct failures, one root.** `layout-port/` and `values-port/` mean **PORTRAIT**, not
**PHONE**:
1. An activity **created** in landscape inflates `layout/` — the 10-foot TV design. Cold-launching
   the app sideways gave the phone the TV home: side rail, "OK SELECT / BACK EXIT" legend.
2. `MainActivity` declares `configChanges="orientation|screenSize|…"`, so **rotating** it does not
   re-inflate. The portrait layout stays, and its vertical stack (chips → preview → Now Playing →
   Program Guide → channel list) is simply taller than a 411dp-tall landscape viewport, so the
   channel list sits below the fold with nothing to suggest it is there.

**Fix, part 1 — the bools are DEVICE questions, so they moved to the device qualifier.** All seven
remaining flags left `values-port` for `values` (phone answers) + `values-television` (TV answers).
Keyed on orientation, a rotated handset silently got the TV answer for every one of them: the
bottom bar became a side rail, browse categories became a left rail, the source sheet stopped
closing on a backdrop tap, and Live's default flipped from the channel list to the D-pad-only EPG
guide. `-television` matches a TV in either orientation and never matches a phone — which is the
question actually being asked. `values-port/bools_ui_mode.xml` is deleted.

**Fix, part 2 — the browse screens stay portrait on a touch device.** `lockPortraitOnTouchDevices()`
in the four activities that HAVE a portrait design (Main, MovieDetail, SeriesDetail, Activation).
Fullscreen playback still rotates; nothing else is locked into a layout it does not have.

⭐ **The call site is `super.onCreate`-first, and that ordering is load-bearing.** Placed after it,
the lock rotated the WINDOW to portrait but the TV layout had already been inflated — and with
`configChanges` swallowing the change it never re-inflated, so the phone showed the TV design inside
a portrait window: worse than before. Screenshotted both ways before moving the call.

**Why not simply write `layout-land/` phone variants:** orientation outranks UI mode in Android's
qualifier precedence, so a TV — which is landscape — would pick `layout-land` over
`layout-television` and every TV screen would regress. Freeing the default bucket would mean
migrating ~150 TV layouts into `-television`. That is a migration, not a fix.

**QA — phone (Material rulebook), Pixel emulator:**
- cold launch with the device rotated to landscape → phone home, bottom nav, no TV rail
- Live TV → rotate → rotate back: stays portrait, **channel list still on screen** (the reported bug)
- 0 FATAL

**QA — TV (10-foot rulebook), Fire TV .64, versionCode 43:** home renders the vertical rail and
horizontal rows exactly as before — which is also the direct evidence that `values-television` is
the file being read, since all seven flags come from it. 0 FATAL.

**Open, not this batch:** `DebridDiscoverActivity`, `DebridSeeAllActivity`, `CompanionSetupActivity`
and `TrailerActivity` are `screenOrientation="landscape"` in the manifest and have no portrait
design, so on a phone they are still forced into the TV layout. And the in-player source sheet runs
in landscape with the player, so it uses the TV panel rather than `layout-port/dialog_source_selection`.

---

### M12 — the EPG guide answers a finger (2026-08-07)

The "New EPG Guide" was the last screen that rendered on a phone and then ignored every touch:
`EpgGridView` is a custom `View` whose only input was `onKeyDown`. Two things were wrong, and both
had to be fixed for the screen to be usable at all.

**Input.** A `GestureDetector` + `OverScroller`, installed only when `!ui_uses_dpad_focus`:
- drag pans **both axes** — it is a 2-D grid, not a list
- fling continues through `computeScroll()`
- **one tap acts**: hit-test → set `focusRow`/`focusProg` → the same `selectFocused()` the remote's
  CENTER calls, so the two input models route through one listener and cannot drift apart
- `requestDisallowInterceptTouchEvent` on ACTION_DOWN, or the scrolling host steals the vertical
  drag as soon as it crosses its slop and the grid stops panning mid-gesture

On TV the detector is never constructed, so the D-pad traversal is reached exactly as before.

**Size.** `epg_channel_col_width` was **300dp** — three quarters of a 411dp handset, leaving ~111dp
of programme lane. Worse, the channel cell is number + logo + name at a 38dp indent and a 40dp tile,
so every channel read `"|..."`. The EPG dimens moved to `values-television` (the original spec
values) with phone values in `values/`: 136dp column, 20dp indent, 26dp logo, rows 52/60/76dp (every
row still a 48dp+ target). Same device-not-orientation rule as M11.

**QA — phone (Material rulebook), Pixel emulator, portrait, real EPG data:**
- horizontal drag moved the time header NOW/19:00 → 19:30/20:00
- vertical drag moved the visible channels from 1-11 → 6-16
- tapping the "Bettys Diagnose" block moved the selection to that row and flipped the detail panel
  from ON AIR NOW to UP NEXT — i.e. `onFocusChanged` + `onProgramSelected` both fired
- channel names read `"|WC| BEIN …"` / `"|WC| DAZ…"` instead of `"|..."`
- 0 FATAL

**QA — TV (10-foot rulebook), Fire TV .64, versionCode 44:** the guide is unchanged — 300dp channel
column, 40dp logos, full names, focus ring on the D-pad-selected row, preview + detail panel, time
header. 0 FATAL.

**Open, not this batch:** the guide's SURROUNDING chrome is still the TV layout
(`fragment_live_tv_guide.xml` has no `layout-port` variant) — the preview tile is small, the
"ON AIR NOW" label renders vertically because its column is squeezed, and the strip runs under the
status bar. The grid itself is usable; the frame around it needs a portrait design.

---

### M13 (IN PROGRESS, branch `mobile-landscape`) — the phone runs landscape, like the TV

Owner decision 2026-08-07, and it reverses M11: **the phone is never portrait.** The app opens
landscape however the handset is held, and looks like the TV — driven by a finger. Owner also chose
**option B**: TV shape, but phone text scaled up (~1.6×), because the TV layouts are sized for 3
metres.

**Done on the branch:** `lockLandscapeOnTouchDevices()` in all eight activities (before
`super.onCreate`), verified — the app opens landscape with the device held portrait. Bools
re-split into **SHAPE** (matches the layout, same on both devices) and **BEHAVIOUR** (touch vs
D-pad, `-television` override). EPG dimens back to the single TV spec set.

**Blocking issue, and the first diagnosis was wrong.** Home renders with a wide panel across the
middle of the hero. I first read that as "the phone shell was built for portrait and needs a
rework". Grepping it says otherwise: the home nav rail is an `<include>` of `view_home_sidebar`
inside the TV layout and its orientation already follows `home_nav_is_horizontal` (now false), while
the panel in the screenshot carries `nav_flyout_title` — it is **`@id/nav_flyout`**, the
focus-driven external flyout from the home redesign, showing while nothing is focused. That is a
much smaller fix than a shell rebuild, and it is unverified: confirm before building on it.

**Remaining:** gate the nav flyout on `ui_uses_dpad_focus`; add the phone text-scale layer; retire
the 21 now-dead `layout-port/` files (leave on disk); QA phone + TV; merge.
