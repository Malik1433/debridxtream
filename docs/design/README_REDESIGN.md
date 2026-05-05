# Derbix TV — Cinematic Redesign Migration Guide

This package replaces the legacy black/gold theme with a refined **cinematic** system: deep ink surfaces, restrained gold accent, glass cards, and gold-edge focus glow. Everything new is namespaced `cin_*` so it lives alongside your existing resources without collision — flip screens over one at a time.

---

## 1. Drop-in install

Copy the contents of `debridxtream-redesign/res/` into your app's `app/src/main/res/` (merge — don't overwrite). Copy the two files in `debridxtream-redesign/kotlin/` into your `ui/theme/` and `ui/patches/` packages and update the `package` line.

**No legacy resource is renamed or deleted.** The migration is screen-by-screen: switch a fragment's `setContentView`/`R.layout.*` reference to the new layout and the cinematic theme takes effect for that screen only.

---

## 2. Apply the theme

In `AndroidManifest.xml`, set the application theme (or per-activity):

```xml
<application
    android:theme="@style/Theme.DerbixCinematic"
    ...>
```

Or apply per-activity if you want to dual-run during the rollout.

---

## 3. Layout migration map

| Old layout | New cinematic layout | Notes |
|---|---|---|
| `fragment_home.xml` | `fragment_home_cinematic.xml` | Hero backdrop + ambient bloom + Top10 row + continue-watching + movies/series rails. Use `cin_item_poster.xml` for adapter rows. |
| `fragment_login.xml` | `fragment_login.xml` (new) | 45/55 split: branding left, glass form right. Demo button is now a ghost-style secondary. |
| `fragment_live_tv.xml` | `fragment_live.xml` | Categories rail + channel rail + large now-playing preview card. |
| `fragment_vod.xml` | `fragment_vod.xml` (new) | Filter row + 6-column poster grid; ambient gold bloom top-right. |
| `fragment_series.xml` | `fragment_series.xml` (new) | Same shape as vod, series-tuned copy. |
| `fragment_movie_detail.xml` | `fragment_movie_detail.xml` (new) | Cinematic hero (640dp backdrop) + poster overlap + gold primary CTA. |
| `fragment_series_detail.xml` | `fragment_series_detail.xml` (new) | Hero + season rail + episode list (`cin_item_episode.xml`). |
| `fragment_settings.xml` | `fragment_settings.xml` (new) | Glass account card + grouped rows (`cin_item_settings.xml`). |
| `fragment_player.xml` | `fragment_player.xml` (new) | Top/bottom gradient overlays, gold seekbar. |
| `fragment_search.xml` | `fragment_search.xml` (new) | Search field in topbar; result grid uses `cin_item_poster.xml`. |
| `fragment_initial_sync.xml` | `fragment_initial_sync.xml` (new) | Logo + ambient bloom + gold progress bar. |
| `fragment_companion_setup.xml` | `fragment_companion_setup.xml` (new) | Glass card with phone-pair instructions + QR frame. |
| `nav_drawer_item.xml` | `cin_view_sidebar.xml` + `color/cin_sidebar_text_selector.xml` | Pill-style active state with 3dp gold indicator. |

Item layouts (adapter rows):

| Purpose | Layout | Drawable focus |
|---|---|---|
| Poster card (movies, series, search) | `cin_item_poster.xml` | `cin_card_poster_focus.xml` |
| Top-10 card with giant numeral | `cin_item_top10.xml` | (uses poster focus) |
| Live channel tile | `cin_item_channel.xml` | `cin_channel_card.xml` + `cin_badge_live.xml` |
| Episode row | `cin_item_episode.xml` | `cin_settings_row.xml` |
| Settings row | `cin_item_settings.xml` | `cin_settings_row.xml` |

---

## 4. Color token map

Replace any direct hex/legacy color usages with these tokens. **Do not invent new colors** — extend the palette in `colors_cinematic.xml` if a new role is needed.

| Old → New | Token |
|---|---|
| `#000000`, `background_pure_black` | `cin_ink_900` |
| App background | `cin_ink_800` |
| Card surface | `cin_ink_600` / `cin_glass_06` |
| Primary text | `cin_text_primary` |
| Secondary text | `cin_text_secondary` |
| Muted/meta text | `cin_text_tertiary` |
| Brand gold | `cin_gold_500` (default), `cin_gold_300` (hover/highlight), `cin_gold_700` (pressed) |
| Gold glow | `cin_gold_glow_25` (focus), `cin_gold_glow_40` (heavy) |
| Live indicator | `cin_live_red` |

