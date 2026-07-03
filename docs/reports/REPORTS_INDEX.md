# Reports Index

Main map for report/history/context files. Use this to choose the smallest useful read set. Never read all reports by default.

## Canonical / Current Reports

| File | Area | Purpose | Read when |
|---|---|---|---|
| `docs/reports/REPORTS_INDEX.md` | AI workflow | Main map of report/history files. | First for any bug-fix prompt. |
| `docs/reports/AI_FIX_WORKFLOW.md` | AI workflow | Standard simple/risky/regression workflow. | Before bug fixing, especially regressions. |
| `docs/reports/RECENT_CHANGES.md` | Regression | Last 10 important changes. | First in regression mode. |
| `docs/reports/REPORT_STANDARD.md` | Reports | Required compact report format. | Before updating reports. |
| `docs/reports/APP_SUCCESS_PATTERNS.md` | App-wide | Proven patterns. | Before runtime behavior changes. |
| `docs/reports/APP_FAILED_PATTERNS.md` | App-wide | Known bad patterns. | Before bug fixes in shared areas. |
| `docs/reports/DO_NOT_REPEAT.md` | App-wide | Hard guardrails. | Before app/runtime changes. |
| `docs/reports/PLAYER_MODULE_REPORT.md` | Player | Canonical shared Player flow and guardrails. | Player, playback, controller, zapping, Episode Browser bugs. |
| `docs/reports/DEBRID_MODULE_REPORT.md` | Debrid | Canonical Debrid/source/resolver summary. | Debrid source picker, resolver, addon, resume bugs. |
| `docs/reports/DEBRID_SOURCE_PICKER_AUDIT.md` | Debrid/source picker | Current source picker entry-point, source type, reliability gap, and safe fix order audit. | Before changing Debrid source picker reliability, source identity, cache/direct labels, or movie/series source launch behavior. |
| `docs/reports/SERIES_MODULE_REPORT.md` | Series | Canonical Series/detail/episode identity summary. | IPTV/Debrid Series and Episode Browser bugs. |
| `docs/reports/LIVE_MODULE_REPORT.md` | Live TV | Canonical Live list/preview/fullscreen/zapping summary. | Live loading, focus, preview, zapping, fullscreen return bugs. |
| `docs/reports/VOD_MODULE_REPORT.md` | VOD/Movies | Canonical IPTV movie/VOD summary. | VOD list/detail/playback bugs. |
| `docs/reports/DEBRID_UI_UX_AUDIT.md` | Debrid UI/UX | Comprehensive Debrid section UI/UX audit report. | Before redesigning or styling Debrid UI elements. |
| `docs/reports/DEBRID_SIDEBAR_AUDIT.md` | Debrid UI/UX | Audit of Debrid sidebar focus and routing. | Before fixing Debrid sidebar routing/visuals. |
| `docs/reports/HOME_MODULE_REPORT.md` | Home | Canonical Home focus/sidebar/rows summary. | Home focus, sidebar, rows, Continue Watching bugs. |
| `docs/reports/LOGIN_MODULE_REPORT.md` | Login | Canonical placeholder for login/session. | Login, session, InitialSync, companion login entry bugs. |
| `docs/reports/SEARCH_MODULE_REPORT.md` | Search | Canonical placeholder for search/voice. | Search or voice-search bugs. |
| `docs/reports/SETTINGS_MODULE_REPORT.md` | Settings | Canonical placeholder for settings/companion config. | Settings, companion setup, EPG sync, Stremio config bugs. |
| `docs/reports/SECURITY_AUDIT.md` | Security | Security controls and sensitive logging. | URL policy, companion security, secrets/logging. |
| `docs/reports/COMPANION_SECURITY_MANUAL_QA.md` | Companion | Companion sync/payload QA status. | Companion or Firestore/device-code payload work. |
| `docs/reports/GLOBAL_BUILD_REPORT.md` | Build/QA | Build verification status. | Build/install/verification issues. |
| `docs/reports/DEEP_SWARM_STABILITY_AUDIT_PLAN.md` | Stability | Shared-spine stability phase plan. | Broad stability or "god mode files" requests. |
| `docs/reports/XTREAM_REPOSITORY_DEEP_AUDIT_REPORT.md` | Data/Repository | Deep audit of XtreamRepository god-class risks, gaps, and IPTV architecture comparison. | XtreamRepository, cache, sync, provider session, EPG, or IPTV data-spine refactor work. |

