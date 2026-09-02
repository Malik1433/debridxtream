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
# Kotlin files under app/src/main/java longer than $1 lines. `awk END{NR}` counts a final line
# with no trailing newline, which is what Groovy's readLines() does too - both gates must agree.
count_files_over() {
  find "$repo_root/app/src/main/java" -name '*.kt' -exec awk -v n="$1" 'END{if(NR>n)print FILENAME}' {} \; | wc -l | tr -d ' '
}

status=0
lowered=0

while IFS='=' read -r key max; do
  case "$key" in ''|\#*) continue ;; esac
  key="$(echo "$key" | tr -d '[:space:]')"
  max="$(echo "$max" | tr -d '[:space:]')"
  [ -n "$key" ] && [ -n "$max" ] || continue

  case "$key" in
    TOTAL)                    actual="$(count_total)" ;;
    KotlinFilesOver600Lines)  actual="$(count_files_over 600)" ;;
    KotlinFilesOver500Lines)  actual="$(count_files_over 500)" ;;
    *)                        actual="$(count_rule "$key")" ;;
  esac

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

# --- Source-level checks -------------------------------------------------------------------------
# The baseline only counts what detekt actually reports, and detekt's own rules have blind spots:
# on 2026-07-26 three real `e.printStackTrace()` calls in PlayerViewModel.kt were NOT flagged by the
# PrintStackTrace rule (verified with --rerun-tasks and the rule un-baselined), so a "0" in the
# baseline did not mean zero in the code. These greps measure the SOURCE, not detekt's opinion.
src_dir="$repo_root/app/src/main/java"
if [ -d "$src_dir" ]; then
  pst="$({ grep -rn --include=*.kt "printStackTrace()" "$src_dir" || true; } | wc -l | tr -d ' ')"
  if [ "$pst" -gt 0 ]; then
    echo "RATCHET FAIL: printStackTrace() found in $pst place(s) — use a logger so it reaches Crashlytics." >&2
    { grep -rn --include=*.kt "printStackTrace()" "$src_dir" || true; } | sed 's/^/    /' >&2
    status=1
  else
    echo "ratchet: printStackTrace() in source = 0"
  fi
fi

if [ "$status" -ne 0 ]; then
  echo "" >&2
  echo "Debt ratchet failed. See docs/reports/WORLD_CLASS_ROADMAP.md §1." >&2
  exit 1
fi

[ "$lowered" -eq 1 ] && echo "ratchet: OK (with improvements to lock in)" || echo "ratchet: OK"
exit 0
