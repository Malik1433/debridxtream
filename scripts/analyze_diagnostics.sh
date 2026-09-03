#!/usr/bin/env bash
# Playback diagnostics: pull the app's JSONL session log off a device and turn it into a
# readable timeline plus an anomaly list (roadmap G1, 2026-09-03).
#
# Why this exists: the recorder (debug/PlaybackDiagnosticsRecorder.kt, 35 call sites) has been
# writing a rich per-event log since 2026-05, but reading 20 000 raw JSON lines is how nobody
# reads it. This answers "what did the app do?" for a session that happened without anyone
# watching - the next "why did it reload?" no longer needs a live adb session.
#
#   ./scripts/analyze_diagnostics.sh                    # pull from the default device, analyse
#   ./scripts/analyze_diagnostics.sh --enable           # turn recording ON (debug build only)
#   ./scripts/analyze_diagnostics.sh --disable          # turn it off again
#   ./scripts/analyze_diagnostics.sh <dir-or-file>      # analyse an already-pulled folder/file
#   ./scripts/analyze_diagnostics.sh -s emulator-5554   # another device
#   ./scripts/analyze_diagnostics.sh --timeline         # print every event, not just the anomalies
#
# The recorder is gated on BuildConfig.DEBUG AND the marker file, so on the shipped release
# build this pulls nothing - install a debug build first (the emulator is fine for this).
# Env overrides: ADB=<path to adb>  DEVICE=<serial>
set -uo pipefail
# Git Bash rewrites /sdcard/... into C:/Program Files/Git/sdcard/... before adb ever sees it;
# every device path in this script would silently point at the wrong place without this.
export MSYS_NO_PATHCONV=1

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE="${DEVICE:-192.168.178.64:5555}"
ADB="${ADB:-C:/Users/Malik/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="com.debridxtream.tv"
REMOTE_DIR="/sdcard/Android/data/$PKG/files/playback-diagnostics"
OUT_ROOT="$repo_root/app/build/diagnostics"

mode="pull"; target=""; timeline=0
while [ $# -gt 0 ]; do
    case "$1" in
        --enable) mode="enable"; shift ;;
        --disable) mode="disable"; shift ;;
        --timeline) timeline=1; shift ;;
        -s|--device) DEVICE="$2"; shift 2 ;;
        -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
        -*) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
        *) mode="local"; target="$1"; shift ;;
    esac
done

case "$mode" in
    enable)
        "$ADB" -s "$DEVICE" shell "mkdir -p $REMOTE_DIR && touch $REMOTE_DIR/.enabled" && echo "recording ENABLED on $DEVICE ($REMOTE_DIR/.enabled) - restart the app"
        exit $? ;;
    disable)
        "$ADB" -s "$DEVICE" shell "rm -f $REMOTE_DIR/.enabled" && echo "recording DISABLED on $DEVICE"
        exit $? ;;
    pull)
        stamp="$(date +%Y%m%d-%H%M%S)"
        target="$OUT_ROOT/$stamp"
        mkdir -p "$target"
        # adb.exe is a Windows program: the LOCAL side must be a Windows path, not the /e/... form
        # Git Bash uses internally (with path conversion off, that form is what $PWD yields).
        local_target="$(cygpath -m "$target" 2>/dev/null || echo "$target")"
        if ! "$ADB" -s "$DEVICE" pull "$REMOTE_DIR" "$local_target"; then
            echo "analyze_diagnostics: nothing to pull from $DEVICE:$REMOTE_DIR (debug build + --enable first?)" >&2
            exit 1
        fi
        echo "pulled to $target" ;;
esac

# python is a Windows program too: hand it the Windows form of the path (pull or local mode).
python - "$(cygpath -m "$target" 2>/dev/null || echo "$target")" "$timeline" <<'PY'
import glob, json, os, sys
from collections import Counter

root, timeline = sys.argv[1], sys.argv[2] == "1"
files = [root] if os.path.isfile(root) else sorted(glob.glob(os.path.join(root, "**", "session-*.jsonl"), recursive=True))
if not files:
    print("no session-*.jsonl under", root); sys.exit(1)

ANOMALY_TYPES = {"stall_warning", "stall_triggered", "retry_triggered", "player_error", "terminal_failure",
                 "buffer_timeout", "video_freeze_detected", "audio_sink_exhausted", "audio_wedge_escape",
                 "black_video_tunneling_retry", "return_to_sources"}
LIFECYCLE_RELEASES = {"unspecified", "on_stop", "on_destroy", "on_pause", "activity_destroyed", "finish", "exit", None}
KEY_FIELDS = ("reasonCode", "errorCode", "httpStatus", "positionMs", "playbackState", "releaseReason",
              "stallStrikeCount", "javaHeapUsedMb", "systemAvailMb", "durationMs", "eventCount")

