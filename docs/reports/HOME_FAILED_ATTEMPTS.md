# Home Failed Attempts

## Purpose
Canonical failed-attempts file for Home module tasks.

## Known Guardrails
- Do not repeat Home row/focus regressions already documented in `docs/reports/DO_NOT_REPEAT.md`.
- Do not change Home focus behavior without documenting the exact before/after behavior and device QA evidence.

## Entries
- `TASK 000-CODEX-SETUP-AUDIT`: No Home app failure was investigated. File created only to complete report/history integration.
- `TASK 031-CONTINUE-WATCHING-QUICK-ACTIONS`: Avoid the old click-only adapter path for TV remotes; long-press must be handled at the D-pad level and clear-status must suppress the next watch-history write so the item does not reappear immediately.
- `TASK 031A-CONTINUE-WATCHING-MENU-POLISH`: No functional failure observed; guardrail is to avoid replacing existing long-press detection or short-press resume wiring while polishing UI.
- `TASK 032-TRENDING-RANK-NUMBERING-FIX`: Avoid omitting adapter position checks in `areContentsTheSame` when using `setHasStableIds(true)`; otherwise, RecyclerView skips re-binding shifted cards, keeping visual overlays (like rank numbers) stuck at outdated values on list updates.
- `TASK 033-HOME-REFACTORING-AND-AUDIT`: Avoid introducing imports from unrelated packages (such as importing `HomeUiState` from `com.tvonnet.debridxtreamiptv.data.model` when it resides in `com.tvonnet.debridxtreamiptv.ui.home`). Ensure package scopes are strictly checked to prevent compilation failures. Do not keep strong references to the fragment or views in managers beyond the lifecycle of the view; clear them in `onDestroyView()` / `cleanup()` to prevent memory leaks.

