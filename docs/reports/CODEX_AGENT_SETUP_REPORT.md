# Codex Agent Setup Report
Status: updated
Scope: agent and tooling setup notes.

Done:
- Claude Flow V3 helpers now run through `node.exe` on Windows, do not depend on `jq`, and accept the Android repo layout.
- Added `.gitattributes` to keep shell helpers on LF line endings.
- Added `claude-flow` and `ruflo` MCP aliases in the global Codex config so other chats can resolve the same server name.

Open:
- V3 domain-folder warnings are expected in this repo and are not fatal.
- An already-open chat may need to be restarted to pick up the refreshed MCP registry.

Proof:
- `bash .claude/helpers/validate-v3-config.sh` now completes with warnings only.
- `bash .claude/helpers/v3.sh full-status` and `bash .claude/helpers/update-v3-progress.sh status` both run successfully.

Next:
- Keep future helper changes Node-based and line-ending safe.
