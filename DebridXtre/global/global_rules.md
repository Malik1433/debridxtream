GLOBAL RULES (Cursor 2.0 / Claude Code multi-agent)

1. Project name: DebridXtreamIPTV
2. Package name: com.tvonnet.debridxtreamiptv
3. Target: Android TV / Amazon Fire TV (Leanback)
4. Language: Kotlin only.
5. Architecture: Single-activity, multi-fragment.
6. Separate layers:
   - ui/... for TV screens (fragments/presenters/adapters)
   - data/... for Retrofit, repositories, models
   - player/... for ExoPlayer
7. Never hardcode real Xtream credentials inside source code. Read from config/assets/local properties.
8. Every step MUST leave the project in a buildable state.
9. Prefer real APIs over mock data.
10. Show only changed / newly created files at each step.

ADDITIONAL FIX RULES (2025-10-31)

11. Fetch EPG immediately after Live/VOD/Series and store it in the same cache file.
12. Xtream repository must be **defensive**: missing, empty, or invalid endpoints must NOT crash the app.
13. All TV XML layouts must define explicit D-pad focus (nextFocusUp/Down/Left/Right) with default focus on the left menu.
14. Implementation order is **TV-first**:
    project -> deps -> login -> home shell -> live list -> player -> vod/series -> cache -> settings.
15. After every implement step, run a gradle validation (assembleDebug or closest) and report missing imports / wrong package names.
16. Agents must use fixed package prefixes:
    - ui.* for TV fragments and presenters
    - data.* for Retrofit/repository
    - player.* for ExoPlayer
17. If EPG fetch fails, categories MUST still load from cache (graceful degrade).
18. Images/posters/logos must use **Glide** with a TV-safe placeholder if the URL is missing or broken.
19. UI components must never crash when image urls, logos, or backdrops are null in the Xtream response.
20. Plan for multi-playlist / multi-account in **phase-2**. Current UI must stay single-account but not hardcoded.
