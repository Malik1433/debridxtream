# debxtrem

> Multi-agent orchestration framework for agentic coding

## Project Overview

A Claude Flow powered project

**Actual App Stack**: Android, Kotlin, Gradle
**Agent Setup**: Claude Flow / Codex local orchestration

## DebridXtream Mandatory Project Rules

### Pre-Task Context
- Before every task, read the relevant report and history files for the module being changed.
- Always check `docs/reports/APP_SUCCESS_PATTERNS.md`, `docs/reports/APP_FAILED_PATTERNS.md`, and `docs/reports/DO_NOT_REPEAT.md` when behavior may repeat prior successes or failures.
- For Player work, read `docs/reports/PLAYER_MODULE_REPORT.md`, `docs/reports/PLAYER_SUCCESS_HISTORY.md`, and `docs/reports/PLAYER_FAILED_ATTEMPTS.md`.
- For Series work, read `docs/reports/SERIES_MODULE_REPORT.md`, `docs/reports/SERIES_SUCCESS_HISTORY.md`, and `docs/reports/SERIES_FAILED_ATTEMPTS.md`.
- For Home work, read `docs/reports/HOME_MODULE_REPORT.md`, `docs/reports/HOME_SUCCESS_HISTORY.md`, and `docs/reports/HOME_FAILED_ATTEMPTS.md`.

### Change Discipline
- Do not create duplicate files, components, layouts, routes, adapters, or controllers.
- Prefer modifying the existing active implementation over adding parallel implementations.
- Preserve working behavior unless the task explicitly asks to change it.
- Do not touch unrelated modules.
- Do not change Android source, UI, Player, Home, Series, Debrid, Live, or VOD code during setup/audit tasks.

### Verification
- For code changes, final validation must include a clean `assembleDebug` whenever feasible: `.\gradlew.bat clean assembleDebug --no-daemon`.
- If clean assemble is too expensive or blocked, document the blocker and run the strongest targeted Gradle validation available.
- Do not claim PASS from compile/build only when behavior is user-facing. Real functional QA is required for user-facing behavior.
- Device QA must include `192.168.0.84:5555` and `192.168.0.21:5555` when requested.

### Report And History Updates
- Every task must update the module report, module success history, module failed attempts, `APP_SUCCESS_PATTERNS.md`, `APP_FAILED_PATTERNS.md`, and `DO_NOT_REPEAT.md` when relevant.
- No task is complete until relevant reports and histories are updated.
- Report files live under `docs/reports/`; do not scatter task reports into the repository root.

### Module-Specific Guardrails
- Player tasks: never change global DPAD behavior without a source/content guard.
- Series tasks: do not repeat skeleton DP margin tuning.
- Home tasks: preserve row/focus rules and document any focus changes.

## Quick Start

### Installation
```bash
./gradlew --version
```

### Build
```bash
./gradlew :app:assembleDebug
```

### Test
```bash
./gradlew testDebugUnitTest
```

### Development
```bash
./gradlew :app:installDebug
```

## Agent Coordination

### Swarm Configuration

This project uses hierarchical swarm coordination for complex tasks:

| Setting | Value | Purpose |
|---------|-------|---------|
| Topology | `hierarchical` | Queen-led coordination (anti-drift) |
| Max Agents | 8 | Optimal team size |
| Strategy | `specialized` | Clear role boundaries |
| Consensus | `raft` | Leader-based consistency |

### When to Use Swarms

**Invoke swarm for:**
- Multi-file changes (3+ files)
- New feature implementation
- Cross-module refactoring
- API changes with tests
- Security-related changes
- Performance optimization

**Skip swarm for:**
- Single file edits
- Simple bug fixes (1-2 lines)
- Documentation updates
- Configuration changes

### Available Skills

Use `$skill-name` syntax to invoke:

| Skill | Use Case |
|-------|----------|
| `$swarm-orchestration` | Multi-agent task coordination |
| `$memory-management` | Pattern storage and retrieval |
| `$sparc-methodology` | Structured development workflow |
| `$security-audit` | Security scanning and CVE detection |
| `$performance-analysis` | Profiling and optimization |
| `$github-automation` | CI/CD and PR management |
| `$agent-coordination` | Custom skill |
| `$agentdb-advanced` | Custom skill |
| `$agentdb-learning` | Custom skill |
| `$agentdb-memory-patterns` | Custom skill |
| `$agentdb-optimization` | Custom skill |
| `$agentdb-vector-search` | Custom skill |
| `$agentic-jujutsu` | Custom skill |
| `$claims` | Custom skill |
| `$embeddings` | Custom skill |
| `$flow-nexus-neural` | Custom skill |
| `$flow-nexus-platform` | Custom skill |
| `$flow-nexus-swarm` | Custom skill |
| `$github-code-review` | Custom skill |
| `$github-multi-repo` | Custom skill |
| `$github-project-management` | Custom skill |
| `$github-release-management` | Custom skill |
| `$github-workflow-automation` | Custom skill |
| `$hive-mind` | Custom skill |
| `$hive-mind-advanced` | Custom skill |
| `$hooks-automation` | Custom skill |
| `$neural-training` | Custom skill |
| `$pair-programming` | Custom skill |
| `$reasoningbank-agentdb` | Custom skill |
| `$reasoningbank-intelligence` | Custom skill |
| `$skill-builder` | Custom skill |
| `$stream-chain` | Custom skill |
| `$swarm-advanced` | Custom skill |
| `$v3-cli-modernization` | Custom skill |
| `$v3-core-implementation` | Custom skill |
| `$v3-ddd-architecture` | Custom skill |
| `$v3-integration-deep` | Custom skill |
| `$v3-mcp-optimization` | Custom skill |
| `$v3-memory-unification` | Custom skill |
| `$v3-performance-optimization` | Custom skill |
| `$v3-security-overhaul` | Custom skill |
| `$v3-swarm-coordination` | Custom skill |
| `$verification-quality` | Custom skill |
| `$worker-benchmarks` | Custom skill |
| `$worker-integration` | Custom skill |
| `$workflow-automation` | Custom skill |
| `$agent-payments` | Custom skill |
| `$agent-challenges` | Custom skill |
| `$agent-sandbox` | Custom skill |
| `$agent-app-store` | Custom skill |
| `$agent-user-tools` | Custom skill |
| `$agent-neural-network` | Custom skill |
| `$agent-swarm` | Custom skill |
| `$agent-workflow` | Custom skill |
| `$agent-authentication` | Custom skill |
| `$agent-docs-api-openapi` | Custom skill |
| `$agent-spec-mobile-react-native` | Custom skill |
| `$agent-v3-security-architect` | Custom skill |
| `$agent-v3-memory-specialist` | Custom skill |
| `$agent-v3-queen-coordinator` | Custom skill |
| `$agent-v3-integration-architect` | Custom skill |
| `$agent-v3-performance-engineer` | Custom skill |
| `$agent-coordinator-swarm-init` | Custom skill |
| `$agent-memory-coordinator` | Custom skill |
| `$agent-automation-smart-agent` | Custom skill |
| `$agent-github-pr-manager` | Custom skill |
| `$agent-implementer-sparc-coder` | Custom skill |
| `$agent-sparc-coordinator` | Custom skill |
| `$agent-migration-plan` | Custom skill |
| `$agent-performance-analyzer` | Custom skill |
| `$agent-orchestrator-task` | Custom skill |
| `$agent-arch-system-design` | Custom skill |
| `$agent-crdt-synchronizer` | Custom skill |
| `$agent-quorum-manager` | Custom skill |
| `$agent-performance-benchmarker` | Custom skill |
| `$agent-security-manager` | Custom skill |
| `$agent-raft-manager` | Custom skill |
| `$agent-gossip-coordinator` | Custom skill |
| `$agent-byzantine-coordinator` | Custom skill |
| `$agent-test-long-runner` | Custom skill |
| `$agent-queen-coordinator` | Custom skill |
| `$agent-swarm-memory-manager` | Custom skill |
| `$agent-worker-specialist` | Custom skill |
| `$agent-collective-intelligence-coordinator` | Custom skill |
| `$agent-scout-explorer` | Custom skill |
| `$agent-code-analyzer` | Custom skill |
| `$agent-analyze-code-quality` | Custom skill |
| `$agent-dev-backend-api` | Custom skill |
| `$agent-base-template-generator` | Custom skill |
| `$agent-agentic-payments` | Custom skill |
| `$agent-pseudocode` | Custom skill |
| `$agent-refinement` | Custom skill |
| `$agent-specification` | Custom skill |
| `$agent-architecture` | Custom skill |
| `$agent-pagerank-analyzer` | Custom skill |
| `$agent-consensus-coordinator` | Custom skill |
| `$agent-trading-predictor` | Custom skill |
| `$agent-performance-optimizer` | Custom skill |
| `$agent-matrix-optimizer` | Custom skill |
| `$agent-code-goal-planner` | Custom skill |
| `$agent-goal-planner` | Custom skill |
| `$agent-sublinear-goal-planner` | Custom skill |
| `$agent-sona-learning-optimizer` | Custom skill |
| `$agent-ml-developer` | Custom skill |
| `$agent-tester` | Custom skill |
| `$agent-coder` | Custom skill |
| `$agent-reviewer` | Custom skill |
| `$agent-researcher` | Custom skill |
| `$agent-planner` | Custom skill |