## Histories / Secondary Reports

| File | Area | Status | Read when |
|---|---|---|---|
| `docs/reports/PLAYER_SUCCESS_HISTORY.md` | Player | Current history | Player fix may repeat a prior success. |
| `docs/reports/PLAYER_FAILED_ATTEMPTS.md` | Player | Current history | Before changing shared Player behavior. |
| `docs/reports/DEBRID_SUCCESS_HISTORY.md` | Debrid | Current history | Debrid fix may repeat a prior success. |
| `docs/reports/DEBRID_FAILED_ATTEMPTS.md` | Debrid | Current history | Before Debrid resolver/source changes. |
| `docs/reports/SERIES_SUCCESS_HISTORY.md` | Series | Current history | Series fix may repeat a prior success. |
| `docs/reports/SERIES_FAILED_ATTEMPTS.md` | Series | Current history | Before Series detail/player/episode changes. |
| `docs/reports/LIVE_SUCCESS_HISTORY.md` | Live TV | Current history | Live focus/loading/zapping fix may repeat a prior success. |
| `docs/reports/LIVE_FAILED_ATTEMPTS.md` | Live TV | Current history | Before Live paging/focus/loading changes. |
| `docs/reports/HOME_SUCCESS_HISTORY.md` | Home | Current history | Home focus/row fix may repeat a prior success. |
| `docs/reports/HOME_FAILED_ATTEMPTS.md` | Home | Current history | Before Home focus/sidebar/adapter changes. |
| `docs/reports/HOME_SCREEN_REPORT.md` | Home | Secondary | Canonical Home report is too brief. |
| `docs/reports/SERIES_MODULE_RETEST.md` | Series | Secondary | Series regression verification history needed. |
| `docs/reports/IPTV_SERIES_DEEP_AUDIT.md` | IPTV Series | Archived deep audit | Complex Series identity/cache issue. |
| `docs/reports/STREMIO_PLAYBACK_STABILITY_DEEP_AUDIT.md` | Debrid/Stremio | Partial deep audit | Direct addon/direct Debrid playback issue. |
| `docs/reports/STREMIO_ADDON_CLEAN_SLATE_PLAN.md` | Debrid/Stremio | Archived plan | Addon URL/manifest hygiene work. |
| `docs/reports/DEBRID_DEEP_AUDIT_STREMIO_COMPARISON.md` | Debrid/Stremio | Archived deep audit | Stremio parity/background architecture. |
| `docs/reports/DEBRID_PHASE1_QA_EVIDENCE.md` | Debrid QA | Archived evidence | Matching Debrid phase QA only. |
| `docs/reports/DEBRID_PHASE2_CACHE_QA_EVIDENCE.md` | Debrid QA | Archived evidence | Cache-status regression evidence. |
| `docs/reports/DEBRID_PHASE2_MANUAL_QA_RUNBOOK.md` | Debrid QA | Archived runbook | Manual Debrid QA. |
| `docs/reports/DEBRID_PHASE3_PLAYBACK_ERROR_AUDIT.md` | Debrid playback | Archived audit | Debrid playback failure/retry bug. |
| `docs/reports/QA_018E_VERIFICATION.md` | Debrid UI QA | Archived evidence | Source picker UI regression. |
| `docs/reports/DEEP_SWARM_AUDIT_REPORT.md` | App-wide | Archived audit | Broad audit prompt only. |
| `docs/reports/CODEX_AGENT_SETUP_REPORT.md` | Tooling | Current non-runtime | Codex/Claude Flow/MCP/tooling work. |
| `docs/reports/AGENT_MODEL_ROUTING_POLICY.md` | Agent setup | Current non-runtime | Swarm/agent coordination work. |

## Phase Audit Reports (Read-Only Diagnose Series)

