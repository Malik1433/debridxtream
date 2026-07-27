#!/usr/bin/env bash
# Point git at the versioned hooks in scripts/githooks (roadmap E2p).
#
#   ./scripts/install_git_hooks.sh
#
# Uses core.hooksPath rather than copying into .git/hooks, so the hooks stay under version control
# and everyone gets the same ones — a copied hook silently rots the moment it is edited upstream.
#
# Undo with:  git config --unset core.hooksPath
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

hooks_dir="scripts/githooks"
[ -d "$hooks_dir" ] || { echo "install_git_hooks: missing $hooks_dir" >&2; exit 2; }

# Executable bits do not survive on Windows checkouts; git honours them from the index, so set
# them there too. Harmless when already set.
chmod +x "$hooks_dir"/* 2>/dev/null || true
git update-index --chmod=+x "$hooks_dir"/* 2>/dev/null || true

git config core.hooksPath "$hooks_dir"

echo "install_git_hooks: core.hooksPath = $(git config core.hooksPath)"
echo "Installed: $(ls "$hooks_dir" | tr '\n' ' ')"
echo "Bypass a single commit with: git commit --no-verify"
