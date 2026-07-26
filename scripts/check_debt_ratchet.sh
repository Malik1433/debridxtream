#!/usr/bin/env bash
# Technical-debt ratchet gate.
#
# Fails if the detekt baseline carries MORE suppressed violations than the ceilings recorded in
# config/detekt/debt-ledger.txt. This is what stops "we'll fix it later" from silently becoming
# "we never fixed it": a phase may lower a ceiling, never raise it.
#
#   ./scripts/check_debt_ratchet.sh
#
# See docs/reports/WORLD_CLASS_ROADMAP.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
baseline="$repo_root/config/detekt/detekt-baseline.xml"
ledger="$repo_root/config/detekt/debt-ledger.txt"

[ -f "$baseline" ] || { echo "ratchet: missing $baseline" >&2; exit 2; }
[ -f "$ledger" ]   || { echo "ratchet: missing $ledger" >&2; exit 2; }

# Count all suppressions, and per-rule counts.
# NOTE: both must tolerate a count of ZERO — `grep` exits 1 when it matches nothing, which under
# `set -euo pipefail` would abort the script exactly when a rule reaches 0 (i.e. on success).
count_total() { grep -c "<ID>" "$baseline" || true; }
count_rule()  { { grep -o "<ID>$1:" "$baseline" || true; } | wc -l | tr -d ' '; }

status=0
lowered=0

while IFS='=' read -r key max; do
  case "$key" in ''|\#*) continue ;; esac
  key="$(echo "$key" | tr -d '[:space:]')"
  max="$(echo "$max" | tr -d '[:space:]')"
  [ -n "$key" ] && [ -n "$max" ] || continue

  if [ "$key" = "TOTAL" ]; then
    actual="$(count_total)"
  else
    actual="$(count_rule "$key")"
  fi

  if [ "$actual" -gt "$max" ]; then
    echo "RATCHET FAIL: $key = $actual, ceiling is $max (+$((actual - max)))." >&2
    echo "  Fix the new violation — do NOT raise the ceiling. Relocations must keep the total flat." >&2
    status=1
  elif [ "$actual" -lt "$max" ]; then
    echo "ratchet: $key = $actual (ceiling $max) — improved by $((max - actual)); lower the ceiling in config/detekt/debt-ledger.txt to lock it in."
    lowered=1
  else
    echo "ratchet: $key = $actual (at ceiling)"
  fi
done < "$ledger"

if [ "$status" -ne 0 ]; then
  echo "" >&2
  echo "Debt ratchet failed. See docs/reports/WORLD_CLASS_ROADMAP.md §1." >&2
  exit 1
fi

[ "$lowered" -eq 1 ] && echo "ratchet: OK (with improvements to lock in)" || echo "ratchet: OK"
exit 0
