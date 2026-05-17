# Debrid Failed Attempts

## Purpose
Canonical failed-attempts file for Debrid module tasks.

## Entries
- `TASK 000-DEBRID-DEEP-AUDIT-STREMIO-COMPARISON`: Audit-only task. No failed implementation attempt occurred. Risks documented: sensitive Debrid/token URL logging, cache-state inference instead of verification, Real-Debrid-only implementation despite multi-provider language, and incomplete Stremio add-on parity.
- `TASK 001-DEBRID-PHASE1-SECURITY-LOG-REDACTION`: Initial Gradle validation was slow because `:app:compileDebugKotlin --offline` timed out twice and `--dry-run` also timed out before useful output. After stopping stale daemons and allowing the full build to complete, `:app:assembleDebug --offline` passed. No failed implementation remains open.
- `TASK 001B-DEBRID-SWARM-REVIEW-FOLLOW-UP`: Swarm review identified incomplete first-pass log coverage and missing persisted QA evidence. Follow-up fixes were implemented and rebuilt successfully; no failed implementation remains open. Full manual Real-Debrid playback QA remains a separate unclaimed scope.
- `TASK 002-DEBRID-PHASE2-RD-CACHE-VERIFICATION`: No failed implementation remains open. Functional PASS is not claimed because source badge/filter behavior and Real-Debrid playback/resume still require manual QA with an authenticated Real-Debrid account.
- `TASK 003-DEBRID-PHASE3-PLAYBACK-ERROR-AUDIT`: Manual QA found provider-side copyright/legal failures, `UNKNOWN` source unavailability, and HTTP `429` add-magnet rate limiting. These are not solved by Phase 2 cache labels. Follow-up implementation is needed for typed failure classification, cooldown/no-retry behavior, and clearer user messages.
