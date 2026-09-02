# Ruflo — Claude Code Configuration

## Rules

- Do what has been asked; nothing more, nothing less
- NEVER create files unless absolutely necessary — prefer editing existing files
- NEVER create documentation files unless explicitly requested
- NEVER save working files or tests to root — use `/src`, `/tests`, `/docs`, `/config`, `/scripts`
- ALWAYS read a file before editing it
- NEVER commit secrets, credentials, or .env files
- Keep files under 500 lines — enforced by detekt, see "Code structure" below
- Validate input at system boundaries

## Code structure & decomposition

**Enforcement:** `./gradlew :app:detekt` (config `config/detekt/detekt.yml`). Everything oversized today
is baselined; it only fails on NEW violations. Thresholds: class 600, method 60, cyclomatic 15,
params 6/7.

**The ratchet gate runs automatically** so "regenerate the baseline and move on" cannot pass quietly:

- `./gradlew :app:check` runs the `debtRatchet` task (defined in the root `build.gradle`) — no shell
  needed, so it also works on CI.
- A **pre-commit hook** runs repo hygiene + the ratchet in under a second. Install once per clone:
  `./scripts/install_git_hooks.sh` (sets `core.hooksPath` to the versioned `scripts/githooks/`).
  Bypass a single commit deliberately with `git commit --no-verify` — the Gradle gate still catches it.

Both front-ends read the same ceilings from `config/detekt/debt-ledger.txt`; that file is the source
of truth, and its numbers may only ever be LOWERED.

**Baseline discipline.** Baseline IDs are keyed `Rule:File.kt$Class$signature`, so *relocating* an
already-baselined method into a new file legitimately re-fires as "new". That case is expected:
regenerate with `./gradlew :app:detektBaseline`, then **verify the total did not grow** —
`grep -c "<ID>" config/detekt/detekt-baseline.xml` must be **<=** the previous count (300 at setup) —
and commit the refreshed baseline together with the refactor. **Never regenerate to silence a genuinely
new violation**, and never "fix" a long method in the same commit that moves it — relocate verbatim
first, improve it in a separate, separately-verified commit.

**Line count is a proxy, not the goal — cohesion is.** Rules when breaking up a large file:

- **Split by responsibility, not by line ranges.** Each extracted unit needs one reason to change. Do
  not slice a file just to get under a number.
- **The new file must not itself be a god-class.** (Real example: a Series extraction produced a
  780-line `SeriesRepository` — that moved the problem instead of solving it. If a domain is too big for
  one collaborator, split it further.)
- **Behaviour-preserving by default:** move bodies verbatim, keep the public API stable so callers don't
  change, verify `compileDebugKotlin` + full `:app:testDebugUnitTest` after every step. Any real
  behaviour change is a separate, explicitly-approved commit.
- **One phase per commit** so a regression is bisectable. Push per phase.
- **UI / playback / Live changes require device QA on the Fire TV**, not just green tests — and must not
  regress the known playback landmines (see the player memory notes and
  `docs/reports/PLAYER_REFACTOR_PLAN.md`).
- **Respect the approval gates** in the plan docs (e.g. PlayerActivity phases are owner-approved one at a
  time; `PlayerNetworkStallManager` is explicitly BLOCKED). Ask before starting a gated phase.
- Prefer the pattern already used in that package (e.g. `player/stabilized/` uses `Player*Manager` /
  `*Controller` delegates; the data layer uses a thin facade over domain collaborators).

## Runtime quality (no linter catches these — they caused real incidents here)

Apply these while touching a file; don't leave them for a later pass.

- **Never do heavy work on the main thread.** Multi-MB Gson parse, disk/prefs I/O and network must be
  inside `withContext(Dispatchers.IO)`. A `suspend` modifier alone guarantees nothing. This is the root
  cause of the app's "loading/jank" complaints.