def fmt_ms(ms):
    s = int(ms) // 1000
    return f"{s//60:02d}:{s%60:02d}"

overall_bad = 0
for path in files:
    evs = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                try: evs.append(json.loads(line))
                except json.JSONDecodeError: pass
    if not evs: continue
    types = Counter(e.get("eventType") for e in evs)
    print("=" * 96)
    print(f"{os.path.basename(path)}  ({len(evs)} events, {os.path.getsize(path)//1024} KB)")
    last = evs[-1]
    closed = last.get("eventType") in ("session_finished", "session_rotated")
    print("  closed:", last.get("eventType") if closed else "NO - the process died or was killed before finishSession")

    # --- anomalies -------------------------------------------------------------
    anomalies = []
    launch_ms = None; first_frame_seen = False
    releases = []
    mem = [e for e in evs if e.get("eventType") == "memory_sample"]
    for e in evs:
        t, el, f = e.get("eventType"), e.get("elapsedMs", 0), e.get("fields", {})
        if t == "playback_launch": launch_ms, first_frame_seen = el, False
        if t == "first_frame_rendered" and launch_ms is not None and not first_frame_seen:
            first_frame_seen = True
            ttff = el - launch_ms
            if ttff > 8000: anomalies.append((el, f"slow first frame: {ttff} ms after launch"))
        # A normal exit releases up to three times (finish -> on_stop -> on_destroy); only
        # rebuild-type releases count towards a reconnect loop. Learned from the first real
        # session, which flagged a clean exit.
        if t == "release_player" and f.get("releaseReason") not in LIFECYCLE_RELEASES:
            releases.append((el, f.get("releaseReason")))
        if t in ANOMALY_TYPES:
            detail = ", ".join(f"{k}={f[k]}" for k in KEY_FIELDS if k in f)
            anomalies.append((el, f"{t}  {detail}"))
        if t == "memory_sample" and f.get("systemLowMemory") is True:
            anomalies.append((el, f"system LOW MEMORY  avail={f.get('systemAvailMb')} MB"))
    if launch_ms is not None and not first_frame_seen:
        anomalies.append((evs[-1].get("elapsedMs", 0), "playback launched but NO first frame ever rendered"))
    # reconnect loop: 3+ releases within 60 s
    for i in range(len(releases) - 2):
        if releases[i + 2][0] - releases[i][0] <= 60_000:
            anomalies.append((releases[i][0], f"reconnect loop: 3 player releases within 60 s ({releases[i][1]}, {releases[i+1][1]}, {releases[i+2][1]})"))
            break
    if len(mem) >= 2:
        first, lastm = mem[0]["fields"], mem[-1]["fields"]
        growth = (lastm.get("javaHeapUsedMb") or 0) - (first.get("javaHeapUsedMb") or 0)
        span = (mem[-1]["elapsedMs"] - mem[0]["elapsedMs"]) / 60000.0
        print(f"  memory: java heap {first.get('javaHeapUsedMb')} -> {lastm.get('javaHeapUsedMb')} MB over {span:.0f} min ({len(mem)} samples, peak {max(m['fields'].get('javaHeapUsedMb') or 0 for m in mem)} MB)")
        if growth >= 64 and span >= 10: anomalies.append((mem[-1]["elapsedMs"], f"heap grew {growth} MB over {span:.0f} min - retained-player leak suspect"))

    summary = last.get("fields", {}) if closed else {}
    if summary:
        print("  summary:", ", ".join(f"{k}={summary[k]}" for k in ("durationMs", "eventCount", "stallWarnings", "stalls", "retries", "playerErrors", "firstFrames", "releases", "peakJavaHeapMb") if k in summary))
    print("  events by type:", ", ".join(f"{k}x{v}" for k, v in types.most_common(12)))

    if anomalies:
        overall_bad += len(anomalies)
        print(f"  ANOMALIES ({len(anomalies)}):")
        for el, msg in sorted(anomalies): print(f"    [{fmt_ms(el)}] {msg}")
    else:
        print("  anomalies: none")

    if timeline:
        print("  timeline:")
        for e in evs:
            f = e.get("fields", {})
            detail = ", ".join(f"{k}={f[k]}" for k in KEY_FIELDS if k in f)
            print(f"    [{fmt_ms(e.get('elapsedMs', 0))}] {e.get('eventType')}  {detail}")

print("=" * 96)
print(f"{len(files)} session file(s), {overall_bad} anomaly line(s)")
sys.exit(2 if overall_bad else 0)
PY
