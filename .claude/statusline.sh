#!/bin/bash
# Compatibility wrapper for the V3 statusline helper.

set -e

NODE_BIN="$(command -v node.exe 2>/dev/null || command -v node 2>/dev/null || true)"
if [ -z "$NODE_BIN" ]; then
  echo "Error: Node.js is required for the V3 statusline"
  exit 1
fi

"$NODE_BIN" "$(dirname "$0")/helpers/statusline.cjs" "$@"
