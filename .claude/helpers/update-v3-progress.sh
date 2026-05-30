#!/bin/bash
# V3 Progress Update Script

set -e

METRICS_DIR=".claude-flow/metrics"
SECURITY_DIR=".claude-flow/security"
NODE_BIN="$(command -v node.exe 2>/dev/null || command -v node 2>/dev/null || true)"

if [ -z "$NODE_BIN" ]; then
  echo "Error: Node.js is required for V3 helpers but was not found"
  exit 1
fi

mkdir -p "$METRICS_DIR" "$SECURITY_DIR"

update_json_field() {
  local file="$1"
  local path_expr="$2"
  local raw_value="$3"

  "$NODE_BIN" - "$file" "$path_expr" "$raw_value" <<'NODE'
const fs = require('fs');
const file = process.argv[2];
const pathExpr = process.argv[3];
const rawValue = process.argv[4];
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const value = /^-?\d+(?:\.\d+)?$/.test(rawValue) ? Number(rawValue) : rawValue;

const parts = pathExpr.split('.');
let cursor = data;
for (let i = 0; i < parts.length - 1; i++) {
  const key = parts[i];
  if (!cursor[key] || typeof cursor[key] !== 'object') cursor[key] = {};
  cursor = cursor[key];
}
cursor[parts[parts.length - 1]] = value;
fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
NODE
}

read_json_field() {
  local file="$1"
  local path_expr="$2"
  local fallback="$3"

  if [ ! -f "$file" ]; then
    echo "$fallback"
    return
  fi

  "$NODE_BIN" - "$file" "$path_expr" "$fallback" <<'NODE'
const fs = require('fs');
const file = process.argv[2];
const pathExpr = process.argv[3];
const fallback = process.argv[4];
try {
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  const value = pathExpr.split('.').reduce((acc, key) => (acc && acc[key] !== undefined ? acc[key] : undefined), data);
  process.stdout.write(String(value ?? fallback));
} catch {
  process.stdout.write(String(fallback));
}
NODE
}

print_statusline() {
  if [ -x ".claude/statusline.sh" ]; then
    bash .claude/statusline.sh
  fi
}

case "$1" in
  domain)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 domain <count>"
      exit 1
    fi
    update_json_field "$METRICS_DIR/v3-progress.json" "domains.completed" "$2"
    echo "Updated domain count to $2/5"
    ;;
  agent)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 agent <count>"
      exit 1
    fi
    update_json_field "$METRICS_DIR/v3-progress.json" "swarm.activeAgents" "$2"
    echo "Updated active agents to $2/15"
    ;;
  security)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 security <fixed_count>"
      exit 1
    fi
    update_json_field "$SECURITY_DIR/audit-status.json" "cvesFixed" "$2"
    if [ "$2" -eq 3 ]; then
      update_json_field "$SECURITY_DIR/audit-status.json" "status" "CLEAN"
    fi
    echo "Updated security: $2/3 CVEs fixed"
    ;;
  performance)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 performance <speedup>"
      exit 1
    fi
    update_json_field "$METRICS_DIR/performance.json" "flashAttention.speedup" "$2"
    echo "Updated Flash Attention speedup to $2"
    ;;
  memory)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 memory <percentage>"
      exit 1
    fi
    update_json_field "$METRICS_DIR/performance.json" "memory.reduction" "$2"
    echo "Updated memory reduction to $2"
    ;;
  ddd)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 ddd <percentage>"
      exit 1
    fi
    update_json_field "$METRICS_DIR/v3-progress.json" "ddd.progress" "$2"
    echo "Updated DDD progress to $2%"
    ;;
  status)
    echo "V3 Development Status:"
    echo "======================"
    domains=$(read_json_field "$METRICS_DIR/v3-progress.json" "domains.completed" 0)
    agents=$(read_json_field "$METRICS_DIR/v3-progress.json" "swarm.activeAgents" 0)
    ddd=$(read_json_field "$METRICS_DIR/v3-progress.json" "ddd.progress" 0)
    cves=$(read_json_field "$SECURITY_DIR/audit-status.json" "cvesFixed" 0)
    speedup=$(read_json_field "$METRICS_DIR/performance.json" "flashAttention.speedup" "1.0x")
    memory=$(read_json_field "$METRICS_DIR/performance.json" "memory.reduction" "0%")
    echo "Domains: $domains/5"
    echo "Agents: $agents/15"
    echo "DDD: $ddd%"
    echo "Security: $cves/3 CVEs fixed"
    echo "Performance: $speedup speedup, $memory memory saved"
    ;;
  *)
    echo "Usage: $0 <domain|agent|security|performance|memory|ddd|status> [value]"
    exit 1
    ;;
esac

if [ "$1" != "status" ] && [ -n "${1:-}" ]; then
  echo
  print_statusline
fi
