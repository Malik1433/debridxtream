#!/usr/bin/env bash
# Performance budget check (2026-08-11).
#
# The project's own name starts with "loading issues", and until now "slow" was an opinion:
# there was no number to test against. This measures the three things the budget in CLAUDE.md
# names, on a REAL device — the emulator is not usable for timing (a host under memory pressure
# reports 27s for a launch the Fire TV does in 5).
#
#   ./scripts/perf_check.sh [serial]     # default: the Fire TV at 192.168.178.64:5555
#
# Prints one line per metric plus PASS/FAIL against the budget, and exits non-zero on a FAIL so
# it can gate a release.
set -u

SERIAL="${1:-192.168.178.64:5555}"
PKG="com.debridxtream.tv"
ACT="$PKG/com.tvonnet.debridxtreamiptv.ui.MainActivity"
ADB="${ADB:-adb}"

# Budget (see CLAUDE.md "Performance budget"). Cold start is the median of 3 runs, because the
# first launch after an install also pays dexopt and is not what a user experiences.
BUDGET_COLD_MS=5000
RUNS=3

say() { printf '%s\n' "$*"; }
fail=0

say "device: $SERIAL"
say "build:  $($ADB -s "$SERIAL" shell dumpsys package $PKG 2>/dev/null | grep -m1 versionName | tr -d '\r' | sed 's/^ *//')"

# ── 1. Cold start to first frame ────────────────────────────────────────────────
times=()
for i in $(seq 1 $RUNS); do
    $ADB -s "$SERIAL" shell "am force-stop $PKG" >/dev/null 2>&1
    sleep 3
    t=$($ADB -s "$SERIAL" shell "am start -W -n $ACT" 2>/dev/null | tr -d '\r' | awk -F': ' '/TotalTime/{print $2}')
    [ -n "${t:-}" ] && times+=("$t") && say "  cold start run $i: ${t}ms"
    sleep 5
done
if [ ${#times[@]} -eq 0 ]; then
    say "cold start: NO MEASUREMENT (device unreachable?)"; fail=1
else
    median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
    if [ "$median" -le "$BUDGET_COLD_MS" ]; then
        say "cold start median: ${median}ms  PASS (budget ${BUDGET_COLD_MS}ms)"
    else
        say "cold start median: ${median}ms  FAIL (budget ${BUDGET_COLD_MS}ms)"; fail=1
    fi
fi

# ── 2. ANRs since boot ──────────────────────────────────────────────────────────
anr=$($ADB -s "$SERIAL" logcat -d 2>/dev/null | grep -c "ANR in $PKG" || true)
if [ "${anr:-0}" -eq 0 ]; then say "ANRs in the log buffer: 0  PASS"; else say "ANRs in the log buffer: $anr  FAIL"; fail=1; fi

# ── 3. Frame drops (jank) over the session so far ───────────────────────────────
# gfxinfo's "Janky frames" percentage is the closest thing to the "no drop over 700ms during
# playback" line in the budget that can be read without a trace capture.
jank=$($ADB -s "$SERIAL" shell dumpsys gfxinfo $PKG 2>/dev/null | tr -d '\r' | grep -m1 "Janky frames" || true)
say "${jank:-Janky frames: (not reported)}"

exit $fail
