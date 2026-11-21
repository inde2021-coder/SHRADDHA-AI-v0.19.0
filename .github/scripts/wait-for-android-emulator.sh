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
echo "Waiting for Android emulator $SERIAL and responsive system services..."
"$ADB" start-server

deadline=$((SECONDS + 900))
last_state=unknown
ready_streak=0
while (( SECONDS < deadline )); do
  state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
  boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"

  if [[ "$state" == device && "$boot" == 1 ]]; then
    package_service="$("$ADB" -s "$SERIAL" shell service check package 2>/dev/null | tr -d '\r' || true)"
    activity_service="$("$ADB" -s "$SERIAL" shell service check activity 2>/dev/null | tr -d '\r' || true)"

    # Match the positive service-check response exactly. A substring test for
    # "found" also matches "not found", which previously let an unready AVD
    # proceed into connectedAndroidTest and fail APK installation.
    if [[ "$package_service" == "Service package: found" &&
          "$activity_service" == "Service activity: found" ]]; then
      # Verify the services can actually answer Binder-backed shell requests,
      # not merely that stale service-manager entries exist.
      package_probe="$("$ADB" -s "$SERIAL" shell pm path android 2>/dev/null | tr -d '\r' || true)"
      activity_probe="$("$ADB" -s "$SERIAL" shell am get-current-user 2>/dev/null | tr -d '\r' || true)"
      if [[ "$package_probe" == package:* && "$activity_probe" =~ ^[0-9]+$ ]]; then
        ready_streak=$((ready_streak + 1))
        if (( ready_streak >= 3 )); then
          echo "Emulator $SERIAL is stable: boot complete, PackageManager and ActivityManager respond (3 consecutive checks)."
          "$ADB" devices -l
          exit 0
        fi
      else
        ready_streak=0
        echo "System services are registered but not responsive yet; waiting..."
      fi
    else
      ready_streak=0
    fi
  else
    ready_streak=0
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
"$ADB" -s "$SERIAL" shell service check package || true
"$ADB" -s "$SERIAL" shell service check activity || true
"$ADB" -s "$SERIAL" logcat -d -t 1000 || true
exit 1