| File | Area | Status | Read when |
|------|------|--------|-----------|
| `docs/reports/AUDIT_PHASE1_PLAYER.md` | Player spine | 2026-06-12 read-only audit | Before fixing player buffering, stall, quality, or CW bugs. |
| `docs/reports/AUDIT_PHASE2_LIVE.md` | Live TV | 2026-06-12 read-only audit | Before fixing Live zapping, EPG, channel list, or overlay bugs. |
| `docs/reports/AUDIT_PHASE3_DEBRID.md` | Debrid chain | 2026-06-12 read-only audit | Before fixing Debrid resolver, source picker, RD auth, CW resume, or proxy detection bugs. |
| `docs/reports/AUDIT_PHASE4_SERIES_VOD_CW.md` | Series/VOD/Episode Browser/CW | 2026-06-12 read-only audit | Before fixing Series detail, VOD detail, Episode Browser watched badges, Continue Watching identity/enrichment, or Home CW row bugs. |
| `docs/reports/AUDIT_PHASE5_DPAD_UI.md` | D-pad focus / Home / Search / Settings / Login UI | 2026-06-12 read-only audit | Before fixing focus loss/jumps, BACK behavior, Home row jank, Search/Settings/Login UX, or sidebar animation bugs. |
| `docs/reports/AUDIT_PHASE6_APPWIDE.md` | App-wide: leaks / ANR / threading / lifecycle / startup | 2026-06-12 read-only audit | Before fixing OOM/ANR crashes, EPG sync/worker bugs, Glide/cache sizing, crash-handler behavior, or memory leaks. Closes all Phase 1–2 cross-phase pointers. |

## Task / Audit / Closure Files

| File or group | Area | Status | Read when |
|---|---|---|---|
| `docs/reports/TASK_011_HOME_SCREEN_DEEP_AUDIT.md` | Home | Archived | Old Home audit context. |
| `docs/reports/TASK_012_HOME_CRITICAL_STABILITY_FIXES_PASS_1.md` | Home | Archived | Matching Home stability history. |
| `docs/reports/TASK_013_HOME_FOCUS_RESTORATION_AND_SIDEBAR_BEHAVIOR.md` | Home | Archived | Historical Home focus restoration detail. |
| `docs/reports/TASK_013B_HOME_FOCUS_DEVICE_QA_VERIFICATION.md` | Home QA | Archived evidence | Home focus QA reproduction/evidence. |
| `docs/reports/TASK_013C_HOME_FOCUS_FIX_PASS_2.md` | Home | Archived | Historical Home focus pass 2 detail. |
| `docs/reports/TASK_014_HOME_ADAPTER_DATA_STABILITY.md` | Home | Archived | Home row data/stable-id bugs. |
| `docs/audit/01-login-page-deep-audit.md` | Login | Archived audit | Login/session/UI/focus/security bug. |
| `docs/audit/02-device-code-deep-audit.md` | Login/identity | Archived audit | Device ID, pairing, reinstall, logout identity bug. |
| `docs/audit/03-device-id-reinstall-persistence-options.md` | Login/identity | Archived audit | Device identity design question. |
| `docs/progress/010-login-module-closure-report.md` | Login | Closure | Login/session task needs more detail than placeholder. |
| `docs/progress/002-login-device-qa-pass.md` | Login QA | Archived evidence | Login device QA history. |
| `docs/progress/002b-login-device-qa-gap-closure.md` | Login QA | Archived evidence | URL validation, companion invalid credentials, InitialSync retry gaps. |
| `docs/progress/004-device-id-stabilization-pass-1.md` | Device identity | Archived evidence | Logout/device ID preservation. |
| `docs/progress/006-device-id-local-architecture-pass.md` | Device identity | Archived evidence | Local identity architecture. |
| `docs/progress/007-device-id-local-verification-pass.md` | Device identity | Archived evidence | Local identity verification. |
| `docs/progress/008-login-1080p-clipping-fix.md` | Login UI | Archived | Login layout clipping bug. |
| `docs/progress/009-login-ui-modernization-with-stitch.md` | Login UI | Archived | Login visual/UI history. |
| `docs/DEEP_SEARCH_REPORT.md` | Search | Archived area report | Search bug needs historical context. |
| `docs/DEBRID_DEEP_RESEARCH_REPORT.md` | Debrid research | Archived research | Provider/research background only. |
| `docs/android-tv-audit-2026-05-09.md` | App-wide audit | Archived audit | Broad technical debt/security/testing context. |
| `docs/player-activity-audit-report-2026-05-09.md` | Player audit | Archived audit | Current Player reports lack older context. |
| `docs/stabilization-phase-report-2026-05-09.md` | Stability | Archived | Historical stability work. |
| `docs/project_context.md`, `docs/architecture*.md`, `docs/source-tree-analysis.md` | General docs | Secondary/may drift | High-level orientation only. |
| `docs/sprint-artifacts/*.md` | Sprint stories | Archived | Only when user references a sprint story. |
| `docs/progress/*.txt`, `docs/progress/*.xml`, `docs/reports/*.png`, `docs/reports/*.xml` | QA artifacts | Raw evidence | Do not read by default; open only when exact evidence is needed. |

