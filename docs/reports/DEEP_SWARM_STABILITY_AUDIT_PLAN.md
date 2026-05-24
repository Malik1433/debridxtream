# Stability Checklist Plan

## Summary
Small phases, minimal line edits, one validation gate per phase, and a restore point after every pass.
If a later phase fails, roll back to the last known-good state, re-test that state, then re-apply the failed phase with best practices.

## Phase Tracker
| Phase | Scope | Files | Edit Style | Validation | Device QA | Restore Point | Status |
|---|---|---|---|---|---|---|---|
| 0 | Whole-app blockers | Crash handler, companion config, EPG parser | minimal hunks only | one build/test + crash scan | `192.168.0.84:5555` | yes | DONE |
| 1 | Shared runtime spine | Player activity, player viewmodel, repository | minimal hunks only | one build/test + relevant tests | `192.168.0.84:5555` | yes | DONE |
| 2 | High-churn surfaces | Home, Live, Series, VOD | minimal hunks only | one build/test + relevant tests | `192.168.0.84:5555` | yes | DONE |
| 3 | Cleanup-only files | Dead/duplicate code (Paging3 buffer expanded instead) | delete only if safe | final verify build/test | `192.168.0.84:5555` | yes | DONE |
| 4 | Release hardening | Build config | last, after runtime stability | final build validation | `192.168.0.84:5555` | yes | DONE |

## Phase 0 Checklist
| Step | Action |
|---|---|
| 1 | Confirm the active runtime path for `GlobalCrashHandler.kt`, `CompanionConfigServer.kt`, and `EpgParser.kt`. |
| 2 | Scan direct callers/callees before editing. |
| 3 | Capture the current git state as the baseline restore point. |
| 4 | Apply only minimal hunks, in this order: crash handler, EPG parser, companion config. |
| 5 | Run one targeted compile, one relevant test pass if coverage exists, one install/smoke on `192.168.0.84:5555`, and one crash/log scan. |
| 6 | If pass, mark Phase 0 as known-good; if fail, rollback before touching Phase 1. |

## Rules
- Whole-file replacement nahi hoga.
- Sirf specific lines ya small hunks edit honge.
- Har phase ka scope fixed rahega.
- Unrelated refactor, UI polish, ya cleanup phase ke beech me nahi hoga.
- Gradle/build command sirf phase complete hone ke baad chalaya jayega.
- Agar validation fail ho to next phase start nahi hoga.

## Restore Points
- Phase start se pehle baseline known-good state note hogi.
- Phase pass hone ke baad restore point save hoga.
- Next phase fail ho to last restore point par rollback hoga.
- Rollback ke baad re-test hoga ke state waise hi stable hai.

## Pass Criteria
- No compile break
- No runtime regression in touched flow
- No stale references
- No unsafe logging or hidden side effects
- Device QA stable on `192.168.0.84:5555`

## Fail Criteria
- Build error
- Crash
- Focus/state regression
- Playback/loading regression
- Unexpected caller breakage
- Any change outside intended scope

## Assumptions
- `192.168.0.84:5555` available rahega.
- Stability speed se zyada important hai.
- Har phase ka test pass hona mandatory hai.
- Har phase ke baad deep re-audit repeat hoga.
/