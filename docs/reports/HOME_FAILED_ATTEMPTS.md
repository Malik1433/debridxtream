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