## Minimum Reports By Issue Type

| Issue type | Minimum reports to read |
|---|---|
| Simple module bug | `REPORTS_INDEX.md`, `DO_NOT_REPEAT.md`, relevant module report |
| Regression | `RECENT_CHANGES.md`, relevant module report, then recent changed files |
| Player bugs | `PLAYER_MODULE_REPORT.md`; add `PLAYER_FAILED_ATTEMPTS.md` if changing behavior |
| Episode Browser bugs | `PLAYER_MODULE_REPORT.md`, `SERIES_MODULE_REPORT.md`, `PLAYER_FAILED_ATTEMPTS.md` |
| Live TV zapping bugs | `LIVE_MODULE_REPORT.md`, `PLAYER_MODULE_REPORT.md`, `LIVE_FAILED_ATTEMPTS.md` |
| Live list/loading/focus bugs | `LIVE_MODULE_REPORT.md`, `LIVE_FAILED_ATTEMPTS.md` |
| IPTV Series bugs | `SERIES_MODULE_REPORT.md`, `SERIES_FAILED_ATTEMPTS.md` |
| IPTV VOD/Movie bugs | `VOD_MODULE_REPORT.md`; add `PLAYER_MODULE_REPORT.md` if playback/controller is involved |
| Debrid resolver/source bugs | `DEBRID_MODULE_REPORT.md`, `DEBRID_FAILED_ATTEMPTS.md` |
| Direct addon/Stremio bugs | `DEBRID_MODULE_REPORT.md`, `STREMIO_PLAYBACK_STABILITY_DEEP_AUDIT.md` |
| Login/session bugs | `LOGIN_MODULE_REPORT.md`; add `docs/progress/010-login-module-closure-report.md` if needed |
| Device code/pairing/identity bugs | `LOGIN_MODULE_REPORT.md`, `docs/audit/02-device-code-deep-audit.md` |
| Home focus/sidebar/row bugs | `HOME_MODULE_REPORT.md`, `HOME_FAILED_ATTEMPTS.md` |
| Search bugs | `SEARCH_MODULE_REPORT.md`; add `docs/DEEP_SEARCH_REPORT.md` if needed |
| Settings/companion bugs | `SETTINGS_MODULE_REPORT.md`, `COMPANION_SECURITY_MANUAL_QA.md`, `SECURITY_AUDIT.md` |
| Build/install issues | `GLOBAL_BUILD_REPORT.md`, relevant module report |
| Broad shared-spine stability | `DEEP_SWARM_STABILITY_AUDIT_PLAN.md`, relevant module report |

## Duplicate / Obsolete Warnings

- `docs/reports/REPORTS_INDEX.md` is canonical. `docs/REPORTS_INDEX.md` is only a pointer kept to avoid breaking older references.
- `HOME_MODULE_REPORT.md` is canonical. `HOME_SCREEN_REPORT.md` and `TASK_011` through `TASK_014` are historical detail/evidence.
- `PLAYER_MODULE_REPORT.md` is canonical. `docs/player-activity-audit-report-2026-05-09.md` is older audit context.
- `SERIES_MODULE_REPORT.md` is canonical. `SERIES_MODULE_RETEST.md` and `IPTV_SERIES_DEEP_AUDIT.md` are secondary/deep references.
- `DEBRID_MODULE_REPORT.md` is canonical but partial. Stremio/Debrid deep audits are edge-case references, not runtime truth.
- `LOGIN_MODULE_REPORT.md`, `SEARCH_MODULE_REPORT.md`, and `SETTINGS_MODULE_REPORT.md` are canonical placeholders; expand only during real module work.
- Raw logs, screenshots, XML dumps, sprint artifacts, and deep audits are not first-read files.

## Credit-Saving Rule

Read only the minimum reports listed above. Never read all reports by default. Do not scan the whole project unless explicitly requested or the listed files prove the issue crosses module boundaries.
