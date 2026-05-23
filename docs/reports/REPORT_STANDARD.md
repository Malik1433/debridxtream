# Report Standard

Use this format for canonical module reports and compact task reports. Keep logs, screenshots, XML dumps, and long narratives out of report files; link or summarize exact evidence only when needed.

## Rules
- One canonical module report per module.
- Prefer short current-state bullets over historical narrative.
- Put old detail in history/task reports, not in canonical reports.
- Keep reports useful for low-credit AI bug fixing.
- Update only the relevant report after a task.

## Canonical Module Report Template
```md
# <Module> Module Report
Status: canonical/current | canonical/partial | archived
Scope: <one short line>

## Current Active Runtime Flow
- <active flow only>

## Important Active Files
- `<path>` - <purpose>

## What Must Not Break
- <guardrail>

## Known Bugs / Open Issues
- <open item or "None documented">

## Recent Fixes
- <date or short fix>

## Failed Approaches / Avoid
- <avoid item>

## QA Checklist
- <build/test/device/manual checks>

## Last Verified State
- <latest verified state or known gap>
```

## Compact Task Report Template
```md
# <Task>
Status: verified | open | blocked | archived
Scope: <one short line>
Done:
- <item>
Open:
- <item>
Risk:
- <short line>
Proof:
- <build/test/device result>
Next:
- <next step>
```
