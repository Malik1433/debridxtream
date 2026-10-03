# DX Play TV (`tv-web`) ↔ Android TV — parity audit (2026-10-02)

Owner rule (CLAUDE.md, "DX Play TV app"): tv-web is a hu-ba-hu copy of the Android app. A gap is
a defect. Source of truth: the Android TV resources — `res/layout*/`, `res/values-television/`,
`res/drawable/`, `res/values/strings*.xml` — and the screen Kotlin. **Units:** the Fire TV renders
960×540 dp at 1080p, so **1 dp = 2 px** on our 1920×1080 stage. Every size below is already in px.

Palette (Android `stremio_*` / `vod_*`): bg `#06090E` · cyan `#00F0FF` · blue `#0077FF` (buttons and
focus are a 315° blue→cyan gradient) · text `#F1F5F9` / `#CBD5E1` / `#94A3B8` / `#64748B` / `#475569`
/ ghost `#3D4F60` · amber `#FFAA00` · green `#00FF88` · red `#FF3355` · purple `#7C3AED`.
tv-web already uses the same bg and cyan. The look differs in structure, not colour.

| # | Screen | Android (TV) | tv-web today | Gap |
|---|---|---|---|---|
| A | **App shell** | Floating **icon rail** 120 px wide (`view_home_sidebar`): brand tile, then icons Search · Home · Live TV · Movies · Series, divider, Settings at the bottom. A focused icon gets a capsule (blue→cyan glass, cyan rim), a 5 px cyan bar, and a **flyout label** beside the rail. Rail bg is a vertical gradient `#121824→#090D14` with radius 30 and a hairline rim. Movies and Series hide it and show their own sidebar (B/D). | 300 px text sidebar, "DX Play" wordmark | Rebuild as the icon rail with flyout; hide it on Movies/Series |
| B | **Home** | **Hero** band 720 px: backdrop, "#1 TRENDING TODAY", title 76 px, meta (rating badge `8.9 ★` cyan, year · genre, quality badge amber), description, buttons **Play Now** (gradient), **More Info** (glass), **+** (favourite), 3 rotation dots. Rows: **Continue Watching** (landscape 288×162 cards, progress line, `15:30 / 45:00`), **Trending Movies** and **Trending Series** (Top-10: 256×384 posters with big **rank numbers**, title + sub), **Recent Live Channels** (256×144 glass cards, LIVE badge, logo, name). Row titles 22 px plus a count. Status bar top-right: clock · `KEY <code>` · IPTV **ACTIVE**. Hint bar: `▲▼◀▶ NAVIGATE │ OK SELECT │ BACK EXIT`. IPTV-only data: Top-10 movies = provider VOD by `added` desc; series = provider's first 10; hero = first of those (`HomeViewModel.buildPhase1State`). | Four text tiles + two rows | Hero, Top-10 rows, card shapes, status bar, hint bar |
| C | **Live TV** | Header: `●` Live TV 30 px · search pill · **horizontal category chips** · clock. List column 520 px: category name (caps) + count pill, channel rows. **Preview panel**: framed video (radius 20, cyan rim) with LIVE badge and quality badge; lower third: NOW PLAYING · programme title 28 px · start–end · progress. Actions row: **NEXT UP** bar (purple label, time, next title), **Watch** (gradient), ★ button. **Program Guide** strip under it (upcoming programmes). | Categories as a vertical column; preview with name and now/next; Full screen / Favourite buttons | Chips header, preview lower third, actions row, guide strip |
| C2 | **Live fullscreen OSD** | Channel bug (logo 68 px, number + name, LIVE + quality), clock top-right; zap card (number 52 px cyan, name, now); bottom: NOW PLAYING, title 46 px, start ▬ end, `N min left`, an **ON AIR NOW** card; buttons **Channels · TV Guide · CC · Audio**. | Bottom band with number, name, now/next | Channel bug, zap card, min-left, button row |
| D | **Movies / Series list** | Own **sidebar 420 px**: DX badge + "IPTV / MOVIES", search bar ("Search movies…"), CATEGORIES title, list in the order **All Movies · Recently Added · Favorites · provider…** (cyan bar on the active one). Main: category title 48 px + `N titles` + **sort chips** RECENTLY ADDED · TOP RATED · A – Z · NEWEST. Grid of **256×384** posters (5 columns), title under the poster 28 px, badges: quality (HD/4K), ✓ watched, ♥ favourite, progress line. | Rows column + 6-column grid; order Favourites · Recently added · All; no sort; no badges | Sidebar, order, sort chips, card badges, sizes |
| E | **Movie detail** (`fragment_movie_detail_v2`) | Full backdrop at α .45; breadcrumb "IPTV Movies"; column 960 px: FEATURE FILM (cyan), title 60 px, meta row (rating badge, year · duration · genre, age badge), **resume bar** (elapsed / remaining + progress), plot, buttons **Watch Now** (gradient) · **Trailer** · ♡ · **Mark Watched**, ✓ WATCHED badge, DIRECTOR + CAST (**photo chips**), **SIMILAR MOVIES** row. TMDB fills plot, rating, cast photos, recommendations. | Poster + text column; Resume / Start over / Favourite | Layout, resume bar, Trailer, Mark Watched, cast photos, Similar |
| F | **Series detail** (`fragment_series_detail_v2`) | TV SERIES label, title 66 px, meta (rating, year · `N Seasons · M Episodes` · genre), plot, **Play S01 · E01** / continue, Trailer, ♡, CREATOR + CAST; **EPISODES** + **SEASON 1 ▾** selector + season progress; **horizontal episode cards** 520×292 (thumbnail, `E1` badge, title, duration, play icon, ✓). Hint bar. | Season chips + vertical episode list | Episode cards, season selector, counts, trailer, credits |
| G | **VOD player** (`custom_player_control_view`) | Top: ‹ Back pill · title + episode pill · subtitle · quality pill · clock. Bottom: poster card 116×166 · NOW PLAYING + remaining · seek bar · position / duration · control card with **focusable buttons** −10s · PREV EP · ▶❚❚ (96 px circle) · NEXT EP · +10s · EPISODES · AUDIO · SUBS · aspect. | Title, bar, times, help text; hidden key shortcuts | Real button row, top bar, poster card, episodes panel |
| H | **Search** (`layout-television/fragment_search`) | "Search" 34 px + query bar with caret and count; left 452 px: **scope chips** All · Movies · Series · Live, an **on-screen A–Z 0–9 key grid**, actions Space · Delete · Clear; right: Trending Searches chips, then results grid with an accent header; a no-results state. (Netflix and YouTube TV use the same on-screen grid. The Samsung IME stays reachable.) | Native IME box + three rows | Key grid, scopes, actions, results grid |
| I | **Settings** (`fragment_settings_v2`) | Two panes: rail 660 px (⚙ Settings, version, categories **Playback · Live TV · Home · Data · About · Account**), and a panel (dot + title + description + items: toggles, selections, actions, info). | Single info list + buttons | Two-pane categories with the items tv-web supports |