The bottom of `colors_cinematic.xml` has commented-out aliases (`background_dark`, `gold_primary`, `cinematic_gold`). Uncomment any of them after auditing the call sites — that flips matching legacy references over without touching every layout.

---

## 5. Drawable map

| Role | Drawable |
|---|---|
| App background gradient | `cin_bg_app.xml` |
| Sidebar background | `cin_bg_sidebar.xml` |
| Glass card surface | `cin_card_glass.xml` (default) / `cin_card_glass_subtle.xml` (rails) |
| Poster card focus state | `cin_card_poster_focus.xml` (selector) |
| Hero gradient overlay | `cin_hero_overlay.xml` |
| Ambient bloom (radial gold) | `cin_bloom_gold.xml` |
| Vignette | `cin_vignette_radial.xml` |
| Primary gold button | `cin_btn_primary_gold.xml` |
| Secondary glass button | `cin_btn_secondary_glass.xml` |
| Ghost button | `cin_btn_ghost.xml` |
| LIVE badge | `cin_badge_live.xml` + `cin_dot_live.xml` |
| Quality / metadata badge | `cin_badge_glass.xml` / `cin_badge_gold.xml` |
| Pill chip | `cin_pill_glass.xml` |
| Progress (gold) | `cin_progress_gold.xml` |
| Player seek bar | `cin_seekbar_track.xml` + `cin_seekbar_thumb.xml` |
| Player chrome scrim | `cin_player_overlay.xml` |
| Sidebar item | `cin_sidebar_item_selector.xml` (`color/cin_sidebar_text_selector.xml` for text) |

---

## 6. Focus animation

Cards get the cinematic 1.06 scale + lift via `@animator/cin_focus_state` applied through `android:stateListAnimator`. This is wired into `cin_item_poster.xml` already. For custom views, either:

- Add `android:stateListAnimator="@animator/cin_focus_state"`, or
- Call `view.applyFocusLift(hasFocus)` from `View.OnFocusChangeListener` (helper in `CinematicTheme.kt`).

---

## 7. Typography

Type scale lives in `dimens_cinematic.xml`. Reference styles in layouts via `style="@style/Cin.Text.Title"` etc:

- `Cin.Text.Display` — 48sp, hero titles
- `Cin.Text.Title` — 28sp, screen titles
- `Cin.Text.SectionHeader` — 18sp bold, row headers
- `Cin.Text.Body` — 16sp
- `Cin.Text.Caption` — 13sp
- `Cin.Text.Overline` — 12sp uppercase gold (use sparingly — section eyebrows only)
- `Cin.Text.Meta` — 13sp tertiary (year/runtime/etc)

---

## 8. Rollout sequence

Suggested screen-by-screen order (each step is independently shippable):

1. **Splash + Initial sync** — lowest risk, sets the tone.
2. **Login + Companion setup** — first screens users see.
3. **Sidebar + Topbar** — applies globally; will visibly retheme everything inside.
4. **Home** — biggest visual win; pairs well with sidebar.
5. **Movies / Series / Live** — content shelves.
6. **Movie detail / Series detail** — uses hero pattern.
7. **Search + Settings** — utility screens.
8. **Player** — last; verify seekbar and chrome on real device.

---

## 9. Files in this package

```
debridxtream-redesign/
├── res/
│   ├── values/        colors_cinematic, dimens_cinematic, styles_cinematic
│   ├── drawable/      33 cin_*.xml drawables
│   ├── layout/        13 fragment_* + 6 cin_item_* + 3 cin_view_*
│   ├── color/         cin_sidebar_text_selector.xml
│   ├── animator/      cin_focus_state, cin_focus_in, cin_focus_out
│   └── anim/          cin_fade_up, cin_pulse
└── kotlin/
    ├── CinematicTheme.kt          — color/text helpers
    └── FragmentBindingPatches.kt  — annotated wiring snippets per fragment
```

All new resources are prefixed `cin_` / `Cin.` so a global find for that prefix surfaces every cinematic touchpoint.
