# Debrid Failed Attempts
Status: compacted
Scope: Debrid failure patterns and guardrails.

# Direct Proxy Uncertain/Terminal Collapse
- **Date**: 2026-06-03
- **Avoid**: Collapsing direct-proxy readiness into a Boolean or treating `UNCERTAIN` the same as `TERMINAL`.
- **Reason**: Some playable addon links cannot prove readiness through HEAD/range preflight, while terminal redirects can expose API/not-cached/provider-error targets. Boolean handling either blocks too much or launches dead links.
- **Fix**: Preserve typed readiness. Block only `TERMINAL`, keep `UNCERTAIN` as explicit-click only, and skip session-failed sources for auto-next/retry.

Done:
- Terminal failures, rate limits, and provider-blocked sources are documented.
- Sensitive logging and cache-state inference are covered by current rules.

Open:
- Add only new failure classes here.

Proof:
- Recent hardening and device QA already passed.

Next:
- Keep detailed retries and investigations in task-specific docs.
# Debrid Playback Stop-After-Some-Time Guardrail
- **Date**: 2026-06-03
- **Avoid**: Fixing long Debrid stops by replaying the same direct URL on every recoverable failure or by sending direct addon URLs through app-side Real-Debrid auth.
- **Reason**: Direct addon/provider URLs can expire or stall mid-session; replaying the same URL repeats the failure, while app-side resolver routing breaks valid direct provider links.
- **Fix**: Use source-profile refresh for direct Debrid recovery, keep resolver-backed Debrid on hash/magnet re-resolution, and require multi-strike stall evidence before terminal escalation.