## Status (2026-10-03) — every row implemented

| # | Commit | Note |
|---|---|---|
| A, B | `bd6b168` parity 1/n | Icon rail with flyout; Home hero, rows, status bar |
| D | `e97931e` parity 2/n | Movies / Series sidebar, sort chips, badges |
| E, F | `58561e5` parity 3/n | Movie and series detail pages (V2) |
| G | `6c43433` parity 4/n | Player controls; one uniform stage scale (N5) |
| C, C2 | `1a1fc7b` parity 5/n | Live: chips, channel list, preview, guide, fullscreen OSD |
| H | `067f67e` parity 6/n | Search key grid, scopes, trending / recent, typed results |
| I | `3d0993e` parity 7/n | Settings two panes; Resume Last Channel |

Deviations, said out loud:
- **Search result columns.** Android computes 6 columns of 95 dp but draws 128 dp posters into them;
  tv-web draws 5 columns that fit beside the rail. A live result opens Live TV on that channel
  (Android opens the player directly; tv-web's live player lives on the Live screen).
- **Settings rail.** Android selects a category on OK; tv-web opens it on focus (250 ms debounce),
  the 10-foot rule in CLAUDE.md, and OK still works.
- **Settings items left out (no tv-web counterpart):** Home Screen (app language, layout, row
  pickers), Addons, preferred audio languages and the smart-audio toggle (Playback shows what this TV
  decodes instead), TV-guide zoom / density / colours / auto-update, diagnostics, clear cached data,
  check for update, Your servers, Sign out (the account comes from the phone link).

**Not copied, said out loud (rule 1):** debrid / Stremio / addons items, IMDb and Rotten Tomatoes
badges (OMDb key, debrid tier), TV-guide zoom / density / genre colours (no full EPG grid yet, §12).
A TMDB trailer plays through YouTube's embed. Whether a Samsung TV allows it is checked on the TV,
and reported if it does not.