- **Bound every network call.** Wrap with `withTimeoutOrNull(...)`; an unbounded stage once parked the
  sync screen for 90-180s. A timeout must degrade to cached/empty, never hang.
- **Structured concurrency.** Never `GlobalScope` or a detached `CoroutineScope`; launch as a child of
  the caller/lifecycle. **Always rethrow `CancellationException`** — swallowing it breaks cooperative
  cancellation (detekt's `SwallowedException` covers part of this).
- **Never let a failed refresh destroy good data.** Empty/failed fetch must not overwrite a populated
  cache or table (see the sync all-empty guard and the EPG generational replace).
- **Room migrations are never destructive.** `fallbackToDestructiveMigration` in release silently wipes
  watch progress and favourites. Add a real migration + a migration test.
- **State shared across coroutines must be thread-safe** — `ConcurrentHashMap` / `@Volatile`, not plain
  `mutableMapOf` (that risked `ConcurrentModificationException` here).
- **Lifecycle hygiene in UI:** collect flows with `repeatOnLifecycle`, and unregister every listener /
  callback you register (an unremoved memory callback leaked LiveFragment).
- **This is an Android TV app:** every interactive element must be D-pad reachable and must not steal
  focus on data refresh. Verify focus behaviour on the device, not in an emulator screenshot.

## One device, one provider — the server-switch contract (2026-08-16)

A customer's account can hold several IPTV playlists, and re-addressing one on the portal MOVES a
device to another provider. Nothing in the data layer records which provider a row came from: the
catalogue, favourites, watch history and watched state are all keyed by `streamId`, and two
providers both number their streams from 1. So a switch does not make the old rows stale, it makes
them **wrong** — the old server's poster in front of the new server's stream id.

**The contract, in one line: when the provider changes, everything that provider gave us goes.**

- **The question is answered from state, never from an event.**
  `CredentialsPreferences.isServerDataStale()` compares the fingerprint of the credentials we point
  at (`ServerIdentity`) with the fingerprint the on-disk data belongs to. That survives a process
  death mid-switch, a missed callback, and a switch that lands while the app is backgrounded.
- **One purge, one place.** `ServerDataReset.purge(SERVER|ACCOUNT)` is the ONLY wipe. Adding a new
  per-provider store means adding it there — and to `ServerDataResetTest`, which fails if a table is
  left out. Never hand-roll a partial clear (the old logout did, and left movies, series, episodes,
  favourites, watched state and searches behind).
- **It runs BEFORE the new sync writes a row**, in `InitialSyncFragment` — the one screen every
  route into a new provider passes through. After the sync, and the purge eats the sync's own rows.
- **Never store an absolute stream URL as identity.** An Xtream URL embeds the host, username and
  password; kept across a switch it can keep *working* against the provider the customer left.
  `ProviderUrlGuard` is the read-side lock, but the real rule is: store ids, build the URL from the
  current session.
- **`source` is not decoration.** `favorites.source` and `watched_state.source` are what let a
  switch clear the IPTV rows and keep the debrid ones (debrid ids are infoHashes and mean the same
  thing everywhere). Any new row that can come from either world carries it.
- **The customer is told.** The first-sync screen says which provider they were moved to; an
  emptied library must never look like data loss.

## Two platforms, two rulebooks — never mix them (owner rule, 2026-08-05)

This is ONE app on two form factors, so every screen must obey **the conventions of the device it is
running on**. Phone work follows phone standards; TV work follows TV standards. Carrying one
platform's idiom onto the other is a defect even when it "works" — and it has already happened here
(the Live screen shipped a TV focus-then-activate model onto a touchscreen; Settings shipped 10-foot
chrome that ate 144dp of a 411dp phone screen before one setting was visible — **fixed in M14**, and
this line stays only as the example of the failure mode).

**How they stay apart:** `values/` holds the PHONE answer and `values-television/` the TV one — the
device question, which never matches a phone in either orientation. `layout-port/` and `values-port/`
are BANNED and deleted: the phone is landscape-locked, so an orientation qualifier answers a question
nobody is asking, and a stray file there silently overrides at runtime while being easy to miss
(exactly what a leftover `layout-port/view_home_sidebar.xml` did until 2026-08-12).

⭐ **The layout bools are deliberately NOT device-split.** `home_nav_is_horizontal`,
`browse_categories_are_horizontal`, `settings_categories_are_horizontal` live only in `values/`, so
BOTH form factors get the same answer: the landscape phone wears the TV's layout on purpose. Only
the VIEWING-DISTANCE numbers differ per device. Do not "fix" a bool by adding a `-television` copy
without re-deciding that.

Same view ids, same view KINDS, same default visibilities — so shared code binds
to either without knowing which it got. Only genuinely behavioural differences go through a resource
`bool` read in code.

**Phone (Material / Android handset conventions)**
- Touch targets **≥48dp**; body text ≥12sp. The 6-9sp "px÷2" trick is a 10-foot rule and is
  unreadable in the hand.
- **One tap acts.** No focus-first, no select-then-activate, no D-pad legends ("PRESS OK").
- Bottom navigation for top-level destinations; **Back goes up**, and every screen must be leavable.
- Vertical scrolling lists and grids — never fixed side-by-side columns.
- Pickers and long option lists are **bottom sheets**, not centre panels; tapping the scrim closes.
- Respect system insets (status bar, gesture nav). Nothing under the bars, nothing behind the pill.
- The **native soft keyboard**, never an on-screen D-pad keyboard.
- Long-press is a shortcut, never the only route to an action.
- Every list has a visible loading / empty / error state, and a failure says something.

**TV (10-foot / Android TV conventions)**
- Everything **D-pad reachable**, in a predictable ladder; nothing touch-only.
- Focus is always **visible**, and never stolen on a data refresh.
- Overscan-safe margins; type sized to read from ~3 metres.
- Landscape-locked; no gestures, no soft-keyboard dependence.
- BACK goes up the hierarchy and never traps the user.

**Before calling any UI batch done, state which rulebook it was checked against and on which device.**
A TV smoke does not certify the phone, and a phone QA does not certify the TV.

## World-class gaps this project has NOT closed yet (audited 2026-08-09)

The three rulebooks above cover code structure, runtime correctness and the two UI platforms. These
are the things a world-class app also needs that this codebase does **not** have today. Each line
carries the number measured on 2026-08-09 so nobody has to re-derive it — and so the number can be
watched going down.

- **Localisation: extraction ✅ DONE 2026-08-10 — translation folders still to come.** All 427
  literal `android:text`/`android:hint`/`android:contentDescription` strings were extracted
  (273 new entries in `values/strings_ui.xml`, 36 reused existing names; glyph-only separators
  stayed inline — they are not language). **Zero word-literals remain in layouts**, so adding a
  language is now purely a `values-<lang>/` folder. Known impurity: some extracted entries are
  layout placeholder samples that runtime overwrites (they belonged in `tools:text`) —
  translators can skip `ui_*` entries that look like sample data. Kotlin-side literal strings
  (Toasts, code-set labels) are a separate later phase. **Rule stays: new user-facing text goes
  in `strings.xml`, never inline.** The owner picks the languages (audience watches |AR| |HI|
  |TA| |DE| content); `supportsRtl` is declared and only 5 layout attributes use hard
  left/right, so RTL is close.
- **Accessibility: ✅ CLOSED 2026-08-10 — every `ImageView`/`ImageButton` now carries a
  `contentDescription` (190 labelled, 0 missing, 0 `tools:ignore`).** Actionable images read a
  name from `values/strings_a11y.xml` (player controls, play icons, favourite/watched
  indicators, QR codes); decorative/duplicating ones (posters, logos, backdrops, field icons —
  their name is in an adjacent TextView) carry an explicit `@null`. Verified in the live
  accessibility tree (uiautomator: "Play" ×7 + "Add to favorites" on the series detail) and in
  the binary (`aapt2 dump xmltree`). **Rule stays: every new image gets a `contentDescription`
  or an explicit `@null` — never nothing, never `tools:ignore`.** The 1.6x phone font scale
  MULTIPLIES the user's accessibility setting rather than replacing it — keep it that way.
- **Theme: the app is dark-only and there is no `values-night`.** That may well be right for a
  10-foot media app, but it is currently an accident rather than a decision. **Treat dark-only as
  the decision, and do not add a half-built light theme** — a partial one is worse than none.
- **Performance budget: ✅ SET AND MEASURABLE 2026-08-11 — the number on file is STALE.**
  Run `./scripts/perf_check.sh` (defaults to the Fire TV at `192.168.178.64:5555`); it exits
  non-zero on a FAIL so it can gate a release. **The budget: cold-start median of 3 runs ≤ 5000ms,
  zero ANRs, and jank reported for the record.** The only recorded baseline —
  **median 5207ms (5098 / 5207 / 6172), FAIL by 200ms**, 0 ANRs, 87% janky frames — was taken on
  the DEBUG build on 2026-08-11 and has never been re-run on what actually ships.
  **The emulator cannot be used for timing** — under host memory pressure it reported a 27s launch
  for what the Fire TV does in 5.
  ⭐ **What ships is the release-signed, R8-minified build — since versionCode 73** (2026-08-16;
  the first release-type smoke measured 1213ms cold start at 15.6MB, against the debug 37.9MB).
  The R8 startup crash that once blocked this (`IllegalStateException @ f6.a.<init>`, missing
  keep rules) is FIXED; do not re-read older notes as "release is broken". Two rules survive it:
  (1) anyone quoting a startup number must confirm the app actually REACHES HOME — a crash loop
  reports a 242ms "cold start" because it is timing RecoveryActivity, not the app; (2) a release
  smoke must reach a Hilt ViewModel screen, because an orphaned `@HiltViewModel` only crashes
  under R8 (see the memory note). **Open item: re-run `perf_check.sh` on the shipped release
  build and replace the 5207ms figure above — the budget has never been graded against it.**
- **No crash-free target.** Crashlytics ships and is wired, but nothing states what "healthy"
  is. Suggested: **crash-free sessions ≥ 99.5%**, checked per release.
- **Testing policy is a COUNT, not a rule.** `WORLD_CLASS_ROADMAP.md` E4/E5 track 17 instrumented
  and 81 unit test files, which says how many exist but not what must be covered. **Rule: every new
  collaborator class gets a unit test, and every playback landmine listed in the player memory notes
  gets an on-device check before release.**
- **Release discipline was only ever written in session memory.** It belongs here:
  **bump `versionCode` AND `versionName` together; copy the APK to
  `admin-panel/DebridXtream-latest.apk`; `firebase deploy --only hosting`; then DOWNLOAD the
  published file back and `aapt2 dump badging` it before quoting the link.** Announcing a
  `versionCode` you have not built is an update LOOP. The panel number and the APK must agree.

**How to use this list:** it is a standing audit, not a backlog to clear in one go. When a batch
touches a screen, apply the *rules* (strings, contentDescription) to what it touches. The
*retro* work — extracting 477 strings, labelling 120 images — is separate, explicitly-scoped work.

## Agent Comms — Reality-Based Coordination

**Tool-availability asymmetry:** `SendMessage` works **lead↔subagent** and lead↔lead, but **NOT subagent↔subagent**. Subagents spawned via the `Agent` tool are stateless one-shot workers — they have no inbox, cannot wait for events, and `SendMessage`/`TaskUpdate` are typically not in their tool allowlists. The `hive-mind_*` MCP tools provide coordination **metadata** (registry, consensus state) but do NOT grant subagents communication channels. Patterns that assume peer messaging will silently fail — agents either abort cleanly or run open-loop with stale assumptions. (See ruvnet/ruflo#2028 for the diagnosis.)

### Canonical pattern: memory-as-bus, lead-orchestrated phases

```
Lead (the orchestrator)
  │
  ├─ spawns agent → agent reads inputs from memory keys → writes outputs to memory keys → completes
  │
  ├─ verifies outputs in memory
  │
  └─ spawns next agent with explicit input-key list in its brief
```

All inter-agent state lives in a shared memory namespace (`memory_store` / `memory_search`). Lead-to-subagent `SendMessage` is fine when needed; subagent-to-subagent `SendMessage` is not.

### Spawning rules

- **Parallelize ONLY when work is genuinely independent** (no upstream dependency between siblings).
- **Spawn dependent agents only after the lead confirms upstream outputs are in memory.** Do NOT tell a downstream agent to "WAIT for SendMessage from X" — it has no mechanism to wait; it will abort.
- **Every subagent brief MUST include a degraded-mode paragraph** at the top: *"If your expected coordination tools (SendMessage, TaskUpdate, hive-mind_*) are missing, do NOT abort. Read these specific source files directly, write outputs to these specific memory keys, and complete your phase."*
- **Name agents** — `name: "role"` makes them addressable by the lead even though they cannot address each other.
- **After spawning**: STOP, tell user what's running, wait for completion notifications. No polling.

### Spawning example (memory-as-bus)

```javascript
// Phase 1 — independent parallel work
Agent({
  prompt: "Read docs at <paths>. Write inventory JSON to memory key phase1/researcher/inventory in namespace <ns>. Degraded mode: if memory tools missing, return inventory in your final message.",
  subagent_type: "researcher", name: "researcher", run_in_background: true
})
Agent({
  prompt: "Walk the source tree. Write capability matrix to memory key phase1/coder/capability-matrix. Degraded mode: ...",
  subagent_type: "coder", name: "source-reader", run_in_background: true
})

// AFTER both Phase 1 agents complete (lead verifies via memory_search), THEN spawn Phase 2.
// Each Phase 2 agent's brief explicitly lists the Phase 1 memory keys it should read.
```

### Patterns

| Pattern | Flow | Use When |
|---------|------|----------|
| **Sequential pipeline** | Lead → A → (verify in memory) → B → (verify) → C | Phase dependencies (audit, complex refactor) |
| **Fan-out** | Lead → A, B, C (parallel) → Lead aggregates from memory | Independent parallel work (research, multi-lens critique) |
| **Lead-as-bus** | Subagents → Lead → reroute by spawning next | Workaround when supervisor↔workers coordination needed |

### Anti-patterns (will silently fail)

- "WAIT for SendMessage from X" in a subagent prompt — no mechanism to wait
- "SendMessage findings to architect" in a subagent prompt — architect can't receive
- Spawning N dependent agents in one batch expecting them to chain via messages — they won't
- Relying on `hive-mind_consensus` to gather subagent votes — subagents aren't registered hive workers

### Lead-only SendMessage (still works)

`SendMessage` is still useful for **lead → subagent** redirects and priority changes:

```javascript
// Lead → subagent: redirect or update priority mid-flight
SendMessage({ to: "developer", summary: "Prioritize auth", message: "Auth is blocking tester, do that first." })
// Lead → subagent: graceful shutdown
SendMessage({ to: "developer", message: { type: "shutdown_request" } })
```

## Swarm & Routing

### Config
- **Topology**: hierarchical-mesh (anti-drift)
- **Max Agents**: 15
- **Memory**: hybrid
- **HNSW**: Enabled
- **Neural**: Enabled

```bash
npx @claude-flow/cli@latest swarm init --topology hierarchical --max-agents 8 --strategy specialized
```

### Agent Routing

| Task | Agents | Topology |
|------|--------|----------|
| Bug Fix | researcher, coder, tester | hierarchical |
| Feature | architect, coder, tester, reviewer | hierarchical |
| Refactor | architect, coder, reviewer | hierarchical |
| Performance | perf-engineer, coder | hierarchical |
| Security | security-architect, auditor | hierarchical |

### When to Swarm
- **YES**: 3+ files, new features, cross-module refactoring, API changes, security, performance
- **NO**: single file edits, 1-2 line fixes, docs updates, config changes, questions

### 3-Tier Model Routing

| Tier | Handler | Use Cases |
|------|---------|-----------|
| 1 | Agent Booster (WASM) | Simple transforms — skip LLM, use Edit directly |
| 2 | Haiku | Simple tasks, low complexity |
| 3 | Sonnet/Opus | Architecture, security, complex reasoning |

## Memory & Learning

### Before Any Task
```bash
npx @claude-flow/cli@latest memory search --query "[task keywords]" --namespace patterns
npx @claude-flow/cli@latest hooks route --task "[task description]"
```

### After Success
```bash
npx @claude-flow/cli@latest memory store --namespace patterns --key "[name]" --value "[what worked]"
npx @claude-flow/cli@latest hooks post-task --task-id "[id]" --success true --store-results true
```

### MCP Tools (use `ToolSearch("keyword")` to discover)

| Category | Key Tools |
|----------|-----------|
| **Memory** | `memory_store`, `memory_search`, `memory_search_unified` |
| **Bridge** | `memory_import_claude`, `memory_bridge_status` |
| **Swarm** | `swarm_init`, `swarm_status`, `swarm_health` |
| **Agents** | `agent_spawn`, `agent_list`, `agent_status` |
| **Hooks** | `hooks_route`, `hooks_post-task`, `hooks_worker-dispatch` |
| **Security** | `aidefence_scan`, `aidefence_is_safe`, `aidefence_has_pii` |
| **Hive-Mind** | `hive-mind_init`, `hive-mind_consensus`, `hive-mind_spawn` |

### Background Workers

| Worker | When |
|--------|------|
| `audit` | After security changes |
| `optimize` | After performance work |
| `testgaps` | After adding features |
| `map` | Every 5+ file changes |
| `document` | After API changes |

```bash
npx @claude-flow/cli@latest hooks worker dispatch --trigger audit
```

## Agents

**Core**: `coder`, `reviewer`, `tester`, `planner`, `researcher`
**Architecture**: `system-architect`, `backend-dev`, `mobile-dev`
**Security**: `security-architect`, `security-auditor`
**Performance**: `performance-engineer`, `perf-analyzer`
**Coordination**: `hierarchical-coordinator`, `mesh-coordinator`, `adaptive-coordinator`
**GitHub**: `pr-manager`, `code-review-swarm`, `issue-tracker`, `release-manager`

Any string works as a custom agent type.

## Build & Test

- ALWAYS run tests after code changes
- ALWAYS verify build succeeds before committing

```bash
npm run build && npm test
```

## CLI Quick Reference

```bash
npx @claude-flow/cli@latest init --wizard           # Setup
npx @claude-flow/cli@latest swarm init --v3-mode     # Start swarm
npx @claude-flow/cli@latest memory search --query "" # Vector search
npx @claude-flow/cli@latest hooks route --task ""    # Route to agent
npx @claude-flow/cli@latest doctor --fix             # Diagnostics
npx @claude-flow/cli@latest security scan            # Security scan
npx @claude-flow/cli@latest performance benchmark    # Benchmarks
```

26 commands, 140+ subcommands. Use `--help` on any command for details.

## Setup

```bash
claude mcp add claude-flow -- npx -y @claude-flow/cli@latest
npx @claude-flow/cli@latest daemon start
npx @claude-flow/cli@latest doctor --fix
```

**Agent tool** handles execution (agents, files, code, git). **MCP tools** handle coordination (swarm, memory, hooks). **CLI** is the same via Bash.
