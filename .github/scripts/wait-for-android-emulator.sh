#!/usr/bin/env bash
set -Eeuo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb || true)"
fi
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  echo "ERROR: Android Debug Bridge executable was not found."
  exit 2
fi

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
echo "Waiting for Android emulator $SERIAL and system services..."
"$ADB" start-server
deadline=$((SECONDS + 900))
last_state=unknown
while (( SECONDS < deadline )); do
  state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
  boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" == device && "$boot" == 1 ]]; then
    package_service="$("$ADB" -s "$SERIAL" shell service check package 2>/dev/null || true)"
    activity_service="$("$ADB" -s "$SERIAL" shell service check activity 2>/dev/null || true)"
    if [[ "$package_service" == *found* && "$activity_service" == *found* ]]; then
      echo "Emulator $SERIAL is online; boot, package and activity services are ready."
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

echo "ERROR: emulator $SERIAL did not become fully ready within 15 minutes."
"$ADB" devices -l || true
"$ADB" -s "$SERIAL" logcat -d -t 1000 || true
exit 1
