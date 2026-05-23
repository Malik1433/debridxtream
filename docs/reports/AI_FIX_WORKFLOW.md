# AI Fix Workflow

Use this workflow for low-credit, low-regression bug fixing. Do not inspect the whole project unless the user explicitly asks for a broad audit.

## A) Simple Bug Mode
- Read only `docs/reports/REPORTS_INDEX.md`, `docs/reports/DO_NOT_REPEAT.md`, and the relevant canonical module report.
- Fix only the named issue.
- Touch maximum 1-3 files unless the root cause clearly requires more.
- No broad audit, redesign, refactor, duplicate files, or opportunistic improvements.
- Update only the relevant report/history when the task changes behavior.

## B) Risky Bug Mode
- Diagnose first; do not change code.
- Find root cause and exact files/lines.
- Stop after diagnosis unless the user asked for implementation in the same turn.
- Apply a fix only after the root cause and affected runtime path are clear.

## C) Regression Mode
- This mode applies when the user says the feature worked before a recent change.
- Read `docs/reports/RECENT_CHANGES.md` first.
- Inspect recently changed files before scanning old code.
- Restore previous working behavior while keeping the new valid fix intact.
- Do not rewrite the whole module.

## D) Stop Rule
If root cause is not found after checking the listed files, stop and report:
- files checked
- what was ruled out
- most likely next 2 files to inspect

Do not continue scanning the whole project.

## Credit-Saving Rules
- Read the minimum useful files only.
- Never read all reports by default.
- Do not open raw logs, screenshots, XML dumps, sprint artifacts, or deep audits unless the current issue needs exact evidence.
- Do not inspect the whole project unless explicitly requested.
- Do not make unrelated cleanup.
- Do not change working systems while fixing one bug.
- For shared Player/Debrid/Live changes, always run regression checks.

## Regression Safety Rules
- Before changing Player, check what must not break: Live TV zapping, IPTV playback, Debrid playback, Episode Browser, D-pad/back behavior.
- Before changing Debrid, check source picker, resolver path, direct HTTP/addon path, and Real-Debrid config path.
- Before changing Series, check player launch extras and episode identity.
- Before changing Live, check zapping and fullscreen return contract.

## Required First Reads By Mode
- Simple module bug: `REPORTS_INDEX.md`, `DO_NOT_REPEAT.md`, one module report.
- Regression: `RECENT_CHANGES.md`, one module report, then the recently changed files named there.
- Shared Player/Debrid/Live bug: add relevant failed-attempt history before editing.
- Login/Search/Settings bug: read the placeholder module report and the specific linked historical report only if needed.
