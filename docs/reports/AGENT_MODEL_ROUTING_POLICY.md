# Agent Model Routing Policy

## Summary
- Default project model remains `gpt-5.3-codex`.
- Agent model selection should be explicit by role, not implicit.
- High-risk, cross-module, ambiguous, or security-sensitive work should escalate one step.

## Preferred Mapping

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

## Rules
- Always assign a model when spawning an agent.
- Do not leave routing implicit for swarm tasks.
- Use `gpt-5.3-codex` as fallback when no role mapping applies.
- Keep routing decisions explainable in notes and reports.

## Status
PASS  Routing policy documented and aligned with project instructions.
