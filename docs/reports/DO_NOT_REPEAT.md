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

## Continue Watching Series Metadata Rule
Do not launch IPTV Series episodes from Continue Watching without `seriesId`, season number, episode number, and episode id. Player episode browser and next episode logic require the same metadata as the Series detail path.
