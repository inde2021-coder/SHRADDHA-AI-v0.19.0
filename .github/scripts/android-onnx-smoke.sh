#!/usr/bin/env bash
set -Eeuo pipefail

bash .github/scripts/wait-for-android-emulator.sh
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then ADB="$(command -v adb)"; fi
# The readiness probe writes the exact emulator serial it verified.
# Prefer that value over ANDROID_SERIAL, which may be inherited stale from the runner.
SERIAL_FILE="${RUNNER_TEMP:-/tmp}/shraddha-emulator-serial"
if [[ ! -s "$SERIAL_FILE" ]]; then
  echo "ERROR: readiness probe did not publish a verified emulator serial."
  "$ADB" devices -l || true
  exit 1
fi
SERIAL="$(tr -d '\r\n' < "$SERIAL_FILE")"
if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]]; then
  echo "ERROR: readiness probe returned an invalid emulator serial: '$SERIAL'."
  exit 1
fi
if [[ -z "$SERIAL" ]] || ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "ERROR: the emulator that passed readiness is no longer connected."
  "$ADB" devices -l || true
  exit 1
fi
# Pin every subsequent ADB/Gradle operation to the emulator that passed readiness.
export ANDROID_SERIAL="$SERIAL"
echo "Using verified Android emulator: $ANDROID_SERIAL"

dump_diagnostics() {
  echo "===== ADB devices ====="
  "$ADB" devices -l || true
  echo "===== Emulator boot/user properties ====="
  "$ADB" -s "$SERIAL" shell getprop sys.boot_completed || true
  "$ADB" -s "$SERIAL" shell getprop sys.user.0.ce_available || true
  echo "===== Android services ====="
  "$ADB" -s "$SERIAL" shell service check package || true
  "$ADB" -s "$SERIAL" shell service check activity || true
  "$ADB" -s "$SERIAL" shell service check window || true
  echo "===== Emulator disk ====="
  "$ADB" -s "$SERIAL" shell df -h /data || true
  echo "===== Emulator logcat ====="
  "$ADB" -s "$SERIAL" logcat -d -t 1200 || true
}
trap 'status=$?; if (( status != 0 )); then dump_diagnostics; fi' EXIT

"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell input keyevent 82 || true
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard || true
"$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk
"$ADB" -s "$SERIAL" shell getprop sys.user.0.ce_available
"$ADB" -s "$SERIAL" shell df -h /data
"$ADB" devices -l

# Build instrumentation APKs without invoking UTP/ddmlib device installation.
# The runner has intermittently misreported API 30 as API 1 during split-APK install.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain --stacktrace

api_level="$( "$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d "\r" )"
echo "Verified Android API level: $api_level"
if ! [[ "$api_level" =~ ^[0-9]+$ ]] || (( api_level < 26 )); then
  echo "ERROR: emulator API level is invalid or below app minSdk 26: $api_level"
  exit 1
fi

echo "Installing app and instrumentation APKs with direct ADB install..."
"$ADB" -s "$SERIAL" install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$SERIAL" install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Running the full Android instrumentation suite..."
instrumentation_output="$( "$ADB" -s "$SERIAL" shell am instrument -w -r in.shraddha.ai.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tr -d "\r" )"
printf "%s\n" "$instrumentation_output"
if ! grep -Eq "INSTRUMENTATION_CODE: 0" <<< "$instrumentation_output" ||
   ! grep -Eq "OK \([0-9]+ tests?\)" <<< "$instrumentation_output"; then
  echo "ERROR: Android instrumentation did not report a clean successful test run."
  exit 1
fi

echo "Android instrumentation suite passed."

echo "Installing and launching the production debug APK..."
"$ADB" -s "$SERIAL" install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$SERIAL" shell monkey -p in.shraddha.ai 1
for attempt in 1 2 3 4 5 6; do
  if "$ADB" -s "$SERIAL" shell pidof in.shraddha.ai | grep -q '[0-9]'; then
    echo "Shraddha AI main process is running."
    exit 0
  fi
  sleep 5
done
echo "ERROR: Shraddha AI did not remain running after launch."
exit 1
