# Player Failed Attempts
Status: compacted
Scope: player regressions and guardrails.

Done:
- Duplicate player activities and global DPAD drift are documented as anti-patterns.

Open:
- Add only new confirmed failures.
- Avoid treating a single Debrid `READY`-state position freeze as terminal. For Debrid VOD, use multi-strike stall diagnostics and preserve source-profile refresh before same-URL retry.
- Avoid handling direct addon/proxy no-first-frame timeouts as generic retries. Return to source selection with the failed Debrid stream id so the detail screen can skip that source and preserve adjacent focus.

Proof:
- The current player path has already passed verification.

Next:
- Keep detailed investigations in task docs.
