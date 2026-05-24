# Swarm Agent Workflow

Status: canonical/current
Scope: low-credit swarm routing for Android TV IPTV/Debrid work.

Use this workflow to keep cheap agents on search, reports, QA, and docs, while reserving senior/expensive review for risky Player/Debrid decisions. This file does not authorize runtime code changes by itself; apply it together with `AI_FIX_WORKFLOW.md`, `REPORTS_INDEX.md`, `DO_NOT_REPEAT.md`, `RECENT_CHANGES.md`, and the relevant module report.

## Core Cost Rule
- Start with the cheapest agent that can answer the next question.
- Never start with a senior/expensive agent unless the task is explicitly risky, a regression, or touches shared Player/Debrid architecture.
- Never let multiple agents edit the same files.
- Never read all reports by default.
- Never scan the whole project unless the user explicitly approves it.
- Stop if the root cause is not found in the listed files; report checked files, ruled-out paths, and the next 1-2 files to inspect.
- Use `docs/reports/RECENT_CHANGES.md` first for regression tracing.
- Keep normal bug fixes to 1-3 touched files.

## Roles

### 1. Scout Agent - cheap
Purpose:
- Grep/search only.
- Find relevant files, functions, and line ranges.
- Read the minimum reports selected for the task.
- No code edits.
- No broad audit.

### 2. Report Agent - cheap
Purpose:
- Read `docs/reports/REPORTS_INDEX.md`.
- Choose the minimum reports needed for the task.
- Summarize relevant guardrails and failed approaches.
- No code edits.

### 3. QA Agent - cheap/medium
Purpose:
- Run the build/install/test checklist chosen for the task.
- Report exact pass/fail commands, devices, and observed behavior.
- No code edits unless a direct compile error fix is explicitly allowed.

### 4. Docs Agent - cheap
Purpose:
- Update `docs/reports/RECENT_CHANGES.md` and the relevant module report after a fix.
- Keep reports compact and delta-based.
- No runtime code edits.

### 5. Fix Agent - medium
Purpose:
- Apply a minimal 1-3 file fix after the root cause is known.
- No broad refactor.
- No redesign.
- No duplicate files, components, layouts, routes, adapters, controllers, or reports.

### 6. Senior Player/Debrid Agent - expensive
Use only when:
- `PlayerActivity` or the shared playback spine is touched.
- Debrid resolver, source identity, direct playback identity, or durable resume identity is touched.
- Multiple previous fixes failed.
- The bug is a regression or multi-module.
- The fix may affect IPTV, Debrid, Live, Series, or Continue Watching together.

The Senior Agent reviews diagnosis and proposed fix boundaries first. It should not be the default implementation agent.

## Workflow Modes

### A) Simple Bug
1. Report Agent selects the minimum reports from `REPORTS_INDEX.md`.
2. Scout Agent finds exact files/functions and confirms the likely root cause.
3. Fix Agent edits a maximum of 1-3 files.
4. QA Agent verifies with the targeted build/install/test checklist.
5. Docs Agent updates the relevant reports.

### B) Risky Bug
1. Scout Agent diagnoses only.
2. Senior Player/Debrid Agent reviews the diagnosis only.
3. Fix Agent applies the minimal approved fix.
4. QA Agent verifies regression tests for every affected runtime path.
5. Docs Agent updates the relevant reports.

### C) Regression
1. Read `docs/reports/RECENT_CHANGES.md` first.
2. Inspect recently changed files before old code.
3. Restore working behavior with the smallest change that preserves valid newer fixes.
4. Require Senior Player/Debrid Agent only if the Player/Debrid shared path is involved.
5. QA Agent verifies the reported regression and adjacent paths.

### D) Continue Watching / Next-Day Bugs
1. Diagnose-only first.
2. Do not claim fixed from same-day testing only.
3. Include next-day simulation or an expired/null `expiresAt` test.
4. Verify saved identity, resolver refresh, and dedupe key.
5. Do not store temporary Debrid direct URLs as durable resume identity.
6. Require Senior Debrid/Storage review before fixing when Debrid resume identity or resolver refresh is involved.

## Decision Table

| Task | Agents | Senior needed? |
|---|---|---|
| Live focus bug | Scout + Fix + QA | No, unless `PlayerActivity` changes. |
| Live zapping race | Scout + Senior Player review + Fix + QA | Yes, player switching is involved. |
| Debrid source/resolver bug | Scout + Senior Debrid review + Fix + QA | Yes. |
| Continue Watching Debrid next-day bug | Scout diagnose-only + Senior Debrid/Storage review before Fix + QA | Yes. |
| Report update only | Docs Agent | No. |
| Build/install only | QA Agent | No. |

## Verification Guardrails
- Build success is not a user-facing PASS by itself.
- Shared Player changes require regression checks for Live TV, IPTV VOD, IPTV Series, Debrid movie, Debrid series, Episode Browser, D-pad/back, and sensitive URL/token/hash redaction.
- Debrid changes require source picker, resolver-backed playback, direct addon/direct HTTP playback, Real-Debrid auth/config, resume, and failure handling checks where relevant.
- Continue Watching fixes must verify stable saved identity and fresh resolver behavior, not replay of expired direct URLs.

