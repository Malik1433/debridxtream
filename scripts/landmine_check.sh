#!/usr/bin/env bash
# The playback landmine checklist, scripted (roadmap F1 / E6, 2026-09-03).
#
# Every line below is an incident this app has actually had. Each is either AUTOMATED here
# (measured over adb against the running app on a real Fire TV) or MANUAL (needs eyes on the
# screen - the Live surface is secure, so screencap is black and no script can see a freeze).
# Run this before quoting a release. It exits non-zero on any automated FAIL.
#
#   ./scripts/landmine_check.sh                 # default device, waits for you to start playback
#   ./scripts/landmine_check.sh -s <serial>     # another device
#   ./scripts/landmine_check.sh --no-perf       # skip the cold-start budget (perf_check.sh)
#
# AUTOMATED
#   L1  cold start within budget                    scripts/perf_check.sh (median of 3, <= 5000 ms), 0 ANRs
#   L2  playback actually decodes                    MediaCodecLogger bitrate lines keep coming for 30 s
#       (the .ts freeze, the first-frame AudioTrack wedge and a black tunneled surface all stop them)
#   L3  MediaSession answers the SYSTEM               `media dispatch pause` -> state 2 with the process alive
#       (media3-session killed the process here)     `media dispatch play`  -> state 3
#   L4  no AudioSink / AudioTrack failures           logcat AudioSink|AudioTrack error lines = 0
#   L5  no crash, no ANR while it played             FATAL EXCEPTION = 0, "ANR in" = 0
#   L6  leaving the player unbinds the session       after BACK: dumpsys media_session has no PlayerMediaSession
#   L7  Live hand-off keeps ONE provider connection  if the session was Live: "adopt: handed over" seen, and the
#       (max_connections=1)                          shared preview still decodes after BACK
#
# MANUAL (printed at the end, tick them on the device)
#   M1  raw .ts channel: no freeze in the first 60 s and none after a pause/resume   (TsExtractor flags)
#   M2  4K episode/source switch mid-stream: picture, not black-with-audio           (tunnel teardown)
#   M3  D-pad focus survives a data refresh on Home / Live / Series                   (CC-1)
#   M4  EPG grid: no ghosted/stacked titles, now/next matches the broadcast           (deOverlap)
#   M5  series final-episode END: prompt, no ghost auto-advance
#   M6  crash-free sessions >= 99.5% on the PREVIOUS release (E13, Crashlytics console)
#
# Env overrides: ADB=<path to adb>  DEVICE=<serial>
set -uo pipefail
# Git Bash rewrites /sdcard/... into C:/Program Files/Git/sdcard/... before adb ever sees it;
# every device path in this script would silently point at the wrong place without this.
export MSYS_NO_PATHCONV=1

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE="${DEVICE:-192.168.178.64:5555}"
ADB="${ADB:-C:/Users/Malik/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="com.debridxtream.tv"
run_perf=1
while [ $# -gt 0 ]; do
    case "$1" in
        -s|--device) DEVICE="$2"; shift 2 ;;
        --no-perf) run_perf=0; shift ;;
        -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
done

adb() { "$ADB" -s "$DEVICE" "$@"; }
fails=0
pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; fails=$((fails + 1)); }
top_activity() { adb shell dumpsys activity activities 2>/dev/null | grep -oE 'mResumedActivity: ActivityRecord\{[^}]*\}' | grep -oE '[A-Za-z]+Activity' | tail -1; }
session_state() { adb shell dumpsys media_session 2>/dev/null | grep -A10 "PlayerMediaSession" | grep -oE "state=[0-9]+" | head -1; }
codec_lines() { adb logcat -d 2>/dev/null | grep -c "MediaCodecLogger" ; }

echo "landmine_check: $DEVICE  build: $(adb shell dumpsys package $PKG 2>/dev/null | grep -m1 -oE 'versionName=[^ ]+')"

# ── L1 ────────────────────────────────────────────────────────────────────────
if [ "$run_perf" -eq 1 ]; then
    echo "[L1] cold-start budget"
    if ADB="$ADB" bash "$repo_root/scripts/perf_check.sh" "$DEVICE" | sed 's/^/      /'; then pass "L1 cold start + ANRs within budget"; else fail "L1 perf_check.sh reported a FAIL"; fi
fi

# ── wait for playback ─────────────────────────────────────────────────────────
echo "[..] start playback on the device now (any movie, episode or channel) - waiting up to 120 s for PlayerActivity"
for i in $(seq 1 60); do [ "$(top_activity)" = "PlayerActivity" ] && break; sleep 2; done
if [ "$(top_activity)" != "PlayerActivity" ]; then
    fail "no PlayerActivity within 120 s - L2..L7 not run"
    echo "landmine_check: $fails automated FAIL(s)"; exit 1