### Agent Types

| Type | Role | Use Case |
|------|------|----------|
| `researcher` | Requirements analysis | Understanding scope |
| `architect` | System design | Planning structure |
| `coder` | Implementation | Writing code |
| `tester` | Test creation | Quality assurance |
| `reviewer` | Code review | Security and quality |

### Agent Model Routing

Use explicit model routing by agent role instead of relying on one global default for all work.

| Agent type | Preferred model |
|------------|-----------------|
| `researcher` | `gpt-5.4-mini` |
| `explorer` | `gpt-5.4-mini` |
| `coder` | `gpt-5.3-codex` |
| `worker` | `gpt-5.3-codex` |
| `tester` | `gpt-5.4` |
| `reviewer` | `gpt-5.5` |
| `architect` | `gpt-5.5` |
| `planner` | `gpt-5.5` |

Routing rules:
- Always assign an explicit model when spawning an agent.
- Escalate one step for cross-module, security-sensitive, ambiguous, or high-risk work.
- Use `gpt-5.3-codex` as the fallback default when no role mapping applies.
- Keep the chosen model explainable in task notes and reports.

## Code Standards

### File Organization
- **NEVER** save to root folder
- `app/src/main/java` - Android/Kotlin app source files
- `app/src/test` - JVM unit tests
- `app/src/androidTest` - Android instrumentation/UI tests
- `docs/reports` - Task reports, module reports, histories, and pattern files
- `app/src/main/res` - Android resources/layouts/drawables

### Quality Rules
- Files under 500 lines
- No hardcoded secrets
- Input validation at boundaries
- Typed interfaces for public APIs
- TDD London School (mock-first) preferred

### Commit Messages
```
<type>(<scope>): <description>

[optional body]

Co-Authored-By: claude-flow <ruv@ruv.net>
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`

## Security

### Critical Rules
- NEVER commit secrets, credentials, or .env files
- NEVER hardcode API keys
- Always validate user input
- Use parameterized queries for SQL
- Sanitize output to prevent XSS

### Path Security
- Validate all file paths
- Prevent directory traversal (../)
- Use absolute paths internally

## Memory System

### Storing Patterns
```bash
npx @claude-flow/cli memory store \
  --key "pattern-name" \
  --value "pattern description" \
  --namespace patterns
```

### Searching Memory
```bash
npx @claude-flow/cli memory search \
  --query "search terms" \
  --namespace patterns
```

## Links

- Documentation: https://github.com/ruvnet/claude-flow
- Issues: https://github.com/ruvnet/claude-flow/issues

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| HNSW Search | 150x-12,500x faster | Vector operations |
| Memory Reduction | 50-75% | Int8 quantization |
| MCP Response | <100ms | API latency |
| CLI Startup | <500ms | Cold start |
| SONA Adaptation | <0.05ms | Neural learning |

## Testing

### Running Tests
```bash
# Unit tests
./gradlew testDebugUnitTest

# Android instrumentation tests, only when a device/emulator is connected
./gradlew connectedDebugAndroidTest

# Build validation
./gradlew :app:assembleDebug

# Install debug APK
./gradlew :app:installDebug
```

### Test Philosophy
- TDD London School (mock-first)
- Unit tests for business logic
- Integration tests for boundaries
- E2E tests for critical paths
- Security tests for sensitive operations

### Coverage Requirements
- Minimum 80% line coverage
- 100% coverage for security-critical code
- All public APIs must have tests

## MCP Integration

