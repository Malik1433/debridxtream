#!/usr/bin/env bash
# Repo hygiene gate — keeps the project folder clean.
#
# Why this exists: by 2026-07-26 the repo had accumulated ~180MB of committed scratch output
# (dozens of build_log_*.txt, logcat dumps, QA screenshots + uiautomator XML, local DBs, one-off
# scripts) plus a steady trickle of ZERO-BYTE files created by mistyped shell redirects — files
# literally named `{,`, `Unit`, `showLoading(message)`, `stream`. .gitignore alone cannot catch the
# second kind, because their names are arbitrary. This does.
#
#   ./scripts/check_repo_hygiene.sh
#
# See docs/reports/WORLD_CLASS_ROADMAP.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

status=0

fail() { echo "HYGIENE FAIL: $1" >&2; status=1; }

# 1) Zero-byte files anywhere git tracks or sees (the mistyped-redirect class).
empty="$(git ls-files -co --exclude-standard | while IFS= read -r f; do
    [ -f "$f" ] && [ ! -s "$f" ] && echo "$f"
done || true)"
if [ -n "$empty" ]; then
    fail "zero-byte file(s) — almost always a mistyped shell redirect:"
    echo "$empty" | sed 's/^/    /' >&2
    echo "    Delete them: git ls-files -co --exclude-standard | while read f; do [ -f \"\$f\" ] && [ ! -s \"\$f\" ] && rm -- \"\$f\"; done" >&2
fi

# 2) Scratch output that must never be committed again (patterns also in .gitignore, but a
#    `git add -f` or a renamed variant would slip past it).
junk="$(git ls-files | grep -iE '^(build_log|build_output|assemble-|compile-|compile_test|kapt_log|app_log|logcat|temp_logs|filtered_logcat|qa[0-9_]|view_|window_).*\.(txt|png|xml)$|\.(sqlite|db|patch|diff)$|^artifacts/' || true)"
if [ -n "$junk" ]; then
    fail "committed scratch/debug output ($(echo "$junk" | wc -l | tr -d ' ') file(s)):"
    echo "$junk" | head -20 | sed 's/^/    /' >&2
fi

# 3) Unexpected files in the repo ROOT. The root is a curated list — everything else belongs in a
#    subdirectory (docs/, scripts/, app/) or in the session scratchpad.
allowed_root="^(\.gitattributes|\.gitignore|\.mcp\.json|\.windsurfrules|AGENTS\.md|CLAUDE\.md|build\.gradle|settings\.gradle|gradle\.properties|gradlew|gradlew\.bat|build_install\.bat|install\.cmd|quick_backup\.sh|keystore\.properties\.example)$"
stray="$(git ls-files | grep -v '/' | grep -vE "$allowed_root" || true)"
if [ -n "$stray" ]; then
    fail "unexpected file(s) in the repo root — move them into docs/, scripts/, or the scratchpad:"
    echo "$stray" | sed 's/^/    /' >&2
    echo "    (If one genuinely belongs at the root, add it to allowed_root in this script.)" >&2
fi

if [ "$status" -ne 0 ]; then
    echo "" >&2
    echo "Repo hygiene failed. Keep scratch work in the session scratchpad, not the repo." >&2
    exit 1
fi

echo "hygiene: OK — root is clean, no zero-byte files, no committed scratch output"
exit 0
