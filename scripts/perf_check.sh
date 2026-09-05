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
# it can gate a release. Gated: cold start, ANRs, janky-frame percentage and the 95th-percentile
# frame time. Crash-free sessions (E13) cannot be read over adb and is printed as a check.
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

# ── 3. Frame timing (jank), over a SCRIPTED scroll ──────────────────────────────
# The old version printed whatever gfxinfo happened to hold and gated nothing, which made the
# number unusable twice over: it accumulated from process start (so it was mostly the launch),
# and it depended on whatever the device had been doing. Here the counters are RESET, a fixed
# scroll is driven on Home, and the result is read — same work every run, so the numbers can be
# compared between releases and gated.
#
# Both ceilings are RATCHETS, exactly like config/detekt/debt-ledger.txt: they may only ever be
# LOWERED. Raising one to make a release pass is the thing this is here to prevent.
BUDGET_JANK_PCT=75          # release 3.0.6 on the Fire TV measured 69.6%
BUDGET_P95_MS=250           # 95th-percentile frame time

$ADB -s "$SERIAL" shell "dumpsys gfxinfo $PKG reset" >/dev/null 2>&1
# 20 D-pad downs and back up: crosses the Home rails, which is where the app actually janks.
for _ in $(seq 1 20); do $ADB -s "$SERIAL" shell input keyevent KEYCODE_DPAD_DOWN >/dev/null 2>&1; done
for _ in $(seq 1 20); do $ADB -s "$SERIAL" shell input keyevent KEYCODE_DPAD_UP >/dev/null 2>&1; done
sleep 2
gfx=$($ADB -s "$SERIAL" shell dumpsys gfxinfo $PKG 2>/dev/null | tr -d '\r')

total=$(printf '%s' "$gfx" | awk '/Total frames rendered/{print $NF}')
jankpct=$(printf '%s' "$gfx" | awk -F'[()%]' '/Janky frames/{print int($2)}')
p95=$(printf '%s' "$gfx" | awk '/95th percentile/{gsub(/ms/,"",$NF); print $NF}')

if [ -z "${total:-}" ] || [ "${total:-0}" -lt 20 ]; then
    say "jank: NO MEASUREMENT (${total:-0} frames rendered - is the app on screen?)"; fail=1
else
    say "frames rendered: $total"
    if [ "${jankpct:-100}" -le "$BUDGET_JANK_PCT" ]; then
        say "janky frames: ${jankpct}%  PASS (ceiling ${BUDGET_JANK_PCT}%)"
    else
        say "janky frames: ${jankpct}%  FAIL (ceiling ${BUDGET_JANK_PCT}%)"; fail=1
    fi
    if [ -n "${p95:-}" ]; then
        if [ "$p95" -le "$BUDGET_P95_MS" ]; then
            say "95th percentile frame: ${p95}ms  PASS (ceiling ${BUDGET_P95_MS}ms)"
        else
            say "95th percentile frame: ${p95}ms  FAIL (ceiling ${BUDGET_P95_MS}ms)"; fail=1
        fi
    else
        say "95th percentile frame: (not reported by this device)"
    fi
fi

# ── 4. Crash-free sessions (E13) ────────────────────────────────────────────────
# There is no adb answer to this one: it lives in the Crashlytics console, and reading it needs a
# Google service account nobody has provisioned here. So it is a CHECK, not a guess — the release
# is not signed off until someone has read the number.
say ""
say "MANUAL - Crashlytics, before quoting this release:"
say "  [ ] crash-free sessions >= 99.5% on the PREVIOUS release"
say "      https://console.firebase.google.com/project/debridxtream-new/crashlytics"

exit $fail
