# ⚠️ ARCHIVE — historical only. Do NOT follow these as instructions.

Everything in this folder is **superseded**. It is kept only so the project's history is readable.
It was moved out of the repo root on **2026-07-26** because these files were actively misleading:
a fresh session (human or agent) reading the root would have followed plans that were finished,
statuses that were a year out of date, and a design system the app no longer uses.

**The live sources of truth are:**

| What | Where |
|---|---|
| Engineering rules (structure, runtime quality, detekt discipline, device QA) | `CLAUDE.md` (repo root) |
| Agent-harness conventions | `AGENTS.md` (repo root) — defers to `CLAUDE.md` |
| What we are doing next, and how "done" is measured | `docs/reports/WORLD_CLASS_ROADMAP.md` |
| Current per-area plans | `docs/reports/*_PLAN.md` (check the Progress section for what actually shipped) |

## Why each file was archived

- **`NEXT_SESSION_START_HERE.md`** — says "Week 9 ready / 50% (8 of 16 weeks)". That programme ended
  long ago; the app is far past it. This was the single most misleading file in the repo.
- **`IMPLEMENTATION_ROADMAP.md`** — the original "MVP → world-class in 16 weeks" plan. Superseded by
  `docs/reports/WORLD_CLASS_ROADMAP.md`, which is measured against the real codebase.
- **`PLAN.md`** — Live TV guide "All" fix + unified search. **Done**: the guide has the unified search
  chip (`LiveTvGuideFragment`, `TAG_SEARCH_CHIP`).
- **`DESIGN.md`** — a "Luxury Gold / Plus Jakarta Sans" design system. The app is **not** that: it uses
  the Stremio-style dark + cyan palette (`stremio_cyan`, `stremio_bg`) with Inter/Outfit. Following this
  file would have repainted the app wrongly.
- **`tttt-AGENTS.md`** — an 11-line verification rule already covered (better) by `CLAUDE.md`.
- **`GEMINI.md`, `qa.md`, `prompt.md`** — one-off context/report/prompt files from early 2026.
- **`*_COMPLETE.md`, `*_SUCCESS.md`, `*_FIX.md`, `QUICK_START*`, `DEVICE_TESTING*`, `BACKUP*`,
  `GITHUB_BACKUP*`, `BEST_PRACTICES_ANALYSIS.md`, `EXOPLAYER_MEDIA3_MIGRATION_NOTES.md`,
  `URDU_QUICK_GUIDE.md`, `DEVICE_QA_REPORT.md`** — point-in-time snapshots/guides from early 2026.
  Several quote device IPs (`192.168.0.84`, `192.168.0.21`) that **do not exist**; the real Fire TVs are
  `192.168.178.35` and `192.168.178.64`.

Nothing here was deleted — `git log --follow -- <file>` still shows the full history.
