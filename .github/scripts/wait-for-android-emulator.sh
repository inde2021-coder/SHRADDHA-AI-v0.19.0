#!/usr/bin/env bash
set -uo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then ADB="$(command -v adb)"; fi

echo "Waiting for Android emulator and system services..."
deadline=$((SECONDS + 900))
last_state=unknown
while (( SECONDS < deadline )); do
  state="$("$ADB" get-state 2>/dev/null || true)"
  boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" == device && "$boot" == 1 ]]; then
    package_service="$("$ADB" shell service check package 2>/dev/null || true)"
    activity_service="$("$ADB" shell service check activity 2>/dev/null || true)"
    if [[ "$package_service" == *found* && "$activity_service" == *found* ]]; then
      echo "Emulator is online; boot, package and activity services are ready."
      "$ADB" devices -l
      exit 0
    fi
  fi
  if [[ "$state" != "$last_state" ]]; then
    echo "ADB state: ${state:-unavailable}; boot_completed: ${boot:-unavailable}"
    last_state="$state"
  fi
  "$ADB" reconnect offline >/dev/null 2>&1 || true
  sleep 5
done
echo "ERROR: emulator did not become fully ready within 15 minutes."
"$ADB" devices -l || true
"$ADB" logcat -d -t 500 || true
exit 1