Claude Flow exposes tools via Model Context Protocol:

```bash
# Start MCP server
npx @claude-flow/cli mcp start

# List available tools
npx @claude-flow/cli mcp tools
```

### Available Tools

| Tool | Purpose | Example |
|------|---------|---------|
| `swarm_init` | Initialize swarm coordination | `swarm_init({topology: "hierarchical"})` |
| `agent_spawn` | Spawn new agents | `agent_spawn({type: "coder", name: "dev-1"})` |
| `memory_store` | Store in AgentDB | `memory_store({key: "pattern", value: "..."})` |
| `memory_search` | Semantic search | `memory_search({query: "auth patterns"})` |
| `task_orchestrate` | Task coordination | `task_orchestrate({task: "implement feature"})` |
| `neural_train` | Train neural patterns | `neural_train({iterations: 10})` |
| `benchmark_run` | Performance benchmarks | `benchmark_run({type: "all"})` |

## Hooks System

Claude Flow uses hooks for lifecycle automation:

### Core Hooks

| Hook | Trigger | Purpose |
|------|---------|---------|
| `pre-task` | Before task starts | Get context, load patterns |
| `post-task` | After task completes | Record completion, train |
| `pre-edit` | Before file changes | Validate, backup |
| `post-edit` | After file changes | Train patterns, verify |
| `pre-command` | Before shell commands | Security check |
| `post-command` | After shell commands | Log results |

### Session Hooks

| Hook | Purpose |
|------|---------|
| `session-start` | Initialize context, load memory |
| `session-end` | Export metrics, consolidate memory |
| `session-restore` | Resume from checkpoint |
| `notify` | Send notifications |

### Intelligence Hooks

| Hook | Purpose |
|------|---------|
| `route` | Route task to appropriate agents |
| `explain` | Generate explanations |
| `pretrain` | Pre-train neural patterns |
| `build-agents` | Build specialized agents |
| `transfer` | Transfer learning between domains |

### Example Usage
```bash
# Before starting a task
npx @claude-flow/cli hooks pre-task \
  --description "implementing authentication"

# After completing a task
npx @claude-flow/cli hooks post-task \
  --task-id "task-123" \
  --success true

# Route a task to agents
npx @claude-flow/cli hooks route \
  --task "implement OAuth2 login flow"
```

## Background Workers

12 background workers provide continuous optimization:

| Worker | Priority | Purpose |
|--------|----------|---------|
| `ultralearn` | normal | Deep knowledge acquisition |
| `optimize` | high | Performance optimization |
| `consolidate` | low | Memory consolidation |
| `predict` | normal | Predictive preloading |
| `audit` | critical | Security analysis |
| `map` | normal | Codebase mapping |
| `preload` | low | Resource preloading |
| `deepdive` | normal | Deep code analysis |
| `document` | normal | Auto-documentation |
| `refactor` | normal | Refactoring suggestions |
| `benchmark` | normal | Performance benchmarking |
| `testgaps` | normal | Test coverage analysis |

### Managing Workers
```bash
# List workers
npx @claude-flow/cli hooks worker list

# Trigger specific worker
npx @claude-flow/cli hooks worker dispatch --trigger audit

# Check worker status
npx @claude-flow/cli hooks worker status
```

## Intelligence System

The RuVector Intelligence System provides neural learning:

### Components
- **SONA**: Self-Optimizing Neural Architecture (<0.05ms adaptation)
- **MoE**: Mixture of Experts for specialized routing
- **HNSW**: Hierarchical Navigable Small World for fast search
- **EWC++**: Elastic Weight Consolidation (prevents forgetting)
- **Flash Attention**: Optimized attention mechanism

### 4-Step Pipeline
1. **RETRIEVE** - Fetch relevant patterns via HNSW
2. **JUDGE** - Evaluate with verdicts (success/failure)
3. **DISTILL** - Extract key learnings via LoRA
4. **CONSOLIDATE** - Prevent catastrophic forgetting via EWC++

## Debugging

### Log Levels
```bash
# Set log level
export CLAUDE_FLOW_LOG_LEVEL=debug

# Enable verbose mode
npx @claude-flow/cli --verbose <command>
```

### Health Checks
```bash
# Run diagnostics
npx @claude-flow/cli doctor --fix

# Check system status
npx @claude-flow/cli status
```
