# Compact Report Standard

Use this format for future task reports, QA notes, and memory-style status files.

## Rules
- Keep one canonical file per topic.
- Write state, proof, and next step only.
- Prefer deltas over full rewrites.
- Use short IDs instead of repeated paragraphs.
- Keep logs out of the report; link or summarize only.

## Template
```md
# <Topic>
Status: verified | open | blocked | deferred
Scope: <one short line>
Done:
- <item>
- <item>
Open:
- <item>
Risk:
- <short line>
Proof:
- <build/test/device result>
Next:
- <next step>
```

## Delta Log Template
```md
## Updates
- 2026-05-22: <short change>
- 2026-05-22: <short change>
```

## Good Example
```md
# Companion Security
Status: verified
Scope: companion sync and payload handling
Done:
- local-network guard
- PIN lockout
- URL validation
Open:
- manual payload round-trip
Risk:
- transport and edge-case payloads
Proof:
- assembleDebug OK
- install OK on 192.168.0.84 and 192.168.0.21
- launch OK on both devices
Next:
- run the manual payload QA checklist
```
