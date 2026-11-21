#!/usr/bin/env bash
set -Eeuo pipefail

bash .github/scripts/wait-for-android-emulator.sh
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then ADB="$(command -v adb)"; fi
SERIAL="${ANDROID_SERIAL:-emulator-5554}"

dump_diagnostics() {
  echo "===== ADB devices ====="
  "$ADB" devices -l || true
  echo "===== Emulator properties ====="
  "$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk || true
  echo "===== Emulator disk ====="
  "$ADB" -s "$SERIAL" shell df -h /data || true
  echo "===== Emulator logcat ====="
  "$ADB" -s "$SERIAL" logcat -d -t 1000 || true
}
trap 'status=$?; if (( status != 0 )); then dump_diagnostics; fi' EXIT

"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk
"$ADB" -s "$SERIAL" shell df -h /data
# The readiness helper already verifies PackageManager and ActivityManager.
# Avoid the flaky standalone "pm list packages" probe; connectedAndroidTest
# performs the real package installation and instrumentation checks.
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace

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