fi
# Detect the Live adopt hand-off BEFORE clearing the log - the adopt line was written when the
# player opened, and a clear-then-grep would never see it (the first version of this script did
# exactly that and skipped L7 every time).
was_live=0; adb logcat -d 2>/dev/null | grep -q "adopt: handed over" && was_live=1
adb logcat -c
sleep 3

# ── L2 ────────────────────────────────────────────────────────────────────────
echo "[L2] decode continuity over 30 s"
before=$(codec_lines); sleep 30; after=$(codec_lines)
if [ $((after - before)) -ge 3 ]; then pass "L2 $((after - before)) MediaCodecLogger samples in 30 s"; else fail "L2 only $((after - before)) codec samples in 30 s - frozen or black"; fi

# ── L3 ────────────────────────────────────────────────────────────────────────
echo "[L3] MediaSession via the system path"
pid_before=$(adb shell pidof $PKG | tr -d '\r')
adb shell media dispatch pause; sleep 4
st=$(session_state); pid_after=$(adb shell pidof $PKG | tr -d '\r')
if [ "$st" = "state=2" ] && [ -n "$pid_after" ] && [ "$pid_after" = "$pid_before" ]; then pass "L3 pause -> $st, process alive"; else fail "L3 pause -> '${st:-no session}' pid $pid_before -> '${pid_after:-dead}'"; fi
adb shell media dispatch play; sleep 4
st=$(session_state)
if [ "$st" = "state=3" ]; then pass "L3 play -> $st"; else fail "L3 play -> '${st:-no session}'"; fi

# ── L4 / L5 ───────────────────────────────────────────────────────────────────
echo "[L4] audio sink health   [L5] crash / ANR"
log=$(adb logcat -d 2>/dev/null)
sink=$(printf '%s' "$log" | grep -ciE "AudioSink.*(Exception|error)|AudioTrack.*(write failed|ERROR_DEAD_OBJECT|obtainBuffer timed out)")
[ "$sink" -eq 0 ] && pass "L4 no AudioSink/AudioTrack failures" || fail "L4 $sink AudioSink/AudioTrack failure line(s)"
fatal=$(printf '%s' "$log" | grep -c "FATAL EXCEPTION"); anr=$(printf '%s' "$log" | grep -c "ANR in $PKG")
[ "$fatal" -eq 0 ] && [ "$anr" -eq 0 ] && pass "L5 0 FATAL, 0 ANR" || fail "L5 FATAL=$fatal ANR=$anr"

# ── L6 / L7 ───────────────────────────────────────────────────────────────────
echo "[L6] BACK out of the player   [L7] Live hand-off"
adb logcat -c
adb shell input keyevent KEYCODE_BACK; sleep 2
[ "$(top_activity)" = "PlayerActivity" ] && { adb shell input keyevent KEYCODE_BACK; sleep 3; }
if [ "$(top_activity)" != "PlayerActivity" ]; then
    left=$(adb shell dumpsys media_session 2>/dev/null | grep -c "PlayerMediaSession")
    [ "$left" -eq 0 ] && pass "L6 session gone after leaving the player" || fail "L6 $left PlayerMediaSession record(s) left behind"
    if [ "$was_live" -eq 1 ]; then
        sleep 6; c=$(codec_lines)
        [ "$c" -ge 1 ] && pass "L7 Live hand-back: shared preview still decoding ($c samples)" || fail "L7 Live hand-back: preview stopped decoding"
    else
        echo "  SKIP  L7 (not a Live session - run again from a channel to cover the hand-off)"
    fi
    fatal=$(adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION"); [ "$fatal" -eq 0 ] || fail "L6 FATAL on exit ($fatal)"
else
    fail "L6 still in PlayerActivity after two BACK presses"
fi

cat <<'EOF'

MANUAL - tick on the device before quoting the release:
  [ ] M1  raw .ts channel: no freeze in the first 60 s, none after pause/resume
  [ ] M2  4K episode/source switch mid-stream: picture, not black-with-audio
  [ ] M3  D-pad focus survives a data refresh on Home / Live / Series
  [ ] M4  EPG grid: no ghosted/stacked titles, now/next matches the broadcast
  [ ] M5  series final-episode END: prompt shown, no ghost auto-advance
  [ ] M6  crash-free sessions >= 99.5% on the previous release (E13)
          https://console.firebase.google.com/project/debridxtream-new/crashlytics
EOF
echo "landmine_check: $fails automated FAIL(s)"
exit $(( fails > 0 ? 1 : 0 ))
