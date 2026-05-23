# Stability Checklist Plan

Use the compact report standard in [REPORT_STANDARD.md](./REPORT_STANDARD.md).

## Phase Tracker
| Phase | Scope | Files | Change | Validation | Device | Restore | Status |
|---|---|---|---|---|---|---|---|
| 0 | Whole-app blockers | Crash handler, companion config, EPG parser | minimal hunks only | compile + targeted test + crash scan | `192.168.0.84:5555` | yes | partial: companion URL gate verified |
| 1 | Shared runtime spine | Player activity, player viewmodel, repository | minimal hunks only | compile + relevant tests | `192.168.0.84:5555` + `192.168.0.21:5555` | yes | partial: IPTV episode-id guard smoke + manual QA verified |
| 2 | High-churn surfaces | Home, Live, Series, VOD | minimal hunks only | compile + relevant tests | `192.168.0.84:5555` | yes | pending |
| 3 | Cleanup-only files | Dead/duplicate code | delete only if safe | final verify build/test | `192.168.0.84:5555` | yes | pending |
| 4 | Release hardening | Build config | last | final build validation | `192.168.0.84:5555` | yes | pending |

## Rules
- Whole-file replacement nahi hoga.
- Sirf specific lines ya small hunks edit honge.
- Har phase ka scope fixed rahega.
- Unrelated refactor, UI polish, ya cleanup phase ke beech me nahi hoga.
- Gradle/build command sirf phase complete hone ke baad chalaya jayega.
- Validation fail ho to next phase start nahi hoga.

## Restore Points
- Phase start se pehle baseline note karo.
- Phase pass ho to restore point save karo.
- Next phase fail ho to last restore point par rollback.
- Rollback ke baad re-test.

## Pass Criteria
- No compile break
- No runtime regression in touched flow
- No stale references
- No unsafe logging or hidden side effects
- Device QA stable on `192.168.0.84:5555`

## Phase 1 Notes
- 2026-05-23: Guarded IPTV Series playlist/browser load so `EXTRA_STREAM_URL` fallback is never treated as an episode id.
- Proof: `:app:compileDebugKotlin`, `:app:assembleDebug`, install/launch/PID/crash scan passed on `192.168.0.84:5555`.
- Proof: install/launch/PID, PlayerActivity display, and crash scan passed on `192.168.0.21:5555` after reconnect.
- Manual QA: IPTV Series and Debrid Series episode browser plus Next Episode behavior confirmed working by user.

## Fail Criteria
- Build error
- Crash
- Focus/state regression
- Playback/loading regression
- Unexpected caller breakage
- Any change outside intended scope
