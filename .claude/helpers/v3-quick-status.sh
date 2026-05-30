#!/bin/bash
# V3 Quick Status - Compact development status overview

set -e

NODE_BIN="$(command -v node.exe 2>/dev/null || command -v node 2>/dev/null || true)"
if [ -z "$NODE_BIN" ]; then
  echo "Error: Node.js is required for V3 quick status"
  exit 1
fi

read_metrics() {
  local file="$1"
  local script="$2"

  "$NODE_BIN" - "$file" <<NODE
const fs = require('fs');
const file = process.argv[2];
try {
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
$script
} catch {
  process.stdout.write('');
}
NODE
}

read_triplet() {
  read_metrics "$1" 'process.stdout.write([
    data.domains?.completed ?? 0,
    data.swarm?.activeAgents ?? 0,
    data.ddd?.progress ?? 0
  ].join(" "));'
}

read_single() {
  read_metrics "$1" "process.stdout.write(String($2 ?? '$3'));"
}

read_pair() {
  read_metrics "$1" 'process.stdout.write([
    data.flashAttention?.speedup ?? "1.0x",
    data.memory?.reduction ?? "0%"
  ].join(" "));'
}

IFS=' ' read -r DOMAINS AGENTS DDD_PROGRESS <<< "$(read_triplet ".claude-flow/metrics/v3-progress.json")"
IFS=' ' read -r CVES_FIXED <<< "$(read_metrics ".claude-flow/security/audit-status.json" 'process.stdout.write(String(data.cvesFixed ?? 0));')"
IFS=' ' read -r SPEEDUP MEMORY <<< "$(read_pair ".claude-flow/metrics/performance.json")"

DOMAINS="${DOMAINS:-0}"
AGENTS="${AGENTS:-0}"
DDD_PROGRESS="${DDD_PROGRESS:-0}"
CVES_FIXED="${CVES_FIXED:-0}"
SPEEDUP="${SPEEDUP:-1.0x}"
MEMORY="${MEMORY:-0%}"

DOMAIN_PERCENT=$((DOMAINS * 20))
AGENT_PERCENT=$((AGENTS * 100 / 15))

if [ $DOMAINS -eq 5 ]; then DOMAIN_COLOR=$'\033[0;32m'; elif [ $DOMAINS -ge 3 ]; then DOMAIN_COLOR=$'\033[0;33m'; else DOMAIN_COLOR=$'\033[0;31m'; fi
if [ $AGENTS -ge 10 ]; then AGENT_COLOR=$'\033[0;32m'; elif [ $AGENTS -ge 5 ]; then AGENT_COLOR=$'\033[0;33m'; else AGENT_COLOR=$'\033[0;31m'; fi
if [ $DDD_PROGRESS -ge 75 ]; then DDD_COLOR=$'\033[0;32m'; elif [ $DDD_PROGRESS -ge 50 ]; then DDD_COLOR=$'\033[0;33m'; else DDD_COLOR=$'\033[0;31m'; fi
if [ $CVES_FIXED -eq 3 ]; then SEC_COLOR=$'\033[0;32m'; elif [ $CVES_FIXED -ge 1 ]; then SEC_COLOR=$'\033[0;33m'; else SEC_COLOR=$'\033[0;31m'; fi

BLUE=$'\033[0;34m'
PURPLE=$'\033[0;35m'
CYAN=$'\033[0;36m'
RESET=$'\033[0m'

echo -e "${PURPLE}Claude Flow V3 Quick Status${RESET}"
echo -e "${BLUE}Domains:${RESET} ${DOMAIN_COLOR}${DOMAINS}/5${RESET} (${DOMAIN_PERCENT}%) | ${BLUE}Agents:${RESET} ${AGENT_COLOR}${AGENTS}/15${RESET} (${AGENT_PERCENT}%) | ${BLUE}DDD:${RESET} ${DDD_COLOR}${DDD_PROGRESS}%${RESET}"
echo -e "${BLUE}Security:${RESET} ${SEC_COLOR}${CVES_FIXED}/3${RESET} CVEs | ${BLUE}Perf:${RESET} ${CYAN}${SPEEDUP}${RESET} | ${BLUE}Memory:${RESET} ${CYAN}${MEMORY}${RESET}"

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")
  echo -e "${BLUE}Branch:${RESET} ${CYAN}${BRANCH}${RESET}"
fi
