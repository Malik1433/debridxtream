#!/usr/bin/env bash
# Run the instrumented (on-device) test suite with one command.
#
# Why this exists (roadmap B1): `./gradlew :app:connectedDebugAndroidTest` does NOT work on this
# machine — it dies inside Gradle's UTP with
#     Cannot run program "C:\Program Files\Java\bin\java": CreateProcess error=1920
# which is a local JDK-path glitch, not a test problem. Do not spend time on it. The route below
# (assemble both APKs, install, drive AndroidJUnitRunner directly over adb) is device-proven —
# it is how the Room migration test was run green on 2026-07-21.
#
# Usage:
#   ./scripts/run_instrumented.sh                          # every instrumented test
#   ./scripts/run_instrumented.sh MigrationTest            # a class (short name is enough)
#   ./scripts/run_instrumented.sh MigrationTest#migrate7To8   # a single test method
#   ./scripts/run_instrumented.sh -s 192.168.178.35:5555      # a different device
#   ./scripts/run_instrumented.sh -n MigrationTest            # skip the build, reuse the APKs
#
# Env overrides: ADB=<path to adb>  DEVICE=<serial>
#
# See docs/reports/WORLD_CLASS_ROADMAP.md §Tier B.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

DEVICE="${DEVICE:-192.168.178.64:5555}"
ADB="${ADB:-C:/Users/Malik/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
TEST_PACKAGE="com.debridxtream.tv.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_SOURCE_ROOT="app/src/androidTest/java"
build=1
filter=""

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--device) DEVICE="$2"; shift 2 ;;
        -n|--no-build) build=0; shift ;;
        -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
        -*) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
        *) filter="$1"; shift ;;
    esac
done

die() { echo "" >&2; echo "run_instrumented: $1" >&2; exit 1; }

[ -x "$ADB" ] || command -v "$ADB" >/dev/null 2>&1 || die "adb not found at '$ADB'. Set ADB=<path>."

# ---------------------------------------------------------------- resolve the class filter
# A short name is enough: expand it to the fully-qualified class the runner needs.
class_arg=""
if [ -n "$filter" ]; then
    class="${filter%%#*}"
    method="${filter#*#}"; [ "$method" = "$filter" ] && method=""
    if [ "${class#*.}" = "$class" ]; then
        match="$(find "$TEST_SOURCE_ROOT" -name "$class.kt" -o -name "$class.java" | head -2)"
        [ -n "$match" ] || die "no instrumented test named '$class' under $TEST_SOURCE_ROOT."
        [ "$(echo "$match" | wc -l)" -eq 1 ] || die "'$class' is ambiguous:
$match"
        class="$(echo "${match#$TEST_SOURCE_ROOT/}" | sed -e 's/\.[^.]*$//' -e 's|/|.|g')"
    fi
    class_arg="$class"
    [ -n "$method" ] && class_arg="$class#$method"
    echo "run_instrumented: filter -> $class_arg"
fi

# ---------------------------------------------------------------- device
if ! "$ADB" devices | grep -q "^$DEVICE[[:space:]]*device$"; then
    case "$DEVICE" in
        *:*) echo "run_instrumented: connecting to $DEVICE ..."; "$ADB" connect "$DEVICE" >/dev/null || true ;;
    esac
    "$ADB" devices | grep -q "^$DEVICE[[:space:]]*device$" \
        || die "device '$DEVICE' is not connected/authorised.
Connected now:
$("$ADB" devices | tail -n +2)"
fi

# ---------------------------------------------------------------- build
app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [ "$build" -eq 1 ]; then
    echo "run_instrumented: assembling debug + androidTest APKs ..."
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
else
    echo "run_instrumented: --no-build, reusing existing APKs"
fi
[ -f "$app_apk" ]  || die "missing $app_apk — run without --no-build."
[ -f "$test_apk" ] || die "missing $test_apk — run without --no-build."

# ---------------------------------------------------------------- install
install_apk() {
    local apk="$1"
    echo "run_instrumented: installing $(basename "$apk") on $DEVICE ..."
    local out
    if ! out="$("$ADB" -s "$DEVICE" install -r "$apk" 2>&1)"; then
        echo "$out" >&2
        case "$out" in
            *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*)
                die "signature mismatch. Uninstall first:
    \"$ADB\" -s $DEVICE uninstall com.debridxtream.tv
    \"$ADB\" -s $DEVICE uninstall $TEST_PACKAGE" ;;
            *) die "install failed for $apk" ;;
        esac
    fi
}
install_apk "$app_apk"
install_apk "$test_apk"

# ---------------------------------------------------------------- run
log_dir="app/build/instrumented"          # under build/, so it is git-ignored (repo hygiene)
mkdir -p "$log_dir"
log="$log_dir/last-run.txt"

echo "run_instrumented: running ${class_arg:-all instrumented tests} ..."
set +e
if [ -n "$class_arg" ]; then
    "$ADB" -s "$DEVICE" shell am instrument -w -r \
        -e class "$class_arg" \
        "$TEST_PACKAGE/$RUNNER" 2>&1 | tee "$log"
else
    "$ADB" -s "$DEVICE" shell am instrument -w -r \
        "$TEST_PACKAGE/$RUNNER" 2>&1 | tee "$log"
fi
set -e

# `am instrument` exits 0 even when tests fail, so the verdict comes from the output.
echo ""
if grep -q "INSTRUMENTATION_RESULT: shortMsg=" "$log"; then
    grep "INSTRUMENTATION_RESULT: shortMsg=" "$log" >&2
    die "the test process crashed. Full output: $log"
fi
if grep -qE "^(FAILURES!!!|INSTRUMENTATION_FAILED)" "$log" || grep -q "Failures: [1-9]" "$log"; then
    echo "----- failures -----" >&2
    grep -E "^INSTRUMENTATION_STATUS: (class|test|stack)=" "$log" | tail -40 >&2
    die "instrumented tests FAILED. Full output: $log"
fi
ok_line="$(grep -oE "OK \([0-9]+ tests?\)" "$log" | tail -1 || true)"
[ -n "$ok_line" ] || die "no result line in the runner output — did anything run? Full output: $log"
# "OK (0 tests)" is the silent-green trap: a mistyped method filter matches nothing and the runner
# still says OK. Treat it as a failure.
[ "$ok_line" = "OK (0 tests)" ] && die "the filter '${class_arg:-<none>}' matched NO tests. Full output: $log"

echo "instrumented: $ok_line on $DEVICE (log: $log)"
exit 0
